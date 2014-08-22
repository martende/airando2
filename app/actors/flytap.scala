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

import actors.PhantomExecutor.Selector
import utils.Utils2.withClose
import model.SearchResult

class FlyTap(maxRepeats:Int=1) extends BaseFetcherActor  {
  var idd = 0

  case class FlightInfo(trel:Selector,points:Seq[model.Flight]) {
    val tuidDateFormatter = new java.text.SimpleDateFormat("yyyyMMddHHmm");
    val tuid = {
        val parts = points.toSeq.map {
          f => tuidDateFormatter.format(f.departure) + f.iataFrom 
        } :+ (tuidDateFormatter.format(points.last.arrival.get) + points.last.iataTo)
        parts.mkString(":")
      }
  }

  case class PriceInfo(classStr:String,priceEl:Selector,price:Float) {
    val flclass = if ( classStr == "discount" || classStr == "basic" || classStr == "classic") Economy else Business
  }

  case class ABFlight(
    iataFrom: String,
    iataTo: String,
    depdate: java.util.Date,
    avldate: java.util.Date,
    flnum:String,
    avline:String
  )

  case class ABTicket(val ticket:model.Ticket,val flclass:FlightClass,val price:Float)

  import context.dispatcher
  import context.become

  val logger = Logger("FlyTap")

  var rqIdx = 0
  //var curSender:ActorRef = null
  //var curRequest:model.TravelRequest = null
  

  def receive = {
    case StartSearch(tr) => 

      processSearch(sender,tr)
  }


  def complete(sender:ActorRef,tr:model.TravelRequest,tickets:Seq[ABTicket] = Seq() ) = {
    logger.info(s"Search $tr: Completed: ${tickets.length} tickets found")

    val tickets2send = tickets.groupBy(_.ticket.tuid).map {
      t => 
      val t0 = if ( tr.flclass ==  Business)
        t._2.filter( _.flclass == Business ).minBy(_.price)  
      else 
        t._2.minBy(_.price)

      t0.ticket

    }.toSeq

    sender ! SearchResult(tr,tickets2send)

  }

  def processSearch(sender:ActorRef,tr:model.TravelRequest) = {
    rqIdx+=1
    
    logger.info(s"StartSearch:${rqIdx} ${tr}")

    //become(waitAnswer(sender,tr))
    if ( availIatas != null ) {
      if (! availIatas.contains(tr.iataFrom)) {
        logger.warn(s"No routes for iataFrom:${tr.iataFrom} availible")
        complete(sender,tr)
      } else if ( ! availIatas.contains(tr.iataTo)) {
        logger.warn(s"No routes for iataTo:${tr.iataTo} availible")
        complete(sender,tr)
      } else doRealSearch(sender,tr)
    } else doRealSearch(sender,tr)
  }

  def asPrice(d:String) = {
    val r = """\d+\.\d{1,2}""".r
    r findFirstIn d.replace(",","").replace("\n","") match {
      case Some(v) => v.toFloat
      case None    => throw new ParseException(s"Price format error: '$d'")
    }
  }

  
  def asTime(d:String) = {
    val r = """(\d\d):(\d\d)""".r
    r findFirstIn d match {
      case Some(r(hh,mm)) => hh.toInt * 60 + mm.toInt 
      case None    => throw new ParseException(s"Departure format error: '$d'")
    }
  }

  def asFlnum(d:String) = {
    d.replaceAll(" ","").toUpperCase
  }

