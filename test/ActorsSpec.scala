import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.TestKit
import org.scalatest.WordSpecLike
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
import play.api.libs.json.JsValue
import play.api.libs.iteratee.Iteratee


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
        new java.util.Date(),new java.util.Date()))

      val it = enumerator run Iteratee.fold(List.empty[JsValue]){ (l, e) => e :: l } map { _.reverse }

      whenReady(it) { s =>
        s.length should be (3)
        
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


