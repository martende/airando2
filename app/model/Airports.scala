package model

import play.api._
import play.api.Play.current 

import play.api.libs.json._
import play.api.libs.functional.syntax._

import utils.Utils2._
import play.api.i18n.Lang

object TravelType extends Enumeration {
  type TravelType = Value
  val OneWay, Return = Value
}

object FlightClass extends Enumeration {
  type FlightClass = Value
  val Economy, Business = Value
}

case class Gate(
  id:String ,
  currency: String,
  label:String
)

case class Flight(
  iataFrom: String,
  iataTo: String,
  airline: String,
  duration: Int,
  flnum: String,
  departure: java.util.Date,
  arrival: Option[java.util.Date],  
  aircraft: Option[String],
  delay: Int
)

case class Ticket(
  sign: String,
  direct_flights: Seq[Flight],
  return_flights: Option[Seq[Flight]],
  native_prices: Map[String,Float],
  order_urls: Map[String,String]
) {
  val dformatter = new java.text.SimpleDateFormat("yyyyMMddHHmm");
  
  def minPrice = native_prices.values.min

  def p2tuid(points:Seq[model.Flight]) = {
    val parts = points.toSeq.map {
      f => dformatter.format(f.departure) + f.iataFrom 
    } :+ (dformatter.format(points.last.arrival.get) + points.last.iataTo)
    parts.mkString(":")
  }

  lazy val tuid = {
    return_flights match {
      case None => p2tuid(direct_flights)
      case Some(rf) => p2tuid(direct_flights) + "-" + p2tuid(rf)
    }
  }
}

object Formatters {
  // Json Formatters 
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  /*
  implicit val flFormat:Writes[Flight] = new Writes[Flight] {
    def writes(v: Flight) = Json.obj(
      "iataFrom" -> v.iataFrom,
      "iataTo" -> v.iataTo,
      "airline" -> v.airline,
      "duration" -> v.duration,
      "flnum" -> v.flnum,
      "departure" -> v.departure,
      "arrival" -> v.arrival,
      "aircraft" -> v.aircraft,
      "delay" -> v.delay
    )
  }
  


  */
  implicit val dateFormat = new Writes[java.util.Date] {
    def writes(v:java.util.Date) = { 
        val df = new java.text.SimpleDateFormat("yyyyMMddHHmm");
        JsString(df.format(v))
    }
  }
  implicit val flFormat   = Json.format[Flight]
  
  implicit val tcktFormat:Writes[Ticket] = new Writes[Ticket] {
    def writes(v: Ticket) = Json.obj(
      "tuid" ->  v.tuid,
      "sign" ->  v.sign,
      "direct_flights" ->  v.direct_flights,
      "return_flights" ->  v.return_flights,
      "native_prices" ->  v.native_prices,
      "order_urls" ->  v.order_urls
    )
  }

}

case class TravelRequest(
  val iataFrom:String,
  val iataTo:String,
  val traveltype:TravelType.TravelType,
  val departure:java.util.Date,
  val arrival:java.util.Date,
  val adults:Int,
  val childs:Int,
  val infants:Int,
  val flclass:FlightClass.FlightClass
) {
  override def toString = s"Tr($iataFrom->$iataTo)"
}



class SearchResponse(tr:TravelRequest)
case class SearchResult(tr:TravelRequest,ts:Seq[Ticket]) extends SearchResponse(tr)
case class SearchError(tr:TravelRequest,e:Throwable) extends SearchResponse(tr)

case class GeoPoint(lon:Float,lat:Float) {
  def distance(t:GeoPoint) = {
    var R = 6371; // km (change this constant to get miles)
    var dLat = (t.lat-lat) * math.Pi / 180;
    var dLon = (t.lon-lon) * math.Pi / 180;
    var a = Math.sin(dLat/2) * Math.sin(dLat/2) +
      Math.cos(lat * math.Pi / 180 ) * Math.cos(t.lat * math.Pi / 180 ) *
      Math.sin(dLon/2) * Math.sin(dLon/2)
    var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
    var d = R * c;
    Math.round(d)
  }
}

case class FlightInfo(
  from:POI,
  to:POI,
  priceEur:Float,
  airline:String,
  departure: java.util.Date,
  arrival:    java.util.Date
)


