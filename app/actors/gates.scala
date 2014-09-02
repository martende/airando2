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

class GatesStorageActor extends Actor  {
    val logger = Logger("GatesStorageActor")

    var gatesMap:Map[String,Gate] = Map(
      "DY"->Gate("DY","eur","NorvegianAirlines"),
      actors.Airberlin.ID->Gate(actors.Airberlin.ID,"eur","Airberlin"),
      "LX"->Gate("LX","eur","Swiss"),
      "TP"->Gate("TP","eur","TAP Portugal"),
      actors.CheapAir.ID->Gate(actors.CheapAir.ID,"usd","CheapAir")
    )

    override def preStart = {
      logger.debug("Started")
    }

    def receive = {
        case gateIds:Seq[Any] =>     
          if (!gateIds.isEmpty) gateIds.head match {
            case _:String  => sender ! gateIds.asInstanceOf[Seq[String]].flatMap {
              x => gatesMap.get(x).orElse {
                logger.error(s"$x Not found")
                None
              }
            }
            case _:Gate    => for (g <- gateIds.asInstanceOf[Seq[Gate]] ) {
              gatesMap += (g.id -> g)
            }
          } 
        case _ => logger.error(s"Unknown message")
    }
}

import com.mongodb.casbah.Imports._
import org.joda.time.DateTime
import com.mongodb.casbah.commons.conversions.scala._


class CurrenciesStorageActor extends Actor {
  var curMap:Map[String,Float] = Map()
  val mongoClient:MongoClient = MongoClient("localhost", 27017)
  val db:MongoDB = mongoClient("airando2")
  val collection = db("currency")

  RegisterJodaTimeConversionHelpers()

  override def preStart() {
    import scala.collection.JavaConversions._
    
    curMap = collection.findOne() match {
      case Some(v:DBObject) => 
        v.flatMap {
          case (k:String,v:AnyRef) => try {
              Some( k -> v.toString.toFloat ) 
            } catch {
              case _:Exception => None
            }
        }.toMap
      case None    => Map("chf"->1.0f,"usd"->1.0f,"eur"->1.0f)
    }
    
    Logger("CurrenciesStorageActor").info("Load currency db")
  }
  
  def receive = {
    case item:(_,_)     =>
      val ii = item.asInstanceOf[(String,Float)] 
      curMap = curMap + ii
      collection.update(MongoDBObject(),
        MongoDBObject(
          "$set" -> MongoDBObject(ii._1 -> ii._2)
        ),upsert=true)
    case items:Map[_,_] => 
      val ii = items.asInstanceOf[Map[String,Float]]
      curMap = curMap ++ ii
      collection.update(MongoDBObject(),
        MongoDBObject(
          "$set" -> MongoDBObject(ii.toList)
        ),upsert=true)
    case items:Seq[_]   => sender ! items.asInstanceOf[Seq[String]].map{ k => ( k -> curMap.getOrElse(k,1.0f) ) }.toMap
    case cur:String     => sender ! curMap.getOrElse(cur,akka.actor.Status.Failure(new Exception(s"Unknown currency $cur")))
  }

}

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
    "iataTo" -> fl.iataTo, 
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

  def getRequired[T: Manifest](k:String)(implicit d:BasicDBObject):T = 
    d.getAs[T](k).getOrElse(throw new Exception(s"getRequired:$k data:$d"))

  private def mongo2fl(d:BasicDBObject) = {
    implicit val _d = d

    val now = DateTime.now()
    val a = MongoDBObject("d"->now)
    
    Flight(
      d.getAs[String]("iataFrom").get,
      getRequired[String]("iataTo"),
      //d.getAs[String]("iataTo").getOrElse(throw new Exception(s"AAAA $d")),
      d.getAs[String]("airline").get,
      d.getAs[Int]("duration").get,
      d.getAs[String]("flnum").get,
      getRequired[DateTime]("departure").toDate,
      d.getAs[DateTime]("arrival").map(_.toDate),
      d.getAs[String]("aircraft"),
      d.getAs[Int]("delay").get
    )
  } 

  def save(service:String,r:SearchResult) {
    collection(service).update(
      "tr" $eq tr2mongo(r.tr),
      MongoDBObject(
        "added" -> DateTime.now(),
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
          MongoDBObject("$gt" -> DateTime.now().minusSeconds(ttl) )
        )
      )
    ).map(ret => SearchResult(tr,ret.getAs[Seq[BasicDBObject]]("ts").get.map {
      d => Ticket(
        d.getAs[String]("sign").get,
        d.getAs[Seq[BasicDBObject]]("direct_flights").get.map(mongo2fl),
        d.getAs[Seq[BasicDBObject]]("return_flights").map(x => x.map(mongo2fl)),
        d.getAs[Map[String,Double]]("native_prices").get.map( x => x._1 -> x._2.toFloat ),
        d.getAs[Map[String,String]]("order_urls").get
      )
    }) )

}

case class CacheResult(service:String,r:SearchResult)
case class CacheRequest(service:String,tr:TravelRequest)

class CacheStorageActor extends Actor with DBApi  {
  val logger = Logger("CacheStorageActor")
  
  val mongoClient:MongoClient = MongoClient("localhost", 27017)
  val db:MongoDB = mongoClient("airando2")

  override def preStart = {
    logger.debug("Started")
  }

  def receive = {
    case CacheResult(id,r) => 
      save(id,r)
    case CacheRequest(id,tr) => 
      val ret = get(id,tr)
      sender ! ret
    case x => logger.error(s"Unkwnown message $x")
  }

}