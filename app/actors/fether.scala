package actors

import play.api.libs.concurrent.Akka

import akka.actor.{Actor,Props,Identify, ActorIdentity,ActorSystem,ActorRef,Cancellable}

import play.api.Logger
import play.api.Play.current

import scala.concurrent.{Await,Future}
import scala.util.{Try, Success, Failure}

import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

import play.api.libs.concurrent.Execution.Implicits._

import play.api.libs.iteratee.{Concurrent,Enumerator}

import play.api._
import play.api.libs.ws._

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

// TravelType enum import
import model.TravelType._

import java.util.Date

case class StartSearch(tr:model.TravelRequest)
case class Refresh()
case class Subscribe()
case class Connected( enumerator: Enumerator[ JsValue ] )

class ExternalGetter extends Actor {
  val ( enumerator, channel ) = Concurrent.broadcast[JsValue]

  var idx = 0
  var schedule:Cancellable = _
  var buffer:List[JsValue] = List()

  val cacheDir = Play.application.path + "/" + Play.current.configuration.getString("cache-dir").getOrElse({
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

  case class AVSFlights(
    origin: String,
    destination: String,
    airline: String,
    duration: Int,
    number: Int,
    arrival: Int,
    aircraft: Option[String],
    departure: Int,
    delay: Int
  )

  case class AVSTicket(
    sign: String,
    main_airline:Option[String],
    total: Float,
    direct_flights: Seq[AVSFlights],
    native_prices: Map[String,Float],
    order_urls: Map[String,Int]
  )

  case class AVSGate(
    id: String,
    label: String
  )

  def fetchAviasales(tr:model.TravelRequest) = {
    val aParams:Map[String,Seq[String]] = Map( 
      "search[params_attributes][origin_name]" -> Seq(tr.iataFrom), 
      "search[params_attributes][destination_name]" -> Seq(tr.iataTo), 
      "search[params_attributes][depart_date]" -> Seq(tr.departure.toString), 
      "search[params_attributes][range]" -> Seq("0"), 
      "search[params_attributes][adults]" -> Seq("1"), 
      "search[params_attributes][children]" -> Seq("0"), 
      "search[params_attributes][infants]" -> Seq("0"), 
      "search[params_attributes][trip_class]" -> Seq("0"), 
      "search[params_attributes][direct]" -> Seq("0")
    )
    val token = "67c3abc2accf4c0890e6b7f8192c7971"
    val marker = "33313"
    val signature:String = md5(s"$token:$marker:" +aParams.keys.toSeq.sorted.map(k=>aParams(k).mkString).mkString(":"))

    implicit val avReads =  (
      (__ \ "origin").read[String] and
      (__ \ "destination").read[String] and
      (__ \ "airline").read[String] and
      (__ \ "duration").read[Int]  and
      (__ \ "number").read[Int] and
      (__ \ "arrival").read[Int] and
      (__ \ "aircraft").readNullable[String] and
      (__ \ "departure").read[Int] and
      (__ \ "delay").read[Int]
    )(AVSFlights)

    implicit val avgReads = (
      (__ \ "id").read[String] and
      (__ \ "label").read[String]
    )(AVSGate)

    implicit val tReads: Reads[AVSTicket] = (
      (__ \ "sign").read[String] and
      (__ \ "main_airline").readNullable[String] and
      (__ \ "total").read[Float] and
      (__ \ "direct_flights").read[Seq[AVSFlights]] and
      (__ \ "native_prices").read[Map[String,Float]] and
      (__ \ "order_urls").read[Map[String,Int]]
    )(AVSTicket.apply _)
    


    val holder = getFromCache(signature).fold { 
      WS.url("http://yasen.aviasales.ru/searches.json") 
      .withRequestTimeout(30000).post(aParams + 
        ("signature" -> Seq(signature) ) + 
        ("enable_api_auth" -> Seq("true") ) + 
        ("marker" -> Seq(marker) )
      ).map { 
        response => 
        saveCache(signature,response.body)
        response.body
      }
    }(Future successful _)

    holder.map {
      response => {
        val data:Seq[AVSTicket] = 
        (Json.parse(response) \ "tickets" ).validate[Seq[AVSTicket]] match {
          case s: JsSuccess[_] => s.get
          case e: JsError => 
            Logger.error("aviasales.ru Parsing Errors: " + JsError.toFlatJson(e).toString()) 
            throw play.api.UnexpectedException(Some("aviasales.ru parsing failed"))
        }
        
        val gates:Seq[AVSGate] = 
        (Json.parse(response) \ "gates_info" ).validate[Seq[AVSGate]] match {
          case s: JsSuccess[_] => s.get
          case e: JsError => 
            Logger.error("aviasales.ru Parsing AVSGate Errors: " + JsError.toFlatJson(e).toString()) 
            throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSGate failed"))
        }
        val curencies: Map[String,Float] = (Json.parse(response) \ "currency_rates" ).validate[Map[String,Float]] match {
          case s: JsSuccess[_] => s.get
          case e: JsError => 
            Logger.error("aviasales.ru Parsing CurentcyRates Errors: " + JsError.toFlatJson(e).toString()) 
            throw play.api.UnexpectedException(Some("aviasales.ru parsing CurentcyRates failed"))
        }
        //val ret = s"len ${data.length}\n" + data

        //Ok(ret + "\n\n\n" )
        (data,gates,curencies)
      }
      //(response.json \ "person" \ "name").as[String]
    }

  }

	def receive = {
    case StartSearch(tr) => 
      Logger.info(s"StartSearch ${tr}")
      /*schedule = Akka.system.scheduler.schedule( 0.seconds, 1.second, self, Refresh )*/
      fetchAviasales(tr) onComplete {
        case Success((tickets,gates,curencies)) => 
          implicit val avsFormat = Json.format[AVSFlights]
          implicit val ticketFormat = Json.format[AVSTicket]
          implicit val avgFormat = Json.format[AVSGate]
          
          broadcast(Json.obj("currency_rates" -> Json.toJson(curencies)))
          broadcast(Json.obj("gates" -> Json.toJson(gates)))
          broadcast(Json.obj("tickets" -> Json.toJson(tickets)))

          channel.end()
        case Failure(t) => 
          Logger.error("An error has occured: " + t.getMessage)
          broadcast(Json.obj("error"->500))
          channel.end()
      }
      /*
    case Refresh => 
      Logger.info(s"PINGG")
      idx+=1
      broadcast(s"aaa $idx\n")
      if ( idx == 10 ) {
        schedule.cancel()
        channel.end()
      }
    */
    case Subscribe => sender ! Connected(
      //Enumerator(gates) >>>
      Enumerator.enumerate(buffer.reverse)  >>> enumerator
    )

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
                case Some(fetcher) => (fetcherIdx,fetcher)
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

    lazy val managerActor = Akka.system.actorOf(Props[ManagerActor],"manager")

    val fastTimeout = 0.1 seconds

    implicit val timeout = Timeout(fastTimeout)
    
    def startSearch(tr:model.TravelRequest) = {
      
      val (fetcherId,fetcher) = Await.result( (managerActor ? StartFetcher(tr)).mapTo[(Int,ActorRef)] , fastTimeout )
      fetcherId
    }

    def getTravelInfo(idx:Int) = {
      Await.result( (managerActor ? GetTravelInfo(idx)).mapTo[Option[model.TravelRequest]] , fastTimeout )
    }
}