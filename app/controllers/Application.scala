package controllers

import play.api._
import play.api.mvc._

// implicit for Play.application usage
import play.api.Play.current

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import play.api.i18n.Lang

// Futures
import scala.concurrent.{Future}

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

import org.joda.time.DateTime
import java.net.URLEncoder

object Application extends Controller with CookieLang with Track {

	val ipGeo = {
		val maxmindDb = Play.application.path + "/" + Play.current.configuration.getString("maxminddb").get
		utils.IpGeo(maxmindDb, memCache = false, lruCache = 10000)
	}

  def index = Action.async {
  	implicit request =>

    val ipaddr:String = request.headers.get("X-Real-IP").getOrElse(request.remoteAddress)

    var ipInfo:utils.IpLocation = ipGeo.getLocation(ipaddr).getOrElse(utils.IpLocation.empty)
    
    Logger.info(s"Index IP:${ipaddr} $ipInfo")

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

  val re = """from-([^-]+)-to-([^-]+)-at-(\d{8})(\d{8})?.html""".r
  
  def flights(page:String) = {
    implicit request => 
  
    page match {
      case re(nameFrom,nameTo,dateFrom,dateTo) => 
        val iataFrom = "HEL"
        val iataTo   = "BER"
        val fltype   =  if ( dateTo == null ) TravelType.OneWay else TravelType.Return
        val tr = model.TravelRequest(iataFrom,iataTo,
        TravelType.OneWay,
        DateTime.now().plusDays(3).toDate(),
        DateTime.now().plusDays(10).toDate(),
        1,0,0,FlightClass.Economy
        ),
        Airports.get("HEL").get,
        Airports.get("BER"),
        currency
        )

        Ok(views.html.result(tr,from,Airports.get(tr.iataTo),currency))
      case _ => NotFound.asInstanceOf[SimpleResult]
    }
  }

  def result(id:Int) = Action {
    implicit request => 
    val defaultTravelInfo = //NotFound.asInstanceOf[SimpleResult]
      Ok(views.html.result(
        model.TravelRequest(
        "HEL",
        "BER",
        TravelType.OneWay,
        DateTime.now().plusDays(3).toDate(),
        DateTime.now().plusDays(10).toDate(),
        1,0,0,FlightClass.Economy
        ),
        Airports.get("HEL").get,
        Airports.get("BER"),
        currency
        )
      )
    //getActorRef(id)
    val currency = request.cookies.get("currency").map(_.value).getOrElse("eur");

    actors.Manager.getTravelInfo(id).fold(
      defaultTravelInfo
    ) {
      tr => 
        Airports.get(tr.iataFrom).fold(NotFound.asInstanceOf[SimpleResult]) {
          from => 
            Ok(views.html.result(tr,from,Airports.get(tr.iataTo),currency))
        }
    }
  }

  private def forFormat(df:String,id:String):(String,String,String,String) = {
    val inpFormat = new java.text.SimpleDateFormat("yyyyMMdd");
    val outFormat = new java.text.SimpleDateFormat(df);

    val i = id.indexOf("-")
    val idl = id.length
    if ( i == -1 ) {
      // 01.08.2013
      val d0 = outFormat.format(inpFormat.parse(id.substring(0,8)))
      val iata0 = id.substring(12,15)
      val iata1 = id.substring(idl-3,idl)
      (iata0,iata1,d0,"")
    } else {
      // 01.08.2013
      val d0 = outFormat.format(inpFormat.parse(id.substring(0,8)))
      val d1 = outFormat.format(inpFormat.parse(id.substring(i+1,i+9)))
      val iata0 = id.substring(12,15)
      val iata1 = id.substring(i-3,i)
      (iata0,iata1,d0,d1)
    }
  }
  
  private def mkParams(m:Map[String,String]) = 
    m.map(x => x._1 + "=" + URLEncoder.encode(x._2, "UTF-8") ).mkString("&")


