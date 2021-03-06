package popeye.storage.hbase

import com.codahale.metrics.MetricRegistry
import java.util
import org.apache.hadoop.hbase.KeyValue
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.util.Bytes
import popeye.proto.{Message, PackedPoints}
import popeye.{Instrumented, Logging}
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import com.typesafe.config.Config
import akka.actor.{ActorRef, Props, ActorSystem}
import java.util.concurrent.TimeUnit
import popeye.util.hbase.{HBaseUtils, HBaseConfigured}
import java.nio.charset.Charset
import org.apache.hadoop.hbase.filter.{RegexStringComparator, CompareFilter, RowFilter}
import java.nio.ByteBuffer
import HBaseStorage._
import scala.collection.immutable.SortedMap
import popeye.util.hbase.HBaseUtils.ChunkedResults
import popeye.pipeline.PointsSink

object HBaseStorage {
  final val Encoding = Charset.forName("UTF-8")

  /**
   * Array containing the hexadecimal characters (0 to 9, A to F).
   * This array is read-only, changing its contents leads to an undefined
   * behavior.
   */
  final val HEX: Array[Byte] = Array[Byte]('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

  final val PointsFamily = "t".getBytes(Encoding)

  final val MetricKind: String = "metric"
  final val AttrNameKind: String = "tagk"
  final val AttrValueKind: String = "tagv"

  final val UniqueIdMapping = Map[String, Short](
    MetricKind -> 3.toShort,
    AttrNameKind -> 3.toShort,
    AttrValueKind -> 3.toShort
  )

  final val UniqueIdNamespaceWidth = 2

  sealed case class ResolvedName(kind: String, namespace: BytesKey, name: String, id: BytesKey) {
    def this(qname: QualifiedName, id: BytesKey) = this(qname.kind, qname.namespace, qname.name, id)

    def this(qid: QualifiedId, name: String) = this(qid.kind, qid.namespace, name, qid.id)

    def toQualifiedName = QualifiedName(kind, namespace, name)

    def toQualifiedId = QualifiedId(kind, namespace, id)
  }

  object ResolvedName {
    def apply(qname: QualifiedName, id: BytesKey) = new ResolvedName(qname.kind, qname.namespace, qname.name, id)

    def apply(qid: QualifiedId, name: String) = new ResolvedName(qid.kind, qid.namespace, name, qid.id)
  }

  sealed case class QualifiedName(kind: String, namespace: BytesKey, name: String)

  sealed case class QualifiedId(kind: String, namespace: BytesKey, id: BytesKey)


  type PointsGroup = Map[PointAttributes, Seq[Point]]

  type PointAttributes = SortedMap[String, String]

  def concatGroups(a: PointsGroup, b: PointsGroup) = {
    b.foldLeft(a) {
      case (accGroup, (attrs, newPoints)) =>
        val pointsOption = accGroup.get(attrs)
        accGroup.updated(attrs, pointsOption.getOrElse(Seq()) ++ newPoints)
    }
  }

  case class PointsGroups(groupsMap: Map[PointAttributes, PointsGroup]) {
    def concat(that: PointsGroups) = {
      val newMap =
        that.groupsMap.foldLeft(groupsMap) {
          case (accGroups, (groupByAttrs, newGroup)) =>
            val groupOption = accGroups.get(groupByAttrs)
            val concatinatedGroups = groupOption.map(oldGroup => concatGroups(oldGroup, newGroup)).getOrElse(newGroup)
            accGroups.updated(groupByAttrs, concatinatedGroups)
        }
      PointsGroups(newMap)
    }
  }

  object Point {
    def apply(timestamp: Int, value: Long): Point = Point(timestamp, Left(value))
  }

  case class Point(timestamp: Int, value: Either[Long, Float]) {
    def isFloat = value.isRight

    def getFloatValue = value match { case Right(f) => f }

    def getLongValue = value match { case Left(l) => l }

    def doubleValue = {
      if (isFloat) {
        getFloatValue.toDouble
      } else {
        getLongValue.toDouble
      }
    }
  }

  case class PointsStream(groups: PointsGroups, next: Option[() => Future[PointsStream]]) {
    def toFuturePointsGroups(implicit eCtx: ExecutionContext): Future[PointsGroups] = {
      val nextPointsFuture = next match {
        case Some(nextFuture) => nextFuture().flatMap(_.toFuturePointsGroups)
        case None => Future.successful(PointsGroups(Map()))
      }
      nextPointsFuture.map {
        nextGroups => groups.concat(nextGroups)
      }
    }

  }

  object PointsStream {
    def apply(groups: PointsGroups): PointsStream = PointsStream(groups, None)

    def apply(groups: PointsGroups, nextStream: => Future[PointsStream]): PointsStream =
      PointsStream(groups, Some(() => nextStream))
  }

  sealed trait ValueIdFilterCondition {
    def isGroupByAttribute: Boolean
  }

  object ValueIdFilterCondition {

    case class SingleValueId(id: BytesKey) extends ValueIdFilterCondition {
      def isGroupByAttribute: Boolean = false
    }

    case class MultipleValueIds(ids: Seq[BytesKey]) extends ValueIdFilterCondition {
      require(ids.size > 1, "must be more than one value id")

      def isGroupByAttribute: Boolean = true
    }

    case object AllValueIds extends ValueIdFilterCondition {
      def isGroupByAttribute: Boolean = true
    }

  }

  sealed trait ValueNameFilterCondition {
    def isGroupByAttribute: Boolean
  }

  object ValueNameFilterCondition {

    case class SingleValueName(name: String) extends ValueNameFilterCondition {
      def isGroupByAttribute: Boolean = false
    }

    case class MultipleValueNames(names: Seq[String]) extends ValueNameFilterCondition {
      require(names.size > 1, "must be more than one value name")

      def isGroupByAttribute: Boolean = true
    }

    case object AllValueNames extends ValueNameFilterCondition {
      def isGroupByAttribute: Boolean = true
    }
  }

}

class HBasePointsSink(storage: HBaseStorage)(implicit eCtx: ExecutionContext) extends PointsSink {

