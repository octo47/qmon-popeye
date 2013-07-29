package popeye

import com.typesafe.config.{ConfigFactory, ConfigValue, Config}
import java.util.Properties
import java.util.Map.Entry
import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

/**
 * @author Andrey Stepachev
 */
object ConfigUtil {

  def loadSubsysConfig(subsys: String): Config = {
    ConfigFactory.parseResources(s"$subsys.conf")
      .withFallback(ConfigFactory.parseResources("popeye.conf"))
      .withFallback(ConfigFactory.parseResources(s"$subsys-dynamic.conf"))
      .withFallback(ConfigFactory.parseResources("dynamic.conf"))
      .withFallback(ConfigFactory.parseResources(s"$subsys-reference.conf"))
      .withFallback(ConfigFactory.load())
  }

  def toFiniteDuration(milliseconds: Long): FiniteDuration = {
    new FiniteDuration(milliseconds, TimeUnit.MILLISECONDS)
  }

  implicit def toProperties(config: Config): Properties = {
    val p = new Properties()
    config.entrySet().foreach({
      e: Entry[String, ConfigValue] => {
        p.setProperty(e.getKey, e.getValue.unwrapped().toString())
      }
    })
    p
  }
}
