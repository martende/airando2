import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import org.scalatest.WordSpecLike
import org.scalatest.FunSuiteLike 
import org.scalatest.Matchers
import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfterAll

import org.scalatest.time.Span
import org.scalatest.time.{Millis,Seconds}



import org.scalatest.concurrent.ScalaFutures

import akka.testkit.ImplicitSender

import com.typesafe.config.ConfigFactory

import play.api.test._
import play.api.test.Helpers._

import play.api._
import play.api.libs.json._
import play.api.libs.iteratee.Iteratee
import scala.concurrent.duration._

import java.text.SimpleDateFormat
import scala.util.{Try, Success, Failure}

import model.SearchResult

/*
  
  Local* - parsers with local deps 
  
*/
class LocalAviasalesParser extends FunSuite {
  
  test("testdata/CGN-BER-20140812-aviasales.json") {
    val d = scala.io.Source.fromFile("testdata/CGN-BER-20140812-aviasales.json").mkString
    val AVSParser = new actors.AvsParser with actors.WithLogger {
      val logger = Logger("AvsParser-tester")
      override def updateGates(gates:Seq[model.Gate]) = {}
    }

    val actors.AviasalesSearchResult(data,curencies,avialines) = AVSParser.parse(d)
    val airberiln = data.filter { x => 
      x.direct_flights(0).airline == "AB"
    }

    
    val m:Seq[Pair[Float,model.Ticket]] = airberiln.map {
      x => ( x.native_prices.getOrElse("203",100000.0f) -> x )
    }

    val ab = m.min(Ordering.by({
      x:Pair[Float,model.Ticket] => x._1
    }))._2

    assert(Math.floor(ab.native_prices("203")) == 65.0 )
    assert(Math.floor(ab.native_prices("62")) == 50.0 )

    assert(ab.order_urls("20")=="avs:e6379cc6-2674-47c0-bd43-61ce4bcd8dd4:20300053")
  }

}


class LocalMongoSpec extends FunSuite with BeforeAndAfterAll {
  import com.mongodb.casbah.Imports._
  import org.joda.time.LocalDateTime
  import com.mongodb.casbah.commons.conversions.scala._

  val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
  implicit def str2Date(s:String) = df.parse(s)

  override def beforeAll {
    MongoClient("localhost", 27017).dropDatabase("airando-test")
  }
  override def afterAll {
    MongoClient("localhost", 27017).dropDatabase("airando-test")
  }

  test("simpleops") {
    val t = new actors.DBApi {
      val mongoClient:MongoClient = MongoClient("localhost", 27017)
      val db:MongoDB = mongoClient("airando-test")
    }
    t.save("aviasales",SearchResult(
      model.TravelRequest("HEL","OSL",model.TravelType.OneWay,
          "2015-03-21","2015-03-21",3,2,1,model.FlightClass.Economy),
      Seq(
        model.Ticket("sign",Seq(
          model.Flight("HEL","BEL","AVV",0,"123","2015-03-21",Some("2015-03-21"),Some("AC"),0),
          model.Flight("BEL","OSL","AVV",0,"123","2015-03-21",Some("2015-03-21"),Some("AC"),0)
        ),Some(
          Seq(
            model.Flight("OSL","BEL","AVV",0,"123","2015-03-21",Some("2015-03-21"),Some("AC"),0),
            model.Flight("BEL","HEL","AVV",0,"123","2015-03-21",Some("2015-03-21"),Some("AC"),0)
          )
        ),Map("123"->123),Map("123"->"123")),
        model.Ticket("sign",Seq(),None,Map("123"->123),Map("123"->"123"))
      )
    ))
    val tr = model.TravelRequest("HEL","OSL",model.TravelType.OneWay,
          "2015-03-21","2015-03-21",3,2,1,model.FlightClass.Economy)
    val r1 = t.get("aviasales",tr)
    assert(! r1.isEmpty)
    val r2 = t.get("aviasales",tr,-1)
    assert(r2.isEmpty)

    //t.mongoClient.dropDatabase("airando-test")
  }
}


class LocalAVSParserSpecLive extends FunSuite with ScalaFutures {
  implicit override def patienceConfig =
  PatienceConfig(timeout = Span(1, Seconds), interval = Span(50, Millis))

  test("MSQ airport") {
    import scala.concurrent._

    //class AA extends ;
    val r = new actors.AvsCacheParserAPI with actors.FileCaching {
      override lazy val cacheDir="./cache/"
    }.fetchAviasalesCheapest("MSQ")

    whenReady(r) { 
      r => assert(r.length > 10)
        
    }

  }

}

class BaseActorTester (_system: ActorSystem) extends akka.testkit.TestKit(_system) with FunSuiteLike with BeforeAndAfterAll {
  def this() = this(ActorSystem("testActorSystem", ConfigFactory.load()))

  implicit val app: FakeApplication = FakeApplication()
  // Execution context statt import ExecutionContext.Implicits.global
  implicit val ec = system.dispatcher

  override def beforeAll {
    Play.start(app)
  }
  override def afterAll {
    akka.testkit.TestKit.shutdownActorSystem(system)
  }

