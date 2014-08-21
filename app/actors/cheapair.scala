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

import actors.PhantomExecutor.{Selector,Page}
import utils.Utils2.withClose

object CheapAir {
  val ID = "CHPA"
}

class CheapAir(maxRepeats:Int=1) extends BaseFetcherActor  {

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

  case class PriceInfo(flclass:FlightClass,priceEl:Selector,price:Float) {
    //val flclass = if ( classStr == "discount" || classStr == "basic" || classStr == "classic") Economy else Business
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

  val logger = Logger("CheapAir")

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
    val r = """\$\d+(\.\d{1,2})?""".r
    r findFirstIn d.replace(",","").replace("\n","") match {
      case Some(v) => v.replace("$","").toFloat
      case None    => throw new ParseException(s"Price format error: '$d'")
    }
  }

  
  def asTime(d:String) = {
    val r = """(\d\d?):(\d\d)([ap]m)""".r
    r findFirstIn d match {
      case Some(r(hh,mm,ampm)) => if ( ampm == "am" ) hh.toInt * 60 + mm.toInt 
        else if (ampm =="pm" ) {
          val h = hh.toInt
          ( if ( h == 12 ) 12 else ( h + 12 ) )* 60 + mm.toInt 
        }
        else throw new ParseException(s"Departure format error: '$d' - nopm")
      case None    => throw new ParseException(s"Departure format error: '$d'")
    }
  }

  def asFlnum(d:String) = {
    d.replaceAll(" ","").toUpperCase
  }

  def asAvialine(v:String) = {
    val r = """/images/al/([A-Z0-9][A-Z0-9])\.imgx""".r    
    r findFirstIn v match {
      case Some(r(iata)) => iata
      case None    => throw new ParseException(s"Avialine format error: '$v'")
    }
  }
     
  def asDirection(d:String) = {
    val r = """\(([A-Z][A-Z][A-Z])\)""".r
    r findFirstIn d match {
      case Some(r(iata)) => iata
      case None    => throw new ParseException(s"Iata format error: '$d'")
    }
  }

  def asAircraft(d:String) = d.toUpperCase.trim
  
  lazy val mainAvline = model.Avialines.getByName("tap portugal").get

  def getPoints(el:Selector,curTime:java.util.Date) = {
    var lastTime  = -1 
    var lastPlus  = 0
    
    el.$(".frAirl").move()

    val popups = el.root(".flightPopUp")
    val popupIdx = popups.length - 1 
    
    val popup = popups.at(popupIdx)
        
    val points = for ( dtr <- popup.$("table.leg")  ) yield {
      val depTr = dtr.$("td.city").parentNode.at(0)
      val avlTr = dtr.$("td.city").parentNode.at(1)

      val depMins = asTime(depTr.$("td").at(3).innerText)
      val depPlus = if ( lastTime > 0 && lastTime >= depMins ) lastPlus + 1 else lastPlus

      lastTime = depMins
      lastPlus  = depPlus

      val iataFrom = asDirection(depTr.$("td.city").innerText)

      val avlMins = asTime(avlTr.$("td").at(3).innerText)
      val avlPlus = if ( lastTime > 0 && lastTime >= avlMins ) lastPlus + 1 else lastPlus

      lastTime = avlMins
      lastPlus  = avlPlus

      val iataTo   = asDirection(avlTr.$("td.city").innerText)
      val flnum    = asFlnum(dtr.$(".alName").at(0).innerText)
      val avialine = asAvialine(dtr.$("td.al img").attr("src"))
      val aircraft = asAircraft(avlTr.$("td").at(0).innerText)

      val cd = new DateTime(curTime)

      val depDate = cd.withHourOfDay(depMins / 60).withMinuteOfHour(depMins % 60).plusDays(depPlus)
      val avlDate = cd.withHourOfDay(avlMins / 60).withMinuteOfHour(avlMins % 60).plusDays(avlPlus)
      
      model.Flight(iataFrom,iataTo,avialine,
        0,flnum,
        depDate.toDate(),Some(avlDate.toDate()),Some(aircraft),0
      )
    }

    el.root(".hdr-logo").move()

    points.toSeq
  }

