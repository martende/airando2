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
import play.api.libs.json.Reads._

import play.api.i18n.Lang

import play.api.libs.iteratee.Enumerator

// Locals 

import model.Gate

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

    def flights_updater = __.read[JsArray].map {
          df => JsArray( df.value.map {
            fl => 
              val fromIata = (fl \ "iataFrom").as[JsString].value
              val toIata = (fl \ "iataTo").as[JsString].value
              val (fromName,fromCityName,fromTZ) = model.Airports.get(fromIata).fold( ("","",0) ) {
                x => 
                  (x.name,x.city,x.time_zone)
              }
              val (toName,toCityName,toTZ) = model.Airports.get(toIata).fold( ("","",0)  ) {
                x => (x.name,x.city,x.time_zone)
              }
              fl.as[JsObject] ++ Json.obj(
                "fromName" -> fromName,
                "toName" -> toName,
                "fromCityName" -> fromCityName,
                "toCityName" -> toCityName,
                "fromTZ"   -> fromTZ,
                "toTZ"   -> toTZ
              )  
          }) 
        }
    
    def direct_flights_transform = (__ \ "direct_flights" ).json.update(flights_updater)
    def return_flights_transform = (__ \ "return_flights" ).json.update(flights_updater)

    def tickets_transform = 
      __.json.update(
        __.read[JsObject].map {
          x => 
            val tkts = (x \ "tickets").asOpt[JsArray].map {
              case JsArray(xs) => Json.obj("tickets" -> JsArray(
                xs.map { tkt => 
                   val dft = (tkt transform direct_flights_transform get) 
                   dft.transform(return_flights_transform).getOrElse( dft )

                }
              )) 
            }
            tkts.fold(x)(x ++ _)
            //x ++ tkts.fold(None)
        }
      )
    var sentGates:Set[String] = Set() 
    if ( cb forall Character.isDigit ) 
      getActorRef(id) match {
        case None => 
          Logger.error(s"Actor $id not found")
          Future.successful(Ok(s"""<script type="text/javascript">parent.cb$cb({ok:0,error:"404",id:$id});</script>\n""").as("text/html"))
        case Some(fetcher) => 
          ( fetcher ? actors.Subscribe() ).map {      
            case actors.Connected( enumerator ) => 
              Ok.chunked(
                enumerator.map {
                  x => 
                  val y = try {
                    val gates = (x \ "tickets").asOpt[JsArray].map {
                      x => x.value.flatMap { tkt => (tkt \ "order_urls").as[JsObject].keys } .toSet
                    }
                    val gatesObjs = gates match {
                      case Some(gset) => 
                          val fgset = gset.filter { x=>
                            ! sentGates.contains(x)
                          }.toSeq
                          actors.Manager.getGates(fgset).toSeq
                      case None => List()
                    }
                    implicit val gatesWrites = Json.writes[Gate]

                    x.transform(
                      tickets_transform
                    ) match {
                      case JsSuccess(ret,_) => 
                        val gatesObj = if (gatesObjs.length > 0 ) Json.obj("gates" -> Json.toJson(gatesObjs)) else Json.obj()

                        val ret2 = gatesObj ++ret.as[JsObject] 
                        ret2
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
                  } catch {
                    case e : Throwable => 
                      Logger.error("Postprocessing error",e)
                      Logger.error("DATA: "+x)
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