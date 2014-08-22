package actors

import play.api.Logger
import scala.util.{Success, Failure}

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import akka.actor.ActorRef

import model.FlightClass._
import scala.concurrent.duration._

import org.joda.time.DateTime
import model.SearchResult

case class NRFlight(
  iataFrom: String,
  iataTo: String,
  depdate: java.util.Date,
  avldate: Option[java.util.Date],  
  flclassStr:String,
  flnum: String
) {
  def withArrivalDate(d:java.util.Date) = NRFlight(iataFrom,iataTo,depdate,Some(d),flclassStr,flnum)
}

case class NRTicket(
  price: Float,
//  flnum: String,
  depdate: java.util.Date,
  avldate: java.util.Date,
  direct_flights: Seq[NRFlight],
  flclass: FlightClass
) {
  val dformatter = new java.text.SimpleDateFormat("yyyyMMddHHmm");
  lazy val tuid = {
    val parts = direct_flights.map {
      f => dformatter.format(f.depdate) + f.iataFrom 
    } :+ (dformatter.format(avldate) + direct_flights.last.iataTo)
    parts.mkString(":")
  }
}

case class NRRequest(
  iataFrom:String,
  iataTo:String,
  adults:Int,
  children:Int,
  infants:Int,
  tickets: Seq[NRTicket]
)


class NorvegianAirlines extends BaseFetcherActor  {
  import context.dispatcher
  import context.become

  val logger = Logger("NorvegianAirlines")

  var srcIatas:Set[String] = null
  var dstIatas:Set[String] = null

  case class UpdateSrcIatas(v:Set[String])
  case class UpdateDstIatas(v:Set[String])
  case class AddTickets(v:Seq[NRTicket])
  case class Complete()

  var rqIdx = 0
  /*override def preStart() {
    logger.info("Started")
  }
  
  override def postStop() = {
    logger.info("postStop") 
  }

  override def preRestart(reason:Throwable,message:Option[Any]) {
    logger.info(s"ReStarted $reason $message")
    
    message match {
      case Some(x) => self forward x
      case None => 
    }

  }

  */

  def waitAnswer(sender:ActorRef,currentRequest:model.TravelRequest ):PartialFunction[Any, Unit] = {
    var tickets = Array[NRTicket]()
    val _waiter:PartialFunction[Any, Unit] = {
    case UpdateSrcIatas(v) => 
      logger.info(s"UpdateSrcIatas ${v.size}")
      srcIatas = v
    case UpdateDstIatas(v) => 
      logger.info(s"UpdateDstIatas ${v.size}")
      dstIatas = v
    case AddTickets(v:Seq[NRTicket]) => tickets = tickets ++ v
    
    case Complete() => 
      logger.info(s"Search $currentRequest: Completed: ${tickets.length} tickets found")
      val tickets2send = tickets.groupBy(_.tuid).map {
        t => 
        if ( currentRequest.flclass ==  Business)
          t._2.filter( _.flclass == Business ).minBy(_.price)  
        else 
          t._2.minBy(_.price)
      }.map {
        tkt:NRTicket => model.Ticket(tkt.tuid,tkt.direct_flights.map {
          fl => model.Flight(fl.iataFrom,fl.iataTo,"DY",0,fl.flnum,fl.depdate,fl.avldate,None,0)
        },None,Map("DY"->tkt.price),Map("DY" -> ("DY:"+tkt.tuid)) )
      }.toSeq

      sender ! SearchResult(currentRequest,tickets2send)
      become(receive)

    case x => logger.error(s"unknown action $x")
    }  
    _waiter
  }
  
  
  def iataMapper(iata:String) = if (iata == "BER" ) "BERALL" else iata
  def receive = {
    case StartSearch(tr) => 
      processSearch(sender,tr)
    
  }

  def processSearch(_sender:ActorRef,tr:model.TravelRequest) = {
    rqIdx+=1
    logger.info(s"StartSearch:${rqIdx} ${tr}")
    become(waitAnswer(_sender,tr))
    if ( srcIatas != null && ! srcIatas.contains(tr.iataFrom)) {
        logger.warn(s"No routes for iataFrom:${tr.iataFrom} availible")
        self ! Complete()
    } else if ( dstIatas != null && ! dstIatas.contains(tr.iataTo)) {
        logger.warn(s"No routes for iataTo:${tr.iataTo} availible")
        self ! Complete()
    } else doRealSearch(tr)
  }

