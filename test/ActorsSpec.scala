import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import org.scalatest.WordSpecLike
import org.scalatest.FunSpecLike
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

class AVSParserSpec extends FunSuite {
  import actors.avsfetcher._
  test("testdata/CGN-BER-20140812-aviasales.json") {
    val d = scala.io.Source.fromFile("testdata/CGN-BER-20140812-aviasales.json").mkString
    val (data,gates,curencies,avialines) = AVSParser.parse(d)
    val airberiln = data.filter { x => 
      x.direct_flights(0).airline == "AB"
    }

    
    val m:Seq[Pair[Float,AVSTicket]] = airberiln.map {
      x => ( x.native_prices.getOrElse("203",100000.0f) -> x )
    }

    val ab = m.min(Ordering.by({
      x:Pair[Float,AVSTicket] => x._1
    }))._2

    assert(Math.floor(ab.native_prices("203")) == 65.0 )
    assert(Math.floor(ab.native_prices("62")) == 50.0 )

    assert(ab.order_urls("20")=="avs:e6379cc6-2674-47c0-bd43-61ce4bcd8dd4:20300053")
  }

}

class AVSParserSpecLive extends FunSuite with ScalaFutures {
  implicit override def patienceConfig =
  PatienceConfig(timeout = Span(1, Seconds), interval = Span(50, Millis))

  import actors.avsfetcher._
  test("MSQ airport") {
    import scala.concurrent._

    val r = new AvsCacheParser() {
      override lazy val cacheDir="./cache/"
    }.fetchAviasalesCheapest("MSQ")

    whenReady(r) { 
      r => assert(r.length > 10)
        
    }

  }

}


class ManagerSpec(_system: ActorSystem) extends TestKit(_system) 
  with ImplicitSender with WordSpecLike with ScalaFutures 
  with BeforeAndAfterAll
{
  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(1, Seconds), interval = Span(50, Millis))
  def this() = this(ActorSystem("testActorSystem", ConfigFactory.load()))
  implicit val app: FakeApplication = FakeApplication()
  // Execution context statt import ExecutionContext.Implicits.global
  implicit val ec = _system.dispatcher

  override def beforeAll {
    Play.start(app)
  }
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Manager Actor " must {
    "getCheapest(MSQ)" in {
      val r = actors.Manager.getCheapest("MSQ")

      whenReady(r) { 
        r => assert(! r.isEmpty )
      }

    }
    "getCheapest(ORY)" in {
      val r = actors.Manager.getCheapest("ORY")

      whenReady(r) { 
        r => assert(! r.isEmpty )
      }

    }
  }

}

class NorvegianAirlinesSpec (_system: ActorSystem) extends TestKit(_system) 
  with ImplicitSender with WordSpecLike with ScalaFutures 
  with BeforeAndAfterAll
{
  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))
  def this() = this(ActorSystem("testActorSystem", ConfigFactory.load()))
  implicit val app: FakeApplication = FakeApplication()
  // Execution context statt import ExecutionContext.Implicits.global
  implicit val ec = _system.dispatcher

  override def beforeAll {
    Play.start(app)
  }
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "NorvegianAirlinesSpec " must {
    "t2" in {
      val t = new actors.PushResultsParser {

      }
      val results = """{"results":{"iataFrom":"ORY","iataTo":"BOJ","adults":1,"infants":0,"children":0,"tickets":[{"depdate":"2014-07-03T18:30:00.363Z","deptime":"20:30","flnum":"DY1497","flclass":"lowfare","points":[{"depdate":"2014-07-03T18:30:00.363Z","deptime":"20:30","dstName":"paris-orly","avlName":"oslo-gardermoen","iataFrom":"ORY","iataTo":"OSL","directions":"Paris-Orly - Oslo-Gardermoen","flnum":"DY1497","flclass":"lowfare"},{"depdate":"2014-07-03T21:30:00.363Z","deptime":"23:30","dstName":"oslo-gardermoen","avlName":"bourgas","iataFrom":"OSL","iataTo":"BOJ","directions":"Oslo-Gardermoen - Bourgas","flnum":"DY6270","flclass":"lowfare"}],"price":"693.90","avldate":"2014-07-04T18:30:00.363Z","avltime":"03:45"},{"depdate":"2014-07-03T18:30:00.325Z","deptime":"20:30","flnum":"DY1497","flclass":"flex","points":[{"depdate":"2014-07-03T18:30:00.325Z","deptime":"20:30","dstName":"paris-orly","avlName":"oslo-gardermoen","iataFrom":"ORY","iataTo":"OSL","directions":"Paris-Orly - Oslo-Gardermoen","flnum":"DY1497","flclass":"flex"},{"depdate":"2014-07-03T21:30:00.325Z","deptime":"23:30","dstName":"oslo-gardermoen","avlName":"bourgas","iataFrom":"OSL","iataTo":"BOJ","directions":"Oslo-Gardermoen - Bourgas","flnum":"DY6270","flclass":"flex"}],"price":"1003.10","avldate":"2014-07-04T18:30:00.325Z","avltime":"03:45"}]}}"""
      var v = Json.parse(results) \ "results"
      val ret = t.processPushResults(v)
      assert(ret.iataFrom == "ORY")
      assert(ret.tickets.length == 2)
    }

    "t1" in {
      val fetcher = system.actorOf(Props[actors.NorvegianAirlines])

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("CGN","BER",model.TravelType.OneWay,
          new java.util.Date(),new java.util.Date(),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
          case actors.SearchResult(tr,List()) => 
        }
      }
    }

    "t3" in {
      val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

      val fetcher = system.actorOf(Props[actors.NorvegianAirlines])

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("ORY","FLL",model.TravelType.OneWay,
          df.parse("2014-08-30"),df.parse("2014-08-30"),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
          case actors.SearchResult(tr,tkts) => 
        }
      }
    }


  }

}

