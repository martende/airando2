package actors
import akka.actor.{Actor,Cancellable}
import scala.concurrent.{Future,Promise}

import play.api.Logger



class PhantomInitException(msg: String) extends RuntimeException(msg)
class PhantomProcessException(msg: String) extends RuntimeException(msg)  
// esli avialinija ne letaet po dannomu napravleniju otlichaetsja ot prosto 
// esli netu biletov
class NoFlightsException() extends RuntimeException("NOFLIGHTS")  

abstract class BaseFetcherActor extends Actor {

  import sys.process._
  val system = akka.actor.ActorSystem("system")

  import context.dispatcher
  import scala.concurrent.duration._

  var pid = 0 

  
  def execAsync(cmd:Seq[String])(fn: String=>Unit) = {
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
}