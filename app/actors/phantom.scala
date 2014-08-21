package actors
import akka.actor.{Actor,ActorRef,Cancellable}
import scala.concurrent.{Future,Promise,Await}
import sys.process._
import play.api.Logger
import scala.concurrent.duration._
import java.io.{ BufferedReader, InputStreamReader, FilterInputStream, OutputStream }

import scala.util.{Try, Success, Failure}
import akka.pattern.ask
import play.api.libs.concurrent.Akka
import play.api.libs.json._
import play.api.libs.functional.syntax._

class PhantomExecutor(_isDebug:Boolean,execTimeout:FiniteDuration = 120 seconds) extends Actor {
  import PhantomExecutor._

  val phantomCmd = Seq("./bin/phantomjs","--proxy=127.0.0.1:3128","phantomjs/core.js")

  import context.{dispatcher,become}
  var isDebug = _isDebug
  val system = akka.actor.ActorSystem("system")
  val logger = Logger("PhantomExecutor")

  val fatalPromise = Promise[Int]()
  var outputStream:OutputStream = null
  var evlid:Int = 0
  var evals:Map[Int,ActorRef] = Map()
  var gonaDie = false
  // var inOpen = false 

  override def preStart() {
    debug(s"preStart $isDebug")
  }

  override def postStop() {
    gonaDie = true
    info("Actor stopped")
    fatalPromise.trySuccess(1)
  }

  def instart(_sender:ActorRef):PartialFunction[Any, Unit] = {
    {
        
      // Inner
      case ev @ Failed(_) => 
        become(failed())
        _sender ! ev
      case ev @ Started() => 
        become(started())
        _sender ! ev
      
    }
  }

  def failed():PartialFunction[Any, Unit] = {
    case _ => sender ! Failed(new Exception("stopped"))
  }


  def debug(message: => String) = if (isDebug) logger.debug(message)
  def warn(message: => String) = if (isDebug) logger.warn(message)
  def info(message: => String) = logger.info(message)

  def started():PartialFunction[Any, Unit] = {
    {
      // case Start() => logger.error("Already started")
      case OpenUrl(url)  => 
        info(s"OpenUrl '$url'")
        become(waiturl(sender))
        sendPhantom("OPEN "+url)
      case Eval(js) => 
        debug("RECV Eval()")
        evlid+=1
        evals += ( evlid -> sender )
        sendPhantom("EVAL " + evlid + " " + js)
      case AsyncEval(js) => 
        debug("RECV AsyncEval()")
        evlid+=1
        evals += ( evlid -> sender )
        sendPhantom("AVAL " + evlid + " " + js)
      case Stats() =>
        sender ! StatsResult(evlid)
      case SetDebug(newDebug) => 
          if ( newDebug != isDebug) {
          if (newDebug) {
            isDebug = newDebug
            debug("Debug mode on")
          } else {
            debug("Debug mode off")
            isDebug = newDebug
          }
        }
      // Inner
      case EvalComplete(_evlid,result) =>
        evals.get(_evlid) match {
          case Some(ret) => ret ! EvalResult(result)

            evals -= _evlid
          case None => logger.error(s"EvalComplete Duplication: ${_evlid} $result")
        }
      case ev @ Failed(_) => 
        become(failed())
        for ( (id,_sender) <- evals) _sender ! ev
          
      //case ev : OpenUrlResult => 
      //    _sender ! ev

      case x => logger.error("Unknown action")

    }
  }

  def waiturl(_sender:ActorRef):PartialFunction[Any, Unit] = {
    case OpenUrl(url) => sender ! OpenUrlResult(Failure(new Exception("duplicate")))
    case ev : OpenUrlResult => 
        _sender ! ev
        become(started())
  }

  def receive = {
    case Start() => 
      debug("RECV Start()")
      become(instart(sender))
      execAsync()

    case x => logger.error("Unkwnown Signal $x")
  }