  def asAvialine(dtr:Selector) = 
     if (dtr.$(".ffCarrier .carrierFlag.availCarrierTP").exists) mainAvline else {
      val sinfo = dtr.nextSibling.at(0).$("td.ffSegmentInfo")
      if ( sinfo.exists ) {
        val it = sinfo.innerText
        val ss = "Flight operated by:"
        val t  = it.indexOf(ss)
        if ( t == -1 ) 
          throw new ParseException(s"Can't find avialine - text:'$it'")
        else {
          val avname = it.substring(t+ss.length).trim.toLowerCase
          val avname2 = if ( avname == "portugalia") "pga-portuglia airlines" else avname
          model.Avialines.getByName(avname2).getOrElse(throw new ParseException(s"Can't find '$avname2'"))
        }
      } else {
        throw new ParseException("Cant find avialine - no td.ffSegmentInfo")
      }
    }
        /*
    val r = """^Operated by (.*)""".r
    r findFirstIn v.trim match {
      case Some(r(operator)) => operator.toLowerCase
      case None    => throw new ParseException(s"Avialine format error: '$v'")
    }
    */
  
  
  def findIata(name:String):String = 
    name2iata.getOrElse(name,{
      val li = name.lastIndexOf("/")
      if ( li != 1 ) findIata(name.substring(0,li).trim()) else 
      throw new ParseException("iata for $'name' not found") 
    })
  

     
  def asDirection(v:String) = {
    var els = v.replace("\n","").toLowerCase.split(" ")
    if (els.length == 2) {
      findIata(els(1))
    } else {
      throw new ParseException("iata format not found") 
    }
  }

  
  lazy val mainAvline = model.Avialines.getByName("tap portugal").get

  def getPoints(el:Selector,curTime:java.util.Date) = {
    var lastTime  = -1 
    var lastPlus  = 0

    val points = for ( dtr <- el.$(".segmentsInfo > tr") if dtr.$(".ffDeparture").exists ) yield {
        val depMins = asTime(dtr.$(".ffDeparture").innerText)
        val depPlus = if ( lastTime > 0 && lastTime >= depMins ) lastPlus + 1 else lastPlus

        lastTime = depMins
        lastPlus  = depPlus

        val iataFrom = asDirection(dtr.$(".ffDeparture").innerText)

        val avlMins = asTime(dtr.$(".ffArrival").innerText)
        val avlPlus = if ( lastTime > 0 && lastTime >= avlMins ) lastPlus + 1 else lastPlus

        lastTime = avlMins
        lastPlus  = avlPlus

        val iataTo   = asDirection(dtr.$(".ffArrival").innerText)
        val flnum    = asFlnum(dtr.$(".ffFlight a").innerText)
        val avialine = asAvialine(dtr)
        
        val cd = new DateTime(curTime)

        val depDate = cd.withHourOfDay(depMins / 60).withMinuteOfHour(depMins % 60).plusDays(depPlus)
        val avlDate = cd.withHourOfDay(avlMins / 60).withMinuteOfHour(avlMins % 60).plusDays(avlPlus)
        
        model.Flight(iataFrom,iataTo,avialine,
          0,flnum,
          depDate.toDate(),Some(avlDate.toDate()),None,0
        )
    }
    
    points.toSeq
  }

  def goToReturnsList(p:actors.PhantomExecutor.Page,flinfo:FlightInfo,v:PriceInfo) {

    idd+=1

    logger.debug(s"Go to returnflights list:$idd for flight:${flinfo.tuid} class:'${v.flclass}' price:${v.price}")
    
    v.priceEl.$("input[type=radio]").click()

  }

  def goBackToArrivalsList(p:actors.PhantomExecutor.Page) {
    val backButton = p.$("#frm-matrix > fieldset > div.l-left > a")

    backButton.click()

    scala.concurrent.Await.result({
        p.waitForSelector(p.$("h1").re("Outbound flight") , pageResultTimeout * 20 ).recoverWith {
        case ex:Throwable => throw new ParseException(s"goBackToArrivalsList-wait:$ex")
      }
    },pageResultTimeout*20)

    waitForMainPage(p)

  }

