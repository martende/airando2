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
import model.SearchResult

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

case class AviasalesSearchResult(tickets:Seq[model.Ticket],airlines:Seq[AVSAirline])

trait AvsCacheParserAPI extends CachingAPI with WithLogger {
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

object AvsCacheParser extends AvsCacheParserAPI with NoCaching {

}

trait AvsParser {
  self:WithLogger => 

  def updateGates(gates:Seq[model.Gate]) = actors.Manager.updateGates(gates)

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
      val d = new java.util.Date( ( mseconds ) *1000  - java.util.TimeZone.getDefault().getRawOffset() - 3600000L )
      d
    } and
    (__ \ "arrival").read[Long].map {
      mseconds => 
        val d = new java.util.Date(mseconds *1000  - java.util.TimeZone.getDefault().getRawOffset()  - 3600000L)
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

    updateGates(gates)
    actors.Manager.updateCurrencies(cur2eur)
    
    AviasalesSearchResult(data,airlines)

  }
}



class Aviasales(maxRepeats:Int=1,noCache:Boolean=false) extends BaseFetcherActor(maxRepeats,noCache) with NoCaching with AvsParser {
  import context.dispatcher
  import context.become

  val ID = "AVS"
  
  def receive = {
    case StartSearch(tr) => processSearch(sender,tr) {
        val t = doRealSearch(tr)
        complete(sender,tr,t)
      }
  }


  def complete(sender:ActorRef,tr:model.TravelRequest,sr:AviasalesSearchResult = AviasalesSearchResult(Seq(),Seq())  ) = {

    logger.info(s"Search $tr: Completed: ${sr.tickets.length} tickets found")

    saveDBCache(SearchResult(tr,sr.tickets))

    sender ! sr
  }

/*
  def processSearch(_sender:ActorRef,tr:model.TravelRequest) = {
    rqIdx+=1
    curSender = _sender 
    curRequest = tr

    logger.info(s"StartSearch:${rqIdx} ${tr}")

    doRealSearch(tr)

  }
*/
  val pageLoadTimeout = 2 seconds
  val pageResultTimeout = 60 seconds


  def doRealSearch(tr:model.TravelRequest) = {
    
    Await.result( fetchAviasales(tr),pageResultTimeout )

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