  def sendPhantom(cmd:String) {
    debug("sendPhantom " + cmd)
    //try {
    outputStream.write((cmd + "\n").getBytes)
    outputStream.flush()
    //} catch {
    //  case ex:java.io.IOException => fatalPromise.failure(ex)
    //}
  }

  def execAsync() = {
    val startedTimeout = system.scheduler.scheduleOnce(1000 milliseconds) {
      fatalPromise.failure(new Exception("start-timeout"))
    }
    
    val outputOpened = Promise[OutputStream]()
    val magicReceived = Promise[Boolean]()

    

    def processLine(s:String) {
      val cmd = if ( s.length >=4 ) s.substring(0,3) else "???"
      val arg = if ( s.length >=4 ) s.substring(4) else s
      cmd match {
        case "INF" => 
          arg match {
            case "STARTED" => magicReceived.success(true)
          }
        case "RET" =>
          val retParser = """(\d+):(.*)$""".r
          val retParser(retId,result) = arg
          debug(s"ret cmd: '$retId,$result'")
          self ! EvalComplete(retId.toInt,Success(result))
        case "ERT" => 
          val retParser = """(\d+):(.*)$""".r
          val retParser(retId,result) = arg
          val excpt = result match {
            case "NoSuchElementException" => new NoSuchElementException()
            case "ManyElementsException" => new ManyElementsException()
            case "TimeoutException" => new TimeoutException()
            case _ => new Exception(result)
          }
          self ! EvalComplete(retId.toInt,Failure(excpt))
        case "DBG" => 
          debug(s"debug cmd: '$s'")
        case "CLT" => 
          debug(s"debug client: '$s'")
        case "OPN" => 
          val openUrlRet = """(\d+):(\w+)""".r
          val openUrlRet(pageId,status) = arg
          debug(s"opn cmd: '$pageId,$status'")
          if ( status == "success") self ! OpenUrlResult(Success(true))
          else self ! OpenUrlResult(Failure(new Exception(status)))
        case "ERR" => logger.error(s"error '$arg'")
        case _ => logger.error(s"Unknown phantom answer '$s'")
      }
    }

    val process = Process(phantomCmd).run(new ProcessIO(
        {
          out => 
            outputOpened.success(out)
        },
        {
          in => 
            val reader = new BufferedReader(new InputStreamReader(in))
            def readFully() {
              val line = reader.readLine()
              if ( line != null ) {
                processLine(line)
                readFully()
              }
            }
            readFully()
        },
        _ => ()
    ))

    val exec = Future {
      val ev = process.exitValue
      debug(s"phantom cmd finished and returns $ev")
      ev
    }

    val timeout = system.scheduler.scheduleOnce(execTimeout) {
      //process.destroy()
      val msg = s"Exec timeout=$execTimeout"
      logger.error(msg)
      fatalPromise.failure(new Exception(msg))
    }

    val r = Future.firstCompletedOf(Seq(fatalPromise.future,exec))

    // postprocessing cleanup
    r.onComplete {
      case Success(v) => 
        process.destroy()
        timeout.cancel()
        if (! gonaDie) self ! Finished()
      case Failure(excp:Throwable) =>
        process.destroy()
        timeout.cancel()
        if (! gonaDie) self ! Failed(excp:Throwable)
    }
    
    var startFuture = Future.sequence(List(outputOpened.future,magicReceived.future))

    startFuture.onSuccess {
      case List(out:OutputStream,true) => 
        outputStream=out
        startedTimeout.cancel()
        self ! Started()
    }
    //  r      
    //} catch {
    //  case e:Exception => Future failed e
    //}

  }

}

object PhantomExecutor {

  import play.api.Play.current
  import akka.actor.{Actor,Props,Identify, ActorIdentity,ActorSystem,ActorRef,Cancellable}
  import akka.util.Timeout
  import play.api.libs.concurrent.Execution.Implicits._

  def props(isDebug: Boolean = true,execTimeout:FiniteDuration=120 seconds ): Props = Props(new PhantomExecutor(isDebug,execTimeout))

