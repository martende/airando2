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

import model.Gate

class GatesStorageActor extends Actor {

    var gatesMap:Map[String,Gate] = Map(
      "DY"->Gate("DY","eur","NorvegianAirlines"),
      "AB"->Gate("AB","eur","Airberlin")
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
    def receive = {
      case items:Map[_,_] => curMap = curMap ++ items.asInstanceOf[Map[String,Float]]
      case items:Seq[_] => sender ! items.asInstanceOf[Seq[String]].map{ k => ( k -> curMap.get(k) ) }.toMap
    }
}