package popeye.storage.hbase

import akka.actor.Props
import akka.testkit.TestActorRef
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.{HTableInterfaceFactory, HTableInterface, HTablePool}
import org.kiji.testing.fakehtable.FakeHTable
import org.scalatest.matchers.{MustMatchers, ShouldMatchers}
import popeye.test.PopeyeTestUtils._
import popeye.test.{PopeyeTestUtils, MockitoStubs}
import popeye.pipeline.test.AkkaTestKitSpec
import scala.collection.JavaConversions.iterableAsScalaIterable
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import popeye.proto.PackedPoints
import com.codahale.metrics.MetricRegistry
import nl.grons.metrics.scala.Meter
import popeye.proto.Message

/**
 * @author Andrey Stepachev
 */
class PointsStorageSpec extends AkkaTestKitSpec("points-storage") with ShouldMatchers with MustMatchers with MockitoStubs {

  implicit val executionContext = system.dispatcher

  implicit val metricRegistry = new MetricRegistry()
  implicit val pointsStorageMetrics = new HBaseStorageMetrics("hbase", metricRegistry)

  implicit val random = new Random(1234)
  final val tableName = "my-table"
  final val hTable = new FakeHTable(tableName, desc = null)
  final val hTablePool = new HTablePool(new Configuration(), 1, new HTableInterfaceFactory {
    def releaseHTableInterface(table: HTableInterface) {}

    def createHTableInterface(config: Configuration, tableName: Array[Byte]): HTableInterface = hTable
  })

  behavior of "PointsStorage"

  it should "produce key values" in {
    val state = new State

    val events = mkEvents(msgs = 4)
    val future = state.storage.writePoints(PackedPoints(events))
    val written = Await.result(future, 5 seconds)
    written should be(events.size)
    val points = hTable.getScanner(HBaseStorage.PointsFamily).map(_.raw).flatMap { kv =>
      kv.map(state.storage.keyValueToPoint)
    }
    points.size should be(events.size)
    events.toList.sortBy(_.getTimestamp) should equal(points.toList.sortBy(_.getTimestamp))

    // write once more, we shold write using short path
    val future2 = state.storage.writePoints(PackedPoints(events))
    val written2 = Await.result(future2, 5 seconds)
    written2 should be(events.size)

  }

  ignore should "performance test" in {
    val state = new State

    val events = mkEvents(msgs = 4000)
    for (i <- 1 to 600) {
      val future = state.storage.writePoints(PackedPoints(events))
      val written = Await.result(future, 5 seconds)
      written should be(events.size)
    }
    printMeter(pointsStorageMetrics.writeHBasePoints)
  }

  private def printMeter(meter: Meter) {
    printf("             count = %d%n", meter.getCount)
    printf("         mean rate = %2.2f events/s%n", meter.getMeanRate)
    printf("     1-minute rate = %2.2f events/s%n", meter.getOneMinuteRate)
    printf("     5-minute rate = %2.2f events/s%n", meter.getFiveMinuteRate)
    printf("    15-minute rate = %2.2f events/s%n", meter.getFifteenMinuteRate)
  }

  class State {
    val id = new AtomicInteger(1)

    val uniqActor: TestActorRef[FixedUniqueIdActor] = TestActorRef(Props.apply(new FixedUniqueIdActor()))

    val metrics = setup(uniqActor, HBaseStorage.MetricKind, PopeyeTestUtils.names)
    val attrNames = setup(uniqActor, HBaseStorage.AttrNameKind, Seq("host"))
    val attrValues = setup(uniqActor, HBaseStorage.AttrValueKind, PopeyeTestUtils.hosts)
    val storage = new HBaseStorage(tableName, hTablePool, metrics, attrNames, attrValues, pointsStorageMetrics)

    def setup(actor: TestActorRef[FixedUniqueIdActor], kind: String, seq: Seq[String]): UniqueId = {
      val uniq = new UniqueIdImpl(HBaseStorage.UniqueIdMapping.get(kind).get, kind, actor)
      seq.map {
        item => actor.underlyingActor.add(HBaseStorage.ResolvedName(kind, item, uniq.toBytes(id.incrementAndGet())))
      }
      uniq
    }
  }

  it should "perform time range queries" in {
    val state = new State
    val points = (0 to 6).map {
      i =>
        Message.Point.newBuilder()
          .setTimestamp(i * 1200)
          .setIntValue(i)
          .setMetric("my.metric1")
          .addAttributes(Message.Attribute.newBuilder()
          .setName("host")
          .setValue("localhost")
        ).build()
    }
    state.storage.writePoints(PackedPoints(points))
    val future = state.storage.getPoints("my.metric1", (1200, 4801), List("host" -> "localhost"))
    val filteredPoints = pointsStreamToList(future)
    filteredPoints should contain(Point(1200, 1))
    filteredPoints should (not contain Point(0, 0))
    filteredPoints should (not contain Point(6000, 5))
  }

  def pointsStreamToList(streamFuture: Future[PointsStream]) = {
    def streamToFutureList(stream: PointsStream): Future[Seq[Point]] = {
      val nextListFuture = stream.next match {
        case Some(nextFuture) => nextFuture().flatMap(streamToFutureList)
        case None => Future.successful(Nil)
      }
      nextListFuture.map {nextList => stream.points ++ nextList}
    }
    Await.result(streamFuture.flatMap(streamToFutureList), 5000000 seconds)
  }


}
