package popeye.query

import com.typesafe.config.Config
import akka.actor.ActorSystem
import scala.concurrent.ExecutionContext

trait HttpServerFactory {
  def runServer(config: Config, storage: PointsStorage, system: ActorSystem, executionContext: ExecutionContext): Unit
}
