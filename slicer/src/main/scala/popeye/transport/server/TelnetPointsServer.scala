package popeye.transport.server

import akka.actor._
import akka.io.IO
import akka.io._
import akka.pattern.AskTimeoutException
import akka.util.{Timeout, ByteString}
import com.codahale.metrics.{Timer, MetricRegistry}
import com.typesafe.config.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import popeye.pipeline.DispatcherProtocol.{Pending, Done}
import popeye.transport.proto.{Message, PackedPoints}
import popeye.{Logging, Instrumented}
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Success, Failure}

class TsdbTelnetMetrics(override val metricRegistry: MetricRegistry) extends Instrumented {
  val requestTimer = metrics.timer("request-time")
  val commitTimer = metrics.timer("commit-time")
  val pointsRcvMeter = metrics.meter("points-received")
  val pointsCommitMeter = metrics.meter("points-commited")
  val connections = new AtomicInteger(0)
  val connectionsGauge = metrics.gauge("connections") {
    connections.get()
  }
}

class TsdbTelnetHandler(connection: ActorRef,
                        kafkaProducer: ActorRef,
                        config: Config,
                        metrics: TsdbTelnetMetrics)
  extends Actor with Logging {


  context watch connection

  val hwPendingPoints: Int = config.getInt("server.telnet.high-watermark")
  val lwPendingPoints: Int = config.getInt("server.telnet.low-watermark")
  val batchSize: Int = config.getInt("server.telnet.batchSize")
  implicit val askTimeout: Timeout = new Timeout(config.getMilliseconds("server.telnet.produce.timeout"), MILLISECONDS)

  require(hwPendingPoints > lwPendingPoints, "High watermark should be greater then low watermark")

  type PointId = Long
  type BatchId = Long
  type CorrelationId = Long

  sealed case class TryReadResumeMessage(timestampMillis: Long = System.currentTimeMillis(), resume: Boolean = false)

  sealed case class CommitReq(sender: ActorRef, pointId: PointId, correlation: CorrelationId, timerContext: Timer.Context)

  private var bufferedPoints = PackedPoints()
  private var pendingCommits: Seq[CommitReq] = Vector()
  private val pendingCorrelations = mutable.TreeSet[PointId]()

  private val commands = new TelnetCommands(metrics, config) {

    override def addPoint(point: Message.Point): Unit = {
      bufferedPoints += point
      if (bufferedPoints.pointsCount >= batchSize) {
        sendPack()
      }
    }

    override def startExit(): Unit = {
      pendingExit = true
      debug(s"Triggered exit")
    }

    override def commit(correlationId: Option[Long]): Unit = {
      sendPack()
      if (correlationId.isDefined) {
        debug(s"Triggered commit for correlationId $correlationId and pointId $pointId")
        pendingCommits = (pendingCommits :+ CommitReq(sender, pointId, correlationId.get,
          metrics.commitTimer.timerContext())).sortBy(_.pointId)
      }
    }
  }

  @volatile
  private var pendingExit = false
  @volatile
  private var lastBatchId: BatchId = 0
  @volatile
  private var pointId: PointId = 0
  @volatile
  private var paused = false

  private val requestTimer = metrics.requestTimer.timerContext()

  override def postStop() {
    super.postStop()
    requestTimer.close()
    commands.close()
  }

  final def receive = {
    case Tcp.Received(data: ByteString) if data.length > 0 =>
      try {
        commands.process(data)
      } catch {
        case ex: Exception =>
          debug(s"Err: ${ex.getMessage}", ex)
          sender ! Tcp.Write(ByteString("ERR " + ex.getMessage + "\n"))
          context.stop(self)
      }
      tryReplyOk()
      throttle()

    case Done(completeCorrelationId, batchId) =>
      if (lastBatchId < batchId) {
        lastBatchId = batchId
      }
      debug(s"Produce done: ${completeCorrelationId.size} correlations " +
        s"as batch $batchId (now lastBatchId=$lastBatchId)")

      pendingCorrelations --= completeCorrelationId
      val commitedSize: Long = completeCorrelationId.size
      metrics.pointsCommitMeter.mark(commitedSize)
      tryReplyOk()
      throttle()

    case r: ReceiveTimeout =>
      throttle(timeout = true)

    case x: Tcp.ConnectionClosed =>
      debug(s"Connection closed $x")
      context.stop(self)
  }

  private def sendPack() {
    import context.dispatcher
    if (bufferedPoints.pointsCount > 0) {
      pointId += 1
      val p = Promise[Long]()
      kafkaProducer ! Pending(Some(p))(bufferedPoints)
      bufferedPoints = new PackedPoints(messagesPerExtent = batchSize + 1)
      pendingCorrelations.add(pointId)
      val timer = context.system.scheduler.scheduleOnce(askTimeout.duration, new Runnable {
        def run() {
          p.tryFailure(new AskTimeoutException("Producer timeout"))
        }
      })
      val cId = Seq(pointId)
      val ctx = context
      val me = self
      p.future onComplete {
        case Success(l) =>
          timer.cancel()
          me ! Done(cId, l)
        case Failure(ex) =>
          timer.cancel()
          connection ! Tcp.Write(ByteString("ERR Command processing timeout\n"))
          ctx.stop(me)
      }
    }
  }

  def tryReplyOk() {

    if (!pendingCommits.isEmpty) {
      val minPoint: Long = pendingCorrelations.headOption getOrElse Long.MaxValue
      pendingCommits.span(_.pointId < minPoint) match {
        case (complete, incomplete) =>
          complete foreach {
            p =>
              debug(s"Commit done: ${p.correlation} = $lastBatchId")
              p.sender ! Tcp.Write(ByteString(s"OK ${p.correlation} = $lastBatchId\n"))
              p.timerContext.stop()
          }
          pendingCommits = incomplete
      }
    }

    if (pendingExit && pendingCommits.isEmpty) {
      connection ! Tcp.Close
    }
  }

  def throttle(timeout: Boolean = false) {
    val size: Int = pendingCorrelations.size
    if (size > hwPendingPoints) {
      if (!paused) {
        paused = true
        debug(s"Pausing reads: $size > $hwPendingPoints")
        context.setReceiveTimeout(1 millisecond)
      }
      connection ! Tcp.SuspendReading
    }

    // resume reading only after recieving 'end-of-queue' marker
    if (paused && timeout && size < lwPendingPoints) {
      paused = false
      connection ! Tcp.ResumeReading
      context.setReceiveTimeout(Duration.Undefined)
      debug(s"Reads resumed: $size < $lwPendingPoints")
    }
  }
}