  val pageLoadimeout = 2 seconds
  val pageResultTimeout = 5 seconds
  def doRealSearch(tr:model.TravelRequest) {
    import actors.PhantomExecutor
    class NotAvailibleDirecttion extends Throwable;
    val dformatter = new java.text.SimpleDateFormat("yyyy-MM-dd");
    val src = iataMapper(tr.iataFrom)
    val dst = iataMapper(tr.iataTo)
    val traveltype = if ( tr.traveltype == model.TravelType.OneWay ) 1 else 2
    val departure = dformatter.format(tr.departure)
    val dep_dd = departure.split("-")(2)
    val dep_yyyymm = departure.split("-").take(2).mkString("")
    val adults = tr.adults
    val childs = tr.childs
    val infants = tr.infants

    val r = PhantomExecutor.open("http://www.norwegian.com/en/",isDebug=false )
    var Success(p) = try {
      scala.concurrent.Await.result(r,pageLoadimeout) 
    } catch {
      case ex:Throwable => 
        logger.error( s"Parsing: $tr failed - fetching failed $ex" )
        self forward StartSearch(tr)
        throw ex
    }
    
    try {
      /*
        import java.util.Random
        var random = new Random
        if (! random.nextBoolean()) throw new Exception("AAAAA")
      */
        val depEl = p.$("select.selectdestination").find {x => x.attr("id").indexOf("Departure") != -1}.get
        val avlEl = p.$("select.selectdestination").find {x => x.attr("id").indexOf("Arrival")   != -1}.get
        def mkCodes(el:PhantomExecutor.Selector) = el.$("option").map {el => 
          val id = el.attr("value").toUpperCase 
          var name =  """\W+\(\w+\)$""".r.replaceAllIn(el.innerText,"").toLowerCase 
          (id -> name )
        }.filter { case (id,name) => id.length == 3 || ( id.length == 6 && id.substring(3) == "ALL" ) }.toMap

        val avlMap = mkCodes(depEl)
        val depMap = mkCodes(depEl)

        val name2iata = avlMap.map(_.swap) ++ depMap.map(_.swap)
        val iatas = ( avlMap ++ depMap ).keys.toSeq.sorted
        
        p.selectJSSelect(
          p.$(".webpart.first .select2-container.selectdestination").at(0),
          p.$(".webpart.first .select2-dropdown-open ul.select2-results .select2-result").find( _.innerText matches ".*\\("+src+"\\)$"  ).getOrElse(throw new NotAvailibleDirecttion())
        )

        p.selectJSSelect(
          p.$(".webpart.first .select2-container.selectdestination").at(1),
          p.$(".webpart.first .select2-dropdown-open ul.select2-results .select2-result").find( _.innerText matches ".*\\("+dst+"\\)$"  ).getOrElse(throw new NotAvailibleDirecttion())
        )

        p.selectRadio(p.$("input[name$=TripType]"),traveltype.toString)
        p.selectOption(p.$("select[name$=DepartureDay]").at(0),dep_dd)
        p.selectOption(p.$("select[name$=DepartureMonth]").at(0),dep_yyyymm,true)

        p.selectOption(p.$("select[name$=AdultCount]").at(0),adults.toString)
        p.selectOption(p.$("select[name$=ChildCount]").at(0),childs.toString)
        p.selectOption(p.$("select[name$=InfantCount]").at(0),infants.toString)

        p.$(".first .search a").click();

        scala.concurrent.Await.result({
          p.waitForSelector(p.$(".ErrorBox,.WarnBox,#avaday-outbound-result",".pagefooterbox") , pageResultTimeout * 2 ).recoverWith {
            case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
          }
        },pageResultTimeout)
        
        
        if ( p.$(".ErrorBox").exists()) {
          if ( p.$(".ErrorBox").innerHTML.indexOf("We could not find any flights on the selected dates") != -1) {
            throw new NoFlightsException()
          } else {
            throw new ParseException("ErrorBox contains unknown error: " + p.$(".ErrorBox").innerText )
          }
        }

        if ( p.$(".WarnBox").exists()) {
          val html = p.$(".WarnBox").innerHTML
          if ( html.indexOf("We could not find any flights on the specified date, please use the Fare calendar to find an available date") != -1 ||
                html.indexOf("We could not find any flights on the selected dates.") != -1
            ) {
            throw new NoFlightsException()
          } else {
            throw new ParseException("WarnBox contains unknown error: " + p.$(".WarnBox").innerText )
          }
        }
        
        testOn("Results page not recongnized") {
          p.$("#avaday-outbound-result").exists()
        }
        
        def asDepTime(d:String) = {
          val r = """\d\d:\d\d""".r
          r findFirstIn d match {
            case Some(v) => v
            case None    => throw new ParseException(s"Departure format error: '$d'")
          }
        }

        case class AvlTime(h:Int,m:Int,daysAdd:Int)

        def asAvlTime(d:String):AvlTime = {
          val r = """(\d\d):(\d\d)( \+1)?""".r
          r findFirstIn d match {
            case Some(r(hh,mm,p)) => AvlTime(hh.toInt,mm.toInt,if ( p == null ) 0 else 1 )
            case None    => throw new ParseException(s"Arrival format error: '$d'")
          }
        }

        def asFlNum(d:String):String = {
          val r = """Flight\W(\w+)""".r
          r findFirstIn d match {
            case Some(r(flnum)) => flnum.toUpperCase
            case None    => throw new ParseException(s"Flight number format error: '$d'")
          }
        }

        def asDate(v:String) = {
          val df = new java.text.SimpleDateFormat("E d. MMM y h:m")
          df.parse(v)
        }

        def asPrice(d:String) = {
          val r = """\d+\.\d{1,2}""".r
          r findFirstIn d.replace(",","").replace("\n","") match {
            case Some(v) => v.toFloat
            case None    => throw new ParseException(s"Price format error: '$d'")
          }
        }

        def asFlClass(cn:String) = {
          val classMappings = Seq("standardlowfare"->"lowfare","standardflex"->"flex","premiumflex"->"premiumflex","premiumlowfare"->"premiumlowfare","lowfare"->"lowfare","flex" -> "flex","premium" -> "premiumlowfare")
          classMappings collectFirst { case (k,v) if cn.indexOf(k) != -1 => v } get
        }

        def findIata(name:String):String = 
          name2iata.getOrElse(name,{
            val li = name.lastIndexOf("/")
            if ( li != 1 ) findIata(name.substring(0,li).trim()) else 
            throw new ParseException("iata for $'name' not found") 
          })
        

           
        def asDirections(v:String) = {
          var els = v.replace("\n","").toLowerCase.split(" - ")
          if (els.length == 2) {
            ( findIata(els(0)), findIata(els(1)) )
          } else {
              els(0) -> els(1)
          }
        }

        case class FlPoint(directions:Pair[String,String],
          depdate:java.util.Date,flclass:String,flnum:String)

        val fetchedTickets:Traversable[NRTicket] = for (priceEl <- p.$("#avaday-outbound-result .avadaytable tr.rowinfo1 input[type=radio]")) yield {
          val td = priceEl.parentNode.parentNode
          val prctd = td.nextSibling
          val tr = td.parentNode
          val flclass = asFlClass(td.className) match {
            case "lowfare" => Economy
            case _ => Business
          }

          val dep = asDepTime(tr.$(".depdest").innerText)
          val avl = asAvlTime(tr.$(".arrdest").innerText)
          val priceForOne = asPrice(prctd.innerText)
          val pricesTable = p.$("table.selectiontable").at(0)

          pricesTable.attr("dirty","1")
          priceEl.click()
          
          scala.concurrent.Await.result({
            p.waitForSelectorAttr(pricesTable,"dirty","",timeout = pageResultTimeout * 2).recoverWith {
                case ex:Throwable => throw new ParseException(s"WaitForClickedPrice:$ex")
            }
          },pageResultTimeout)          
          
          val trs = pricesTable.$("tr")
          val lastI = trs.indexWhere({el => el.children.length == 2} ,2)
          
          testOn("Details parsing lastI = -1")( lastI != -1 )

          val points = for (i <- 2 until lastI by 4) yield {
            val directions = asDirections(trs(i).innerText)
            val depdate =  asDate(trs(i+1).innerText)
            val flclass =  asFlClass(trs(i+2).innerText.toLowerCase)
            val flnum =  asFlNum(trs(i+2).innerText)
            NRFlight(directions._1,directions._2,depdate,None,flclass,flnum)
          }

          testOn("Points not found")(points.length > 0)
          
          val price = asPrice(trs.at(lastI).innerText)
          val depDate = points(0).depdate
          val avlDate = (new DateTime(depDate)).withHourOfDay(avl.h).withMinuteOfHour(avl.m).plusDays(avl.daysAdd).toDate()
          
          val points2 = points.updated(points.length-1,points(points.length-1).withArrivalDate(avlDate))

          NRTicket(price,depDate,avlDate,points2,flclass)
        }
        
        self ! AddTickets(fetchedTickets.toSeq)

        self ! Complete()
        p.render("phantomjs/images/norvegian1.png")      
    } catch {
        case ex:NoFlightsException => 
          logger.info(s"Searching $tr flights are not availible" )
          p.render("phantomjs/images/norvegian-warn.png")
          p.close
          self ! Complete()
        case ex:NotAvailibleDirecttion =>
          p.close
          logger.info( s"Parsing: $tr bo such direction")
          self ! Complete()

        case ex:Throwable => 
          logger.error( s"Parsing: $tr failed unkwnon exception $ex\n" + ex.getStackTrace().mkString("\n") )
          p.render("phantomjs/images/norvegian-error.png")
          p.close
          //self ! Complete()
          throw ex
    } 
    
  }


}
