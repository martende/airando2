package actors
import akka.actor.{Actor,Cancellable}
import scala.concurrent.{Future,Promise}

abstract class BaseFetcherActor extends Actor {
  import sys.process._
  val system = akka.actor.ActorSystem("system")

  import context.dispatcher
  import scala.concurrent.duration._

  var pid = 0 


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