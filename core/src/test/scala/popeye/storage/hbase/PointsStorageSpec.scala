package popeye.storage.hbase

import akka.actor.Props
import akka.testkit.TestActorRef
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
import popeye.proto.{PackedPoints, Message}
import com.codahale.metrics.MetricRegistry
import nl.grons.metrics.scala.Meter
import java.util.regex.Pattern
import scala.util.Random
import java.nio.CharBuffer
import popeye.storage.hbase.HBaseStorage.ValueIdFilterCondition._

/**
 * @author Andrey Stepachev
 */
class PointsStorageSpec extends AkkaTestKitSpec("points-storage") with ShouldMatchers with MustMatchers with MockitoStubs {

  implicit val executionContext = system.dispatcher

  implicit val metricRegistry = new MetricRegistry()
  implicit val pointsStorageMetrics = new HBaseStorageMetrics("hbase", metricRegistry)

  final val tableName = "my-table"

  behavior of "PointsStorage"

  it should "produce key values" in {
    implicit val random = new java.util.Random(1234)

    val state = new State

    val events = mkEvents(msgs = 4)
    val future = state.storage.writePoints(PackedPoints(events))
    val written = Await.result(future, 5 seconds)
    written should be(events.size)
    val points = state.hTable.getScanner(HBaseStorage.PointsFamily).map(_.raw).flatMap {
      kv =>
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
    implicit val random = new java.util.Random(1234)

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

  class State(metricNames: Seq[String] = PopeyeTestUtils.names,
              attributeNames: Seq[String] = Seq("host"),
              attributeValues: Seq[String] = PopeyeTestUtils.hosts) {
    val id = new AtomicInteger(1)

    val hTable = new FakeHTable(tableName, desc = null)
    val hTablePool = new HTablePool(new Configuration(), 1, new HTableInterfaceFactory {
      def releaseHTableInterface(table: HTableInterface) {}

      def createHTableInterface(config: Configuration, tableName: Array[Byte]): HTableInterface = hTable
    })

    val uniqActor: TestActorRef[FixedUniqueIdActor] = TestActorRef(Props.apply(new FixedUniqueIdActor()))

    val metrics = setup(uniqActor, HBaseStorage.MetricKind, metricNames)
    val attrNames = setup(uniqActor, HBaseStorage.AttrNameKind, attributeNames)
    val attrValues = setup(uniqActor, HBaseStorage.AttrValueKind, attributeValues)
    val storage = new HBaseStorage(
      tableName,
      hTablePool,
      metrics,
      attrNames,
      attrValues,
      pointsStorageMetrics,
      readChunkSize = 10
    )

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
          .setValue("localhost"))
          .build()
    }
    state.storage.writePoints(PackedPoints(points))
    import HBaseStorage.ValueNameFilterCondition._
    val future = state.storage.getPoints("my.metric1", (1200, 4801), Map("host" -> Single("localhost")))
    val filteredPoints = pointsStreamToList(future)
    filteredPoints should contain(point(1200, 1))
    filteredPoints should (not contain point(0, 0))
    filteredPoints should (not contain point(6000, 5))
  }

  it should "perform multiple attributes queries" in {
    val state = new State(
      metricNames = Seq("metric"),
      //order of attribute names is important
      attributeNames = Seq("b", "a"),
      attributeValues = Seq("foo")
    )
    val messagePoint = Message.Point.newBuilder()
      .setTimestamp(0)
      .setIntValue(1)
      .setMetric("metric")
      .addAttributes(attribute("a", "foo"))
      .addAttributes(attribute("b", "foo"))
      .build()

    state.storage.writePoints(PackedPoints(Seq(messagePoint)))
    import HBaseStorage.ValueNameFilterCondition._
    val future = state.storage.getPoints("metric", (0, 1), Map("a" -> Single("foo"), "b" -> Single("foo")))
    val filteredPoints = pointsStreamToList(future)
    filteredPoints should contain(point(0, 1))
  }