trait POI {
  val iata:String
  val country_code:String
  val name_en:String
  val name_de:String
  val name_ru:String

  val country_en:String
  val country_de:String
  val country_ru:String

  val location:GeoPoint

  val time_zone:Int
	def distance(t:GeoPoint) = location.distance(t)

  def name(implicit l:Lang) = l.code match {
    case "en" => name_en
    case "de" => name_de
    case "ru" => name_ru
  }

  def city(implicit l:Lang):String

  val t:String

  def contains(q:String):Boolean

  def ratedContains(q:String)(implicit l:Lang):Int
}

case class Airport(iata:String,
  location:GeoPoint,
  time_zone:Int,
  city_iata:String,
  country_code:String,

  // coordinates:Option[Point],
	name_en:String,
  name_de:String,
  name_ru:String,
  
  city_en:String,
  city_de:String,
  city_ru:String,

  country_en:String,
  country_de:String,
  country_ru:String

	) extends POI {
  
  val t = "airport" 

  override def toString = s"Airport: $iata\nname: $name_en,$name_de,$name_ru\ncity: $city_en,$city_de,$city_ru\ncountry: $country_en,$country_de,$country_ru\nlocation: $location"

  def city(implicit l:Lang) = l.code match {
    case "en" => city_en
    case "de" => city_de
    case "ru" => city_ru
  }

  def contains(q:String) = 
    Seq(iata,name_en,name_de,name_ru,city_en,city_de,city_ru).exists(_.toLowerCase contains q)

  def ratedContains(q:String)(implicit l:Lang) = 
    if ( contains(q) ) {
      if ( q.length == 3 && iata.toLowerCase == q) 1
      else if ( city.toLowerCase.startsWith(q) ) 2
      else if ( name.toLowerCase.startsWith(q) ) 3
      else 4
    } else -1
    
}

case class CityPOI(iata:String,
  location:GeoPoint,
  time_zone:Int,
  country_code:String,

  name_en:String,
  name_de:String,
  name_ru:String,

  country_en:String,
  country_de:String,
  country_ru:String

) extends POI {
  val t = "city" 

  def city(implicit l:Lang) = l.code match {
    case "en" => name_en
    case "de" => name_de
    case "ru" => name_ru
  }

  def contains(q:String) = 
    Seq(iata,name_en,name_de,name_ru).exists(_.toLowerCase contains q)

  def ratedContains(q:String)(implicit l:Lang) = 
    if ( contains(q) ) {
      if ( q.length == 3 && iata.toLowerCase == q) 1
      else if ( name.toLowerCase.startsWith(q) ) 2
      else 4
    } else -1
}

object Airports {
	
	def mkDbRecord(iata:String,otype:String,
    location:GeoPoint,
    time_zone:Int,
    city_iata:Option[String],
    country_code:String,

    airport_en:Option[String],
    airport_de:Option[String],
    airport_ru:Option[String],

    city_en:String,
    city_de:String,
    city_ru:String,

    country_en:String,
    country_de:String,
    country_ru:String

  ):POI = {
    //if ( time_zone.isEmpty) {
    // println(s"IATA:$iata $time_zone -EMPTY TIM")
    //} 
    otype match {
      case "airport" => Airport(iata,location,time_zone,city_iata.getOrElse(iata),country_code,airport_en.get,airport_de.get,airport_ru.get,city_en,city_de,city_ru,country_en,country_de,country_ru)  
      case "city" => CityPOI(iata,location,time_zone,country_code,city_en,city_de,city_ru,country_en,country_de,country_ru)
    }
  }

	implicit val pointReads = Json.reads[GeoPoint]

	implicit val airportReads = (
    (__ \ "iata").read[String] and
    (__ \ "otype" ).read[String] and
    (__ ).read[GeoPoint] and
    (__ \ "timezone").read[Int]  and
    (__ \ "city_iata" ).readNullable[String] and

    (__ \ "country_code" ).read[String] and
    
    (__ \ "airport_name_en" ).readNullable[String] and
    (__ \ "airport_name_de" ).readNullable[String] and
    (__ \ "airport_name_ru" ).readNullable[String] and
    
    (__ \ "city_name_en" ).read[String] and
    (__ \ "city_name_de" ).read[String] and
    (__ \ "city_name_ru" ).read[String] and
    
    (__ \ "country_name_en" ).read[String] and
    (__ \ "country_name_de" ).read[String] and
    (__ \ "country_name_ru" ).read[String] 
    
  )(mkDbRecord _)

	
	Logger.info(s"Load Airports database devMode=${play.api.Play.isDev} testMode=${play.api.Play.isTest}")

