package actors

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import play.api.Logger
import play.api.libs.ws._

//import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.{Await,Future}
import scala.util.{Try, Success, Failure}

import akka.actor.{ActorRef}
import scala.concurrent.duration._

// TravelType enum import
import model.TravelType._
import model.FlightClass._

import java.text.SimpleDateFormat

case class AVSAirline(
  id: String,
  name: String,
  alliance_name: Option[String]
)

case class AVSCheapestAnswer(
  iataTo:String,
  price:Int,
  airline:String,
  flight_number:Int,
  departure_at: java.util.Date,
  return_at:    java.util.Date,
  expires_at:   java.util.Date
)

object AviasalesConfig {
  val token = "67c3abc2accf4c0890e6b7f8192c7971"
  val marker = "33313"
}

case class AviasalesSearchResult(tickets:Seq[model.Ticket],cur2eur:Map[String,Float],airlines:Seq[AVSAirline])

class AvsCacheParser extends Caching with WithLogger {

  val logger = Logger("AvsCacheParser")

  import play.api.libs.concurrent.Execution.Implicits._

  def iataConverter = Map[String,String]("ORY"->"PAR")

  def fetchAviasalesCheapest(_iataFrom:String):Future[Seq[AVSCheapestAnswer]] = {
    
    val iataFrom = iataConverter.getOrElse(_iataFrom, _iataFrom  )

    logger.info("AvsCacheParser.fetchAviasalesCheapest iataFrom:" + (if ( iataFrom == _iataFrom ) iataFrom else "(" + _iataFrom + " -> " + iataFrom   + ")" ) )
    val signature = md5(s"avs:$iataFrom")
    val holder = getFromCache(signature).fold {
      val url = s"http://api.aviasales.ru/v1/cities/$iataFrom/directions/-/prices.json?currency=EUR&token=${AviasalesConfig.token}"
      WS.url(url).withRequestTimeout(5000).get().map {
        response => 
        saveCache(signature,response.body)
        response.body
      }
    }(Future successful _ )
    holder.map(parseCheapest)
  }

  def parseCheapest(response:String) = {
    val xp = Json.parse(response)
    xp match {
      case JsString(s) => 
        logger.error(s"aviasales Parsing error return String '$s'")
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSGate failed"))
      case jv:JsValue => 
        val error = (jv \ "error").asOpt[String]

        error match {
          case Some(ev) => 
            logger.error(s"aviasales returns error '$ev'")
            throw play.api.UnexpectedException(Some(s"aviasales returns error $error"))
          case None => _parseCheapest((jv \ "data").as[JsObject])
        }
    }
  }
  

  def _parseCheapest(response:JsObject) = {
    val df = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    val ret = response.fields.flatMap {
      case (iataTo,v) => 
        implicit val avReads =  (
          __.read[String](new Reads[String] {
            def reads(json:JsValue) = JsSuccess(iataTo)
          }) and
          (__ \ "price").read[Int] and
          (__ \ "airline").read[String] and
          (__ \ "flight_number").read[Int] and
          (__ \ "departure_at").read[java.util.Date](Reads.dateReads(df)) and 
          (__ \ "return_at").read[java.util.Date](Reads.dateReads(df)) and
          (__ \ "expires_at").read[java.util.Date](Reads.dateReads(df))
        )(AVSCheapestAnswer)
        

        v.validate[Map[String,AVSCheapestAnswer]] match {
          case s: JsSuccess[_] => s.get.values
          case e: JsError => 
            logger.error("aviasales.ru Parsing CurentcyRates Errors: " + JsError.toFlatJson(e).toString()) 
            throw play.api.UnexpectedException(Some("aviasales.ru parsing CurentcyRates failed"))
        }
    }
    
    ret
    //println("AAAAA " + response)
  }
  def fetchRedirUrl(id:String) = {
    val Array(searchId,gateId) = id.split(":")
    val url = s"http://yasen.aviasales.ru/searches/$searchId/order_urls/$gateId?marker=${AviasalesConfig.marker}"
    WS.url(url).withRequestTimeout(10000).get().map {
      response => 
      val xp = Json.parse(response.body)
      logger.info(s"fetchRedirUrl $id returns ${response.body}")
      (xp \ "url").as[String]
    }
  }

}

object AvsCacheParser extends AvsCacheParser {

}

trait AvsParser {
  self:WithLogger => 

  implicit val avReads =  (
    (__ \ "origin").read[String] and
    (__ \ "destination").read[String] and
    (__ \ "airline").read[String] and
    (__ \ "duration").read[Int]  and
    ( (__ \ "airline").read[String] and (__ \ "number").read[Int] tupled ).map {
      case (airline,flnum) => airline + flnum.toString
    } and
    (__ \ "departure").read[Long].map {
      mseconds => 
      // TODO: - 3600000L WTF ?
      val d = new java.util.Date( ( mseconds ) *1000  - java.util.TimeZone.getDefault().getRawOffset() )
      //println("DEPRT",d,mseconds)
      d
    } and
    (__ \ "arrival").read[Long].map {
      mseconds => 
        val d = new java.util.Date(mseconds *1000  - java.util.TimeZone.getDefault().getRawOffset()  )
        //println("AVL",mseconds,mseconds*1000,d)
        Some(d)
    } and
    (__ \ "aircraft").readNullable[String] and
    (__ \ "delay").read[Int]
  )(model.Flight)

  implicit val avgReads = (
    (__ \ "id").read[String] and
    (__ \ "currency_code").read[String] and
    (__ \ "label").read[String] 
  )(model.Gate)

