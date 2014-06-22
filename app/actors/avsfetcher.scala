package actors.avsfetcher

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import play.api.Logger

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
  order_urls: Map[String,Int]
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




object AVSParser {

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
      (__ \ "order_urls").read[Map[String,Int]]
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