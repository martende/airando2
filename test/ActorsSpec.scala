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
import scala.util.{Try, Success, Failure}
import model.SearchResult


import play.api.i18n.Lang

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

  "Model " must {

    "Airports.get(MSQ)" in {
      val r = model.Airports.get("MSQ")
      assert(!r.isEmpty)
    }
    
    "Airports.get(BAK)" in {
      val r = model.Airports.get("BAK")
      assert(!r.isEmpty)
    }

    "Avialines.getByName(Pegasus Airlines) == PC" in {
      val r = model.Avialines.getByName("pegasusairlines")
      assert(r == Some("PC"))
    }

    "getgates" in {
      val r = actors.Manager.getGates(Seq("DY"))
    }
    
  }


}



class AirberlinSpec (_system: ActorSystem) extends TestKit(_system) 
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

  "AirberlinSpec " must {
    "HEL -> OSL" in {
      val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

      val fetcher = actors.ExternalGetter.airberlin

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("HEL","OSL",model.TravelType.OneWay,
          df.parse("2014-10-21"),df.parse("2014-10-21"),3,2,1,model.FlightClass.Economy))

        expectMsgPF() {
          case SearchResult(tr,tkts @ List(model.Ticket("201503071105ORY:201503071515CPH:201503072020FLL",
            Vector(
              model.Flight("ORY","CPH","NR",_,"DY3635",_,None,None,0)
            ),
            None,prices,order_urls))) => 
            println("Result" , tkts)
            assert(prices == Map("NR"->307.9f))
            assert(order_urls == Map("NR" -> "NR:201503071105ORY:201503071515CPH:201503072020FLL"))
          case SearchResult(tr,tkts) => 
            println("Result2" , tkts)
            //assert(tkts == List(
            //  Ticket(",
            //    Vector(Flight("ORY","CPH","NR",0,"DY3635",Sat Mar 07 11:05:00 CET 2015,None,None,0), Flight(CPH,FLL,NR,0,DY7041,Sat Mar 07 15:15:00 CET 2015,None,None,0)),None,Map(NR -> 307.9),Map(NR -> NR:201503071105ORY:201503071515CPH:201503072020FLL)))) )
        }

        
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
    /*
    "t2" in {
      val t = new actors.PushResultsParser {

      }
      val results = """{"results":{"iataFrom":"ORY","iataTo":"FLL","adults":1,"infants":0,"children":0,"tickets":[{"depdate":"2014-08-30T11:00","deptime":"11:00","direct_flights":[{"depdate":"2014-08-30T11:00","deptime":"11:00","dstName":"paris-orly","avlName":"oslo-gardermoen","iataFrom":"ORY","iataTo":"OSL","directions":"Paris-Orly - Oslo-Gardermoen",
      "flnum":"DY1491","flclass":"lowfare"},{"depdate":"2014-08-30T16:10","deptime":"16:10",
      "dstName":"oslo-gardermoen","avlName":"florida-fort lauderdale",
      "iataFrom":"OSL","iataTo":"FLL",
      "directions":"Oslo-Gardermoen - Florida-Fort Lauderdale",
      "flnum":"DY7031","flclass":"lowfare"}],
      "flnum":"DY1491","flclass":"lowfare","price":"225.60","avldate":"2014-08-31T20:00","avltime":"20:00"},{"depdate":"2014-08-30T11:00","deptime":"11:00","direct_flights":[{"depdate":"2014-08-30T11:00","deptime":"11:00","dstName":"paris-orly","avlName":"oslo-gardermoen","iataFrom":"ORY","iataTo":"OSL","directions":"Paris-Orly - Oslo-Gardermoen","flnum":"DY1491","flclass":"flex"},{"depdate":"2014-08-30T16:10","deptime":"16:10","dstName":"oslo-gardermoen","avlName":"florida-fort lauderdale","iataFrom":"OSL","iataTo":"FLL","directions":"Oslo-Gardermoen - Florida-Fort Lauderdale","flnum":"DY7031","flclass":"flex"}],"flnum":"DY1491","flclass":"flex","price":"718.40","avldate":"2014-08-31T20:00","avltime":"20:00"},{"depdate":"2014-08-30T11:00","deptime":"11:00","direct_flights":[{"depdate":"2014-08-30T11:00","deptime":"11:00","dstName":"paris-orly","avlName":"oslo-gardermoen","iataFrom":"ORY","iataTo":"OSL","directions":"Paris-Orly - Oslo-Gardermoen","flnum":"DY1491","flclass":"lowfare"},{"depdate":"2014-08-30T16:10","deptime":"16:10","dstName":"oslo-gardermoen","avlName":"florida-fort lauderdale","iataFrom":"OSL","iataTo":"FLL","directions":"Oslo-Gardermoen - Florida-Fort Lauderdale","flnum":"DY7031","flclass":"premium"}],"flnum":"DY1491","flclass":"lowfare","price":"612.10","avldate":"2014-08-31T20:00","avltime":"20:00"},{"depdate":"2014-08-30T11:00","deptime":"11:00","direct_flights":[{"depdate":"2014-08-30T11:00","deptime":"11:00","dstName":"paris-orly","avlName":"oslo-gardermoen","iataFrom":"ORY","iataTo":"OSL","directions":"Paris-Orly - Oslo-Gardermoen","flnum":"DY1491","flclass":"flex"},{"depdate":"2014-08-30T16:10","deptime":"16:10","dstName":"oslo-gardermoen","avlName":"florida-fort lauderdale","iataFrom":"OSL","iataTo":"FLL","directions":"Oslo-Gardermoen - Florida-Fort Lauderdale","flnum":"DY7031","flclass":"premiumflex"}],"flnum":"DY1491","flclass":"flex","price":"782.80","avldate":"2014-08-31T20:00","avltime":"20:00"}]}}"""
      var v = Json.parse(results) \ "results"
      val ret = t.processPushResults(v)
      assert(ret.iataFrom == "ORY")
      assert(ret.tickets.length == 4)
    }
    
    "t1" in {
      val fetcher = system.actorOf(Props[actors.NorvegianAirlines])

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("CGN","BER",model.TravelType.OneWay,
          new java.util.Date(),new java.util.Date(),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
ase actors.SearchResult(tr,_) => 
          //case x => println(x)
        }
      }
    }
    */
      /*
    "t3" in {
      val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

      val fetcher = system.actorOf(Props[actors.NorvegianAirlines])

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("ORY","FLL",model.TravelType.OneWay,
          df.parse("2015-03-07"),df.parse("2015-03-07"),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
          case SearchResult(tr,tkts @ List(model.Ticket("201503071105ORY:201503071515CPH:201503072020FLL",
            Vector(
              model.Flight("ORY","CPH","NR",_,"DY3635",_,None,None,0)
            ),
            None,prices,order_urls))) => 
            println("Result" , tkts)
            assert(prices == Map("NR"->307.9f))
            assert(order_urls == Map("NR" -> "NR:201503071105ORY:201503071515CPH:201503072020FLL"))
          case SearchResult(tr,tkts) => 
            println("Result2" , tkts)
            //assert(tkts == List(
            //  Ticket(",
            //    Vector(Flight("ORY","CPH","NR",0,"DY3635",Sat Mar 07 11:05:00 CET 2015,None,None,0), Flight(CPH,FLL,NR,0,DY7041,Sat Mar 07 15:15:00 CET 2015,None,None,0)),None,Map(NR -> 307.9),Map(NR -> NR:201503071105ORY:201503071515CPH:201503072020FLL)))) )
        }
      }
    }
*/
    /*
    "ORY -> FLL" in {
      val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

      val fetcher = actors.ExternalGetter.norvegianAirlines

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("ORY","FLL",model.TravelType.OneWay,
          df.parse("2015-03-07"),df.parse("2015-03-07"),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
          case SearchResult(tr,tkts @ List(model.Ticket("201503071105ORY:201503071515CPH:201503072020FLL",
            Vector(
              model.Flight("ORY","CPH","NR",_,"DY3635",_,None,None,0)
            ),
            None,prices,order_urls))) => 
            println("Result" , tkts)
            assert(prices == Map("NR"->307.9f))
            assert(order_urls == Map("NR" -> "NR:201503071105ORY:201503071515CPH:201503072020FLL"))
          case SearchResult(tr,tkts) => 
            println("Result2" , tkts)
            //assert(tkts == List(
            //  Ticket(",
            //    Vector(Flight("ORY","CPH","NR",0,"DY3635",Sat Mar 07 11:05:00 CET 2015,None,None,0), Flight(CPH,FLL,NR,0,DY7041,Sat Mar 07 15:15:00 CET 2015,None,None,0)),None,Map(NR -> 307.9),Map(NR -> NR:201503071105ORY:201503071515CPH:201503072020FLL)))) )
        }

        
      }
    }
    */

    "HEL -> OSL" in {
      val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

      val fetcher = actors.ExternalGetter.norvegianAirlines

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("HEL","OSL",model.TravelType.OneWay,
          df.parse("2014-10-21"),df.parse("2014-10-21"),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
          case SearchResult(tr,tkts @ List(model.Ticket("201503071105ORY:201503071515CPH:201503072020FLL",
            Vector(
              model.Flight("ORY","CPH","NR",_,"DY3635",_,None,None,0)
            ),
            None,prices,order_urls))) => 
            println("Result" , tkts)
            assert(prices == Map("NR"->307.9f))
            assert(order_urls == Map("NR" -> "NR:201503071105ORY:201503071515CPH:201503072020FLL"))
          case SearchResult(tr,tkts) => 
            println("Result2" , tkts)
            //assert(tkts == List(
            //  Ticket(",
            //    Vector(Flight("ORY","CPH","NR",0,"DY3635",Sat Mar 07 11:05:00 CET 2015,None,None,0), Flight(CPH,FLL,NR,0,DY7041,Sat Mar 07 15:15:00 CET 2015,None,None,0)),None,Map(NR -> 307.9),Map(NR -> NR:201503071105ORY:201503071515CPH:201503072020FLL)))) )
        }

        
      }
    }
/*
    "CGN -> HEL - no flights" in {
      val df:SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");

      val fetcher = system.actorOf(Props[actors.NorvegianAirlines])

      within (1 minute) {
        fetcher ! actors.StartSearch(model.TravelRequest("CGN","HEL",model.TravelType.OneWay,
          df.parse("2014-08-02"),df.parse("2014-08-03"),1,1,0,model.FlightClass.Economy))

        expectMsgPF() {
          case SearchResult(tr,tkts) => 
            assert(tkts.length == 0)
        }
      } 
    }
*/
  }

}

/*
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
    val logger = ???
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
  
*/

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