  def getPriceMap(trel:Selector) = {
    val priceEls  = List ( 
      trel.children.at(1) -> "discount" , 
      trel.children.at(2) -> "basic" , 
      trel.children.at(3) -> "classic" , 
      trel.children.at(4) -> "plus" , 
      trel.children.at(5) -> "executive"
    ).filter(_._1.exists) 

    val prices = for ( (priceEl,classStr) <- priceEls ; it <- Some(priceEl.innerText) if it != "") yield PriceInfo(classStr,priceEl,asPrice( it ) )

    prices.foldLeft(Map[model.FlightClass.FlightClass,PriceInfo]()) {
      (acc,pr) => 
        val flclass = pr.flclass
        acc.get(flclass) match {
          case Some(x) => if (x.price > pr.price) acc + (flclass -> pr) else acc
          case None => acc + (flclass -> pr)
        }
    }
  }

  def getReturnPriceMap(trel:Selector) = getPriceMap(trel) filter {
    case (k,v) => v.priceEl.$(".flightCombinable").exists
  }

  def waitForMainPage(p:actors.PhantomExecutor.Page) {
    scala.concurrent.Await.result({
        p.waitForSelector(p.$("#noflightsError,#totalPriceBoxTop","div.layoutFooter") , pageResultTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
        }
      },pageResultTimeout)

    if ( p.$("#noflightsError").exists ) throw new NoFlightsException()

  }




  val pageLoadTimeout = 5 seconds
  val pageResultTimeout = 20 seconds

  def doRealSearch(sender:ActorRef,tr:model.TravelRequest,_maxRepeats:Int = maxRepeats ) {

    try {

      val t = doRealSearch2( tr )

      complete(sender,tr,t)

    } catch {
      
      case ex:Throwable => 
        if ( _maxRepeats > 1 ) {
          logger.warn(s"Parsingxx: $tr failed. Try ${maxRepeats - _maxRepeats}/$maxRepeats times exception: $ex\n" + ex.getStackTrace().mkString("\n"))
          doRealSearch(sender,tr,_maxRepeats-1)
        } else {
          logger.error(s"Parsing: $tr failed unkwnon exception $ex\n" + ex.getStackTrace().mkString("\n"))
          sender ! akka.actor.Status.Failure(ex)
        }
    }

  }

  var name2iata = Map[String,String]()

