package controllers

import play.api.mvc.{Action, Controller}
import play.api.Logger

import play.api.Play.current


// Akka
import play.api.libs.concurrent.Akka
import akka.pattern.ask
import akka.util.Timeout
// implicit for Akka ExecutionContext.Implicits.global
import play.api.libs.concurrent.Execution.Implicits._

import akka.actor.{ Identify, ActorIdentity , ActorRef}


// 5 Seconds implicit
import scala.concurrent.duration._

// Futures
import scala.concurrent.{Await,Future}

// Json
import play.api.libs.json._
import play.api.libs.functional.syntax._

import play.api.i18n.Lang


import play.api.libs.iteratee.Enumerator

trait Track extends Controller {
  private[Track] implicit val timeout = Timeout(5 seconds)

  def getActorRef(id:String):Option[ActorRef] = {
    val myFutureStuff = Akka.system.actorSelection(s"akka://application/user/fetcher-${id}")
      
    val aid:ActorIdentity = Await.result((myFutureStuff ? Identify(1)).mapTo[ActorIdentity],0.1 seconds)
    
    aid.ref

  }

  
    /*(__ \ "tickets" ).json.update(
      __.readOpt[JsArray].map {
        case Some(JsArray(xs)) => JsArray(xs)
      }
    )*/


  def track(id:String,cb:String) = Action.async {
    implicit request =>
    Logger.info(s"Start tracking for id=$id")
    def direct_flights_transform = 
      (__ \ "direct_flights" ).json.update(
        __.read[JsArray].map {
          df => JsArray( df.value.map {
            fl => 
              val fromIata = (fl \ "origin").as[JsString].value
              val toIata = (fl \ "destination").as[JsString].value
              val fromName = model.Airports.get(fromIata).fold("") {
                x => x.name
              }
              val toName = model.Airports.get(toIata).fold("") {
                x => x.name
              }
              fl.as[JsObject] ++ Json.obj(
                "fromName" -> fromName,
                "toName" -> toName
              )  
          }) 
        }
      )
    def tickets_transform = 
      __.json.update(
        __.read[JsObject].map {
          x => 
            val tkts = (x \ "tickets").asOpt[JsArray].map {
              case JsArray(xs) => Json.obj("tickets" -> JsArray(
                xs.map {
                  tkt => tkt.transform(direct_flights_transform).get
                }
              )) 
            }
            tkts.fold(x)(x ++ _)
            //x ++ tkts.fold(None)
        }
      )
    if ( cb forall Character.isDigit ) 
      getActorRef(id) match {
        case None => 
          Logger.error(s"Actor $id not found")
          Future.successful(Ok(s"""<script type="text/javascript">parent.cb$cb({ok:0,error:"404",id:$id});</script>\n""").as("text/html"))
        case Some(fetcher) => 
          ( fetcher ? actors.Subscribe ).map {      
            case actors.Connected( enumerator ) => 
              Ok.chunked(
                enumerator.map {
                  x => 
                  val y = try {
                    x.transform(
                      tickets_transform
                    ) match {
                      case JsSuccess(ret,_) => ret
                      case JsError(errors) => 
                        Logger.error("Json postprocessing error")
                        for ( (path,elist) <- errors) {
                          Logger.error(s"Errors on $path: $elist")
                        }
                        Json.obj(
                          "error" -> "501",
                          "ok" -> 0
                        )  
                    }
                    /*
                    JsArray(x.as[Seq[JsObject]].map {
                      o => o.transform(
                        direct_flights_transform
                      ).get
                    })
                    */
                  } catch {
                    case e => 
                      Logger.error("Post processing error",e)
                      Json.obj(
                        "error" -> "500",
                        "ok" -> 0
                      )  
                  }

                  s"""<script type="text/javascript">parent.cb$cb({ok:1,data:$y});</script>\n"""
                } >>> 
                Enumerator(s"""<script type="text/javascript">parent.cb$cb(null);</script>\n""")
              ).as("text/html")
          }
      }
    else {
      Logger.error(s"CB is not digit")
      Future.successful(NotFound)
    }
  }


}