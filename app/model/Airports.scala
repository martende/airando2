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

case class TravelRequest(
  val iataFrom:String,
  val iataTo:String,
  val traveltype:TravelType.TravelType,
  val departure:java.util.Date,
  val arrival:java.util.Date
)

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

	def distance(t:GeoPoint) = location.distance(t)

  def name(implicit l:Lang) = l.code match {
    case "en" => name_en
    case "de" => name_de
    case "ru" => name_ru
  }

  def city(implicit l:Lang):String

  val t:String

  def contains(q:String):Boolean
}

case class Airport(iata:String,
  location:GeoPoint,
  city_iata:String,
  country_code:String,
  // coordinates:Option[Point],
  //time_zone:String,
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
}

case class CityPOI(iata:String,
  location:GeoPoint,
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
}

object Airports {
	
	def mkDbRecord(iata:String,otype:String,
    location:GeoPoint,
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
    otype match {
      case "airport" => Airport(iata,location,city_iata.getOrElse(iata),country_code,airport_en.get,airport_de.get,airport_ru.get,city_en,city_de,city_ru,country_en,country_de,country_ru)  
      case "city" => CityPOI(iata,location,country_code,city_en,city_de,city_ru,country_en,country_de,country_ru)
    }
  }

	implicit val pointReads = Json.reads[GeoPoint]

	implicit val airportReads = (
    (__ \ "iata").read[String] and
    //(__ \ "time_zone").read[String]  and
    (__ \ "otype" ).read[String] and
    (__ ).read[GeoPoint] and
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
  
	Logger.info(s"Airports loaded length=${data.length}")

  implicit object nearestOrdering extends Ordering[(Long,POI)] {
    def compare(a:(Long,POI), b:(Long,POI)) = a._1 compare b._1
  }

	def nearest(q:GeoPoint,limit:Int=1) = {
    data.map(x => (x.distance(q) -> x) ).mins(limit).map(_._2)
  }

  def suggest(q:String) = {
    val ql = q.toLowerCase
    data.filter(_ contains ql).take(20)
  }
  def get(iata:String) = data.find(_.iata==iata)

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