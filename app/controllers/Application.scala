package controllers

import play.api._
import play.api.mvc._

// implicit for Play.application usage
import play.api.Play.current

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import play.api.i18n.Lang

// Forms
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.{Constraint,Valid,Invalid,ValidationError}
import play.api.data.format.Formatter
// Locals
import model._
// TravelType enum import
import model.TravelType._
import model.FlightClass._

// implicit for Akka ExecutionContext.Implicits.global
import play.api.libs.concurrent.Execution.Implicits._

object Application extends Controller with CookieLang with Track {

	val ipGeo = {
		val maxmindDb = Play.application.path + "/" + Play.current.configuration.getString("maxminddb").get
		utils.IpGeo(maxmindDb, memCache = false, lruCache = 10000)
	}

  def index = Action.async {
  	implicit request =>
  	
    var ipInfo:utils.IpLocation = ipGeo.getLocation(request.remoteAddress).getOrElse(utils.IpLocation.empty)
    
    Logger.info(s"Index IP:${request.remoteAddress} $ipInfo")

    val from = request.cookies.get("from").flatMap(Airports get _.value).getOrElse(Airports.nearest(GeoPoint(ipInfo.longitude,ipInfo.latitude),1)(0))

    val to = request.cookies.get("to").flatMap(Airports get _.value)

    val traveltype = request.cookies.get("traveltype").map(_.value).getOrElse("return");

    val arrival   = request.cookies.get("arrival").map(_.value).getOrElse("");
    val departure = request.cookies.get("departure").map(_.value).getOrElse("");
    val currency = request.cookies.get("currency").map(_.value).getOrElse("eur");

    val cheapestPromise = actors.Manager.getCheapest(from.iata)

    cheapestPromise.map { cheapest => 
      val cheapestTaken = scala.util.Random.shuffle(cheapest.sortBy(_.priceEur).take(50)).take(10).sortBy(_.priceEur)
      Ok(views.html.index(from,to,traveltype,currency,arrival,departure,cheapestTaken))
    }

    
  }

  def term(q:String) = Action {
  	implicit request =>

    implicit val creatureWrites = new Writes[POI] {
	  def writes(c: POI): JsValue = {
	  	c match {
	  		case x:Airport => 
	  			Json.obj(
			    	"iata" -> x.iata,
            "city_iata" -> x.city_iata,
			    	"name" -> x.name,
			    	"city" -> x.city,
            "country_code" -> x.country_code,
			    	"t" -> "airport"
	    		)
	  		case x:CityPOI    => 
	  			Json.obj(
            "iata" -> c.iata,
            "name" -> c.name,
            "city" -> c.city,
            "country_code" -> x.country_code,
            "t" -> "city"
          )
	  	}
	  }
	}

    val suggestions = Airports.suggest(q)
    val ret = Json.obj(
      "total"  -> suggestions.length,
      "result" -> Json.toJson(suggestions)
     )
    Ok(ret)
  }

  def start = Action {
    implicit request =>

    val iataConstraint: Constraint[String] = Constraint("constraints.iata")({
      plainText => 
        if ( Airports.exists(plainText) ) Valid else Invalid(Seq(ValidationError("Not found")))
    })

    val iata: Mapping[String] = nonEmptyText(minLength = 3,maxLength=3)
      .verifying(iataConstraint)

    implicit val ttFormat = new Formatter[TravelType] {
      def bind(key: String, data: Map[String, String]):Either[Seq[FormError], TravelType] = {
        data.get(key) match {
          case Some("oneway") => Right(TravelType.OneWay)
          case Some("return") => Right(TravelType.Return)
          case None => Left(Seq(FormError(key, "traveltype not found", Nil)))
          case _ => Left(Seq(FormError(key, "traveltype error", Nil)))
        }
      }
      def unbind(key: String, value: TravelType) = value match {
        case TravelType.Return => Map(key -> "return")
        case TravelType.OneWay => Map(key -> "oneway")
      }
    }
    implicit val fcFormat = new Formatter[FlightClass] {
      def bind(key: String, data: Map[String, String]):Either[Seq[FormError], FlightClass] = {
        data.get(key) match {
          case Some("economy")  => Right(FlightClass.Economy)
          case Some("business") => Right(FlightClass.Business)
          //case None => Right(FlightClass.Economy)
          case _ => Right(FlightClass.Economy)
        }
      }
      def unbind(key: String, value: FlightClass) = value match {
        case FlightClass.Economy  => Map(key -> "exonomy")
        case FlightClass.Business => Map(key -> "business")
      }
    }

    val searchForm = Form(
      mapping(
        "from" -> iata,
        "to" -> iata,
        "traveltype" -> of[TravelType],
        "departure" -> date("yyyyMMdd"),
        "arrival" -> date("yyyyMMdd"),
        "adults"  -> default(number,1),
        "childs"  -> default(number,0),
        "infants"  -> default(number,0),
        "flclass"  -> of[FlightClass]
      )(model.TravelRequest.apply)(model.TravelRequest.unapply)
    )

    searchForm.bindFromRequest.fold(
      formWithErrors => {
        Ok(Json.obj("id" -> "params1","errors"->formWithErrors.errorsAsJson))
      },

      userData => {
        Logger.info(s"Start searching tr:$userData")
        val searchActor = actors.Manager.startSearch(userData)
        Ok(Json.obj(
          "id" -> searchActor
        ))
      }
    )

  }

  def result(id:Int) = Action {
    implicit request => 
    //getActorRef(id)
    val currency = request.cookies.get("currency").map(_.value).getOrElse("eur");

    actors.Manager.getTravelInfo(id).fold(
      //NotFound.asInstanceOf[SimpleResult]
      Ok(views.html.result(
        model.TravelRequest(
        "HEL",
        "BER",
        TravelType.OneWay,
        new java.util.Date(114,7,12),
        new java.util.Date(114,7,18),
        1,0,0,FlightClass.Economy
        ),
        Airports.get("HEL").get,
        Airports.get("BER"),
        currency
        )
      )
    ) {
      tr => 
        Airports.get(tr.iataFrom).fold(NotFound.asInstanceOf[SimpleResult]) {
          from => 
            Ok(views.html.result(tr,from,Airports.get(tr.iataTo),currency))
        }
        
    }
  }

  def redirect(id:String) = Action.async {
    if ( id.substring(0,4) == "avs:") {
      import actors.avsfetcher.AvsCacheParser
      AvsCacheParser.fetchRedirUrl(id.substring(4)).map {
        url => Results.Found(url)
      }
    } else ???
  }
}