package actors
import akka.actor.{Actor,Cancellable,Identify,ActorIdentity,Props}
import scala.concurrent.{Future,Promise}

import play.api.{Logger,Play}

import scala.concurrent.Await
import akka.pattern.ask
import akka.util.Timeout

import model.{SearchResult,TravelRequest}
import model.FlightClass._

import actors.PhantomExecutor.Page

import akka.actor.{ActorRef}

class PhantomInitException(msg: String) extends RuntimeException(msg)
class PhantomProcessException(msg: String) extends RuntimeException(msg)  
// esli avialinija ne letaet po dannomu napravleniju otlichaetsja ot prosto 
// esli netu biletov
class NoFlightsException() extends RuntimeException("NOFLIGHTS")  
class ParseException(s:String) extends RuntimeException(s)  


trait CachingAPI {
  def md5(text: String) : String = java.security.MessageDigest.getInstance("MD5").digest(text.getBytes()).map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  def saveCache(f:String,responseBody:String)
  def getFromCache(f:String):Option[String]
}

trait NoCaching extends CachingAPI {
  def saveCache(f:String,responseBody:String) {}
  def getFromCache(f:String):Option[String] = None
}

trait FileCaching extends CachingAPI {
  self:WithLogger => 

  import play.api.Play.current

  lazy val cacheDir = Play.application.path + "/" + Play.current.configuration.getString("cache-dir").getOrElse({
      logger.error("cache-dir not configured")
      throw play.api.UnexpectedException(Some("cache-dir not configured"))  
    })

  def writeFile(fname:String,content:String) {
    Some(new java.io.PrintWriter(fname)).foreach{p => p.write(content); p.close}
  }
  def saveCache(f:String,responseBody:String) {
      val d1 = f.substring(0,2)
      val d2 = f.substring(2,4)
      val d3 = f.substring(4)
      val d1f = new java.io.File(cacheDir,d1)
      if (! d1f.exists) d1f.mkdir()
      val d2f = new java.io.File(cacheDir,s"$d1/$d2")
      if (! d2f.exists) d2f.mkdir()
      val fname = new java.io.File(cacheDir,s"$d1/$d2/$d3.chtml")

      logger.info(s"Save message: '$f' to cache ${fname.getAbsolutePath}")

      writeFile(fname.getPath,responseBody)
  }

  def getFromCache(f:String):Option[String] = {
    val d1 = f.substring(0,2)
    val d2 = f.substring(2,4)
    val d3 = f.substring(4)
    val fstr = s"$d1/$d2/$d3.chtml"
    val fname = new java.io.File(cacheDir,fstr)
    if (fname.exists) {
      logger.info(s"Read message: '$f' from cache ${fname.getAbsolutePath}")
        //log.debug(s"Fetch ${url} from cache ${fname}")
        Some(scala.io.Source.fromFile(fname.getPath).mkString)
    } else None
  }  
}

trait WithLogger {
  val logger:Logger
}

abstract class BaseFetcherActor(maxRepeats:Int,noCache:Boolean=false) extends Actor with WithLogger {
  val ID:String 

  class NotAvailibleDirecttion extends Throwable;

  import sys.process._
  val system = akka.actor.ActorSystem("system")

  import context.dispatcher
  import scala.concurrent.duration._

  var rqIdx = 0
  
  val dbCacheActor = {
    implicit val timeout = Timeout(1 seconds)
    val myFutureStuff = system.actorSelection(s"akka://application/user/CacheStorageActor")
    val aid:ActorIdentity = Await.result((
      myFutureStuff ? Identify(1)).mapTo[ActorIdentity],
      0.1 seconds)
    aid.ref match {
      case Some(cacher) => 
        cacher
      case None => 
        system.actorOf(Props[actors.CacheStorageActor],"CacheStorageActor")
    }
  }

  def saveDBCache(r:SearchResult) = if (!noCache) {
    dbCacheActor ! CacheResult(ID,r)
  }

  def checkDBCache(tr:TravelRequest) = if (noCache) None else {
    implicit val timeout = Timeout(1 seconds)
    Await.result(
      (dbCacheActor ? CacheRequest(ID,tr)).mapTo[Option[SearchResult]]
    ,1 seconds)
  }

  override def preStart() {
    logger.info("Started")
  }
  
  override def postStop() = {
    logger.info("postStop") 
  }

