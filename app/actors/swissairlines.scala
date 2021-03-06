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

object SwissAirlines {
  val ID = "LX"
} 

class SwissAirlines(maxRepeats:Int=1,noCache:Boolean=false) extends SingleFetcherActor(maxRepeats,noCache)  {
  var idd = 0
  val ID = "LX"
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
    val flclass = if ( classStr == "economysaver" || classStr == "economy" || classStr == "economyflex") Economy else Business
  }

  import context.dispatcher
  import context.become

  lazy val usd2eur = Manager.getCurrencyRatio("usd")
  lazy val chf2eur = Manager.getCurrencyRatio("chf")
  lazy val nok2eur = Manager.getCurrencyRatio("nok")
  

  def asPrice(d:String) = {
    val ratio = if (d.indexOf("CHF") != -1 ) {
      chf2eur
    } else if ( d.indexOf("USD") != -1 ) {
      usd2eur
    } else if ( d.indexOf("NOK") != -1 ) {
      nok2eur
    } else if ( d.indexOf("EUR") != -1 ) 1 else 
      throw new ParseException(s"Price format error: '$d'")
    
    val r = """\d+[.0-9 ]*""".r
    r findFirstIn d.replace(",","").replace("'","").replace("\n","") match {
      case Some(v) => v.replaceAll("[. ]","").toFloat * ratio
      case None    => throw new ParseException(s"Price format error: '$d'")
    }
  }

  def asDirections(v:String) = {
    //var els = v.toLowerCase.split(", ")
    //els.last.toUpperCase
    v.toUpperCase
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

  def asAvialine(v:String) = {
    val r = """^Operated by (.*)""".r
    r findFirstIn v.trim match {
      case Some(r(operator)) => operator.toLowerCase
      case None    => throw new ParseException(s"Avialine format error: '$v'")
    }

  }

  lazy val mainAvline = model.Avialines.getByName("swiss").get

  def getPoints(trel:Selector,curTime:java.util.Date) = {
    var lastTime = -1 
    var lastPlus  = 0

    val points = for ( dtr <- trel.$("td.book-matrix-information div.book-flight-entry")  ) yield {
        val depMins = asTime(dtr.$(".book-flight-entry-departure-time").innerText)
        val depPlus = if ( lastTime > 0 && lastTime >= depMins ) lastPlus + 1 else lastPlus
        
        lastTime = depMins
        lastPlus  = depPlus

        val iataFrom = asDirections(dtr.$(".book-flight-entry-departure-loction").innerText)

        val avlMins = asTime(dtr.$(".book-flight-entry-arrival-time").innerText)
        val avlPlus = if ( lastTime > 0 && lastTime >= avlMins ) lastPlus + 1 else lastPlus

        lastTime = avlMins
        lastPlus  = avlPlus

        val iataTo   = asDirections(dtr.$(".book-flight-entry-arrival-location").innerText)
        val flnum    = asFlnum(dtr.$(".book-flight-entry-company-flight-label").innerText)
        val avialine = asAvialine(dtr.$(".book-flight-entry-company-operator").innerText)
        
        val cd = new DateTime(curTime)

        val depDate = cd.withHourOfDay(depMins / 60).withMinuteOfHour(depMins % 60).plusDays(depPlus)
        val avlDate = cd.withHourOfDay(avlMins / 60).withMinuteOfHour(avlMins % 60).plusDays(avlPlus)

        ABFlight(iataFrom,iataTo,depDate.toDate(),avlDate.toDate(),flnum,avialine)
    }

    points.map {
      fl => 
        model.Flight(fl.iataFrom,fl.iataTo,
          model.Avialines.getByName(
            fl.avline match {
              case "swiss european" => "swiss"
              case x if (x.indexOf("with swiss") != -1) => "swiss"
              case _ => fl.avline
            }
          ).getOrElse(
            throw new ParseException(s"Can't find '${fl.avline}'")
            /*mainAvline*/
          ),
          0,fl.flnum,fl.depdate,Some(fl.avldate),None,0)
    }.toSeq

  }

  def goToReturnsList(p:actors.PhantomExecutor.Page,flinfo:FlightInfo,v:PriceInfo) {
    val submitButton = p.$("#frm-matrix button")
    val infoHeader =  p.$("#booking-cart .booking-cart-heading")
    val trel = flinfo.trel
    val inputAlreadyChecked = v.priceEl.$("input").isChecked

    idd+=1

    logger.debug(s"Go to returnflights list:$idd for flight:${flinfo.tuid} class:'${v.flclass}' price:${v.price} inputAlreadyChecked=$inputAlreadyChecked")
    

    if (! inputAlreadyChecked ) {
      infoHeader.attr("dirty","1")
      infoHeader.attr("style","background-color:red")
      v.priceEl.attr("style","background-color:red")
      
      if (! v.priceEl.isVisible ) {
        
        val grEl = v.classStr match {
          case m if m.startsWith("economy") => trel.$("td.book-matrix-economy-summary")
          case m if m.startsWith("business") => trel.$("td.book-matrix-business-summary")
          case m if m.startsWith("first") => trel.$("td.book-matrix-first-summary")
          case m => throw new ParseException("td has unknown type: " + m)
        }
        Thread.sleep(100)
        grEl.$("input").click()
        Thread.sleep(100)
        val isVisible = v.priceEl.isVisible

        logger.debug("Price Element is invisible open its group -> Price Element visible: " + isVisible )
        /*
        if (! isVisible ) {
          grEl.$("input").click()
          Thread.sleep(200)
          logger.debug("Price Element visible2:" + v.priceEl.isVisible )
        }
        */
      }

      //render(p,s"phantomjs/images/a-$idd.png")
      v.priceEl.$("input").click()
      //render(p,s"phantomjs/images/b-$idd.png")
      waitFor(pageLoadTimeout,"priceChange") {
        p.waitForSelectorAttr(infoHeader,"dirty","",timeout = pageLoadTimeout * 2).recoverWith {
            case ex:Throwable => throw new ParseException(s"WaitForClickedPrice:$idd:$ex")
        }
      }
      //render(p,s"phantomjs/images/c-$idd.png")
    }
                
    submitButton.click()
    
    scala.concurrent.Await.result({
        p.waitForSelector(p.$("h1").re("Return flight") , pageResultTimeout * 2 ).recoverWith {
        case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
      }
    },pageResultTimeout )

    waitForMainPage(p)
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
    val priceEls  = List ( trel.$("td.book-matrix-economy-saver") -> "economysaver" , 
      trel.$("td.book-matrix-economy") -> "economy" , 
      trel.$("td.book-matrix-economy-flex") -> "economyflex" , 
      trel.$("td.book-matrix-business-saver") -> "businesssaver" , 
      trel.$("td.book-matrix-business") -> "business" , 
      trel.$("td.book-matrix-business-flex") -> "businessflex" , 
      trel.$("td.book-matrix-first") -> "first" 
    ).filter(_._1.exists) 

    val prices = for ( (priceEl,classStr) <- priceEls ; it <- Some(priceEl.$(".book-matrix-price").innerText) if it != "--") yield PriceInfo(classStr,priceEl,asPrice( it ) )

    prices.foldLeft(Map[model.FlightClass.FlightClass,PriceInfo]()) {
      (acc,pr) => 
        val flclass = pr.flclass
        acc.get(flclass) match {
          case Some(x) => if (x.price > pr.price) acc + (flclass -> pr) else acc
          case None => acc + (flclass -> pr)
        }
    }

  }

  def waitForMainPage(p:actors.PhantomExecutor.Page) {
    scala.concurrent.Await.result({
        p.waitForSelector(p.$("#flightsData,#selectflightform,#GlobalNotificationBar .notification-error","div.site-footer") , pageResultTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
        }
      },pageResultTimeout)

    if ( ! p.$("#flightsData").exists ) {
      if ( p.$("#selectflightform,#GlobalNotificationBar .notification-error").exists ) {
        throw new NoFlightsException()
      } else {
        throw new ParseException("No data on unkown page")          
      }
    }

    if ( p.$(".book-matrix-row-separator a").exists ) {
      logger.debug("Row separator exists open it")
      p.$(".book-matrix-row-separator a").click()
    }
  }




  val pageLoadTimeout = 5 seconds
  // 10 - malo
  val pageResultTimeout = 20 seconds

  def doRealSearch2(tr:model.TravelRequest):Seq[ABTicket] = {
    
    val dformatter = new java.text.SimpleDateFormat("dd/MM/yyyy");
    
    val departure = dformatter.format(tr.departure)
    val arrival = dformatter.format(tr.arrival)


    val p = PhantomExecutor(isDebug=false,execTimeout=300 seconds)

    var tidx = 0

    catchFetching(p,tr) {
      // wait for autocomplete selectors

      waitFor(pageLoadTimeout,"pageLoad") {
        p.open("http://www.swiss.com/de/en")
      }

      scala.concurrent.Await.result({
        p.waitForSelector(p.$("#bookingbar-flight-subtab > form > div > div > div.l-right > button") , pageLoadTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForIndex:$ex")
        }
      },pageLoadTimeout)


      p.$("#Origin").click()

      p.$("#Origin").value = tr.iataFrom

      p.$("#Destination").click()

      p.$("#Destination").value = tr.iataTo

      if ( tr.traveltype == model.TravelType.OneWay ) {
        p.$("#BookingbarFlightMode[value=oneway]").click()
        p.$("#bookingbaronewaydate").value = departure
      }
      else {
        p.$("#bookingbaroutbounddate").value = departure
        p.$("#bookingbarreturndate").value = arrival
      }
      

      p.selectOption(p.$("#Adults").at(0),tr.adults.toString)
      p.selectOption(p.$("#Children").at(0),tr.childs.toString)
      p.selectOption(p.$("#Infants").at(0),tr.infants.toString)

      p.$("#bookingbar-flight-subtab > form > div > div > div.l-right > button").click()
      
      waitForMainPage(p)

      

      def getFlightInfos(curTime:java.util.Date) = {
        p.$("#matrix > fieldset > table > tbody > tr.book-matrix-row").map {
          trel:Selector => FlightInfo(trel,getPoints(trel,curTime))
        }
      }

      def getReturnFlightInfos(curTime:java.util.Date,flinfo:FlightInfo,priceInfo:PriceInfo) = {
        goToReturnsList(p,flinfo,priceInfo) 
        withClose ( p.$("#matrix > fieldset > table > tbody > tr.book-matrix-row").map {
          trel:Selector => FlightInfo(trel,getPoints(trel,curTime))
        } ) {
          () => goBackToArrivalsList(p)
        }
      }
      
      val fetchedTickets = if ( tr.traveltype == model.TravelType.OneWay )
        for ( 
          flinfo <- getFlightInfos(tr.departure) ;
          (_,priceInfo) <- getPriceMap(flinfo.trel)
        ) yield {
          val price = priceInfo.price * tr.adults + priceInfo.price * tr.childs 
          val ticket = model.Ticket(flinfo.tuid,flinfo.points,
            None,
            Map("LX"->price),
            Map("LX" -> ("LX:" + flinfo.tuid ) )
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
          (_,returnPriceInfo) <- getPriceMap(returnFlinfo.trel)
        ) yield {
          val price = ( returnPriceInfo.price + priceInfo.price ) * ( tr.adults + tr.childs )
          val tuid  = flinfo.tuid + "-" + returnFlinfo.tuid
          val ticket = model.Ticket(tuid,flinfo.points,
            Some(returnFlinfo.points),
            Map(ID->price),
            Map(ID -> (ID + ":" + tuid ) )
          )
          ABTicket ( ticket, 
            flclass = if ( priceInfo.flclass == Business && returnPriceInfo.flclass == Business ) Business else Economy,
            price = price      
          )
        }
      
      fetchedTickets.toSeq

		}

	}  
}