  override def sendPoints(batchId: Long, points: Message.Point*): Future[Long] = {
    storage.writeMessagePoints(points :_*)(eCtx)
  }

  override def sendPacked(batchId: Long, buffers: PackedPoints*): Future[Long] = {
    storage.writePackedPoints(buffers :_*)(eCtx)
  }

  override def close(): Unit = {}
}


case class HBaseStorageMetrics(name: String, override val metricRegistry: MetricRegistry) extends Instrumented {
  val writeHBaseTime = metrics.timer(s"$name.storage.write.hbase.time")
  val writeHBaseTimeMeter = metrics.meter(s"$name.storage.write.hbase.time-meter")
  val writeTime = metrics.timer(s"$name.storage.write.time")
  val writeTimeMeter = metrics.meter(s"$name.storage.write.time-meter")
  val writeHBasePoints = metrics.meter(s"$name.storage.write.points")
  val readProcessingTime = metrics.timer(s"$name.storage.read.processing.time")
  val readHBaseTime = metrics.timer(s"$name.storage.read.hbase.time")
  val resolvedPointsMeter = metrics.meter(s"$name.storage.resolved.points")
  val delayedPointsMeter = metrics.meter(s"$name.storage.delayed.points")
}

class HBaseStorage(tableName: String,
                   hTablePool_ : HTablePool,
                   metricNames: UniqueId,
                   attributeNames: UniqueId,
                   attributeValues: UniqueId,
                   tsdbFormat: TsdbFormat,
                   metrics: HBaseStorageMetrics,
                   resolveTimeout: Duration = 15 seconds,
                   readChunkSize: Int) extends Logging with HBaseUtils {

  def hTablePool: HTablePool = hTablePool_

  val tableBytes = tableName.getBytes(Encoding)

  def uniqueIdsMap(kind: String): UniqueId = kind match {
    case MetricKind => metricNames
    case AttrNameKind => attributeNames
    case AttrValueKind => attributeValues
  }

  def getPoints(metric: String,
                timeRange: (Int, Int),
                attributes: Map[String, ValueNameFilterCondition])
               (implicit eCtx: ExecutionContext): Future[PointsStream] = {
    val scanNames = tsdbFormat.getScanNames(metric, timeRange, attributes)
    val scanNameIdPairsFuture = Future.traverse(scanNames) {
      qName =>
        uniqueIdsMap(qName.kind)
          .resolveIdByName(qName.namespace, qName.name, create = false)(resolveTimeout)
          .map(id => Some(qName, id))
          .recover { case e: NoSuchElementException => None }
    }
    val futurePointsStream =
      for {
        scanNameIdPairs <- scanNameIdPairsFuture
      } yield {
        val scanNameToIdMap = scanNameIdPairs.collect { case Some(x) => x }.toMap
        val scans = tsdbFormat.getScans(metric, timeRange, attributes, scanNameToIdMap)
        val chunkedResults = getChunkedResults(tableName, readChunkSize, scans)
        val groupByAttributeNames =
          attributes
            .toList
            .filter { case (attrName, valueFilter) => valueFilter.isGroupByAttribute}
            .map(_._1)
        createPointsStream(chunkedResults, timeRange, groupByAttributeNames)
      }
    futurePointsStream.flatMap(future => future)
  }

  def createPointsStream(chunkedResults: ChunkedResults,
                         timeRange: (Int, Int),
                         groupByAttributeNameIds: Seq[String])
                        (implicit eCtx: ExecutionContext): Future[PointsStream] = {

    def toPointsStream(chunkedResults: ChunkedResults): Future[PointsStream] = {
      val (results, nextResults) = chunkedResults.getRows()
      val rowResults = results.map(tsdbFormat.parseRowResult)
      val ids = tsdbFormat.getRowResultsIds(rowResults)
      val idNamePairsFuture = Future.traverse(ids) {
        case qId@QualifiedId(kind, ns, id) =>
          uniqueIdsMap(kind).resolveNameById(ns, id)(resolveTimeout).map(name => (qId, name))
      }
      idNamePairsFuture.map {
        idNamePairs =>
          val idMap = idNamePairs.toMap
          val pointSequences: Map[PointAttributes, Seq[Point]] = toPointSequencesMap(rowResults, timeRange, idMap)
          val pointGroups: Map[PointAttributes, PointsGroup] = groupPoints(groupByAttributeNameIds, pointSequences)
          val nextStream = nextResults.map {
            query => () => toPointsStream(query)
          }
          PointsStream(PointsGroups(pointGroups), nextStream)
      }
    }
    toPointsStream(chunkedResults)
  }

  private def toPointSequencesMap(rows: Array[ParsedRowResult],
                                  timeRange: (Int, Int),
                                  idMap: Map[QualifiedId, String]): Map[PointAttributes, Seq[Point]] = {
    val (startTime, endTime) = timeRange
    rows.groupBy(row => row.getAttributes(idMap)).mapValues {
      rowsArray =>
        val pointsArray = rowsArray.map(_.points)
        val firstRow = pointsArray(0)
        pointsArray(0) = firstRow.filter(point => point.timestamp >= startTime)
        val lastIndex = pointsArray.length - 1
        val lastRow = pointsArray(lastIndex)
        pointsArray(lastIndex) = lastRow.filter(point => point.timestamp < endTime)
        pointsArray.toSeq.flatten
    }
  }

  def groupPoints(groupByAttributeNames: Seq[String],
                  pointsSequences: Map[PointAttributes, Seq[Point]]): Map[PointAttributes, PointsGroup] = {
    pointsSequences.groupBy {
      case (attributes, _) =>
        val groupByAttributeValueIds = groupByAttributeNames.map(attributes(_))
        SortedMap[String, String](groupByAttributeNames zip groupByAttributeValueIds: _*)
    }
  }

  def ping(): Unit = {
    val future = metricNames.resolveIdByName(new BytesKey(Array[Byte](0, 0)), "_.ping", create = true)(resolveTimeout)
    Await.result(future, resolveTimeout)
  }

  def writePackedPoints(packed: PackedPoints*)(implicit eCtx: ExecutionContext): Future[Long] = {
    writePoints(Left(packed))
  }

  def writeMessagePoints(points: Message.Point*)(implicit eCtx: ExecutionContext): Future[Long] = {
    writePoints(Right(points))
  }

  /**
   * Write points, returned future is awaitable, but can be already completed
   * @param data what to write
   * @return number of written points
   */
  def writePoints(data: Either[Seq[PackedPoints], Seq[Message.Point]])(implicit eCtx: ExecutionContext): Future[Long] = {

    val ctx = metrics.writeTime.timerContext()
      // resolve identifiers
      // unresolved will be delayed for future expansion
    convertToKeyValues(data).flatMap {
      case (partiallyConvertedPoints, keyValues) =>
        // write resolved points
        val writeComplete = if (!keyValues.isEmpty) Future[Int] {
          keyValues.size
        } else Future.successful[Int](0)
        writeKv(keyValues)

        val delayedKeyValuesWriteFuture = writePartiallyConvertedPoints(partiallyConvertedPoints)

        (delayedKeyValuesWriteFuture zip writeComplete).map {
          case (a, b) =>
            val time = ctx.stop.nano
            metrics.writeTimeMeter.mark(time.toMillis)
            (a + b).toLong
        }
    }
  }

  private def writePartiallyConvertedPoints(pPoints: PartiallyConvertedPoints)
                                           (implicit eCtx: ExecutionContext): Future[Int] = {
    if (pPoints.points.nonEmpty) {
      val idMapFuture = resolveNames(pPoints.unresolvedNames)
      idMapFuture.map {
        idMap =>
          val keyValues = pPoints.convert(idMap)
          writeKv(keyValues)
          keyValues.size
      }
    } else Future.successful[Int](0)
  }

  private def convertToKeyValues(data: Either[Seq[PackedPoints], Seq[Message.Point]])
                                (implicit eCtx: ExecutionContext)
  : Future[(PartiallyConvertedPoints, Seq[KeyValue])] = Future {
    val idCache: QualifiedName => Option[BytesKey] = {
      case QualifiedName(kind, namespace, name) => uniqueIdsMap(kind).findIdByName(namespace, name)
    }
    val result@(partiallyConvertedPoints, keyValues) = tsdbFormat.convertToKeyValues(data, idCache)
    metrics.resolvedPointsMeter.mark(keyValues.size)
    metrics.delayedPointsMeter.mark(partiallyConvertedPoints.points.size)
    result
  }

  private def resolveNames(names: Set[QualifiedName])(implicit eCtx: ExecutionContext): Future[Map[QualifiedName, BytesKey]] = {
    val groupedNames = names.groupBy(_.kind)
    def idsMapFuture(qualifiedNames: Set[QualifiedName], uniqueId: UniqueId): Future[Map[QualifiedName, BytesKey]] = {
      val namesSeq = qualifiedNames.toSeq
      val idsFuture = Future.traverse(namesSeq) {
        qName => uniqueId.resolveIdByName(qName.namespace, qName.name, create = true)(resolveTimeout)
      }
      idsFuture.map {
        ids => namesSeq.zip(ids)(scala.collection.breakOut)
      }
    }
    val metricsMapFuture = idsMapFuture(groupedNames.getOrElse(MetricKind, Set()), metricNames)
    val attrNamesMapFuture = idsMapFuture(groupedNames.getOrElse(AttrNameKind, Set()), attributeNames)
    val attrValueMapFuture = idsMapFuture(groupedNames.getOrElse(AttrValueKind, Set()), attributeValues)
    for {
      metricsMap <- metricsMapFuture
      attrNameMap <- attrNamesMapFuture
      attrValueMap <- attrValueMapFuture
    } yield metricsMap ++ attrNameMap ++ attrValueMap
  }

  private def writeKv(kvList: Seq[KeyValue]) = {
    debug(s"Making puts for ${kvList.size} keyvalues")
    val puts = new util.ArrayList[Put](kvList.length)
    kvList.foreach {
      k =>
        puts.add(new Put(k.getRow).add(k))
    }
    withDebug {
      val l = puts.map(_.heapSize()).foldLeft(0l)(_ + _)
      debug(s"Writing ${kvList.size} keyvalues (heapsize=$l)")
    }
    val timer = metrics.writeHBaseTime.timerContext()
    val hTable = hTablePool.getTable(tableName)
    hTable.setAutoFlush(false, true)
    hTable.setWriteBufferSize(4 * 1024 * 1024)
    try {
      hTable.batch(puts)
      debug(s"Writing ${kvList.size} keyvalues - flushing")
      hTable.flushCommits()
      debug(s"Writing ${kvList.size} keyvalues - done")
      metrics.writeHBasePoints.mark(puts.size())
    } catch {
      case e: Exception =>
        error("Failed to write points", e)
        throw e
    } finally {
      hTable.close()
    }
    val time = timer.stop()
    metrics.writeHBaseTimeMeter.mark(time)
  }

  /**
   * Makes Future for Message.Point from KeyValue.
   * Most time all identifiers are cached, so this future returns 'complete',
   * but in case of some unresolved identifiers future will be incomplete and become asynchronous
   *
   * @param kv keyvalue to restore
   * @return future
   */
  private def mkPointFuture(kv: KeyValue)(implicit eCtx: ExecutionContext): Future[Message.Point] = {
    import scala.collection.JavaConverters._
    val rowResult = tsdbFormat.parseRowResult(new Result(Seq(kv).asJava))
    val rowIds = tsdbFormat.getRowResultsIds(Seq(rowResult))
    val idNamePairsFuture = Future.traverse(rowIds) {
      case qId@QualifiedId(kind, namespace, id) =>
        uniqueIdsMap(kind).resolveNameById(namespace, id)(resolveTimeout).map {
          name => (qId, name)
        }
    }
    val valuePoint = rowResult.points.head
    idNamePairsFuture.map {
      idNamePairs =>
        val metricName = rowResult.getMetricName(idNamePairs.toMap)
        val attrs = rowResult.getAttributes(idNamePairs.toMap)
        val builder = Message.Point.newBuilder()
        builder.setTimestamp(valuePoint.timestamp)
        builder.setMetric(metricName)
        if (valuePoint.isFloat) {
          builder.setFloatValue(valuePoint.getFloatValue)
        } else {
          builder.setIntValue(valuePoint.getLongValue)
        }
        for ((name, value) <- attrs) {
          builder.addAttributesBuilder()
            .setName(name)
            .setValue(value)
        }
        builder.build()
    }
  }

  def keyValueToPoint(kv: KeyValue)(implicit eCtx: ExecutionContext): Message.Point = {
    Await.result(mkPointFuture(kv), resolveTimeout)
  }

}

class HBaseStorageConfig(val config: Config,
                          val actorSystem: ActorSystem,
                          val metricRegistry: MetricRegistry,
                          val storageName: String = "hbase")
                         (implicit val eCtx: ExecutionContext){
  val uidsTableName = config.getString("table.uids")
  val pointsTableName = config.getString("table.points")
  val poolSize = config.getInt("pool.max")
  val zkQuorum = config.getString("zk.quorum")
  val resolveTimeout = new FiniteDuration(config.getMilliseconds(s"uids.resolve-timeout"), TimeUnit.MILLISECONDS)
  val readChunkSize = config.getInt("read-chunk-size")
  val uidsConfig = config.getConfig("uids")
  val timeRangeIdMapping = {
    val idRotationPeriodInHours = config.getInt("uids.rotation-period-hours")
    new PeriodicTimeRangeId(idRotationPeriodInHours)
  }
}

/**
 * Encapsulates configured hbase client and points storage actors.
 * @param config provides necessary configuration parameters
 */
class HBaseStorageConfigured(config: HBaseStorageConfig) {