  def goToReturnsList(p:Page,flinfo:FlightInfo,v:PriceInfo) {

    idd+=1

    logger.debug(s"Go to returnflights list:$idd for flight:${flinfo.tuid} class:'${v.flclass}' price:${v.price}")
    
    v.priceEl.$("input[type=radio]").click()

  }

  def goBackToArrivalsList(p:Page) {
    val backButton = p.$("#frm-matrix > fieldset > div.l-left > a")

    backButton.click()

    scala.concurrent.Await.result({
        p.waitForSelector(p.$("h1").re("Outbound flight") , pageResultTimeout * 20 ).recoverWith {
        case ex:Throwable => throw new ParseException(s"goBackToArrivalsList-wait:$ex")
      }
    },pageResultTimeout*20)

    waitForMainPage(p)

  }

  def getPriceMap(tr:model.TravelRequest,trel:Selector):Map[model.FlightClass.FlightClass,PriceInfo] = {
    /*val priceEls  = List ( 
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
    }*/
    val prTxt =trel.$(".frPrc").innerText
    if (prTxt == "Check Fare") Map() else 
    Map( tr.flclass -> PriceInfo(tr.flclass,trel,asPrice(prTxt)) ) 
  }

  def getReturnPriceMap(tr:model.TravelRequest,trel:Selector) = getPriceMap(tr,trel)/* filter {
    case (k,v) => v.priceEl.$(".flightCombinable").exists
  }*/

  def waitForMainPage(p:Page) {
    scala.concurrent.Await.result({
        p.waitForSelector(p.$("#alList h1","footer") , pageResultTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
        }
      },pageResultTimeout)

    //if ( p.$("#alList").exists ) throw new NoFlightsException()

  }

  def waitUntilVisible(p:Page,selector:String) {
    scala.concurrent.Await.result({
        p.waitUntilVisible(p.$(selector) , pageLoadTimeout * 2 ).recoverWith {
          case ex:Throwable => throw new ParseException(s"waitUntilVisible:$ex")
        }
    },pageLoadTimeout)
  }

  def getFlightInfos(p:Page,curTime:java.util.Date) = {
    
  // #flList table.fltsItin
//    println( "fl_depart.exists" ,p.$("#fl_depart tr.rowOption").length )
//    println( "flList.exists" ,p.$("").length )

    p.$("#fl_depart tr.rowOption").map {
      el:Selector =>  {
        val ret = FlightInfo(el,getPoints(el,curTime))
        //logger.debug(s"flInfo = $ret")
        ret
      }
    }
  }


  def getReturnFlightInfos(p:Page,curTime:java.util.Date,flinfo:FlightInfo,priceInfo:PriceInfo) = {
    
    goToReturnsList(p,flinfo,priceInfo)

    p.$("#fl_return tr.rowOption").map {
      el:Selector => FlightInfo(el,getPoints(el,curTime))
    }

    /*
    testOn("No return table") {
      p.$("table.ffTable").at(1).exists
    }

    

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
    */
  }

  def getFlightInfos2(p:Page,curTime:java.util.Date) = { 
    p.render(s"phantomjs/images/getFlightInfos2")
    p.$("#flList table.fltsItin").map {
      el:Selector =>  {
        val r = FlightInfo(el,getPoints2(el.$("table.leg").at(0),curTime))
        r
      }
    }
  }

  def getReturnFlightInfos2(p:Page,curTime:java.util.Date,flinfo:FlightInfo,priceInfo:PriceInfo) = 
    Seq( FlightInfo(flinfo.trel,getPoints2(flinfo.trel.$("table.leg").at(1),curTime))   )

  def getPriceMap2(tr:model.TravelRequest,trel:Selector):Map[model.FlightClass.FlightClass,PriceInfo] = {
    val prTxt =trel.$("tr.head strong").innerText
    Map( tr.flclass -> PriceInfo(tr.flclass,trel,asPrice(prTxt)) ) 
  }