  def redirect(adults:Int,children:Int,infants:Int,id:String) = Action.async {
    val ti = id.indexOf(":")
    val inpFormat = new java.text.SimpleDateFormat("yyyyMMdd");
    val (idd,idc) = ( id.substring(0,ti),id.substring(ti+1) )
    if ( ti != -1 ) idd match {
      case "avs" =>
        import actors.AvsCacheParser
        AvsCacheParser.fetchRedirUrl(id.substring(4)).map {
          url => Results.Found(url)
        }

      case "CHPA" =>
        val (iataFrom,iataTo,departure,arrival) = forFormat("MM/dd/yyyy",idc)
        // 
        val aParams:Map[String,String] = Map( 
          "dt1" -> departure,
          "dt2" -> arrival,
          "aid" -> "11206255",
          "pid" -> "7621953",
          "url" -> "http://www.cheapair.com/air/?uid=397",
          "AID" -> "11206255",
          "PID" -> "7621953",
          "uid" -> "397",
          "rt"  -> ( if (arrival == "") "O" else "R"),
          "from1" -> iataFrom,
          "to1" -> iataTo,
          "adults" -> adults.toString
        )

        val url = s"http://www.jdoqocy.com/interactive?" + mkParams(aParams)
        Future.successful(Results.Found(url))

      case "DY" => 
        // 
        val dFormat = new java.text.SimpleDateFormat("d");
        val mFormat = new java.text.SimpleDateFormat("yyyyMM");
        val i = id.indexOf("-")
        val idl = id.length
        val (iataFrom,iataTo,dday,dmonth,rday,rmonth,fltype) = if ( i == -1 ) {
          // 01.08.2013
          val d0 = inpFormat.parse(id.substring(3,11))
          val iata0 = id.substring(15,18)
          val iata1 = id.substring(idl-3,idl)
          (iata0,iata1,dFormat.format(d0),mFormat.format(d0),"","",1)
        } else {
          // 01.08.2013
          val d0 = inpFormat.parse(id.substring(3,11))
          val d1 = inpFormat.parse(id.substring(i+1,i+9))
          val iata0 = id.substring(15,18)
          val iata1 = id.substring(i-3,i)
          (iata0,iata1,dFormat.format(d0),mFormat.format(d0),dFormat.format(d1),mFormat.format(d1),1)
        }

        val url = s"http://www.norwegian.com/en/flight/select-flight/?D_City=$iataFrom&A_City=$iataTo&TripType=$fltype&D_Day=$dday&D_Month=$dmonth&R_Day=$rday&R_Month=$rmonth&AdultCount=$adults&ChildCount=$children&InfantCount=$infants"
        Future.successful(Results.Found(url))
      case "LX" => 
        // swiss.com
        //LX:201408110950ZRH:201408111235JFK:201408182100JFK:201408191050ZRH
        // http://clk.tradedoubler.com/click?p=80201&a=2429603&g=18602412&url=http://tracking.mlsat03.de/swiss/affiliate/custom-forwarding.php?kid=571&dlid=10&fwid=34715&origin=CGN&destination=BER&depart=01.08.2012&inbound=01.08.2013&adults=3&children=2&infants=1&country=DE
        val (iataFrom,iataTo,departure,arrival) = forFormat("dd.MM.yyyy",idc)

        val country = "DE"
        val url = s"http://clk.tradedoubler.com/click?p=80201&a=2429603&g=18602412&url=http://tracking.mlsat03.de/swiss/affiliate/custom-forwarding.php?kid=571&dlid=10&fwid=34715&origin=$iataFrom&destination=$iataTo&depart=$departure&inbound=$arrival&adults=$adults&children=$children&infants=$infants&country=$country"
        Future.successful(Results.Found(url))
      case "TP" =>
        // http://book.flytap.com/r3air/TAPDE/PoweredAvailabilityBP.aspx?origin=FRA&destination=AGP&flightType=Return&depDate=16.08.2014&retDate=23.08.2014&cabinClass=Y&ccSearch=P&adt=1&depTime=0&retTime=0&market=DE&_l=de&maxConn=-1&pageTrace=6

        val lang = "en"

        val (iataFrom,iataTo,departure,arrival) = forFormat("dd.MM.yyyy",idc)
        val fltype = if (arrival =="" ) "Single" else "Return" 
                
        val country = "DE"
        val _iurl = "http://book.flytap.com/r3air/TAPDE/PoweredAvailabilityBP.aspx?origin=$iataFrom&destination=$iataTo&flightType=$fltype&depDate=$departure&retDate=$arrival&cabinClass=Y&ccSearch=P&adt=$adults&depTime=0&retTime=0&market=$country&_l=$lang&maxConn=-1&pageTrace=6"
        val iurl = URLEncoder.encode(_iurl, "UTF-8")

        val url = s"http://clkuk.tradedoubler.com/click?p(242403)a(2429603)g(21639146)url($iurl)"
        Future.successful(Results.Found(url))
    } else {
      Future.successful(NotFound)
    }
  }
}