  it should "perform multiple attribute value queries" in {
    val state = new State(
      metricNames = Seq("metric"),
      attributeNames = Seq("type"),
      attributeValues = Seq("foo", "bar", "junk")
    )
    val fooPoint = messagePoint(
      metricName = "metric",
      timestamp = 0,
      value = 1,
      attrs = Seq("type" -> "foo")
    )
    val barPoint = messagePoint(
      metricName = "metric",
      timestamp = 0,
      value = 2,
      attrs = Seq("type" -> "bar")
    )
    val junkPoint = messagePoint(
      metricName = "metric",
      timestamp = 0,
      value = 3,
      attrs = Seq("type" -> "junk")
    )
    state.storage.writePoints(PackedPoints(Seq(fooPoint, barPoint, junkPoint)))
    import HBaseStorage.ValueNameFilterCondition._
    val future = state.storage.getPoints("metric", (0, 1), Map("type" -> Multiple(Seq("foo", "bar"))))
    val filteredPoints = pointsStreamToList(future)
    filteredPoints should (contain(point(0, 1)) and contain(point(0, 2)))
    filteredPoints should (not contain point(0, 3))
  }

  it should "perform multiple attribute value queries (All filter)" in {
    val state = new State(
      metricNames = Seq("metric"),
      attributeNames = Seq("type", "attr"),
      attributeValues = Seq("foo", "bar")
    )
    val fooPoint = messagePoint(
      metricName = "metric",
      timestamp = 0,
      value = 1,
      attrs = Seq("type" -> "foo", "attr" -> "foo")
    )
    val barPoint = messagePoint(
      metricName = "metric",
      timestamp = 0,
      value = 2,
      attrs = Seq("type" -> "bar", "attr" -> "foo")
    )
    val junkPoint = messagePoint(
      metricName = "metric",
      timestamp = 0,
      value = 3,
      attrs = Seq("type" -> "foo", "attr" -> "bar")
    )
    state.storage.writePoints(PackedPoints(Seq(fooPoint, barPoint, junkPoint)))
    import HBaseStorage.ValueNameFilterCondition._
    val future = state.storage.getPoints("metric", (0, 1), Map("type" -> All, "attr" -> Single("foo")))
    val filteredPoints = pointsStreamToList(future)
    filteredPoints should (contain(point(0, 1)) and contain(point(0, 2)))
    filteredPoints should (not contain point(0, 3))
  }

  def point(time: Int, value: Number): Point = {
    Point(time, value, attributes = new BytesKey(Array()))
  }

  def messagePoint(metricName: String, timestamp: Long, value: Long, attrs: Seq[(String, String)]) = {
    val builder = Message.Point.newBuilder()
      .setMetric(metricName)
      .setTimestamp(timestamp)
      .setIntValue(value)
    for ((name, value) <- attrs) {
      builder.addAttributes(attribute(name, value))
    }
    builder.build()
  }

  def attribute(name: String, value: String) =
    Message.Attribute.newBuilder().setName(name).setValue(value).build()

  def pointsStreamToList(streamFuture: Future[PointsStream]) = {
    def streamToFutureList(stream: PointsStream): Future[Seq[Point]] = {
      val nextListFuture = stream.next match {
        case Some(nextFuture) => nextFuture().flatMap(streamToFutureList)
        case None => Future.successful(Nil)
      }
      nextListFuture.map {nextList => stream.points ++ nextList}
    }
    Await.result(streamFuture.flatMap(streamToFutureList), 5 seconds)
  }

  behavior of "HBaseStorage.createRowRegexp"

  it should "handle a simple case" in {
    val attributes = Map(bytesKey(0, 0, 1) -> Single(bytesKey(0, 0, 1)))
    val regexp = HBaseStorage.createRowRegexp(offset = 7, attrNameLength = 3, attrValueLength = 3, attributes)
    val pattern = Pattern.compile(regexp)

    val validRow = bytesToString(bytesKey(0, 0, 2, 76, -45, -71, -128, 0, 0, 1, 0, 0, 1))
    pattern.matcher(validRow).matches() should be(true)

    val invalidRow = bytesToString(bytesKey(0, 0, 2, 76, -45, -71, -128, 0, 0, 1, 0, 0, 3))
    pattern.matcher(invalidRow).matches() should be(false)
  }