  def getReturnPriceMap2(tr:model.TravelRequest,trel:Selector) = {
    val ret = getPriceMap2(tr,trel)
    println(ret)
    ret 
  }
    //println("getReturnPriceMap2",trel.outerHTML)
    
  

  var ii = 0
  def getPoints2(el:Selector,curTime:java.util.Date) = {
    var lastTime  = -1 
    var lastPlus  = 0
    
    el.$(".alName").move()

    //el.page.render(s"phantomjs/images/kkk$ii")
    //ii+=1

    val popups = el.root(".flightPopUp")
    val popupIdx = popups.length - 1 
    
    val popup = popups.at(popupIdx)

    val points = for ( dtr <- popup.$("table.leg")  ) yield {
      val depTr = dtr.$("td.city").parentNode.at(0)
      val avlTr = dtr.$("td.city").parentNode.at(1)

      val depMins = asTime(depTr.$("td").at(3).innerText)
      val depPlus = if ( lastTime > 0 && lastTime >= depMins ) lastPlus + 1 else lastPlus

      lastTime = depMins
      lastPlus  = depPlus

      val iataFrom = asDirection(depTr.$("td.city").innerText)

      val avlMins = asTime(avlTr.$("td").at(3).innerText)
      val avlPlus = if ( lastTime > 0 && lastTime >= avlMins ) lastPlus + 1 else lastPlus

      lastTime = avlMins
      lastPlus  = avlPlus

      val iataTo   = asDirection(avlTr.$("td.city").innerText)
      val flnum    = asFlnum(dtr.$(".alName").at(0).innerText)
      val avialine = asAvialine(dtr.$("td.al img").attr("src"))
      val aircraft = asAircraft(avlTr.$("td").at(0).innerText)

      val cd = new DateTime(curTime)

      val depDate = cd.withHourOfDay(depMins / 60).withMinuteOfHour(depMins % 60).plusDays(depPlus)
      val avlDate = cd.withHourOfDay(avlMins / 60).withMinuteOfHour(avlMins % 60).plusDays(avlPlus)
      
      model.Flight(iataFrom,iataTo,avialine,
        0,flnum,
        depDate.toDate(),Some(avlDate.toDate()),Some(aircraft),0
      )
    }

    el.root(".hdr-logo").move()

    points.toSeq
  }


  def fetchTickets2(p:Page,tr:model.TravelRequest,
    getFlightInfos: (Page,java.util.Date) => Traversable[FlightInfo],
    getPriceMap: (model.TravelRequest,Selector) => Map[model.FlightClass.FlightClass,PriceInfo],
    getReturnFlightInfos: (Page,java.util.Date,FlightInfo,PriceInfo) => Traversable[FlightInfo],
    getReturnPriceMap: (model.TravelRequest,Selector) => Map[model.FlightClass.FlightClass,PriceInfo]
  ) = tr.traveltype match {
    case model.TravelType.OneWay => 
      for ( 
        flinfo <- getFlightInfos(p,tr.departure) ;
        (_,priceInfo) <- getPriceMap(tr,flinfo.trel)
      ) yield {
        val price = priceInfo.price * tr.adults + priceInfo.price * tr.childs 
        val ticket = model.Ticket(flinfo.tuid,flinfo.points,
          None,
          Map(CheapAir.ID->price),
          Map(CheapAir.ID -> (CheapAir.ID + ":" + flinfo.tuid ) )
        )
        ABTicket ( ticket, 
          priceInfo.flclass,
          price = price      
        )
      }
    case model.TravelType.Return => 
      for ( 
        flinfo <- getFlightInfos(p,tr.departure) ;
        (_,priceInfo) <- getPriceMap(tr,flinfo.trel);
        returnFlinfo <- getReturnFlightInfos(p,tr.arrival,flinfo,priceInfo);
        (_,returnPriceInfo) <- getReturnPriceMap(tr,returnFlinfo.trel)
      ) yield {
        val price = returnPriceInfo.price  * ( tr.adults + tr.childs )
        val tuid  = flinfo.tuid + "-" + returnFlinfo.tuid
        val ticket = model.Ticket(tuid,flinfo.points,
          Some(returnFlinfo.points),
          Map(CheapAir.ID->price),
          Map(CheapAir.ID -> (CheapAir.ID + ":" + tuid ) )
        )
        ABTicket ( ticket, 
          flclass = if ( priceInfo.flclass == Business && returnPriceInfo.flclass == Business ) Business else Economy,
          price = price      
        )
      }
  }

