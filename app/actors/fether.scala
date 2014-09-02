package actors

import play.api.libs.concurrent.Akka

import akka.actor.{Actor,Props,Identify, ActorIdentity,ActorSystem,ActorRef,Cancellable}

import play.api.Logger
import play.api.Play.current

import scala.concurrent.{Await,Future,Promise,Awaitable}
import scala.util.{Try, Success, Failure}

import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

import play.api.libs.concurrent.Execution.Implicits._

import play.api.libs.iteratee.{Concurrent,Enumerator}

import play.api._
import play.api.libs.ws._

// Json
//import play.api.libs.json.{JsValue,Json,JsObject}
//import play.api.libs.functional.syntax._


import java.util.Date
import java.text.SimpleDateFormat

import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._

import model.SearchResult

case class StartSearch(tr:model.TravelRequest)


case class Refresh()
case class Subscribe()
case class Connected( enumerator: Enumerator[ JsValue ] )
case class ConnectionFailed()

class Supervisor extends Actor {
  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._
  import scala.concurrent.duration._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 0, withinTimeRange = 1 minute) {
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
  implicit val timeout = Timeout(0.1 seconds)
  val supervisor = Akka.system.actorOf(Props[Supervisor])

  /*
  val norvegianAirlines = Await.result((supervisor ? Props[actors.NorvegianAirlines]).mapTo[ActorRef], timeout.duration)
  val airberlin         = Await.result((supervisor ? Props[actors.Airberlin]).mapTo[ActorRef], timeout.duration)
  val aviasales         = Await.result((supervisor ? Props[actors.Aviasales]).mapTo[ActorRef], timeout.duration)
  */

  val norvegianAirlines = Akka.system.actorOf(Props(new actors.NorvegianAirlines()),"NorvegianAirlines")  
  val airberlin = Akka.system.actorOf(Props(new actors.Airberlin()),"Airberlin")  
  val aviasales = Akka.system.actorOf(Props(new actors.Aviasales(noCache=true)),"Aviasales")
  val swissairlines = Akka.system.actorOf(Props(new actors.SwissAirlines()),"SwissAirlines")
  val flytap = Akka.system.actorOf(Props(new actors.FlyTap()),"FlyTap")
  val cheapair = Akka.system.actorOf(Props(new actors.CheapAir()),"CheapAir")

  lazy val fetchers = Seq(
    "NorvegianAirlines" -> norvegianAirlines,
    "Airberlin" -> airberlin,
    "Aviasales" -> aviasales,
    "SwissAirlines" -> swissairlines,
    "FlyTap" -> flytap,
    "CheapAir" -> cheapair
  )
  
}

class ExternalGetter extends Actor {
  import ExternalGetter._
  
  val ( enumerator, channel ) = Concurrent.broadcast[JsValue]

  var idx = 0
  var buffer:List[JsValue] = List()
  val logger = Logger("ExternalGetter")

  
  import model.{Flight,Ticket}

  var want2stop = false

	def receive = {

    case StartSearch(tr) => 
      val manager = sender 
      logger.info(s"StartSearch ${tr}")

      implicit val avaFormat = Json.format[AVSAirline]
      import model.Formatters._

      val allFetchers = for ( (fid,fetcher) <- fetchers) yield {
        val fut = ask(fetcher,StartSearch(tr))(Timeout(model.Config.fetcherAskTimeout));
        val promise = Promise[Boolean]()

        fut.onComplete {
          case Success(AviasalesSearchResult(tickets,airlines)) => 
            logger.info(s"$fid returns: ${tickets.length} results")
            broadcast(Json.obj("airlines" -> Json.toJson(airlines)))
            broadcast(Json.obj("tickets" -> Json.toJson(tickets)))
            promise.success(true)
          case Success(SearchResult(tr,tickets)) => 
            logger.info(s"$fid returns: ${tickets.length} results")
            broadcast(Json.obj("tickets" -> Json.toJson(tickets)))
            promise.success(true)
          case Success(x) => 
            logger.error(s"$fid: airlines returns bad answer: $x")
            promise.success(false)
          case Failure(t) => 
            logger.error(s"$fid: An error has occured: " + t.getMessage)
            promise.success(false)
        }
        promise.future
      }

      val allFetchersFut = Future.sequence(allFetchers)

      allFetchersFut onComplete {
        case Success(results:Seq[_]) => 
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
          manager ! StoppedFetcher()

          want2stop = true
          //Akka.system.stop(self)
        case Failure(ex) => 
          logger.error("allFetchers - error occured: " +ex.getMessage)
          broadcast(Json.obj("error"->500))
          channel.end()
          manager ! StoppedFetcher()
          want2stop = true
      }

    case Subscribe() => 
      if ( ! want2stop )
        sender ! Connected(
          Enumerator.enumerate(buffer.reverse)  >>> enumerator
        )
      else 
        sender ! ConnectionFailed()

    case unk => logger.error(s"Unknown signal $unk")
	}

