package actors

import play.api.libs.concurrent.Akka

import akka.actor.{Actor,Props,Identify, ActorIdentity,ActorSystem,ActorRef,Cancellable}

import play.api.Logger
import play.api.Play.current

import scala.concurrent.{Await,Future,Promise}
import scala.util.{Try, Success, Failure}

import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

import play.api.libs.concurrent.Execution.Implicits._

import play.api.libs.iteratee.{Concurrent,Enumerator}

import play.api._
import play.api.libs.ws._

// Json
import play.api.libs.json.{JsValue,Json,JsObject}
//import play.api.libs.functional.syntax._

// TravelType enum import
import model.TravelType._
import model.FlightClass._

import java.util.Date
import java.text.SimpleDateFormat

import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._

case class StartSearch(tr:model.TravelRequest)

class SearchResponse(tr:model.TravelRequest)
case class SearchResult(tr:model.TravelRequest,ts:Seq[model.Ticket]) extends SearchResponse(tr)
case class SearchError(tr:model.TravelRequest,e:Throwable) extends SearchResponse(tr)

case class Refresh()
case class Subscribe()
case class Connected( enumerator: Enumerator[ JsValue ] )

trait Caching {
  lazy val cacheDir = Play.application.path + "/" + Play.current.configuration.getString("cache-dir").getOrElse({
      Logger.error("cache-dir not configured")
      throw play.api.UnexpectedException(Some("cache-dir not configured"))  
    })

  def md5(text: String) : String = java.security.MessageDigest.getInstance("MD5").digest(text.getBytes()).map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  def writeFile(fname:String,content:String) = Some(new java.io.PrintWriter(fname)).foreach{p => p.write(content); p.close}
  def saveCache(f:String,responseBody:String) = {
      val d1 = f.substring(0,2)
      val d2 = f.substring(2,4)
      val d3 = f.substring(4)
      val d1f = new java.io.File(cacheDir,d1)
      if (! d1f.exists) d1f.mkdir()
      val d2f = new java.io.File(cacheDir,s"$d1/$d2")
      if (! d2f.exists) d2f.mkdir()
      val fname = new java.io.File(cacheDir,s"$d1/$d2/$d3.chtml")

      Logger.info(s"Save message: '$f' to cache ${fname.getAbsolutePath}")

      writeFile(fname.getPath,responseBody)
  }

  def getFromCache(f:String):Option[String] = {
    val d1 = f.substring(0,2)
    val d2 = f.substring(2,4)
    val d3 = f.substring(4)
    val fstr = s"$d1/$d2/$d3.chtml"
    val fname = new java.io.File(cacheDir,fstr)
    if (fname.exists) {
      Logger.info(s"Read message: '$f' from cache ${fname.getAbsolutePath}")
        //log.debug(s"Fetch ${url} from cache ${fname}")
        Some(scala.io.Source.fromFile(fname.getPath).mkString)
    } else None
  }  
}

abstract class AnyParser {
  val id:String
}

class Supervisor extends Actor {
  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._
  import scala.concurrent.duration._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) {
      case _: ArithmeticException      => Resume
      case _: NullPointerException     => Restart
      case _: IllegalArgumentException => Stop
      case _: Exception                => Restart
    }

  def receive = {
    case p: Props => sender ! context.actorOf(p)
  }
}

object ExternalGetter {
  implicit val timeout = Timeout(5 seconds)
  val supervisor = Akka.system.actorOf(Props[Supervisor])
  val norvegianAirlines = Await.result((supervisor ? Props[actors.NorvegianAirlines]).mapTo[ActorRef], timeout.duration)
  //val norvegianAirlines = Akka.system.actorOf(Props[actors.NorvegianAirlines])  
}

class ExternalGetter extends Actor with Caching {
  import ExternalGetter._
  
  val ( enumerator, channel ) = Concurrent.broadcast[JsValue]

  var idx = 0
  var schedule:Cancellable = _
  var buffer:List[JsValue] = List()
  val logger = Logger("ExternalGetter")

  
  import actors.avsfetcher._
  import model.{Flight,Ticket}