class TsdbTelnetServer(local: InetSocketAddress, kafka: ActorRef, metrics: TsdbTelnetMetrics) extends Actor with Logging {

  import Tcp._

  implicit def system = context.system

  IO(Tcp) ! Bind(self, local)

  def receive: Receive = {
    case _: Bound ⇒
      info("Bound to $sender")
      context.become(bound(sender))
  }

  def bound(listener: ActorRef): Receive = {
    case Connected(remote, _) ⇒
      val connection = sender

      val handler = context.actorOf(Props.apply(new TsdbTelnetHandler(connection, kafka, system.settings.config, metrics))
        .withDeploy(Deploy.local))

      debug(s"Connection from $remote (connection=${connection.path})")
      metrics.connections.incrementAndGet()
      connection ! Tcp.Register(handler, keepOpenOnPeerClosed = true)
  }

  override def preStart() {
    super.preStart()
    info("Started Tsdb Telnet server")
  }

  override def postStop() {
    super.postStop()
    info("Stoped Tsdb Telnet server")
  }
}

object TsdbTelnetServer {

  def start(config: Config, kafkaProducer: ActorRef)(implicit system: ActorSystem, metricRegistry: MetricRegistry): ActorRef = {
    val hostport = config.getString("server.telnet.listen").split(":")
    val addr = new InetSocketAddress(hostport(0), hostport(1).toInt)
    system.actorOf(Props.apply(new TsdbTelnetServer(addr, kafkaProducer, new TsdbTelnetMetrics(metricRegistry))))
  }
}