  val hbase = new HBaseConfigured(
    config.config, config.zkQuorum, config.poolSize)

  CreateTsdbTables.createTables(hbase.hbaseConfiguration, config.pointsTableName, config.uidsTableName)

  config.actorSystem.registerOnTermination(hbase.close())

  val uniqueIdStorage = new UniqueIdStorage(config.uidsTableName, hbase.hTablePool)

  val storage = {
    implicit val eCtx = config.eCtx

    val uniqIdResolved = config.actorSystem.actorOf(Props.apply(UniqueIdActor(uniqueIdStorage)))
    val metrics = makeUniqueIdCache(config.uidsConfig, HBaseStorage.MetricKind, uniqIdResolved, uniqueIdStorage,
      config.resolveTimeout)
    val attrNames = makeUniqueIdCache(config.uidsConfig, HBaseStorage.AttrNameKind, uniqIdResolved, uniqueIdStorage,
      config.resolveTimeout)
    val attrValues = makeUniqueIdCache(config.uidsConfig, HBaseStorage.AttrValueKind, uniqIdResolved, uniqueIdStorage,
      config.resolveTimeout)
    val tsdbFormat = new TsdbFormat(config.timeRangeIdMapping)
    new HBaseStorage(
      config.pointsTableName,
      hbase.hTablePool,
      metrics,
      attrNames,
      attrValues,
      tsdbFormat,
      new HBaseStorageMetrics(config.storageName, config.metricRegistry),
      config.resolveTimeout,
      config.readChunkSize)
  }

  private def makeUniqueIdCache(config: Config, kind: String, resolver: ActorRef,
                                storage: UniqueIdStorage, resolveTimeout: FiniteDuration)
                               (implicit eCtx: ExecutionContext): UniqueId = {
    new UniqueIdImpl(storage.kindWidth(kind), kind, resolver,
      config.getInt(s"$kind.initial-capacity"),
      config.getInt(s"$kind.max-capacity"),
      resolveTimeout
    )
  }

}
