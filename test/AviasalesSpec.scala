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
          "2014-10-21","2014-10-21",3,2,1,model.FlightClass.Economy))

}

class AviasalesSpec extends BaseActorTester
  with ImplicitSender with ScalaFutures {

  implicit override def patienceConfig =
    PatienceConfig(timeout = Span(60, Seconds), interval = Span(500, Millis))
  
  val fetcher = system.actorOf(Props[actors.Aviasales])  

  test("t1") {
    within (5 seconds) {
      fetcher ! p1
      expectMsgPF() {
        case actors.AviasalesSearchResult(tickets,_,_) => 
          assert ( tickets.length >  100 ) 
      }
    }
  }
/*
  "PhantomSpec " must {

    "t1" in {

      fetcher ! 
      
      // val fetcher = actors.ExternalGetter.airberlin

      println(12345)
    }
  }
*/
}