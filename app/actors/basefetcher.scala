package actors
import akka.actor.{Actor,Cancellable}
import scala.concurrent.{Future,Promise}

import play.api.{Logger,Play}



class PhantomInitException(msg: String) extends RuntimeException(msg)
class PhantomProcessException(msg: String) extends RuntimeException(msg)  
// esli avialinija ne letaet po dannomu napravleniju otlichaetsja ot prosto 
// esli netu biletov
class NoFlightsException() extends RuntimeException("NOFLIGHTS")  
class ParseException(s:String) extends RuntimeException(s)  

trait Caching {
  self:WithLogger => 

  import play.api.Play.current

  lazy val cacheDir = Play.application.path + "/" + Play.current.configuration.getString("cache-dir").getOrElse({
      logger.error("cache-dir not configured")
      throw play.api.UnexpectedException(Some("cache-dir not configured"))  
    })

  def md5(text: String) : String = java.security.MessageDigest.getInstance("MD5").digest(text.getBytes()).map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  def writeFile(fname:String,content:String) = Some(new java.io.PrintWriter(fname)).foreach{p => p.write(content); p.close}
  def saveCache(f:String,responseBody:String) = {
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

abstract class BaseFetcherActor extends Actor with WithLogger {

  class NotAvailibleDirecttion extends Throwable;

  import sys.process._
  val system = akka.actor.ActorSystem("system")

  import context.dispatcher
  import scala.concurrent.duration._

  var pid = 0 

  

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
  
}