  override def preRestart(reason:Throwable,message:Option[Any]) {
    logger.info(s"ReStarted $reason $message")
    /*
    message match {
      case Some(x) => self forward x
      case None => 
    }
    */
  }
  
  val updateIatas = true

  var availIatas:Set[String] = null

  def updateAvailIatas(v:Set[String]) = {
    logger.info(s"UpdateAvailIatas ${v.size}")
    availIatas = v
  }

  def processSearch(sender:ActorRef,tr:model.TravelRequest)(x: =>Unit) = {
    rqIdx+=1
    
    logger.info(s"StartSearch:${rqIdx} ${tr}")

    //become(waitAnswer(sender,tr))
    val stopSearch = if ( availIatas != null ) {
      if (! availIatas.contains(tr.iataFrom)) {
        logger.warn(s"No routes for iataFrom:${tr.iataFrom} availible")
        true
      } else if ( ! availIatas.contains(tr.iataTo)) {
        logger.warn(s"No routes for iataTo:${tr.iataTo} availible")
        true
      } else false
    } else false

    if ( stopSearch ) completeEmpty(sender,tr) else checkDBCache(tr) match {
      case Some(cacheret) => 
        logger.debug(s"Load from cache:${rqIdx} ${tr}")
        sender ! SearchResult(tr,cacheret.ts)
      case None => withNRepeats(sender,tr)(x)
    }

  }

  def completeEmpty(sender:ActorRef,tr:model.TravelRequest) = sender ! SearchResult(tr,Seq())


  def withNRepeats(sender:ActorRef,tr:model.TravelRequest,_maxRepeats:Int = maxRepeats )(x : =>Unit ) {

    try {
      /*
      val t = doRealSearch2( tr )

      complete(sender,tr,t)
      */
      x
    } catch {
      
      case ex:Throwable => 
        if ( _maxRepeats > 1 ) {
          logger.warn(s"Parsing: $tr failed. Try ${maxRepeats - _maxRepeats}/$maxRepeats times exception: $ex\n" + ex.getStackTrace().mkString("\n"))
          withNRepeats(sender,tr,_maxRepeats-1)(x)
        } else {
          logger.error(s"Parsing: $tr failed unkwnon exception $ex\n" + ex.getStackTrace().mkString("\n"))
          sender ! akka.actor.Status.Failure(ex)
        }
    }

  }

  // Util

  def waitFor[T](timeout:Duration,what:String)(awaitable: scala.concurrent.Awaitable[T]) = {
    val t0 = System.nanoTime()

    try {
      scala.concurrent.Await.result(awaitable,timeout)
    } catch {
      case x:java.util.concurrent.TimeoutException => throw new java.util.concurrent.TimeoutException(s"$what:timeout $timeout")
      case x:Throwable => throw x
    }
    val t1 = System.nanoTime()
    val taken = (t1-t0)/1e9
    logger.debug(s"waitFor:$what taken=$taken s")
  }


  def testOn(str:String)(f: => Boolean) {
    if (! f )  throw new ParseException(str)
  }

  def catchFetching[T](p:Page,tr:model.TravelRequest)(x : => Seq[T]):Seq[T] = {
    try {
      x
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



abstract class SingleFetcherActor(maxRepeats:Int,noCache:Boolean=false) extends BaseFetcherActor(maxRepeats,noCache) {

  case class ABFlight(
    iataFrom: String,
    iataTo: String,
    depdate: java.util.Date,
    avldate: java.util.Date,
    flnum:String,
    avline:String
  )

  case class ABTicket(val ticket:model.Ticket,val flclass:FlightClass,val price:Float)

  def complete(sender:ActorRef,tr:model.TravelRequest,tickets:Seq[ABTicket] ) = {
    logger.info(s"Search $tr: Completed: ${tickets.length} tickets found")

    val tickets2send = tickets.groupBy(_.ticket.tuid).map {
      t => 
      val t0 = if ( tr.flclass ==  Business)
        t._2.filter( _.flclass == Business ).minBy(_.price)  
      else 
        t._2.minBy(_.price)

      t0.ticket

    }.toSeq

    val ret = SearchResult(tr,tickets2send)

    saveDBCache(ret)

    sender ! ret
  }


  def receive = {
    case StartSearch(tr) => processSearch(sender,tr) {
      val t = doRealSearch2( tr )
      complete(sender,tr,t)      
    }
  }

  def doRealSearch2(tr:model.TravelRequest):Seq[ABTicket]

}
