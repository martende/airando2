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

import model.{Gate,CacheRequest}

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
  var colls:Map[String,MongoCollection]

  implicit def tr2Mongo(tr:TravelRequest) = MongoDBObject(
    "iataFrom" -> tr.iataFrom,
    "iataTo" -> tr.iataTo,
    "departure" -> tr.departure,
    "arrival" -> tr.arrival,
    "adults"  -> tr.adults,
    "childs"  -> tr.childs,
    "infants"  -> tr.childs,
    "traveltype" -> tr.traveltype,
    "flclass" -> tr.flclass
  )

  def save(service:String,r:SearchResult) {
    
    val collection = colls.getOrElse(service,{
      colls += service -> db(collection)
    })

    cheapairCollection.update(
      MongoDBObject("tr" -> tr)
      MongoDBObject(
        "added" -> LocalDateTime.now(),
        "tr"    -> tr,
        "ts"    -> ts
      )
    )
  }
}

class CacheStorageActor extends Actor with DBApi {
  
  RegisterJodaTimeConversionHelpers()

  val mongoClient:MongoClient = MongoClient("localhost", 27017)
  val db:MongoDB = mongoClient("airando2")
  val cheapairCollection:MongoCollection = db("cheapair")


  

  def receive = {
    case CacheResult("cheapair",SearchResult(tr,ts)) => 
      cheapairCollection.update(
        MongoDBObject("tr" -> tr)
        MongoDBObject(
          "added" -> LocalDateTime.now(),
          "tr"    -> tr,
          "ts"    -> ts
        )
      )
    case CacheRequest("cheapair",tr) => 
      sender ! cheapairCollection.findOne(tr).map({
        ret => 
        SearchResult(tr,Seq())
      })
  }
}