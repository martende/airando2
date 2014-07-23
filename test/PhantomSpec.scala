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

import actors.PhantomExecutor

class PhantomTest (_system: ActorSystem) extends TestKit(_system) 
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

  "PhantomSpec " must {

    "t1" in {
      val fetcher = system.actorOf(PhantomExecutor.props(isDebug=false),"test1")

      within (2 seconds) {
        fetcher ! PhantomExecutor.Start()

        expectMsgPF() {
          case PhantomExecutor.Started() => 
          //case PhantomExecutor.Failed(ex) => assert(false)
          //case x => println(x)
        }

        fetcher ! PhantomExecutor.OpenUrl("file:///home/belka/airando2/testdata/t2.html")

        expectMsgPF() {
          case PhantomExecutor.OpenUrlResult(Success(s)) => 
          //case PhantomExecutor.Failed(ex) => assert(false)
          //case x => println(x)
        }
        
      }
    }


  "t2" in {
      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)

      var p:PhantomExecutor.Page = null;
      whenReady(r) { 
          case Success(tp:PhantomExecutor.Page) => p = tp
          case Failure(_) => assert(false)
      }

      assert(p.title == "t2")
      assert(p.title == "t2")

      assert(p.$("#d1").innerHTML == "D1")
      assert(p.$("#d1no").innerHTML == "")

      p.$("#d2").click()

      assert(p.$("#d1").innerHTML == "D1C")

      intercept[PhantomExecutor.NoSuchElementException] {
        p.$("#d1no").click()  
      }

      assert(p.$("#d2").attr("class") == "t3 d2class")

      // p.$("select.selectdestination").filter($x => $x.id.indexOf("Departure") != -1 )
    }

    "length" in {

      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)
      var Success(p) = scala.concurrent.Await.result(r,1 seconds) 

      val t = p.$("div.t3")
      assert( t.length == 2 )
      
    }

    "t3" in {
      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)
      var Success(p) = scala.concurrent.Await.result(r,1 seconds) 

      //assert(p.$("#d2").attr("class") == "d2class")
      
      p.$("div.t3").foreach { x => 
        val t = x.attr("id")
        assert( t == "d1" || t == "d2")  
      }

      val divs = p.$("div.t3")
      assert(divs.length==2)
      var cnt = 0
      for ( el <- divs if el.attr("id")=="d1" ) {
        cnt+=1
      }
      assert(cnt==1)
    }
  }

  "children $" in {
      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)
      var Success(p) = scala.concurrent.Await.result(r,1 seconds) 
      val c0 = p.$("div.c0")
      assert(c0.$("div").length == 2)
      val c1= p.$("div.c1")
      assert(c1.$("div").length == 4)

      assert(p.$("#c3").$("span").length == 3)
      assert(p.$("#c3").children.length == 2)
  }

  "children $.$" in {
      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)
      var Success(p) = scala.concurrent.Await.result(r,1 seconds) 
      val c1= p.$("div.c1")
      val spans = c1.$("div").$("span")
      assert(spans.length == 3)

      val spanF =  spans.find{
        x => x.innerHTML == "span2"
      }.get

      val s2 = spans.head
    
      assert(s2.innerHTML == "span1")
    }
    "getBoundingClientRect" in {
      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)
      var Success(p) = scala.concurrent.Await.result(r,1 seconds) 
      val cr= p.$("#d2").getBoundingClientRect()
      
      assert(cr == PhantomExecutor.ClientRect(8.0,108.0,29.0,129.0,100.0,100.0) )

    }
    "offsetParent" in {
      val r = PhantomExecutor.open("file:///home/belka/airando2/testdata/t2.html",isDebug=false)
      var Success(p) = scala.concurrent.Await.result(r,1 seconds) 
      val op= p.$("#d2").offsetParent
      assert(op.tagName == "BODY")
      // many offset parents      
      val ops= p.$("div.t3").offsetParent
      assert(ops.length == 1)

      val opspn = p.$("span").offsetParent
      
      assert(opspn.length == 3 )
    }
}


class PhantomTestX (_system: ActorSystem) extends TestKit(_system) 
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

  "PhantomSpec " must {
    
    "norvegian" in {
        val r = PhantomExecutor.open("http://www.norwegian.com/en/",isDebug=false )
        var Success(p) = scala.concurrent.Await.result(r,2 seconds) 
        val depEl = p.$("select.selectdestination").find {x => x.attr("id").indexOf("Departure") != -1}.get
        val avlEl = p.$("select.selectdestination").find {x => x.attr("id").indexOf("Arrival")   != -1}.get
        def mkCodes(el:PhantomExecutor.Selector) = el.$("option").map {el => 
          val id = el.attr("value").toUpperCase 
          var name =  """\W+\(\w+\)$""".r.replaceAllIn(el.innerText,"").toLowerCase 
          (id -> name )
        }.filter { case (id,name) => id.length == 3 || ( id.length == 6 && id.substring(3) == "ALL" ) }.toMap

        val avlMap = mkCodes(depEl)
        val depMap = mkCodes(depEl)

        val name2iata = avlMap.map(_.swap) ++ depMap.map(_.swap)
        val iatas = ( avlMap ++ depMap ).keys.toSeq.sorted

        val src = "CGN"
        val dst = "HEL"
        val traveltype = 1
        val departure = "2014-08-02"
        val dep_dd = departure.split("-")(2)
        val dep_yyyymm = departure.split("-").take(2).mkString("")
        val adults = 3
        val childs = 2
        val infants = 1
        p.selectJSSelect(src,
          p.$(".webpart.first .select2-container.selectdestination").at(0),
          p.$(".webpart.first .select2-dropdown-open ul.select2-results .select2-result").find( _.innerText matches ".*\\("+src+"\\)$"  ).get
        )
        p.selectJSSelect(dst,
          p.$(".webpart.first .select2-container.selectdestination").at(1),
          p.$(".webpart.first .select2-dropdown-open ul.select2-results .select2-result").find( _.innerText matches ".*\\("+dst+"\\)$"  ).get
        )
        p.selectRadio(p.$("input[name$=TripType]"),traveltype.toString)
        p.selectOption(p.$("select[name$=DepartureDay]").at(0),dep_dd)
        p.selectOption(p.$("select[name$=DepartureMonth]").at(0),dep_yyyymm,true)

        p.selectOption(p.$("select[name$=AdultCount]").at(0),adults.toString)
        p.selectOption(p.$("select[name$=ChildCount]").at(0),childs.toString)
        p.selectOption(p.$("select[name$=InfantCount]").at(0),infants.toString)

        p.$(".first .search a").click();

        p.setDebug(true)
        

        val ret = try {
          scala.concurrent.Await.result({
            p.waitForSelector(p.$(".ErrorBox,.WarnBox,#avaday-outbound-result",".pagefooterbox") )  
            },5 seconds)
          } catch {
            case ex:java.util.concurrent.TimeoutException => println(ex)
            case ex:PhantomExecutor.TimeoutException => println(ex)
          }

        p.setDebug(false)

        println(ret)
        println(p.stats)
     

        p.render("phantomjs/images/norvegian1.png")
    }
  }

}