  it should "check attribute name length" in {
    val attributes = Map(bytesKey(0) -> Single(bytesKey(0)))
    val exception = intercept[IllegalArgumentException] {
      HBaseStorage.createRowRegexp(offset = 7, attrNameLength = 3, attrValueLength = 1, attributes)
    }
    exception.getMessage should (include("3") and include("1") and include("name"))
  }

  it should "check attribute value length" in {
    val attributes = Map(bytesKey(0) -> Single(bytesKey(0)))
    val exception = intercept[IllegalArgumentException] {
      HBaseStorage.createRowRegexp(offset = 7, attrNameLength = 1, attrValueLength = 3, attributes)
    }
    exception.getMessage should (include("3") and include("1") and include("value"))
  }

  it should "check that attribute name and value length is greater than zero" in {
    intercept[IllegalArgumentException] {
      HBaseStorage.createRowRegexp(
        offset = 7,
        attrNameLength = 0,
        attrValueLength = 1,
        attributes = Map(bytesKey(0) -> Single(bytesKey(0)))
      )
    }.getMessage should (include("0") and include("name"))
    intercept[IllegalArgumentException] {
      HBaseStorage.createRowRegexp(
        offset = 7,
        attrNameLength = 1,
        attrValueLength = 0,
        attributes = Map(bytesKey(0) -> Single(bytesKey(0)))
      )
    }.getMessage should (include("0") and include("value"))
  }

  it should "check that attribute list is not empty" in {
    val exception = intercept[IllegalArgumentException] {
      HBaseStorage.createRowRegexp(offset = 7, attrNameLength = 1, attrValueLength = 2, attributes = Map())
    }
    exception.getMessage should include("empty")
  }

  it should "escape regex escaping sequences symbols" in {
    val badStringBytes = stringToBytes("aaa\\Ebbb")
    val attrName = badStringBytes
    val attrValue = badStringBytes
    val rowRegexp = HBaseStorage.createRowRegexp(offset = 0, attrName.length, attrValue.length, Map(attrName -> Single(attrValue)))
    val rowString = createRowString(attrs = List((attrName, attrValue)))
    rowString should fullyMatch regex rowRegexp
  }

  it should "escape regex escaping sequences symbols (non-trivial case)" in {
    val attrName = stringToBytes("aaa\\")
    val attrValue = stringToBytes("Eaaa")
    val regexp = HBaseStorage.createRowRegexp(offset = 0, attrName.length, attrValue.length, Map(attrName -> Single(attrValue)))
    val rowString = createRowString(attrs = List((attrName, attrValue)))
    rowString should fullyMatch regex regexp
  }

  it should "handle newline characters" in {
    val attrName = stringToBytes("attrName")
    val attrValue = stringToBytes("attrValue")
    val rowRegexp = HBaseStorage.createRowRegexp(offset = 1, attrName.length, attrValue.length, Map(attrName -> Single(attrValue)))
    val row = createRow(prefix = stringToBytes("\n"), List((attrName, attrValue)))
    val rowString = bytesToString(row)
    rowString should fullyMatch regex rowRegexp
  }

  it should "create regexp for multiple value filtering" in {
    val attrName = stringToBytes("attrName")
    val attrValues = List(bytesKey(1), bytesKey(2))
    val rowRegexp = HBaseStorage.createRowRegexp(
      offset = 0,
      attrName.length,
      attrValueLength = 1,
      Map((attrName, Multiple(attrValues)))
    )

    createRowString(attrs = List((attrName, bytesKey(1)))) should fullyMatch regex rowRegexp
    createRowString(attrs = List((attrName, bytesKey(2)))) should fullyMatch regex rowRegexp
    createRowString(attrs = List((attrName, bytesKey(3)))) should not(fullyMatch regex rowRegexp)
  }

