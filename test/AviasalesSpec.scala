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


class AviasalesParserSpec extends FunSuite {
  
  test("testdata/CGN-BER-20140812-aviasales.json") {
    val d = scala.io.Source.fromFile("testdata/CGN-BER-20140812-aviasales.json").mkString
    val AVSParser = new actors.AvsParser with actors.WithLogger {
      val logger = Logger("AvsParser-tester")
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




class AVSParserSpecLive extends FunSuite with ScalaFutures {
  implicit override def patienceConfig =
  PatienceConfig(timeout = Span(1, Seconds), interval = Span(50, Millis))

  test("MSQ airport") {
    import scala.concurrent._

    val r = new actors.AvsCacheParser() {
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

class AviasalesSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))
  
  val fetcher = system.actorOf(Props[actors.Aviasales])  

  test("t1") {
    within (5 seconds) {
      fetcher ! p3
      expectMsgPF() {
        case actors.AviasalesSearchResult(tickets,_,_) => 
          assert ( tickets.length >  100 ) 
      }
    }
  }

}

class SwissAirlinesSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(600, Seconds), interval = Span(500, Millis))
  
  val fetcher = system.actorOf(Props(new actors.SwissAirlines(maxRepeats=1)))  
  
  test("p1") {
    //actors.Manager.updateCurrencies("chf"->1.0f)
    within (200 seconds) {
      fetcher ! p1
      expectMsgPF() {
        case actors.SearchResult(_,tickets) => 
          assert ( tickets.length ==  0 ) 
      }
    }
  }


  test("p2") {
    actors.Manager.updateCurrencies("chf"->1.0f)
    within (200 seconds) {
      fetcher ! p2
      expectMsgPF() {
        case actors.SearchResult(_,tickets) => 
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
        case actors.SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 601.0 )
          
      }
    }
  }

}


class FlyTapSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  val fetcher = system.actorOf(Props(new actors.FlyTap(maxRepeats=1)))

  test("HAM -> SVQ") {
    within (20 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.Return,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case actors.SearchResult(_,tickets) => 
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
        case actors.SearchResult(_,tickets) => 
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
        case actors.SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 258.69f )
      }
    }
  }
}



class CheapAirSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))

  val fetcher = system.actorOf(Props(new actors.CheapAir(maxRepeats=1)))

  test("HAM -> SVQ") {
    within (60 seconds) {
      fetcher ! actors.StartSearch(model.TravelRequest("HAM","SVQ",model.TravelType.Return,
          "2015-03-21","2015-03-23",1,0,0,model.FlightClass.Economy))
      expectMsgPF() {
        case actors.SearchResult(_,tickets) => 
          assert ( tickets.length !=  0 ) 
          val mt = tickets.minBy(_.minPrice) 
          assert( mt.minPrice == 534.52f )
      }
    }
  }


}