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
import actors.Manager

trait Track extends Controller {
  private[Track] implicit val timeout = Timeout(5 seconds)

  
  def flights_updater(implicit l:Lang) = __.read[JsArray].map {
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
    
    def direct_flights_transform(implicit l:Lang) = (__ \ "direct_flights" ).json.update(flights_updater)
    def return_flights_transform(implicit l:Lang) = (__ \ "return_flights" ).json.update(flights_updater)

    def tickets_transform(implicit l:Lang) = 
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

  private def subscribeToFetcher(fetcher:ActorRef,cb:String,id:String)(implicit l:Lang) = ( fetcher ? actors.Subscribe() ).map {      
    case actors.ConnectionFailed() => 
      Logger.error(s"Actor $id not found - connection phase")
      Ok(s"""<script type="text/javascript">parent.cb$cb({ok:0,error:"404",id:$id});</script>\n""").as("text/html")
    case actors.Connected( enumerator ) => 
      var sentGates:Set[String] = Set()

      Ok.chunked({
          val currency_rates = Manager.getCurrencyRates()
          val y = Json.obj("currency_rates" -> Json.toJson(currency_rates))
          Enumerator(s"""<script type="text/javascript">parent.cb$cb({ok:1,data:$y});</script>\n""")
        } >>>
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
                  Manager.getGates(fgset).toSeq
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

          if (y.fields.length == 1 && y.fields(0)._1 == "tickets" && y.fields(0)._2.as[JsArray].value.length == 0) {
            "\n"
          } else 
            s"""<script type="text/javascript">parent.cb$cb({ok:1,data:$y});</script>\n"""
        } >>> 
        Enumerator(s"""<script type="text/javascript">parent.cb$cb(null);</script>\n""")
      ).as("text/html")
  }
    /*(__ \ "tickets" ).json.update(
      __.readOpt[JsArray].map {
        case Some(JsArray(xs)) => JsArray(xs)
      }
    )*/


  def track(id:String,cb:String) = Action.async {
    implicit request =>
    Logger.info(s"Start tracking for id=$id")

    if ( cb forall Character.isDigit ) 
      Manager.getActorRef(id)  match {
        case None => 
          Manager.getTravelInfo(id.toInt) match {
            case Some(tr) => 
              Logger.info("Respawn actor for $tr")
              val (newSearchId,newFetcher) = actors.Manager.startSearch2(tr)
              subscribeToFetcher(newFetcher,cb,newSearchId.toString)
            case None     => 
              Logger.error(s"Actor $id not found")
              Future.successful(Ok(s"""<script type="text/javascript">parent.cb$cb({ok:0,error:"404",id:$id});</script>\n""").as("text/html"))
          }
        case Some(fetcher) => 
          subscribeToFetcher(fetcher,cb,id)
          
      }
    else {
      Logger.error(s"CB is not digit")
      Future.successful(NotFound)
    }
  }


}