  case class Start()
  case class Started()
  case class Finished()
  case class Stats()
  case class Failed(excp:Throwable)
  case class OpenUrl(url:String)
  case class Eval(js:String)
  case class AsyncEval(js:String)
  case class EvalComplete(evlid:Int,ret:Try[String])
  case class EvalResult(js:Try[String])
  case class OpenUrlResult(ret:Try[Boolean])
  case class StatsResult(evals:Int)
  case class SetDebug(isDebug:Boolean)

  abstract class PhantomException extends Throwable
  class NoSuchElementException extends PhantomException
  class ManyElementsException extends PhantomException 
  class TimeoutException extends PhantomException 
  class AutomationException(msg:String) extends PhantomException {
    override def toString = s"AutomationException($msg)"
  }

  implicit val openTimeout = Timeout(60 seconds)
  val fastTimeout = 0.5 seconds

  case class ClientRect(left:Double,right:Double,top:Double,bottom:Double,height:Double,width:Double)
  
  implicit val clReads = (
    (__ \ "left").read[Double] and
    (__ \ "right").read[Double] and
    (__ \ "top").read[Double] and
    (__ \ "bottom").read[Double] and
    (__ \ "height").read[Double] and 
    (__ \ "width").read[Double] 
  )(ClientRect)

  class Page(_fetcher:ActorRef) {
    private def wrapException(x: => Unit) = try {
        x
      } catch {
        case e:Throwable => throw new AutomationException(s"failed: " + e.toString )
      }

    private def evaljs[T](js:String,timeout:Duration = fastTimeout)(implicit r:Reads[T]):T = Await.result( (
      _fetcher ? Eval(js)).mapTo[EvalResult].map {
          case EvalResult(hui) => Json.parse(hui.get).as[T](r)
      } , timeout)

    lazy val title:String = evaljs[String]("""page.evaluate(function() {return document.title;});""")

    //def disableWindowOpen() = evaljs[Boolean]("""page.evaluate(function() {window.open=function() {console.log("XXXXXXXXX")};return true;});""");
    def evalJsClient(js:String) = wrapException {
      evaljs[Boolean](s"""page.evaluate(function(){"""+js.replaceAll("\n"," ")+""";return true;} );""")
    }

    def stats = Await.result( (_fetcher ? Stats() ).mapTo[StatsResult] , fastTimeout)

    def render(_fname:String) = {
      val fname = if ( _fname.endsWith(".png") ) _fname.substring(0,_fname.length-4) else _fname
      try {
        evaljs[Boolean](s"page.render('$fname.png');fs.write('$fname.html',page.content,'w');true;",5 seconds)
        Logger("PhantomExecutor").info(s"Render page to $fname - OK")
      } catch {
        case ex:Throwable => Logger("PhantomExecutor").warn(s"Render page to $fname - failed Exception($ex")
      }
    }

    //def goBack() = evaljs[Boolean](s"page.goBack();true;",2 seconds)

    // real type on input 
    def typeValue(input:Selector,v:String) {
      input.click()
      input.value = v
    }

    def selectJSSelect(button:Selector,_targetEl: => Selector  , waitOpening:Boolean = false) {
      button.click()
      def invalidate(el : => Selector,cnt:Int,sleep:Duration):Selector = {
        try {
          val ret = el
          ret
        } catch {
          case x:Throwable => if (cnt == 0) throw x else {
            Thread.sleep(sleep.toMillis)
            invalidate(el,cnt-1,sleep)
          }
        }
      }
      val targetEl = if( waitOpening ) invalidate(_targetEl,4,500 milliseconds) else _targetEl
      
      //setDebug(true)
      //println(targetEl.offsetParent.exists())
      //setDebug(false)

      val ob = targetEl.offsetParent.getBoundingClientRect()
      
      
      val tb = targetEl.getBoundingClientRect()
      if ( tb.top >= ob.top &&  tb.bottom <= ob.bottom ) {
        targetEl.click()
      } else {
        val sof = (tb.bottom - ob.top - ob.height  )
        targetEl.offsetParent.scrollTop = sof
        var newtb = targetEl.getBoundingClientRect()
        if ( newtb.top >= ob.top &&  newtb.bottom <= ob.bottom ) {
          //targetEl.hightlight()
          targetEl.click()
        } else {
          throw new AutomationException("Scrolling failed")
        }
      }
    }