  def fetchAviasales(tr:model.TravelRequest) = {
    val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

    val aParams:Map[String,Seq[String]] = Map( 
      "search[params_attributes][origin_name]" -> Seq(tr.iataFrom), 
      "search[params_attributes][destination_name]" -> Seq(tr.iataTo), 
      "search[params_attributes][depart_date]" -> Seq(df.format(tr.departure)), 
      "search[params_attributes][return_date]" -> Seq( if (tr.traveltype == OneWay ) "" else df.format(tr.arrival) ), 
      "search[params_attributes][range]" -> Seq("0"), 
      "search[params_attributes][adults]" -> Seq(tr.adults.toString), 
      "search[params_attributes][children]" -> Seq(tr.childs.toString), 
      "search[params_attributes][infants]" -> Seq(tr.infants.toString), 
      "search[params_attributes][trip_class]" -> Seq( if (tr.traveltype == Business ) "1" else "0" ), 
      "search[params_attributes][direct]" -> Seq("0")
    )
    val signature:String = md5(s"${avsfetcher.AVSParser.token}:${avsfetcher.AVSParser.marker}:" +aParams.keys.toSeq.sorted.map(k=>aParams(k).mkString).mkString(":"))

    val holder = getFromCache(signature).fold { 
      WS.url("http://yasen.aviasales.ru/searches.json") 
      .withRequestTimeout(60000).post(aParams + 
        ("signature" -> Seq(signature) ) + 
        ("enable_api_auth" -> Seq("true") ) + 
        ("marker" -> Seq(avsfetcher.AVSParser.marker) )
      ).map { 
        response => 
        saveCache(signature,response.body)
        response.body
      }
    }(Future successful _)


    holder.map(avsfetcher.AVSParser.parse)

  }

	def receive = {

    case StartSearch(tr) => 
      logger.info(s"StartSearch ${tr}")
      /*schedule = Akka.system.scheduler.schedule( 0.seconds, 1.second, self, Refresh )*/

      val norvegianAirlinesFut = ask(norvegianAirlines,StartSearch(tr))(Timeout(60 seconds))
      val avsFut = fetchAviasales(tr)
      val norvegianAirlinesPromise = Promise[Boolean]()
      val avsPromise = Promise[Boolean]()

      norvegianAirlinesFut onComplete {
        case Success(SearchResult(tr,tickets)) => 
          logger.info(s"Norvegian airlines returns: $tickets")
          var t:Flight = null
          implicit val flFormat = Json.format[Flight]
          implicit val avsFormat = Json.format[Ticket]

          broadcast(Json.obj("tickets" -> Json.toJson(tickets)))

          norvegianAirlinesPromise.success(true)
        case Success(x) => 
          logger.error(s"Norvegian airlines returns bad answer: $x")
          norvegianAirlinesPromise.success(false)
        case Failure(t) => 
          logger.error("norvegianAirlinesFut: An error has occured: " + t.getMessage)
          norvegianAirlinesPromise.success(false)
          //broadcast(Json.obj("error"->500))
          //channel.end()
      }

      avsFut onComplete {
        case Success((tickets,curencies,airlines)) => 
          logger.info(s"Aviasales returns: ${tickets.length} results")

          implicit val avsFormat = Json.format[AVSFlights]
          implicit val ticketFormat = Json.format[AVSTicket]
          //implicit val avgFormat = Json.format[AVSGate]
          implicit val avaFormat = Json.format[AVSAirline]
          
          
          broadcast(Json.obj("currency_rates" -> Json.toJson(curencies)))
          // broadcast(Json.obj("gates" -> Json.toJson(gates)))
          broadcast(Json.obj("airlines" -> Json.toJson(airlines)))
          broadcast(Json.obj("tickets" -> Json.toJson(tickets)))
          
          avsPromise.success(true)
        case Failure(t) => 
          logger.error("An error has occured: " + t.getMessage)
          norvegianAirlinesPromise.success(false)
          //broadcast(Json.obj("error"->500))
          //channel.end()
      }

      val allFetchers = List(norvegianAirlinesPromise.future,avsPromise.future)

      val allFetchersFut = Future.sequence(allFetchers)

      allFetchersFut onComplete {
        case Success(results:List[_]) => 
          val failed = results.count(! _ )
          val total  = results.length
          if ( failed == total) {
            logger.error("allFetchers returend errror- errors")
            broadcast(Json.obj("error"->500))
          } else if ( failed > 0 ) {
            logger.warn(s"$failed of $total fetchers failed")
          } else {
            logger.info("Fetching succesfully ended")  
          }

          channel.end()
        case Failure(ex) => 
          logger.error("allFetchers - error occured: " +ex.getMessage)
          broadcast(Json.obj("error"->500))
          channel.end()
      }
      /*
    case Refresh => 
      logger.info(s"PINGG")
      idx+=1
      broadcast(s"aaa $idx\n")
      if ( idx == 10 ) {
        schedule.cancel()
        channel.end()
      }
    */
    case Subscribe() => 
      sender ! Connected(
        //Enumerator(gates) >>>
        Enumerator.enumerate(buffer.reverse)  >>> enumerator
      )

    case unk => logger.error(s"Unknown signal $unk")
	}