  def fetchTickets(p:Page,tr:model.TravelRequest) = fetchTickets2(p,tr,
    getFlightInfos,getPriceMap,getReturnFlightInfos,getReturnPriceMap
  )

  val pageLoadTimeout = 10 seconds
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
    
    val dformatter = new java.text.SimpleDateFormat("MM/dd/yyyy");
    
    val departure = dformatter.format(tr.departure)
    val arrival = dformatter.format(tr.arrival)

    val r = actors.PhantomExecutor.open("http://www.cheapair.com/",isDebug=false,execTimeout=300 seconds)

    var Success(p) = try {
      scala.concurrent.Await.result(r,pageLoadTimeout) 
    } catch {
      case ex:Throwable => 
        logger.error( s"Parsing: $tr failed - fetching failed $ex" )
        throw ex
    }

    try {
      p.$("#dTxtBox1").value = tr.iataFrom
      p.$("#dCode1").value = tr.iataFrom
      p.$("#rTxtBox1").value = tr.iataTo
      p.$("#rCode1").value = tr.iataTo
      p.$("#dt1").value = departure

      if ( tr.flclass == Business)
        p.selectOption(p.$("cabinClass"),"B")

      
      p.evalJsClient("""PopupHandler.show = function(){};""")
      if ( tr.traveltype == model.TravelType.OneWay ) 
        p.$("#tabsOW").click()
      else 
        p.$("#rdt").value = arrival

      p.$("#searchForm > div.btnReg > button").click()
      
      waitForMainPage(p)
   
      p.evalJsClient("""
        jQuery.popunder = function() {};
      """)

      val fetchedTickets = for (avlineEl <- p.$("#alList > div")) yield {

        val avlineTxt = avlineEl.innerText.replace("\n"," ").trim

        logger.debug(s"Check $avlineTxt")
        
        avlineEl.click()

        scala.concurrent.Await.result({
          p.waitForSelector(p.$("#alTitle").re("Flights on ") , pageResultTimeout * 2 ).recoverWith {
            case ex:Throwable => throw new ParseException(s"WaitForResult:$ex")
          }
        },pageResultTimeout)

        waitUntilVisible(p,"#overlay")

        waitUntilVisible(p,"#loadingBox")
        
        p.render(s"phantomjs/images/r-$ii")
        ii+=1

        if ( avlineTxt.indexOf("Multiple Airlines")!= -1 ) 
          fetchTickets2(p,tr,
            getFlightInfos2,
            getPriceMap2,
            getReturnFlightInfos2,
            getReturnPriceMap2
          )
        else 
          fetchTickets(p,tr)
      }

      val els = fetchedTickets.flatten


      /*
      for (avline <- p.$("#alList > div")) {
        avline.click()
      }
      */

      
      p.render(s"phantomjs/images/${this.getClass.getName}-ok.png")
      p.close

      els.toSeq
		} catch {
      case ex:NoFlightsException => 
        logger.info(s"Searching $tr flights are not availible" )
        p.render(s"phantomjs/images/${this.getClass.getName}-warn.png")
        p.close
        Seq()

      case ex:NotAvailibleDirecttion =>
        logger.info( s"Parsing: $tr no such direction")
        p.render(s"phantomjs/images/${this.getClass.getName}-error.png")
        p.close
        Seq()

      case ex:Throwable => 
        logger.error( s"Parsing: $tr failed unkwnon exception $ex\n" + ex.getStackTrace().mkString("\n") )
        p.render(s"phantomjs/images/${this.getClass.getName}-error.png")
        p.close
        //self ! Complete()
        throw ex
		}

	}  
}