    def selectJSSelect2(button:Selector,_targetEl: => Selector  , waitOpening:Boolean = false) {
      button.click()
      def invalidate(el : => Selector,cnt:Int,sleep:Duration):Selector = {
        try {
          val ret = el
          ret
        } catch {
          case x:Throwable => if (cnt == 0) throw x else {
            Thread.sleep(sleep.toMillis)
            invalidate(el,cnt-1,sleep)
          }
        }
      }
      val targetEl = if( waitOpening ) invalidate(_targetEl,4,500 milliseconds) else _targetEl
      
      //setDebug(true)
      //println(targetEl.offsetParent.exists())
      //setDebug(false)

      val ob = targetEl.offsetParent.getBoundingClientRect()
      
      
      val tb = targetEl.getBoundingClientRect()
      if ( tb.top >= ob.top &&  tb.bottom <= ob.bottom ) {
        targetEl.click()
      } else {
        val sof = (tb.bottom - ob.top - ob.height  )
        targetEl.offsetParent.scrollTop = sof
        var newtb = targetEl.getBoundingClientRect()
        if ( newtb.top >= ob.top &&  newtb.bottom <= ob.bottom ) {
          //targetEl.hightlight()
          targetEl.click()
        } else {
          throw new AutomationException("Scrolling failed")
        }
      }
    }

    def close() = {
      _fetcher ! akka.actor.PoisonPill
      //Akka.system.stop(_fetcher)
    }

    def selectRadio(radioEls:Selector,value:String) = {
      var found = false 
      for (r <- radioEls) {
        if (r.value == value) {
          r.checked = "checked"
          found = true
        } else {
          r.checked = ""
        }
      }
      if (! found ) throw new AutomationException("Radio selection failed")

    }
    
    def selectOption(el:Selector,value:String,force:Boolean=false) {
      el.value = value
      if ( el.value != value ) {
        if ( ! force ) throw new AutomationException("selectOption failed")
        // TODO: FIX htmlquote
        val qv = quote(value)
        val hqv = htmlquote(value)
        el.appendChild(s"<option value='$qv'>$hqv</option>")
        el.value = value
      }
    } 

    def setDebug(isDebug:Boolean) = _fetcher ! SetDebug(isDebug)

    private def _waitForSelector(el:Selector,sellist:String,timeout:Duration) = {
      val tms = Math.max(timeout.toMillis,100)
      val js = """
        var timeout = """+tms+""";
        var ic = timeout / 100;
        var selector = function() {
          return page.evaluate(function(){ 
              if (!window._query)console.log("$$INJECT");
              """+sellist+"""
          });
        };
        var depressed = false;
        var itvl = setInterval(function() {
          if (depressed) return;
          if (selector()) {
            clearInterval(itvl);
            depressed = true;
            ret(eid,true);
          }
          ic--;
          if ( ic <= 0) {
            clearInterval(itvl);
            depressed = true;
            errret(eid,"TimeoutException");
          }
        },100);
      """;
      (_fetcher ? AsyncEval(js.replaceAll("\r\n","")))./*mapTo[EvalResult].*/map {
        case EvalResult(hui) => 
          Json.parse(hui.get).as[Boolean]
        case Failed(ex:Throwable) => throw ex
      }
    }

    def waitForSelector(el:Selector,timeout:Duration=1000 milliseconds) = {
      val sellist = "return " + ( el match {
        case els:ListedSelector => 
          els.items.map {
            el => s"_query(${el.selector}).length >= 1"
          }.mkString(" && ");
        case _ => 
          s"_query(${el.selector}).length >= 1"
      })
      _waitForSelector(el,sellist,timeout)
    }

