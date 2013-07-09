package popeye.transport

import akka.actor.{Props, ActorSystem}
import akka.io.IO
import spray.can.Http
import popeye.transport.legacy.LegacyHttpHandler
import popeye.transport.kafka.EventProducer
import akka.event.LogSource
import akka.routing.FromConfig
import popeye.transport.ConfigUtil._
import popeye.uuid.IdGenerator

/**
 * @author Andrey Stepachev
 */
object Boot extends App {
  implicit val system = ActorSystem("popeye")
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName

    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
  val log = akka.event.Logging(system, this)

  implicit val idGenerator = new IdGenerator(1)
  // the handler actor replies to incoming HttpRequests
  val kafka = {
    system.actorOf(EventProducer.props(system.settings.config)
      .withRouter(FromConfig()), "kafka-producer")
  }
  val handler = system.actorOf(Props(new LegacyHttpHandler(kafka)), name = "legacy-http")
  IO(Http) ! Http.Bind(handler, interface = "0.0.0.0", port = 8080)

}