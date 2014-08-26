package utils

import akka.actor.{Props,ActorRef,Identify,ActorIdentity,ActorSystem,Actor}
import scala.concurrent.duration._
import akka.pattern.ask
import scala.concurrent.Await
import akka.util.Timeout
import scala.reflect.ClassTag

object ActorUtils {
	def selectActor[T <: Actor  : ClassTag](name:String,system:ActorSystem) = {
      implicit val timeout = Timeout(0.1 seconds)
      val myFutureStuff = system.actorSelection(s"akka://application/user/"+name)
      val aid:ActorIdentity = Await.result((
        myFutureStuff ? Identify(1)).mapTo[ActorIdentity],
        0.1 seconds)
      aid.ref match {
        case Some(cacher) => 
          cacher
        case None => 
          system.actorOf(Props[T],name)
      }		
	}
}