    def waitForSelectorAttr(el:Selector,attr:String,v:String,timeout:Duration=1000 milliseconds) = {
      val sellist = el match {
        case els:ListedSelector => 
          "return " + els.items.map {
            el => s"_query(${el.selector}).length > 0 && (_query(${el.selector})[0].getAttribute('$attr') || '' ) == '${quote(v)}' "
          }.mkString(" && ");
        case _ => 
          s"return  _query(${el.selector}).length > 0 && (_query(${el.selector})[0].getAttribute('$attr') || '' ) == '${quote(v)}' "
      }
      _waitForSelector(el,sellist,timeout)
    }
    
    def waitUntilVisible(el:Selector,timeout:Duration=1000 milliseconds) = {
      val sellist = el match {
        case els:ListedSelector => 
          "return " + els.items.map {
            el => s"_query(${el.selector}).length > 0 && (! _query(${el.selector})[0].offsetParent ) "
          }.mkString(" && ");
        case _ => 
          s"return  _query(${el.selector}).length > 0 && (! _query(${el.selector})[0].offsetParent ) "
      }
      _waitForSelector(el,sellist,timeout)
    }
    

    def $(csss:String*) = if (csss.length == 1 ) Selector(_fetcher,csss.head) 
      else Selector(_fetcher,csss.map {css => Selector(_fetcher,css) })

  } 

  private def quote(q:String) = q.replace("'","\\'")
  private def htmlquote(q:String) = q.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")

  abstract class Selector(_fetcher:ActorRef) extends Traversable[Selector] {

    override def toString = "$("+selector+")"
  //extends scala.collection.TraversableLike[Selector, Selector]  {
    def selector:String   

    def setDebug(isDebug:Boolean) = _fetcher ! SetDebug(isDebug)

    private def evfun(js:String) = "page.evaluate(" + 
      "function(selector) {" +
        """if (!window._query)console.log("$$INJECT");var R;var d = _query(selector);""" +
         js.replaceAll("\r\n","") + "return R; } " + ","+selector+");"

