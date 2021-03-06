package popeye.util

import org.scalatest.{BeforeAndAfter, Matchers, FlatSpec}
import scala.concurrent.{Await, Promise}
import popeye.test.{TestExecContext, EmbeddedZookeeper}
import org.I0Itec.zkclient.ZkClient
import akka.actor.ActorSystem
import scala.concurrent.duration._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable
import scala.util.Try

class PeriodicExclusiveTaskSpec extends FlatSpec with Matchers with BeforeAndAfter with TestExecContext {

  var zookeeper: EmbeddedZookeeper = null
  var actorSystem: ActorSystem = null
  before {
    zookeeper = new EmbeddedZookeeper()
    actorSystem = ActorSystem()
  }

  after {
    zookeeper.shutdown()
    actorSystem.shutdown()
  }

  behavior of "PeriodicExclusiveTask"

  val lockPath = "/lock"

  it should "run task periodically" in {
    val testCompletion = Promise[Unit]()
    val runCount = new AtomicInteger(0)
    val zkClient = zookeeper.newClient
    PeriodicExclusiveTask.run(zkClient, lockPath, actorSystem.scheduler, executionContext, 10 millis) {
      if (runCount.getAndIncrement == 10) {
        testCompletion.success(())
      }
    }
    Await.result(testCompletion.future, 1 second)
  }

  it should "handle long tasks" in {
    val testCompletion = Promise[Unit]()
    val runCount = new AtomicInteger(0)
    val zkClient = zookeeper.newClient
    PeriodicExclusiveTask.run(zkClient, lockPath, actorSystem.scheduler, executionContext, 10 millis) {
      runCount.getAndIncrement match {
        case 1 =>
          Thread.sleep(500)
          testCompletion.success(())
        case 2 =>
          testCompletion.failure(new AssertionError("second task started too early"))
      }
    }
    Await.result(testCompletion.future, 1 second)
  }

  it should "run operations exclusively" in {
    val testCompletion = Promise[Unit]()
    val runCount = new AtomicInteger(0)
    PeriodicExclusiveTask.run(zookeeper.newClient, lockPath, actorSystem.scheduler, executionContext, 10 millis) {
      if (runCount.getAndIncrement == 10) {
        testCompletion.success(())
      }
    }
    PeriodicExclusiveTask.run(zookeeper.newClient, lockPath, actorSystem.scheduler, executionContext, 10 millis) {
      testCompletion.failure(new AssertionError("fail"))
    }
    Await.result(testCompletion.future, 1 second)
  }

  it should "run operations exclusively in case of a failure" in {
    val testCompletion = Promise[Unit]()
    val firstOperationExecuted = new AtomicBoolean(false)
    val zkClient: ZkClient = zookeeper.newClient
    val runCount = new AtomicInteger(0)
    PeriodicExclusiveTask.run(zkClient, lockPath, actorSystem.scheduler, executionContext, 10 millis) {
      if (firstOperationExecuted.get) {
        testCompletion.failure(new AssertionError("fail"))
      }
      if (runCount.getAndIncrement == 10) {
        firstOperationExecuted.set(true)
        zkClient.close()
      }
    }
    PeriodicExclusiveTask.run(zookeeper.newClient, lockPath, actorSystem.scheduler, executionContext, 10 millis) {
      testCompletion.complete(Try {
        firstOperationExecuted.get() should be(true)
      })
    }
    Await.result(testCompletion.future, 1 second)
  }

}