  def broadcast(msg:JsValue) = {
    buffer  ::= msg
    channel.push( msg )
  }

}

case class StartFetcher(tr:model.TravelRequest)
case class GetTravelInfo(idx:Int)

class ManagerActor extends Actor {
    var idx:Int = 0

    var requestMap = Map[model.TravelRequest,Int]()
    var idx2TravelRequest = Map[Int,model.TravelRequest]()

    implicit val timeout = Timeout(Manager.fastTimeout)

    private def newFetcher(tr:model.TravelRequest,start:Boolean=true) = {
      
      idx+=1
      requestMap += tr -> idx
      idx2TravelRequest += idx -> tr

      val fetcher = Akka.system.actorOf(Props[ExternalGetter],s"fetcher-${idx}")

      Logger.info(s"Spawn search actor fetcherIdx:$idx for tr:$tr")

      if (start) fetcher ! StartSearch(tr)

      (idx,fetcher)

    }

    def receive = {
        case StartFetcher(tr) =>  
          val ret = requestMap.get(tr) match {
            case Some(fetcherIdx) => 
              val myFutureStuff = Akka.system.actorSelection(s"akka://application/user/fetcher-${fetcherIdx}")
              val aid:ActorIdentity = Await.result((
                myFutureStuff ? Identify(1)).mapTo[ActorIdentity],
                0.1 seconds)
              aid.ref match {
                case Some(fetcher) => 
                  Logger.info(s"Use existing search actor fetcherIdx:$fetcherIdx for tr:$tr")
                  (fetcherIdx,fetcher)
                case None => newFetcher(tr)
              }
            case None => newFetcher(tr)
          }
          sender ! ret
        case GetTravelInfo(idx) => 
          sender ! idx2TravelRequest.get(idx)
    }
}

object Manager {

  val logger = Logger("Manager")

  lazy val managerActor = Akka.system.actorOf(Props[ManagerActor],"manager")
  lazy val gatesActor = Akka.system.actorOf(Props[actors.GatesStorageActor],"GatesStorageActor")

  val fastTimeout = 0.1 seconds

  implicit val timeout = Timeout(fastTimeout)
  
  def startSearch(tr:model.TravelRequest) = {    
    val (fetcherId,fetcher) = Await.result( (managerActor ? StartFetcher(tr)).mapTo[(Int,ActorRef)] , fastTimeout )
    fetcherId
  }

  def getTravelInfo(idx:Int) = {
    Await.result( (managerActor ? GetTravelInfo(idx)).mapTo[Option[model.TravelRequest]] , fastTimeout )
  }

  def getCheapest(iataFrom:String):Future[Seq[model.FlightInfo]] =  
      model.Airports.get(iataFrom) match {
        case Some(from) => 
          val f = avsfetcher.AvsCacheParser.fetchAviasalesCheapest(iataFrom)
          val ret0 = f.map {
            ret => 
              ret.groupBy(_.iataTo).flatMap {
              case (iataTo,rs) => 
                model.Airports.get(iataTo) match {
                  case Some(to) => 
                    if ( rs.isEmpty )
                      Option.empty[model.FlightInfo]
                    else {
                      val r = rs.minBy(_.price)
                      Some(
                        model.FlightInfo(from,to,r.price.toFloat,r.airline,r.departure_at,r.return_at)
                      )
                    }
                  case None     => 
                    logger.warn(s"getCheapest:iataFrom:$iataFrom iataTo:$iataTo - no such airport")
                    Option.empty[model.FlightInfo]
                }
            }.toSeq
          }.recoverWith { case e: Exception =>  Future.successful(List.empty[model.FlightInfo]) } 

          ret0

        case None => 
          logger.error(s"getCheapest $iataFrom - no such airport")
          Future.successful(List.empty[model.FlightInfo])
      }

  def getGates(gates:Seq[String]):Seq[model.Gate] = {
    Await.result( (gatesActor ? gates ).mapTo[Seq[model.Gate]] , fastTimeout )
  }
/*
  def updateGates(gates:Seq[JsObject]) = {
    val gReads = (
    (__ \ "id").read[String] and
    (__ \ "currency").read[String] and
    (__ \ "label").read[String]
    )(model.Gate)

    gatesActor ! gates.map { g => g.as[String](gReads) }
  }
  */
  def updateGates(gates:Seq[model.Gate]) = {
    gatesActor ! gates
  }
}