    def innerHTML:String = Await.result( ( 
        _fetcher ? Eval(
          evfun("""R="";
            for (var i=0;i<d.length;i++) {R+=d[i].innerHTML;}
            """)
        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[String]
        } , fastTimeout)

    def outerHTML:String = Await.result( ( 
        _fetcher ? Eval(
          evfun("""R="";
            for (var i=0;i<d.length;i++) {R+=d[i].outerHTML;}
            """)
        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[String]
        } , fastTimeout)

    
    def _cleanup(s:String) = s.replace("\u00a0"," ")

    def innerText:String = Await.result( ( 
        _fetcher ? Eval(
          evfun("""R="";
            for (var i=0;i<d.length;i++) {R+=d[i].innerText;}
            """)
        )).mapTo[EvalResult].map {
            case EvalResult(hui) => _cleanup(Json.parse(hui.get).as[String])
        } , fastTimeout)
    
    def length:Int = Await.result( (
      _fetcher ? Eval(
          evfun("""R=d.length;""")
        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[Int]
        } , fastTimeout )
    def exists() = length > 0
    def at(idx:Int) = Selector(_fetcher,this,idx)

    def apply(idx:Int) = at(idx)

    private def singleValJs[T](js:String)(implicit r:Reads[T]) = {
      try Await.result( ( _fetcher ? Eval(
          """var retval = """ + evfun("""R=null;
            if ( d.length == 0 ) {
              console.log("ERROR:singleValJs:"+JSON.stringify(selector)+" element not found");
              R = -1;
            } else if ( d.length > 1 ) {
              console.log("ERROR:singleValJs:"+JSON.stringify(selector)+" many elements");
              R = -2;
            } else {
              R = { val:"""+js+""" };
            }
            """) + 
          """
          if ( retval == -1 ) {
            throw("NoSuchElementException");
          } else if ( retval == -2 ) {
            throw("ManyElementsException");
          } else if ( retval ) {
            retval.val;
          } else {
            throw("attr failed");
          }
          
          """.replaceAll("\r\n","")

        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[T](r)
        } , fastTimeout)
      catch {
        case e:Throwable => throw new AutomationException(s"singleValJs $selector failed: $e" )
      }      
    }

    private def singleValJsSet(js:String) = {
      Await.result( ( _fetcher ? Eval(
        """var retval = """ + evfun("""R=null;
          if ( d.length == 0 ) {
            console.log("ERROR:click:"+JSON.stringify(selector)+" element not found");
            R = -1;
          } else if ( d.length > 1 ) {
            console.log("ERROR:click:"+JSON.stringify(selector)+" many elements");
            R = -2;
          } else {
            """+js+""";
            R = 1;
          }
          """) + 
        """
        if ( retval == -1 ) {
          throw("NoSuchElementException");
        } else if ( retval == -2 ) {
          throw("ManyElementsException");
        } else if ( retval == 1 ) {
          true;
        } else {
          throw("attr failed");
        }
        
        """.replaceAll("\r\n","")

      )).mapTo[EvalResult].map {
          case EvalResult(hui) => Json.parse(hui.get).as[Boolean]
      } , fastTimeout)
    }
    def attr(_attr:String):String = singleValJs[String]("d[0].getAttribute('"+_attr+"')")
    def attr(_attr:String,v:String) = singleValJsSet("d[0].setAttribute('"+_attr+"','"+quote(v)+"')")

    
    def appendChild(html:String) = singleValJsSet(
      """
      var div = document.createElement('div');
      div.innerHTML = '"""+quote(html)+"""';
      var elements = div.childNodes;
      for ( var i = 0 ; i < elements.length;i++) {
        d[0].appendChild(elements[i]);
      }
      """
    )
    def getBoundingClientRect():ClientRect = singleValJs[ClientRect]("d[0].getBoundingClientRect()")

    def isVisible:Boolean = {
      val br = getBoundingClientRect()
      ! ( br.width == 0 || br.height == 0)
    }

    def isChecked:Boolean = singleValJs[Boolean]("d[0].checked")

    def tagName:String = singleValJs[String]("d[0].tagName")
    def id:String = singleValJs[String]("d[0].id")
    
    def value:String = singleValJs[String]("d[0].value")
    def value_=(v:String) = wrapException {
      singleValJsSet("d[0].value='"+quote(v)+"'")
    }

    private def wrapException(x: => Unit) = try {
        x
      } catch {
        case e:Throwable => throw new AutomationException(s"$selector failed: " + e.toString )
      }


    def scrollTop:Double = singleValJs[Double]("d[0].scrollTop")
    def scrollTop_=(v:Double) = singleValJsSet("d[0].scrollTop="+v)
    
    def checked:String = singleValJs[String]("d[0].checked")
    def checked_=(v:String) = singleValJsSet("d[0].checked='"+quote(v)+"'")

    def className:String = singleValJs[String]("d[0].className")

    def click() = {
      def clickfun(js:String) = "page.evaluate(" + 
      "function(selector,h,w) {" +
        """if (!window._query)console.log("$$INJECT");var R;var d = _query(selector);""" +
         js.replaceAll("\r\n","") + "return R; } " + ","+selector+",page.viewportSize.height,page.viewportSize.width);"

      try 
        Await.result( ( 
        _fetcher ? Eval(
          """var rect = """ + clickfun("""R=null;
            if ( d.length == 0 ) {
              console.log("ERROR:click:"+JSON.stringify(selector)+" element not found");
              R = -1;
            } else if ( d.length > 1 ) {
              console.log("ERROR:click:"+JSON.stringify(selector)+" many elements");
              R = -2;
            } else {
              R = d[0].getBoundingClientRect();
              var p = R.top + R.height / 2;
              if ( p > h ) R = -3;
            }
            """) + 
          """
          if ( rect == -1 ) {
            throw("NoSuchElementException");
          } else if ( rect == -2 ) {
            throw("ManyElementsException");
          } else if ( rect == -3 ) {
            throw("OutOfScreen");
          } else if ( rect ) {
            page.sendEvent('click', rect.left + rect.width / 2, rect.top + rect.height / 2);  
            true;
          } else {
            throw("click failed");
          }
          
          """.replaceAll("\r\n","")

        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[Boolean]
            //case EvalResult(Failure(e))   => throw new AutomationException(s"click $selector failed: $e" )
        } , fastTimeout)
      catch {
        case e:Throwable => throw new AutomationException(s"click $selector failed: $e" )
      }
    }

    def move() = {
      def movefun(js:String) = "page.evaluate(" + 
      "function(selector,h,w) {" +
        """if (!window._query)console.log("$$INJECT");var R;var d = _query(selector);""" +
         js.replaceAll("\r\n","") + "return R; } " + ","+selector+",page.viewportSize.height,page.viewportSize.width);"

      try 
        Await.result( ( 
        _fetcher ? Eval(
          """var rect = """ + movefun("""R=null;
            if ( d.length == 0 ) {
              console.log("ERROR:move:"+JSON.stringify(selector)+" element not found");
              R = -1;
            } else if ( d.length > 1 ) {
              console.log("ERROR:move:"+JSON.stringify(selector)+" many elements");
              R = -2;
            } else {
              R = d[0].getBoundingClientRect();
              var p = R.top + R.height / 2;
              if ( p > h ) {
                window.document.body.scrollTop = R.top;
                R = {
                  top: R.top - window.document.body.scrollTop,
                  left: R.left,
                  width: R.width,
                  height: R.height,
                  rollback: true
                };
              }
            }
            """) + 
          """
          if ( rect == -1 ) {
            throw("NoSuchElementException");
          } else if ( rect == -2 ) {
            throw("ManyElementsException");
          } else if ( rect == -3 ) {
            throw("OutOfScreen");
          } else if ( rect ) {
            page.sendEvent('mousemove', rect.left + rect.width / 2, rect.top + rect.height / 2);  
            if ( rect.rollback ) {
              page.evaluate(function() {window.document.body.scrollTop = 0;});
            }
            true;
          } else {
            throw("move failed");
          }
          
          """.replaceAll("\r\n","")

        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[Boolean]
            //case EvalResult(Failure(e))   => throw new AutomationException(s"move $selector failed: $e" )
        } , fastTimeout)
      catch {
        case e:Throwable => throw new AutomationException(s"move $selector failed: $e" )
      }
    }

    def highlight() = Await.result( ( 
        _fetcher ? Eval(
          """var rect = """ + evfun("""R=null;
            if ( d.length == 0 ) {
              console.log("ERROR:click:"+JSON.stringify(selector)+" element not found");
              R = -1;
            } else if ( d.length > 1 ) {
              console.log("ERROR:click:"+JSON.stringify(selector)+" many elements");
              R = -2;
            } else {
              R = d[0].getBoundingClientRect();
            }
            """) + 
          """
          if ( rect == -1 ) {
            throw("NoSuchElementException");
          } else if ( rect == -2 ) {
            throw("ManyElementsException");
          } else if ( rect ) {
            page.evaluate(function(rect) {
              var v = document.createElement("div");
              v.style.position = "absolute";

              
              v.style.top = rect.top + "px";
              v.style.height = rect.height +"px";
              v.style.left = rect.left + "px";
              v.style.width = rect.width +"px";
              v.style['z-index'] = 10000;

              v.style['background-color'] = "rgba(0,255,0,0.5)";
              document.body.appendChild(v);
            },rect);
            true;
          } else {
            throw("click failed");
          }
          
          """.replaceAll("\r\n","")

        )).mapTo[EvalResult].map {
            case EvalResult(hui) => Json.parse(hui.get).as[Boolean]
        } , fastTimeout)
    //def seq:Selector = this

    /*def filter(p: Selector => Boolean) = {
      
    }*/
    def foreach[U](f: Selector => U): Unit = {
      val l = length
      var i = 0
      while (i < l) {
        f(at(i))
        i+=1
      }
    }

    def indexWhere(p: Selector => Boolean, from: Int): Int = {
      var i = from
      var these = this drop from
      while (these.nonEmpty) {
        if (p(these.head))
          return i

        i += 1
        these = these.tail
      }
      -1
    }
    
    def page = new Page(_fetcher)

    def $(selector:String) = new NestedSelector(_fetcher,this,selector)
    def root(selector:String) = Selector(_fetcher,selector)
    def children = new ChildSelector(_fetcher,this)
    def offsetParent = new OffsetParentSelector(_fetcher,this)
    def parentNode = new ParentSelector(_fetcher,this)
    def nextSibling = new SiblingSelector(_fetcher,this)
    def re(_re:String) = new RegexpSelector(_fetcher,this,_re)

    def getOrElse(default: => Selector): Selector = if ( exists() ) this else default

  }

  class CssSelector(_fetcher:ActorRef,css:String) extends Selector(_fetcher) {
    def selector = "'" + css + "'"
  }

  class NestedSelector(_fetcher:ActorRef,parent:Selector,css:String) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'>>','"+css  +"']"
  }

  class RegexpSelector(_fetcher:ActorRef,parent:Selector,re:String) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'re','"+re  +"']"
  }

  class ChildSelector(_fetcher:ActorRef,parent:Selector) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'>']"
  }