class BaseFetcherActorSpec (_system: ActorSystem) extends TestKit(_system) 
  with ImplicitSender with WordSpecLike with ScalaFutures 
  with BeforeAndAfterAll
{
  import BaseFetcherActorSpec._

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(1, Seconds), interval = Span(50, Millis))
  def this() = this(ActorSystem("testActorSystem", ConfigFactory.load()))
  implicit val app: FakeApplication = FakeApplication()
  // Execution context statt import ExecutionContext.Implicits.global
  implicit val ec = _system.dispatcher

  override def beforeAll {
    Play.start(app)
  }
  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }


  "BaseFetcherActor " must {
    "t1" in {
      val fetcher = system.actorOf(Props[BaseFetcherActorTester])

      fetcher ! 1

      expectMsgPF() {
        case Some("1\n") => 1
      }

    }
    "t2" in {

      val fetcher = system.actorOf(Props[BaseFetcherActorTester])

      fetcher ! 2

      expectMsgPF() {
        case None => 1
      }

    }
    "t3" in {
      val fetcher = system.actorOf(Props[BaseFetcherActorTester])

      fetcher ! 3

      expectMsgPF() {
        case Some("13\n2\n") => 1
      }

    }

    "phantomjs.t1" in {
      val fetcher = system.actorOf(Props[BaseFetcherActorTester])

      fetcher ! (4,"t1")

      expectMsgPF() {
        case Some("Hello, world!\n") => 
      }

    }

    "phantomjs.t2" in {
      val fetcher = system.actorOf(Props[BaseFetcherActorTester])

      fetcher ! (4,"t2")

      expectMsgPF() {
        case Some("Example Domain\n") => 
      }

    }

    "phantomjs.test" in {
      val fetcher = system.actorOf(Props[BaseFetcherActorTester])

      fetcher ! (4,"t3")

      expectMsgPF() {
        case None =>         
      }

    }

  }

}


object BaseFetcherActorSpec {
  class BaseFetcherActorTester extends actors.BaseFetcherActor {
    import context.dispatcher
    def receive = {
      case 1 => 
        val s = sender
        execWithTimeout(Seq("perl","-e",""" print "1"; """)).map {
          case r => s ! r
        }
      case 2 => 
        val s = sender
        val f = execWithTimeout(Seq("perl","-e","""sleep(1000); print "1"; """)).map {
          case r => s ! r
        }
      case 3 => 
        val s = sender
        execWithTimeout(Seq("perl","-e",""" print "13\n2"; """)).map {
          case r => s ! r
        }
      case (4,x) => 
        val s = sender
        execWithTimeout(Seq("./bin/phantomjs","phantomjs/tests/"+x+".js")).map {
          case r => s ! r
        }

    }
  }
}
  


class FetcherActorSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures {


  implicit override def patienceConfig =
  PatienceConfig(timeout = Span(10, Seconds), interval = Span(500, Millis))


  def this() = this(ActorSystem("testActorSystem", ConfigFactory.load()))

  implicit val app: FakeApplication = FakeApplication()

  // Execution context statt import ExecutionContext.Implicits.global
  implicit val ec = _system.dispatcher

  override def beforeAll {
    Play.start(app)
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
 
  "An Fetcher actor" must {
    
    "answer to subscribe with enumerator" in {
      val fetcher = system.actorOf(Props[actors.ExternalGetter])
      
      fetcher ! actors.Subscribe()
      
      val enumerator = expectMsgPF() {
        case actors.Connected(a) => a
      }

    }

    "answer to request with JSON" in {
      
      val fetcher = system.actorOf(Props[actors.ExternalGetter])
      
      fetcher ! actors.Subscribe()
      
      val enumerator = expectMsgPF() {
        case actors.Connected(a) => a
      }

      fetcher ! actors.StartSearch(model.TravelRequest("CGN","BER",model.TravelType.OneWay,
        new java.util.Date(),new java.util.Date(),1,0,0,model.FlightClass.Economy))

      val it = enumerator run Iteratee.fold(List.empty[JsValue]){ (l, e) => e :: l } map { _.reverse }

      whenReady(it) { s =>
        s.length should be (4)
        
        assert {
          (s(0) \ "currency_ratesx").asOpt[JsValue].isEmpty
        }
        assert {
          ! (s(0) \ "currency_rates").asOpt[JsValue].isEmpty
        }
        
      }

    }
    
   }
}


