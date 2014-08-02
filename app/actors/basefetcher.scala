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
    
    message match {
      case Some(x) => self forward x
      case None => 
    }

  }
/*
  def execAsync(cmd:Seq[String])(fn: String=>Boolean) = {
    Logger.info(s"execAsync ${cmd.mkString(" ")}")
    pid+=1

    val fatalPromise = Promise[Option[String]]()
    val pb = Process(cmd)
    
    val out = new StringBuilder
    //val err = new StringBuilder

    try {
      val pr:Process = pb.run(ProcessLogger {
        s =>
        try {
          fn(s)
        } catch {
          case e:Exception => 
            fatalPromise.failure(e)
        }
      })
      val exec = Future {
        //val ret = cmd !!
        pr.exitValue match {
          case 0 => Some(out.toString)
          case _ => None
        }
        
      }

      var timeout = system.scheduler.scheduleOnce(10000 millis) {
        pr.destroy()
        fatalPromise.success(None)
      }
      

      val r = Future.firstCompletedOf(Seq(fatalPromise.future,exec))

      // postprocessing cleanup
      r.onComplete {
        _ => 
          pr.destroy()
          timeout.cancel()
      }
      
      r      
    } catch {
      case e:Exception => Future failed e
    }

  }

  def execWithTimeout(cmd:Seq[String]) = {
  	pid+=1

    val p = Promise[Option[String]]()
    val pb = Process(cmd)
    
    val out = new StringBuilder
    //val err = new StringBuilder

    val pr = pb.run(ProcessLogger(out append _+"\n"/*,err append _*/))
    

    val exec = Future {
      //val ret = cmd !!
      pr.exitValue match {
        case 0 => Some(out.toString)
        case _ => None
      }
      
    }

    var timeout = system.scheduler.scheduleOnce(1000 millis) {
      pr.destroy()
      p.success(None)
    }
    

    val r = Future.firstCompletedOf(Seq(p.future,exec))
    
    exec.onComplete {
      _ => timeout.cancel()
    }

    r
  }

  */
}