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

import actors.PhantomExecutor.Selector


object Airberlin {
  val ID = "AB"
}

class Airberlin(maxRepeats:Int=1) extends SingleFetcherActor(maxRepeats)  {
  import context.dispatcher

  val ID = Airberlin.ID
  val logger = Logger("Airberlin")

  val pageLoadTimeout = 10 seconds
  val pageResultTimeout = 10 seconds

  def doRealSearch2(tr:model.TravelRequest) = {

    val dformatter = new java.text.SimpleDateFormat("dd/MM/yyyy");
    val src = tr.iataFrom
    val dst = tr.iataTo
    val departure = dformatter.format(tr.departure)

    val p = PhantomExecutor(isDebug=false)

    catchFetching(p,tr) {

      waitFor(pageLoadTimeout,"pageLoad") {
        p.open("http://www.airberlin.com/en-DE/site/start.php")
      }

    	// wait for autocomplete selectors

      scala.concurrent.Await.result({
        p.waitForSelector(p.$("#route-outbound .fullsuggest-closed") , pageLoadTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForIndex:$ex")
        }
      },pageLoadTimeout)

      if ( updateIatas )  {
        p.$("#route-outbound .fullsuggest-closed").click()
      
        scala.concurrent.Await.result({
          p.waitForSelector(p.$("#route-outbound .fullsuggest-open") , pageLoadTimeout * 2 ).recoverWith {
            case ex:Throwable => throw new ParseException(s"WaitForIataOpening:$ex")
          }
        },pageLoadTimeout)

        val iataMap = ( for ( el <- p.$("#route-outbound .suggestcontainer > div") ) yield {
          val t0 = """<.*?>.*?</.*?>""".r.replaceAllIn(el.innerHTML,"")
          val r1 = """^(.*) \((\w+)\)$""".r
          r1.findFirstIn(t0) match {
            case Some(r1(name,iata)) => Some(iata.toUpperCase -> name.toLowerCase)
            case None => None
          }
        }).flatten.toMap

        updateAvailIatas(iataMap.keys.toSet)

      }
      

      p.$("#route-outbound .fullsuggest-open").click()

    	p.selectJSSelect(
    		p.$("#route-outbound .fullsuggest-closed"),
    		p.$("#route-outbound .suggestcontainer > div").find( _.innerText matches ".*\\("+src+"\\)\\W*$"  ).getOrElse(throw new NotAvailibleDirecttion()),
        waitOpening = true
    	)

      p.selectJSSelect(
        p.$("#route-inbound .fullsuggest-closed"),
        p.$("#route-inbound > div.suggestcontainer > div").find( _.innerText matches ".*\\("+dst+"\\)\\W*$"  ).getOrElse(throw new NotAvailibleDirecttion()),
        waitOpening = true
      )

      //if ( tr.traveltype == model.TravelType.OneWay )
      p.$("#oneway").click()
      
      p.$("input[name=outboundDate]").value = departure

      p.selectOption(p.$("select[name=adultCount]").at(0),tr.adults.toString)
      p.selectOption(p.$("select[name=childCount]").at(0),tr.childs.toString)
      p.selectOption(p.$("select[name=infantCount]").at(0),tr.infants.toString)

      p.$("#frm-route-selection > div.formsubmit > button").click()

      
      scala.concurrent.Await.result({
        p.waitForSelector(p.$("table.flighttable,#vacancy_error .entry") , pageResultTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
        }
      },pageResultTimeout)

      if ( p.$("#vacancy_error .entry").exists()) {
        val html = p.$("#vacancy_error .entry").innerHTML
        if ( false ) {
          throw new NoFlightsException()
        } else {
          throw new ParseException("WarnBox contains unknown error: " + html )
        }
      }
      def asPrice(d:String) = {
        val r = """\d+\.\d{1,2}""".r
        r findFirstIn d.replace(",","").replace("\n","") match {
          case Some(v) => v.toFloat
          case None    => throw new ParseException(s"Price format error: '$d'")
        }
      }

      def asDirections(v:String) = {
        var els = v.toLowerCase.split(", ")
        els.last.toUpperCase
      }

      def asTime(d:String) = {
        val r = """(\d\d):(\d\d)""".r
        r findFirstIn d match {
          case Some(r(hh,mm)) => hh.toInt * 60 + mm.toInt 
          case None    => throw new ParseException(s"Departure format error: '$d'")
        }
      }

      def asFlnum(d:String) = {
        d.toUpperCase
      }
      
      val tuidDateFormatter = new java.text.SimpleDateFormat("yyyyMMddHHmm");
      
      var fetchedTickets = for ( trel <- p.$("table.flighttable > tbody > tr.flightrow") ) yield {
        var lastTime = -1 
        var lastPlus  = 0

        val detail_trs = trel.nextSibling.$("table > tbody > tr")
        val points = for ( dtr <- detail_trs ) yield {
            val depMins = asTime(dtr.children.at(0).innerText)
            val depPlus = if ( lastTime > 0 && lastTime >= depMins ) lastPlus + 1 else lastPlus
            
            lastTime = depMins
            lastPlus  = depPlus

            val iataFrom = asDirections(dtr.children.at(1).innerText)
            val avlMins = asTime(dtr.children.at(2).innerText)
            val avlPlus = if ( lastTime > 0 && lastTime >= avlMins ) lastPlus + 1 else lastPlus

            lastTime = avlMins
            lastPlus  = avlPlus

            val iataTo   = asDirections(dtr.children.at(3).innerText)
            val flnum    = asFlnum(dtr.children.at(4).innerText)
            val avialine = dtr.children.at(5).$("img").attr("title").trim().toLowerCase
            
            val depDate = new DateTime(tr.departure).withHourOfDay(depMins / 60).withMinuteOfHour(depMins % 60).plusDays(depPlus)
            val avlDate = new DateTime(tr.departure).withHourOfDay(avlMins / 60).withMinuteOfHour(avlMins % 60).plusDays(avlPlus)

            ABFlight(iataFrom,iataTo,depDate.toDate(),avlDate.toDate(),flnum,avialine)
        }

        val tuid = {
          val parts = points.toSeq.map {
            f => tuidDateFormatter.format(f.depdate) + f.iataFrom 
          } :+ (tuidDateFormatter.format(points.last.avldate) + points.last.iataTo)
          parts.mkString(":")
        }

        val priceEls  = List ( trel.$("td.ECO.SAVER label") -> "saver" , 
          trel.$("td.ECO.FLEX label") -> "flex",
          trel.$("td.BUS.FLEX label") -> "bussmart",
          trel.$("td.BUS.SAVER label") -> "bussaver",
          trel.$("td.BUS.FLEX label") -> "busdeal"
        ).flatMap { x =>  if ( x._1.exists() ) Some(x) else None } 
        

        val prices = for ( (priceEl,classStr) <- priceEls ) yield {

          val priceTable = p.$("#vacancy_priceoverview div.overview")
          priceTable.attr("dirty","1")
          priceEl.click()

          scala.concurrent.Await.result({
            p.waitForSelectorAttr(priceTable,"dirty","",timeout = pageLoadTimeout * 2).recoverWith {
                case ex:Throwable => throw new ParseException(s"WaitForClickedPrice:$ex")
            }
          },pageLoadTimeout)
          
          val price = asPrice( priceTable.$("table.total tbody > tr > td").innerText )
          (classStr,price)
        }

        val tkts = for ( p <- points ; pr <- prices ) yield {
          val flclass = if ( pr._1 == "saver" || pr._1 == "flex") Economy else Business
          model.Avialines.getByName(p.avline) match {
            case None => logger.warn(s"Cant find avialine '${p.avline}' - skip it");None
            case Some(avline) => 
              val tkt = ABTicket( model.Ticket(tuid,points.map {
                  fl => model.Flight(fl.iataFrom,fl.iataTo,avline,0,fl.flnum,fl.depdate,Some(fl.avldate),None,0)
                }.toSeq,None,Map(ID->pr._2),Map(ID -> (ID +":" + tuid ) )) ,
                flclass ,
                pr._2
              )
              Some(tkt)
          }
        }

        tkts.flatten

      }
        
      p.render("phantomjs/images/airberlin1.png")
      p.close
      
      fetchedTickets.flatten.toSeq
      
		}

	}  
}