  def parse(response:String) = {
    val xp = Json.parse(response)
    xp match {
      case JsString(s) => 
        logger.error(s"aviasales Parsing error return String '$s'")
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSGate failed"))
      case _:JsValue => _parse(xp)
    }
  }
  def _parse(xp:JsValue) = {
    val searchId = (xp \ "search_id").as[String]

    val gates:Seq[model.Gate] = 
    (xp \ "gates_info" ).validate[Seq[model.Gate]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        logger.error("aviasales.ru Parsing AVSGate Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSGate failed"))
    }

    
    val airlines:Seq[AVSAirline] = (xp \ "airlines" ).as[JsObject].fields.map { 
      x =>  AVSAirline(
        x._1,
        (x._2 \ "name").as[JsString].value,
        (x._2 \ "alliance_name").asOpt[JsString].fold[Option[String]](None){x => Some(x.value)}
      )
    }
    
    /*
    (xp \ "airlines" ).validate[Map[String,InnerAirline]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        logger.error("aviasales.ru Parsing AVSAirline Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSAirline failed"))
    }*/

    val cur2rub: Map[String,Float] = (xp \ "currency_rates" ).validate[Map[String,Float]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        logger.error("aviasales.ru Parsing CurentcyRates Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing CurentcyRates failed"))
    }

    val g2cc = gates.map { x => 
      x.id.toString -> x.currency
    }.toMap
    
    val eur = cur2rub("eur")
    val cur2eur:Map[String,Float] = cur2rub.map { x => x._1 -> x._2 / eur }

    implicit val tReads: Reads[model.Ticket] = (
      (__ \ "sign").read[String] and
      //(__ \ "main_airline").readNullable[String] and
      //(__ \ "total").read[Float] and
      (__ \ "direct_flights").read[Seq[model.Flight]] and
      (__ \ "return_flights").readNullable[Seq[model.Flight]] and
      (__ \ "native_prices").read[Map[String,Float]].map {
        x => 
        x.map {
          case (k:String,v:Float) => 
            k -> v * cur2eur(g2cc(k)) 
        }
      } and
      (__ \ "order_urls").read[Map[String,Int]].map {
        x =>
        x.map {
          case (k:String,v:Int) => 
            k -> s"avs:$searchId:${v.toString}" 
        }
      }
    )(model.Ticket.apply _)

    val data:Seq[model.Ticket] = 
    (xp \ "tickets" ).validate[Seq[model.Ticket]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        logger.error("aviasales.ru Parsing Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing failed"))
    }

    //val ret = s"len ${data.length}\n" + data

    //Ok(ret + "\n\n\n" )
    actors.Manager.updateGates(gates)

    AviasalesSearchResult(data/*,gates*/,cur2eur,airlines)

  }
}



class Aviasales extends BaseFetcherActor with Caching with AvsParser {
  import context.dispatcher
  import context.become

  val logger = Logger("Aviasales")

  var availIatas:Set[String] = null
  var rqIdx = 0
  var curSender:ActorRef = null
  var curRequest:model.TravelRequest = null
  
  def receive = {
    case StartSearch(tr) => 
      processSearch(sender,tr)
  }


  def complete(sr:AviasalesSearchResult = AviasalesSearchResult(Seq(),Map(),Seq()) ) = {

    logger.info(s"Search $curRequest: Completed: ${sr.tickets.length} tickets found")

    curSender ! sr

    curSender = null
  }

  def processSearch(_sender:ActorRef,tr:model.TravelRequest) = {
    rqIdx+=1
    curSender = _sender 
    curRequest = tr

    logger.info(s"StartSearch:${rqIdx} ${tr}")

    doRealSearch(tr)

  }

  val pageLoadTimeout = 2 seconds
  val pageResultTimeout = 10 seconds


  def doRealSearch(tr:model.TravelRequest,maxTryes:Int = 3) {
    

    //class NotAvailibleDirecttion extends Throwable;
    
    try {

      val t = Await.result( fetchAviasales(tr),pageResultTimeout )

      complete(t)

    } catch {
      /*case ex:NoFlightsException => 
        logger.info(s"Searching $tr flights are not availible" )
        complete()

      case ex:NotAvailibleDirecttion =>
        logger.info( s"Parsing: $tr no such direction")
        complete()
      */
      case ex:Throwable => 
        if ( maxTryes != 0 ) {
          logger.warn(s"Parsing: $tr failed. Try ${maxTryes-1} times exception: $ex\n" + ex.getStackTrace().mkString("\n"))
          doRealSearch(tr,maxTryes-1)
        } else {
          curSender ! akka.actor.Status.Failure(ex)
          logger.error(s"Parsing: $tr failed unkwnon exception $ex\n" + ex.getStackTrace().mkString("\n"))
        }
    }
  }  

  // def md5(text: String) : String = java.security.MessageDigest.getInstance("MD5").digest(text.getBytes()).map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}

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
    val signature:String = md5(s"${AviasalesConfig.token}:${AviasalesConfig.marker}:" +aParams.keys.toSeq.sorted.map(k=>aParams(k).mkString).mkString(":"))

    val holder = getFromCache(signature).fold { 
      // WS.url("http://yasen.aviasales.ru/searches.json") 
      WS.url("http://yasen.aviasales.ru/searches_jetradar.json")
      .withRequestTimeout(60000).post(aParams + 
        ("signature" -> Seq(signature) ) + 
        // ("enable_api_auth" -> Seq("true") ) + 
        ( "locale" -> Seq("en") ) + 
        ("marker" -> Seq(AviasalesConfig.marker) )
      ).map { 
        response => 
        saveCache(signature,response.body)
        response.body
      }
    }(Future successful _)


    holder.map(parse)

  }
}


