package actors

import play.api.libs.concurrent.Akka

import akka.actor.{Actor,Props,Identify, ActorIdentity,ActorSystem,ActorRef,Cancellable}

import play.api.Logger
import play.api.Play.current

import scala.concurrent.{Await,Future,Promise}
import scala.util.{Try, Success, Failure}

import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._

import play.api.libs.concurrent.Execution.Implicits._

import play.api.libs.iteratee.{Concurrent,Enumerator}

import play.api._
import play.api.libs.ws._

// Json
import play.api.libs.json.{JsValue,Json}
//import play.api.libs.functional.syntax._

// TravelType enum import
import model.TravelType._
import model.FlightClass._

import java.util.Date
import java.text.SimpleDateFormat

import model.{Gate,TravelRequest,SearchResult,Ticket,Flight}

class GatesStorageActor extends Actor {

    var gatesMap:Map[String,Gate] = Map(
      "DY"->Gate("DY","eur","NorvegianAirlines"),
      "AB"->Gate("AB","eur","Airberlin"),
      "LX"->Gate("LX","eur","Swiss"),
      "TP"->Gate("TP","eur","TAP Portugal"),
      actors.CheapAir.ID->Gate(actors.CheapAir.ID,"usd","CheapAir")
    )

    def receive = {
        case gateIds:Seq[Any] => 
          if (!gateIds.isEmpty) gateIds.head match {
            case _:String  => sender ! gateIds.asInstanceOf[Seq[String]].flatMap(gatesMap get _)
            case _:Gate    => for (g <- gateIds.asInstanceOf[Seq[Gate]] ) {
              gatesMap += (g.id -> g)
            }
          } 
        
    }
}



class CurrenciesStorageActor extends Actor {
  var curMap:Map[String,Float] = Map()

  override def preStart() {
    Logger("CurrenciesStorageActor").info("Load currency db")
    curMap = Map("chf"->1.0f,"usd"->1.0f,"eur"->1.0f)
  }
  
  def receive = {
    case item:(_,_)     => curMap = curMap + item.asInstanceOf[(String,Float)]
    case items:Map[_,_] => curMap = curMap ++ items.asInstanceOf[Map[String,Float]]
    case items:Seq[_]   => sender ! items.asInstanceOf[Seq[String]].map{ k => ( k -> curMap.get(k) ) }.toMap
    case cur:String     => sender ! curMap.getOrElse(cur,akka.actor.Status.Failure(new Exception(s"Unknown currency $cur")))
  }

}

import com.mongodb.casbah.Imports._
import org.joda.time.LocalDateTime
import com.mongodb.casbah.commons.conversions.scala._

trait DBApi {
  val mongoClient:MongoClient
  val db:MongoDB

  val colls:scala.collection.mutable.Map[String,MongoCollection] = scala.collection.mutable.Map()

  RegisterJodaTimeConversionHelpers()

  private def collection(service:String) = colls.getOrElse(service,{
    val c = db(service)
    colls += service -> c
    c
  })

  private def fl2mongo(fl:Flight) = MongoDBObject(
    "iataFrom" -> fl.iataFrom, 
    "airline" -> fl.airline,
    "duration" -> fl.duration,
    "flnum" -> fl.flnum,
    "departure" -> fl.departure,
    "arrival" -> fl.arrival,
    "aircraft" -> fl.aircraft,
    "delay" -> fl.delay
  )

  private def tr2mongo(tr:TravelRequest) = MongoDBObject(
    "iataFrom" -> tr.iataFrom,
    "iataTo" -> tr.iataTo,
    "departure" -> tr.departure,
    "arrival" -> tr.arrival,
    "adults"  -> tr.adults,
    "childs"  -> tr.childs,
    "infants"  -> tr.childs,
    "traveltype" -> tr.traveltype.toString,
    "flclass" -> tr.flclass.toString
  )

  def save(service:String,r:SearchResult) {
    collection(service).update(
      "tr" $eq tr2mongo(r.tr),
      MongoDBObject(
        "added" -> LocalDateTime.now(),
        "tr"    -> tr2mongo(r.tr),
        "ts"    -> r.ts.map({
          tkt => MongoDBObject(
            "sign" -> tkt.sign,
            "direct_flights" -> tkt.direct_flights.map(fl2mongo),
            "return_flights" -> tkt.return_flights.map (
              fls => fls.map(fl2mongo)
            ),
            "native_prices" -> tkt.native_prices,
            "order_urls" -> tkt.order_urls
          )
        })
      ),
      upsert = true
    )
    
  }

  def get(service:String,tr:TravelRequest,ttl:Int=86400) = collection(service).findOne(
      $and ( "tr" $eq tr2mongo(tr) , 
        MongoDBObject( "added" -> 
          MongoDBObject("$gt" -> LocalDateTime.now().minusSeconds(ttl) )
        )
      )
    ).map(ret => SearchResult(tr,ret.getAs[Seq[Ticket]]("ts").get) )

}

case class CacheResult(service:String,r:SearchResult)
case class CacheRequest(service:String,tr:TravelRequest)

class CacheStorageActor extends Actor with DBApi {
  
  
  val mongoClient:MongoClient = MongoClient("localhost", 27017)
  val db:MongoDB = mongoClient("airando2")

  def receive = {
    case CacheResult("cheapair",r) => save("cheapair",r)
    case CacheRequest("cheapair",tr) => sender ! get("cheapair",tr)
  }

}