  def broadcast(msg:JsValue) = {
    buffer  ::= msg
    channel.push( msg )
  }

}

case class StartFetcher(tr:model.TravelRequest)
case class GetTravelInfo(idx:Int)
case class StoppedFetcher()
case class GetActorRef(id:String)

class ManagerActor extends Actor {
    var idx:Int = 0

    var requestMap = Map[model.TravelRequest,Int]()
    var idx2TravelRequest = Map[Int,model.TravelRequest]()
    var actor2idx = Map[ActorRef,Int]()
    implicit val timeout = Timeout(ManagerConfig.fastTimeout)
    private def newFetcher(tr:model.TravelRequest) = {
      
      idx+=1
      requestMap += tr -> idx
      idx2TravelRequest += idx -> tr

      val fetcher = Akka.system.actorOf(Props[ExternalGetter],s"fetcher-${idx}")

      Logger.info(s"Spawn search actor fetcherIdx:$idx for tr:$tr")

      actor2idx += fetcher -> idx
      fetcher ! StartSearch(tr)

      (idx,fetcher)

    }

    def receive = {
        case StoppedFetcher() => 
          val idx = actor2idx.get(sender).get
          val tr  = idx2TravelRequest.get(idx).get
          Logger.info(s"Actor $idx for $tr stopped")
          actor2idx -= sender
          //idx2TravelRequest -= idx
          requestMap -= tr 
          sender ! akka.actor.PoisonPill

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

        case GetActorRef(fetcherIdx:String) => 
          val myFutureStuff = Akka.system.actorSelection(s"akka://application/user/fetcher-${fetcherIdx}")

          val aid:ActorIdentity = Await.result((
            myFutureStuff ? Identify(1)).mapTo[ActorIdentity],
            0.1 seconds)

          sender ! aid.ref
    }
}

object ManagerConfig {
  val fastTimeout = 0.1 seconds

}

object Manager {
  import ManagerConfig._

  val logger = Logger("Manager")

  val managerActor = Akka.system.actorOf(Props[ManagerActor],"manager")
  val gatesActor = Akka.system.actorOf(Props[actors.GatesStorageActor],"GatesStorageActor")
  val currencyActor = Akka.system.actorOf(Props[actors.CurrenciesStorageActor],"CurrencyStorageActor")
  val phantomIdCounter = utils.ActorUtils.selectActor[PhantomIdCounter]("PhantomIdCounter",Akka.system)
  //Akka.system.actorOf(Props[actors.PhantomIdCounter],"PhantomIdCounter")


  def fastAwait[T](awaitable: Awaitable[T]) = try {
    Await.result(awaitable,fastTimeout)
  } catch {
    case x:java.util.concurrent.TimeoutException => throw new java.util.concurrent.TimeoutException(s"timeout $fastTimeout")
    case x:Throwable => throw x
  }

  

  implicit val timeout = Timeout(fastTimeout)
  
  def startSearch(tr:model.TravelRequest) = {
    val (fetcherId,fetcher) = fastAwait( (managerActor ? StartFetcher(tr)).mapTo[(Int,ActorRef)] )
    fetcherId
  }

  def startSearch2(tr:model.TravelRequest) = {
    val (fetcherId,fetcher) = fastAwait( (managerActor ? StartFetcher(tr)).mapTo[(Int,ActorRef)] )
    (fetcherId,fetcher)
  }

  def getTravelInfo(idx:Int) = {
    fastAwait( (managerActor ? GetTravelInfo(idx)).mapTo[Option[model.TravelRequest]] )
  }

  def getCheapest(iataFrom:String):Future[Seq[model.FlightInfo]] =  
      model.Airports.get(iataFrom) match {
        case Some(from) => 
          val f = AvsCacheParser.fetchAviasalesCheapest(iataFrom)
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
    if (gates.isEmpty) Seq() else 
    fastAwait( (gatesActor ? gates ).mapTo[Seq[model.Gate]]  )
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

  def getCurrencies(curs:Seq[String]):Map[String,Float] = {
    fastAwait( (gatesActor ? curs ).mapTo[Map[String,Float]] )
  }

  def updateCurrencies(curs: Map[String,Float]) = {
    currencyActor ! curs
  }
  def updateCurrencies(curs: (String,Float)) = {
    currencyActor ! curs
  }

  def getCurrencyRatio(cur:String) = {
    fastAwait( (currencyActor ? cur ).mapTo[Float] )
  }

  val allCurrencies = Seq("usd","eur","rub")
  def getCurrencyRates() = {
    fastAwait( (currencyActor ? allCurrencies ).mapTo[Map[String,Float]] )
  }

  def nextPhantomId() = 
    //val idactor = utils.ActorUtils.selectActor[PhantomIdCounter]("PhantomIdCounter",Akka.system) 
    fastAwait( (phantomIdCounter ? 1 ).mapTo[Int] )
 
  def getActorRef(id:String) = 
    fastAwait( (managerActor ? GetActorRef(id) ).mapTo[Option[ActorRef]] )   
}

