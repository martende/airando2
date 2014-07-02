package actors

import play.api.Logger
import scala.util.{Success, Failure}

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

class NorvegianAirlines extends BaseFetcherActor {
  import context.dispatcher
  
  var srcIatas:Set[String] = null
  var dstIatas:Set[String] = null

  case class UpdateSrcIatas(v:Set[String])
  case class UpdateDstIatas(v:Set[String])
/*
  def waitAnswer = {
    case UpdateSrcIatas(v) => srcIatas = v
    case UpdateDstIatas(v) => dstIatas = v

  }
  */
  def receive = {
    
    case StartSearch(tr) => 
      val _sender = sender
      Logger.info(s"StartSearch ${tr}")
        
      //become(waitAnswer)

      execAsync(Seq("./bin/phantomjs","--proxy=http://127.0.0.1:3128",
        "phantomjs/fetcher.js","norvegianairlines","ORY","BOJ","2014-07-03")) {
        x:String => 
          if ( x.startsWith("Can't open '") )
            throw new PhantomInitException(x)
          else if ( x.startsWith("PUSH:") ) {
            val data = x.substring(5)
            Logger.debug( "Push: " + data ) 

            Json.parse(data) match {
              case JsString(s) => 
                throw new PhantomInitException(s"push awaits JSON as answer but string found '$s'")
              case JsObject(pairs) => 
                for ((k,v) <- pairs) {
                  processPush(k,v)
                }
                //processPush(js.)
              case s => 
                throw new PhantomInitException(s"push awaits JSON as answer but found '$s'")
            }
          } else 
            println(x)
      } onComplete {
        case Failure(e) => Logger.error(e.toString)
        case Success(x) => 
          _sender ! "1"
          println("Complete ",x)
      }


  }

  def processPush(k:String,v:JsValue) {
    k match {
      case "avl" => self ! UpdateSrcIatas(v.as[List[String]].toSet)
      case "dep" => self ! UpdateDstIatas(v.as[List[String]].toSet)
      case _ => Logger.error("processPush: unknown key " + k)
    }
  }
}