  val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
  implicit def str2Date(s:String) = df.parse(s)

  val p1 = actors.StartSearch(model.TravelRequest("HEL","OSL",model.TravelType.OneWay,
          "2015-03-21","2015-03-21",3,2,1,model.FlightClass.Economy))

  val p2 = actors.StartSearch(model.TravelRequest("ZRH","BER",model.TravelType.OneWay,
          "2015-03-21","2015-03-21",3,2,1,model.FlightClass.Economy))

  val p3 = actors.StartSearch(model.TravelRequest("FRA","JFK",model.TravelType.Return,
          "2015-03-21","2015-03-22",1,0,0,model.FlightClass.Economy))


}

class RemoteAviasalesSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))
  
  val fetcher = system.actorOf(Props[actors.Aviasales])  

  test("t1") {
    within (60 seconds) {
      fetcher ! p3
      expectMsgPF() {
        case t @ ( SearchResult(_,_) | actors.AviasalesSearchResult(_,_,_) ) => 
          val tickets = t match {
            case SearchResult(_,v) => v
            case actors.AviasalesSearchResult(v,_,_) => v
          }
          assert ( tickets.length >  100 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 521.6497f )
        //case x => println("AAAAAAAAA",x.getClass)
      }
      
      Thread.sleep(1000)

    }
  }

}

class RemoteSwissAirlinesSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(600, Seconds), interval = Span(500, Millis))
  
  val fetcher = system.actorOf(Props(new actors.SwissAirlines(maxRepeats=1)))  
  
  test("p1") {
    //actors.Manager.updateCurrencies("chf"->1.0f)
    within (200 seconds) {
      fetcher ! p1
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length ==  0 ) 
      }
    }
  }


  test("p2") {
    actors.Manager.updateCurrencies("chf"->1.0f)
    within (200 seconds) {
      fetcher ! p2
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 545.0 )
      }
    }
  }

  test("p3") {
    within (200 seconds) {
      fetcher ! p3
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 596.0 )
          
      }
    }
  }

}


class RemoteFlyTapSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  val fetcher = system.actorOf(Props(new actors.FlyTap(maxRepeats=1)))

  test("HAM -> SVQ") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.Return,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 534.52f )
      }
    }
  }

  test("HAM -> SVQ oneway") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.OneWay,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 304.04f )
      }
    }
  }

  test("SVQ -> HAM oneway") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("SVQ","HAM",model.TravelType.OneWay,
          "2015-03-23","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 258.69f )
      }
    }
  }
}



class RemoteCheapAirSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  val fetcher = system.actorOf(Props(new actors.CheapAir(maxRepeats=1)))
/*
  test("HAM -> SVQ") {
    within (60 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.Return,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 507.0f )
          
          val f1 = tickets.find(_.minPrice == 585 ).get
          assert ( f1.tuid == "201503211100HAM:201503211440PMI:201503211610SVQ-201503231010SVQ:201503231230BCN:201503231510HAM" )
          
      }
    }
  }
*/
  test("p1") {
    within (60 seconds) {
      fetcher ! p1
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          /*for ( t <- tickets ) {
            println(t)
          }*/
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 520 )
          
          //val f1 = tickets.find(_.minPrice == 585 ).get
          //assert ( f1.tuid == "201503211100HAM:201503211440PMI:201503211610SVQ-201503231010SVQ:201503231230BCN:201503231510HAM" )
      }
    }
  }

}

class RemoteAirberlinSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  val fetcher = system.actorOf(Props(new actors.Airberlin(maxRepeats=1)))

  test("HAM -> SVQ") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.Return,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 265.84f )
      }
    }
  }
/*
  test("HAM -> SVQ oneway") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.OneWay,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 304.04f )
      }
    }
  }

  test("SVQ -> HAM oneway") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("SVQ","HAM",model.TravelType.OneWay,
          "2015-03-23","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 258.69f )
      }
    }
  }
  */
}



class RemoteNorvegianSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  val fetcher = system.actorOf(Props(new actors.NorvegianAirlines(maxRepeats=1,noCache=true)))
  
  test("HAM -> SVQ") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.Return,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length ==  0 ) 
          //val mt = tickets.minBy(_.minPrice) 
          //assert( mt.minPrice == 265.84f )
      }
    }
  }

  test("HEL -> BER") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HEL","BER",model.TravelType.OneWay,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length ==  0 ) 
      }
    }
  }


  test("HEL -> BER - 2") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HEL","BER",model.TravelType.OneWay,
          "2015-03-23","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length ==  2 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 110.7f )

      }
    }
  }
 
  test("BER -> OSL") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("BER","OSL",model.TravelType.OneWay,
          "2015-03-23","2015-03-23",2,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length ==  5 ) 
          val mt = tickets.find(_.tuid == "201503231205SXF:201503231345OSL").get
          assert( mt.minPrice == 105.4f )
      }
    }
  }

/*
  test("HAM -> SVQ oneway") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.OneWay,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 304.04f )
      }
    }
  }

  test("SVQ -> HAM oneway") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("SVQ","HAM",model.TravelType.OneWay,
          "2015-03-23","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 258.69f )
      }
    }
  }
  */
}
