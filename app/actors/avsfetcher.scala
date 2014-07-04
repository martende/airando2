package actors.avsfetcher

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import play.api.Logger
import play.api.libs.ws._

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.{Await,Future}

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
  return_flights: Option[Seq[AVSFlights]],
  native_prices: Map[String,Float],
  order_urls: Map[String,String]
)

case class AVSGate(
  id: String,
  label: String,
  currency_code:String
)


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

class AvsCacheParser extends actors.Caching {

  def iataConverter = Map[String,String]("ORY"->"PAR")

  def fetchAviasalesCheapest(_iataFrom:String):Future[Seq[AVSCheapestAnswer]] = {
    val iataFrom = iataConverter.getOrElse(_iataFrom, _iataFrom  )
    Logger.info(s"AvsCacheParser.fetchAviasalesCheapest iataFrom:$iataFrom")
    val signature = md5(s"avs:$iataFrom")
    val holder = getFromCache(signature).fold {
      val url = s"http://api.aviasales.ru/v1/cities/$iataFrom/directions/-/prices.json?currency=EUR&token=${AVSParser.token}"
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
        Logger.error(s"aviasales Parsing error return String '$s'")
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSGate failed"))
      case jv:JsValue => 
        val error = (jv \ "error").asOpt[String]

        error match {
          case Some(ev) => 
            Logger.error(s"aviasales returns error '$ev'")
            throw play.api.UnexpectedException(Some("aviasales returns error $error"))
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
            Logger.error("aviasales.ru Parsing CurentcyRates Errors: " + JsError.toFlatJson(e).toString()) 
            throw play.api.UnexpectedException(Some("aviasales.ru parsing CurentcyRates failed"))
        }
    }
    
    ret
    //println("AAAAA " + response)
  }
  def fetchRedirUrl(id:String) = {
    val Array(searchId,gateId) = id.split(":")
    val url = s"http://yasen.aviasales.ru/searches/$searchId/order_urls/$gateId?marker=${AVSParser.marker}"
    WS.url(url).withRequestTimeout(1000).get().map {
      response => 
      val xp = Json.parse(response.body)
      Logger.info(s"fetchRedirUrl $id returns ${response.body}")
      (xp \ "url").as[String]
    }
  }

}

object AvsCacheParser extends AvsCacheParser {
  //val p = new AvsCacheParser()
  //def fetchAviasalesCheapest(iataFrom:String) = p.fetchAviasalesCheapest(iataFrom)
  //def fetchRedirUrl(id:String) = p.fetchRedirUrl(id)
}

object AVSParser extends actors.AnyParser {
  val token = "67c3abc2accf4c0890e6b7f8192c7971"
  val marker = "33313"
  val id = "avs"


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
    (__ \ "label").read[String] and
    (__ \ "currency_code").read[String] 
  )(AVSGate)

  def parse(response:String) = {
    val xp = Json.parse(response)
    xp match {
      case JsString(s) => 
        Logger.error(s"aviasales Parsing error return String '$s'")
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSGate failed"))
      case _:JsValue => _parse(xp)
    }
  }
  def _parse(xp:JsValue) = {
    val searchId = (xp \ "search_id").as[String]

    val gates:Seq[AVSGate] = 
    (xp \ "gates_info" ).validate[Seq[AVSGate]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        Logger.error("aviasales.ru Parsing AVSGate Errors: " + JsError.toFlatJson(e).toString()) 
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
        Logger.error("aviasales.ru Parsing AVSAirline Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing AVSAirline failed"))
    }*/

    val cur2rub: Map[String,Float] = (xp \ "currency_rates" ).validate[Map[String,Float]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        Logger.error("aviasales.ru Parsing CurentcyRates Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing CurentcyRates failed"))
    }

    val g2cc = gates.map { x => 
      x.id.toString -> x.currency_code
    }.toMap
    
    val eur = cur2rub("eur")
    val cur2eur:Map[String,Float] = cur2rub.map { x => x._1 -> x._2 / eur }

    implicit val tReads: Reads[AVSTicket] = (
      (__ \ "sign").read[String] and
      (__ \ "main_airline").readNullable[String] and
      (__ \ "total").read[Float] and
      (__ \ "direct_flights").read[Seq[AVSFlights]] and
      (__ \ "return_flights").readNullable[Seq[AVSFlights]] and
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
            k -> s"$id:$searchId:${v.toString}" 
        }
      }
    )(AVSTicket.apply _)

    val data:Seq[AVSTicket] = 
    (xp \ "tickets" ).validate[Seq[AVSTicket]] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        Logger.error("aviasales.ru Parsing Errors: " + JsError.toFlatJson(e).toString()) 
        throw play.api.UnexpectedException(Some("aviasales.ru parsing failed"))
    }

    //val ret = s"len ${data.length}\n" + data

    //Ok(ret + "\n\n\n" )
    (data,gates,cur2eur,airlines)

  }
}