  def doRealSearch2(tr:model.TravelRequest):Seq[ABTicket] = {
    
    val dformatter = new java.text.SimpleDateFormat("dd/MM/yyyy");
    
    val departure = dformatter.format(tr.departure)
    val arrival = dformatter.format(tr.arrival)

    val r = actors.PhantomExecutor.open("http://www.flytap.com/Deutschland/en/Homepage",isDebug=false,execTimeout=300 seconds)

    var Success(p) = try {
      scala.concurrent.Await.result(r,pageLoadTimeout) 
    } catch {
      case ex:Throwable => 
        logger.error( s"Parsing: $tr failed - fetching failed $ex" )
        throw ex
    }

    var tidx = 0

    try {
      
    	// wait for autocomplete selectors

      val airportsListFrom = p.$("#side_booking_flights .container_airports_flights_from a").re("airports list")
      val airportsListTo = p.$("#side_booking_flights .container_airports_flights_to a").re("airports list")

      scala.concurrent.Await.result({
        p.waitForSelector(airportsListFrom , pageLoadTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForIndex:$ex")
        }
      },pageLoadTimeout)

      airportsListFrom.click()
      airportsListTo.click()

      if ( updateIatas ) {
        val iataMap = (for (el <- p.$(".airports_flights_from ul.newList li")) yield {
          val cityName = el.innerText.split(",").head.trim.toLowerCase
          ( el.attr("class").toUpperCase -> cityName)
        })
        name2iata = iataMap.map(_.swap).toMap
        updateAvailIatas( iataMap.map(_._1).toSet )  
      }
      
      p.selectJSSelect(
        p.$(".combo_airports_flights_from .newListSelected"),
        p.$(".combo_airports_flights_from ul.newList li."+tr.iataFrom).getOrElse(throw new NotAvailibleDirecttion())
      )
      
      p.selectJSSelect(
        p.$(".combo_airports_flights_to .newListSelected"),
        p.$(".combo_airports_flights_to ul.newList li a[rel="+tr.iataTo+"]").parentNode.getOrElse(throw new NotAvailibleDirecttion()),
        waitOpening = true
      )
      
      //p.$("#flights_to").value = tr.iataTo
      
      //p.$("#Origin").click()

      p.$("#departing_date").value = departure

      if ( tr.traveltype == model.TravelType.OneWay ) 
        p.$("#flighttype_oneway").click()
      else 
        p.$("#returning_date").value = arrival
      
      

      p.selectOption(p.$("#flight_adults"),tr.adults.toString)
      p.selectOption(p.$("#flight_children"),tr.childs.toString)
      p.selectOption(p.$("#flight_babies"),tr.infants.toString)

      p.$("#ibebutton").click()
      
      waitForMainPage(p)

      def getFlightInfos(curTime:java.util.Date) = {
        p.$("table.ffTable").at(0).$("td.flightInfo").map {
          el:Selector => FlightInfo(el.parentNode,getPoints(el,curTime))
        }
      }

      def getReturnFlightInfos(curTime:java.util.Date,flinfo:FlightInfo,priceInfo:PriceInfo) = {

        testOn("No return table") {
          p.$("table.ffTable").at(1).exists
        }

        goToReturnsList(p,flinfo,priceInfo)

        p.$("table.ffTable").at(1).$("td.flightInfo").map {
          el:Selector => FlightInfo(el.parentNode,getPoints(el,curTime))
        }
        
        
        
        /*
        withClose ( p.$("#matrix > fieldset > table > tbody > tr.book-matrix-row").map {
          el:Selector => FlightInfo(el,getPoints(el,curTime))
        } ) {
          () => goBackToArrivalsList(p)
        }
        */
      }
      
      val fetchedTickets = if ( tr.traveltype == model.TravelType.OneWay )
        for ( 
          flinfo <- getFlightInfos(tr.departure) ;
          (_,priceInfo) <- getPriceMap(flinfo.trel)
        ) yield {
          val price = priceInfo.price * tr.adults + priceInfo.price * tr.childs 
          val ticket = model.Ticket(flinfo.tuid,flinfo.points,
            None,
            Map("TP"->price),
            Map("TP" -> ("TP:" + flinfo.tuid ) )
          )
          ABTicket ( ticket, 
            priceInfo.flclass,
            price = price      
          )
        }
      else 
        for ( 
          flinfo <- getFlightInfos(tr.departure) ;
          (_,priceInfo) <- getPriceMap(flinfo.trel);
          returnFlinfo <- getReturnFlightInfos(tr.arrival,flinfo,priceInfo);
          (_,returnPriceInfo) <- getReturnPriceMap(returnFlinfo.trel)
        ) yield {
          val price = ( returnPriceInfo.price + priceInfo.price ) * ( tr.adults + tr.childs )
          val tuid  = flinfo.tuid + "-" + returnFlinfo.tuid
          val ticket = model.Ticket(tuid,flinfo.points,
            Some(returnFlinfo.points),
            Map("TP"->price),
            Map("TP" -> ("TP:" + tuid ) )
          )
          ABTicket ( ticket, 
            flclass = if ( priceInfo.flclass == Business && returnPriceInfo.flclass == Business ) Business else Economy,
            price = price      
          )
        }
      
      p.render("phantomjs/images/flytap1.png")
      p.close

      fetchedTickets.toSeq

		} catch {
      case ex:NoFlightsException => 
        logger.info(s"Searching $tr flights are not availible" )
        p.render("phantomjs/images/flytap-warn.png")
        p.close
        Seq()

      case ex:NotAvailibleDirecttion =>
        logger.info( s"Parsing: $tr no such direction")
        p.render("phantomjs/images/flytap-error.png")
        p.close
        Seq()

      case ex:Throwable => 
        logger.error( s"Parsing: $tr failed unkwnon exception $ex\n" + ex.getStackTrace().mkString("\n") )
        p.render("phantomjs/images/flytap-error.png")
        p.close
        //self ! Complete()
        throw ex
		}

	}  
}