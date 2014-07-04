package actors

import play.api.Logger
import scala.util.{Success, Failure}

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import akka.actor.ActorRef

import model.FlightClass._

case class NRFlight(
  iataFrom: String,
  iataTo: String,
  depdate: java.util.Date,
  avldate: java.util.Date,  
  flclassStr:String,
  flnum: String
)

case class NRTicket(
  price: Float,
  flnum: String,
  depdate: java.util.Date,
  avldate: Option[java.util.Date],
  direct_flights: Seq[NRFlight],
  flclass: FlightClass
)

case class NRRequest(
  iataFrom:String,
  iataTo:String,
  adults:Int,
  children:Int,
  infants:Int,
  tickets: Seq[NRTicket]
)

trait PushResultsParser {
  val dateFormat = "yyyy-MM-dd'T'HH:mm"

  implicit val fReads = (
    (__ \ "iataFrom").read[String] and
    (__ \ "iataTo").read[String] and
    (__ \ "depdate").read[java.util.Date](Reads.dateReads(dateFormat))  and
    (__ \ "avldate").read[java.util.Date](Reads.dateReads(dateFormat))  and
    (__ \ "flclass").read[String] and
    (__ \ "flnum").read[String]
  )(NRFlight)

  implicit val tReads =  (
    (__ \ "price").read[String].map(_.toFloat) and
    (__ \ "flnum").read[String] and
    (__ \ "depdate").read[java.util.Date](Reads.dateReads(dateFormat))  and
    (__ \ "avldate").read[java.util.Date](Reads.dateReads(dateFormat))  and
    (__ \ "direct_flights").read[Seq[NRFlight]] and
    (__ \ "flclass").read[String].map {
      case "lowfare" => Economy
      case _ => Business
    }
  )(NRTicket)

  implicit val rReads = (
    (__ \ "iataFrom").read[String] and
    (__ \ "iataTo").read[String] and
    (__ \ "adults").read[Int] and
    (__ \ "children").read[Int] and
    (__ \ "infants").read[Int] and
    (__ \ "tickets").read[Seq[NRTicket]] 
  )(NRRequest)


  def processPushResults(v:JsValue):NRRequest = {
    val ret:NRRequest = v.validate[NRRequest] match {
      case s: JsSuccess[_] => s.get
      case e: JsError => 
        throw new PhantomProcessException("Norvegianairlines push Parsing Errors: " + JsError.toFlatJson(e).toString())
    }
    ret
  }
}

class NorvegianAirlines extends BaseFetcherActor with PushResultsParser {
  import context.dispatcher
  import context.become

  var srcIatas:Set[String] = null
  var dstIatas:Set[String] = null
  var tickets = Array[NRTicket]()

  case class UpdateSrcIatas(v:Set[String])
  case class UpdateDstIatas(v:Set[String])
  case class AddTickets(v:Seq[NRTicket])

  def waitAnswer(sender:ActorRef):PartialFunction[Any, Unit] = {
    case UpdateSrcIatas(v) => srcIatas = v
    case UpdateDstIatas(v) => dstIatas = v
    case AddTickets(v:Seq[NRTicket])     => tickets = tickets ++ v 
  }
  
  def iataMapper(iata:String) = if (iata == "BER" ) "BERALL" else iata
  def receive = {
    
    case StartSearch(tr) => 
      val _sender:ActorRef = sender
      Logger.info(s"StartSearch ${tr}")
        
      become(waitAnswer(_sender))
      
      val dformatter = new java.text.SimpleDateFormat("yyyy-MM-dd");

      execAsync(Seq("./bin/phantomjs","--proxy=http://127.0.0.1:3128",
        "phantomjs/fetcher.js","norvegianairlines",
        iataMapper(tr.iataFrom),
        iataMapper(tr.iataTo),
        dformatter.format(tr.departure),
        "ANY",
        tr.adults.toString,
        tr.childs.toString,
        tr.infants.toString)) {
        x:String => 
          if ( x.startsWith("Can't open '") )
            throw new PhantomInitException(x)
          else if ( x.startsWith("PUSH:") ) {
            val data = x.substring(5)
            Logger.debug( "Push: " + data ) 
            Json.parse(data) match {
              case JsString(s) => 
                throw new PhantomInitException(s"push awaits JSON as answer but string found '$s'")
              case JsObject(pairs) => 
                for ((k,v) <- pairs) {
                  processPush(k,v)
                }
                //processPush(js.)
              case s => 
                throw new PhantomInitException(s"push awaits JSON as answer but found '$s'")
            }
          } else if ( x.startsWith("ERROR:") ) {
            val data = x.substring(6)
            println(data)
            data match {
              case "NOFLIGHTS" => throw new NoFlightsException()
              case _ => throw new PhantomProcessException(s"Error received: $data")
            }
          } else
            Logger.info(s"Recv $x")
      } onComplete {
        case Failure(e:NoFlightsException) => 
          Logger.warn(s"Searching ${tr.iataFrom}->${tr.iataTo} is not availible" )
          _sender ! SearchResult(tr,List())
        case Failure(e) => 
          Logger.error("Searching failed " + e.toString)
          _sender ! SearchResult(tr,List())

        case Success(x) => 
          _sender ! "1"
          println("Complete ",x)
      }


  }



  def processPush(k:String,v:JsValue) {
    k match {
      case "avl" => self ! UpdateSrcIatas(v.as[List[String]].toSet)
      case "dep" => self ! UpdateDstIatas(v.as[List[String]].toSet)
      case "results" => 
        val r = processPushResults(v)
        println(r)
      case _ => Logger.error("processPush: unknown key " + k)
    }
  }
}