	val citiesFile = Play.application.path + "/" + 
  	( if ( play.api.Play.isDev(play.api.Play.current) || play.api.Play.isTest(play.api.Play.current) ) "conf/airports.json" else "conf/airports.json" )
	
	val data:Seq[POI] = Json.parse(scala.io.Source.fromFile(citiesFile).mkString).validate[Seq[POI]] match {
		case s: JsSuccess[_] => s.get
		case e: JsError => 
			Logger.error("Errors: " + JsError.toFlatJson(e).toString()) 
			throw play.api.UnexpectedException(Some("cities.json loading failed"))
			???
	}
  
  val langs = Seq("en","de","ru")

  val slug2iata = (for ( lang <- langs ) yield {
      var name2iata = Map[String,String]()
      // for ( model.CityPOI(iata,_,_,_,name_en,_,_,_,_,_) <- model.Airports.data  ) {
      for ( el <- model.Airports.data if el.isInstanceOf[model.CityPOI] ) {        
        val iata = el.iata
        val name = el.name(Lang(lang,"")).toLowerCase.replace(" ","_")
        if (! name2iata.contains(name) )
          name2iata += name -> iata
      }
      
      for ( el <- model.Airports.data if el.isInstanceOf[model.Airport] ) {        
        val iata = el.iata
        val name = el.city(Lang(lang,"")).toLowerCase.replace(" ","_")
        name match {
          case _ if ! name2iata.contains(name) => name2iata += name -> iata
          case _ => 
            var i = 0
            while ( name2iata.contains(name + "_" + i.toString) ) i+=1
            name2iata += (name + "_" + i.toString) -> iata
        }
        
      }
      
      lang -> name2iata

    }).toMap

  val iata2slug = slug2iata.map {
    case (k,v) => k -> v.map(_.swap)
  }

	Logger.info(s"Airports loaded length=${data.length}")

  implicit object nearestOrdering extends Ordering[(Long,POI)] {
    def compare(a:(Long,POI), b:(Long,POI)) = a._1 compare b._1
  }

	def nearest(q:GeoPoint,limit:Int=1) = {
    data.map(x => (x.distance(q) -> x) ).mins(limit).map(_._2)
  }

  def suggest(q:String)(implicit l:Lang) = {
    val ql = q.toLowerCase

    println( ( for (x <- data; r = x.ratedContains(ql) ; if r>0 ) yield r->x )   )
    ( for (x <- data; r = x.ratedContains(ql); if r>0) yield r->x ).sortBy(_._1).map(_._2).take(20)
/*
    data.filter { x => 
      x.ratedContains(ql) > 0 
    }.take(20)
    */
  }

  def get(iata:String) = data.find(_.iata==iata) orElse data.find { 
    case ap:Airport => ap.city_iata==iata
    case _ => false 
  }

  def exists(iata:String):Boolean = ! get(iata).isEmpty
	/*
  def nearest(q:Seq[AirportInfo],limit:Int=5) = {
    _data/*.filter(! q.contains(_) )*/.map(x => {
      x -> q.map(_.distance(x)).min
    }).sortWith(_._2 < _._2).take(5)
    //get("MSQ")
  }
	*/
}


object Avialines {

  lazy val name2iata = {
    val citiesFile = "conf/airlines.csv"

    Logger.info(s"Load avialines info from $citiesFile")

    val lines = scala.io.Source.fromFile(citiesFile).getLines()
    lines.map { x => 
      //println(x.trim.split(";").toList)
      val Array(iata,name) = x.trim.split(";")
      name.replaceAll(" ","").replaceAll("-","").toLowerCase -> iata.toUpperCase
      
    }.toMap
  }

  def getByName(name:String) = name2iata.get(name.toLowerCase.replaceAll(" ","").replaceAll("-",""))

}