  it should "create regexp for any value filtering" in {
    val attrName = stringToBytes("attrName")
    val rowRegexp = HBaseStorage.createRowRegexp(
      offset = 0,
      attrName.length,
      attrValueLength = 1,
      Map(attrName -> All)
    )

    createRowString(attrs = List((attrName, bytesKey(1)))) should fullyMatch regex rowRegexp
    createRowString(attrs = List((attrName, bytesKey(100)))) should fullyMatch regex rowRegexp
    createRowString(attrs = List((stringToBytes("ATTRNAME"), bytesKey(1)))) should not(fullyMatch regex rowRegexp)
  }

  it should "pass randomized test" in {
    implicit val random = deterministicRandom
    for (_ <- 0 to 100) {
      val offset = random.nextInt(10)
      val attrNameLength = random.nextInt(5) + 1
      val attrValueLength = random.nextInt(5) + 1
      val searchAttrs = randomAttributes(attrNameLength, attrValueLength)
      val attrsForRegexp = searchAttrs.map { case (n, v) => (n, Single(v))}.toMap
      val searchAttrNamesSet = searchAttrs.map { case (name, _) => name.bytes.toList}.toSet
      val rowRegexp = HBaseStorage.createRowRegexp(offset, attrNameLength, attrValueLength, attrsForRegexp)
      def createJunkAttrs() = randomAttributes(attrNameLength, attrValueLength).filter {
        case (name, _) => !searchAttrNamesSet(name.bytes.toList)
      }
      for (_ <- 0 to 10) {
        val junkAttrs = createJunkAttrs()
        val rowString = arrayToString(createRow(offset, searchAttrs ++ junkAttrs))
        if (!Pattern.matches(rowRegexp, rowString)) {
          println(attrNameLength)
          println(attrValueLength)
          println(stringToBytes(rowRegexp).bytes.toList)
          println(stringToBytes(rowString).bytes.toList)
        }
        rowString should fullyMatch regex rowRegexp

        val anotherJunkAttrs = createJunkAttrs()
        val anotherRowString = arrayToString(createRow(offset, anotherJunkAttrs ++ junkAttrs))
        anotherRowString should not(fullyMatch regex rowRegexp)
      }
    }
  }

  def bytesKey(bytes: Byte*) = new BytesKey(Array[Byte](bytes: _*))

  def deterministicRandom: Random = {
    new Random(0)
  }

  def randomBytes(nBytes: Int)(implicit random: Random): List[Byte] = {
    val array = new Array[Byte](nBytes)
    random.nextBytes(array)
    array.toList
  }

  def randomAttributes(attrNameLength: Int, attrValueLength: Int)(implicit random: Random) = {
    val randomAttrs =
      for (_ <- 0 to random.nextInt(7))
      yield {
        (randomBytes(attrNameLength), randomBytes(attrValueLength))
      }
    // uniquify attribute names
    randomAttrs.toMap.toList.map {
      case (attrName, attrValue) => (new BytesKey(attrName.toArray), new BytesKey(attrValue.toArray))
    }
  }

  def createRow(prefix: Array[Byte], attrs: List[(BytesKey, BytesKey)]) = {
    val sorted = attrs.sortBy(_._1)
    prefix ++ sorted.map(pair => pair._1.bytes ++ pair._2.bytes).foldLeft(Array[Byte]())(_ ++ _)
  }

  def createRow(offset: Int, attrs: List[(BytesKey, BytesKey)])(implicit random: Random): Array[Byte] =
    createRow(randomBytes(offset).toArray, attrs)

  def createRowString(prefix: Array[Byte] = Array.empty[Byte], attrs: List[(BytesKey, BytesKey)]) =
    bytesToString(createRow(prefix, attrs))

  private def bytesToString(bKey: BytesKey) = arrayToString(bKey.bytes)

  private def arrayToString(array: Array[Byte]) = new String(array,HBaseStorage.ROW_REGEX_FILTER_ENCODING

  )

  private def stringToBytes(string: String): BytesKey = {
    val charBuffer = CharBuffer.wrap(string)
    new BytesKey(HBaseStorage.ROW_REGEX_FILTER_ENCODING.encode(charBuffer).array())
  }

}