  class OffsetParentSelector(_fetcher:ActorRef,parent:Selector) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'op']"
  }

  class ParentSelector(_fetcher:ActorRef,parent:Selector) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'up']"
  }

  class SiblingSelector(_fetcher:ActorRef,parent:Selector) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'ns']"
  }

  class IdxSelector(_fetcher:ActorRef,parent:Selector,idx:Int) extends Selector(_fetcher) {
    def selector = "[" + parent.selector + ",'=',"+idx  +"]"
    override def foreach[U](f: Selector => U): Unit = {
      f(this)
    }
  }

  class FilteredSelector(_fetcher:ActorRef,parent:Selector,p: Selector => Boolean) extends Selector(_fetcher) {
    def selector = ???

    override def foreach[U](f: Selector => U): Unit = {
      for (x <- parent)
        if (p(x)) f(x)
    }
  }

  class ListedSelector(_fetcher:ActorRef,val items:Seq[Selector]) extends Selector(_fetcher) {
    def selector = ???

    override def foreach[U](f: Selector => U): Unit = {
      for (x <- items) f(x)
    }
  }

  object Selector {
    def apply(_fetcher:ActorRef,css:String) = new CssSelector(_fetcher,css)
    def apply(_fetcher:ActorRef,parent:Selector,idx:Int) = new IdxSelector(_fetcher,parent,idx)
    def apply(_fetcher:ActorRef,items:Seq[Selector]) = new ListedSelector(_fetcher,items)

    //def newBuilder = ListedSelector
  }

  //var openIdx = 1

  def open(url:String,isDebug:Boolean=true,execTimeout:FiniteDuration=120 seconds):Future[Try[Page]] = {
    val fetcher = Akka.system.actorOf(props(isDebug=isDebug,execTimeout=execTimeout))
    //openIdx+=1

    (fetcher ? Start()).flatMap {
      case Started() => 
          (fetcher ? OpenUrl(url)).mapTo[OpenUrlResult].map {
            case OpenUrlResult(Success(_)) => Success(new Page(fetcher))
            case OpenUrlResult(Failure(ex)) => Failure(ex)
          }
          //Future.successful(Success(new Page(fetcher)))
      case Failed(ex) => 
          Akka.system.stop(fetcher)
          Future.failed(ex)
      //case x => Failure(new Exception("OKO"))
    }
    
    /*for ( 
      f1 <- (fetcher ? Start()) ;
      f2 <- (fetcher ? OpenUrl(url)) 
    ) yield {
      println("AAAAAA",f1,f1.getClass)
      println("BBBBBB",f2,f2.getClass)
      Failure(new Exception("IJI"))
    }*/
  }

}