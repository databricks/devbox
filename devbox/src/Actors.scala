package devbox

import java.awt.event.{ActionEvent, ActionListener, MouseEvent, MouseListener}
import java.io.{PrintWriter, StringWriter}
import java.nio.ByteBuffer
import java.time.ZoneId
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{Bytes, Logger, Response, Rpc, RpcClient, Signature, Skipper, Util, Vfs}

import scala.collection.mutable


object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: Rpc, logged: String) extends Msg
  case class ForceRestart() extends Msg
  case class SendChunks(value: SyncFiles.SendChunks) extends Msg
  case class Restarted() extends Msg
  case class ReadRestarted() extends Msg
  case class Receive(data: Response) extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          syncer: => SyncActor,
                          statusActor: => StatusActor,
                          logger: Logger)
                         (implicit ac: ActorContext)
  extends SimpleActor[AgentReadWriteActor.Msg](){
  private val buffer = mutable.ArrayDeque.empty[(Rpc, StatusActor.Msg)]

  var sending = true
  var retryCount = 0

  def sendLogged(msg: Rpc, logged: String) = {
    ac.reportSchedule()

    buffer.append(msg -> StatusActor.Syncing(logged))

    if (sending) sendRpcs(Seq(msg))
    else statusActor.send(StatusActor.Error(
      "Unable to connect to devbox\n" +
      "click on this logo to try again"
    ))
  }

  val client = new RpcClient(agent.stdin, agent.stdout, logger.apply(_, _))

  def run(item: AgentReadWriteActor.Msg): Unit = item match{
    case AgentReadWriteActor.Send(msg, logged) => sendLogged(msg, logged)

    case AgentReadWriteActor.ForceRestart() =>
      if (!sending){
        statusActor.send(StatusActor.Syncing("Syncing Restarted"))
        retryCount = 0
        restart()
      }

    case AgentReadWriteActor.ReadRestarted() =>
      if (sending){
        retry()
      }

    case AgentReadWriteActor.SendChunks(
      SyncFiles.SendChunks(
        p, dest, segments, chunkIndices, fileIndex, fileTotalCount, blockHashes
      )
    ) =>

      var bytes = 0L
      val byteArr = new Array[Byte](Util.blockSize)
      val buf = ByteBuffer.wrap(byteArr)
      Util.autoclose(os.read.channel(p)) { channel =>
        for (i <- chunkIndices) {
          val hashMsg = if (blockHashes.size > 1) s" $i/${blockHashes.size}" else ""

          buf.rewind()
          channel.position(i * Util.blockSize)
          var n = 0
          while ( {
            if (n == Util.blockSize) false
            else channel.read(buf) match {
              case -1 => false
              case d =>
                n += d
                true
            }
          }) ()
          bytes += n
          val msg = Rpc.WriteChunk(
            dest,
            segments,
            i * Util.blockSize,
            new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr)
          )

          sendLogged(
            msg,
            s"Syncing file chunk [$fileIndex/$fileTotalCount$hashMsg]:\n$segments"
          )
        }
      }
      statusActor.send(StatusActor.FilesAndBytes(1, bytes))

    case AgentReadWriteActor.Restarted() =>
      if (!sending){
        spawnReaderThread()
        if (buffer.isEmpty) {
          ac.reportSchedule()
          buffer.append(Rpc.Complete() -> StatusActor.Done())
        }
        sendRpcs(buffer.toSeq.map(_._1))
        sending = true
      }

    case AgentReadWriteActor.Receive(data) =>
      retryCount = 0
      syncer.send(SyncActor.Receive(data))

      if (data.isInstanceOf[Response.Ack]) {
        ac.reportComplete()

        val (droppedMsg, logged) = buffer.removeHead()

        statusActor.send(if (buffer.nonEmpty) logged else StatusActor.Done())
      }
  }

  def spawnReaderThread() = {
    new Thread(() => {
      while ( {
        val strOpt =
          try Some(agent.stderr.readLine())
          catch{
            case e: java.io.EOFException => None
            case e: java.io.IOException => None
          }
        strOpt match{
          case None | Some(null)=> false
          case Some(str) =>
            try {
              logger.write(ujson.read(str).str)
              true
            } catch {
              case e: ujson.ParseException =>
                println(str)
                false
            }
        }
      }) ()
    }).start()
    new Thread(() => {
      while(try{
        val response = client.readMsg[Response]()
        this.send(AgentReadWriteActor.Receive(response))
        true
      }catch{
        case e: java.io.IOException =>
          this.send(AgentReadWriteActor.ReadRestarted())
          false
      })()
    }).start()
  }


  def sendRpcs(msgs: Seq[Rpc]) = {
    try {
      for(msg <- msgs)client.writeMsg(msg)
    }catch{ case e: java.io.IOException =>
      restart()
    }
  }

  def restart(): Unit = {
    println("RESTART")
    retryCount += 1
    sending = false
    try agent.destroy()
    catch{case e: Throwable => /*donothing*/}
    try {
      statusActor.send(StatusActor.Syncing(s"Restarting agent\nAttempt #$retryCount"))
      agent.start()
      this.send(AgentReadWriteActor.Restarted())
    } catch{case e: os.SubprocessException =>
      retry()
    }
  }

  def retry() = {
    if (retryCount < 5) {
      val seconds = math.pow(2, retryCount).toInt
      statusActor.send(StatusActor.Error(
        s"Unable to connect to devbox, trying again after $seconds seconds"
      ))
      Thread.sleep(1000 * seconds)
      restart()
    }
    else statusActor.send(StatusActor.Error(
      "Unable to connect to devbox, gave up after 5 attempts;\n" +
        "click on this logo to try again"
    ))

  }
}

object SyncActor{
  sealed trait Msg
  case class Scan() extends Msg
  case class ScanComplete(vfsArr: Seq[Vfs[Signature]]) extends Msg

  case class Events(paths: Set[os.Path]) extends Msg
  case class LocalScanned(paths: os.Path, index: Int, total: Int) extends Msg
  case class Debounced(debounceId: Object) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
  case class LocalScanComplete() extends Msg
}
class SyncActor(agentReadWriter: => AgentReadWriteActor,
                mapping: Seq[(os.Path, os.RelPath)],
                logger: Logger,
                signatureTransformer: (os.RelPath, Signature) => Signature,
                skipper: Skipper,
                scheduledExecutorService: ScheduledExecutorService,
                statusActor: => StatusActor)
               (implicit ac: ActorContext)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = Initializing(Set())

  case class Initializing(changedPaths: Set[os.Path]) extends State({
    case SyncActor.Events(paths) => Initializing(changedPaths ++ paths)
    case SyncActor.Scan() =>
      agentReadWriter.send(
        AgentReadWriteActor.Send(
          Rpc.FullScan(mapping.map(_._2)),
          "Remote Scanning " + mapping.map(_._2).mkString(", ")
        )
      )
      scala.concurrent.Future{
        try{
          common.InitialScan.initialSkippedScan(mapping.map(_._1), skipper){ (scanRoot, p, sig, i, total) =>
            this.send(SyncActor.LocalScanned(p, i, total))
          }
          this.send(SyncActor.LocalScanComplete())
        }catch{case e: Throwable =>
          ac.reportFailure(e)
        }
      }
      RemoteScanning(
        Set.empty,
        Set.empty,
        mapping.map(_._2 -> new Vfs[Signature](Signature.Dir(0))),
        0
      )
  })

  case class RemoteScanning(localPaths: Set[os.Path],
                            remotePaths: Set[os.Path],
                            vfsArr: Seq[(os.RelPath, Vfs[Signature])],
                            scansComplete: Int) extends State({
    case SyncActor.Events(paths) =>
      RemoteScanning(localPaths ++ paths, remotePaths, vfsArr, scansComplete)

    case SyncActor.LocalScanned(path, i, total) =>
      logger.progress(s"Scanned local path [$i/$total]", path.toString())
      RemoteScanning(localPaths ++ Seq(path), remotePaths, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Scanned(base, p, sig, i, total)) =>
      vfsArr.collectFirst{case (b, vfs) if b == base =>
        Vfs.overwriteUpdateVfs(p, sig, vfs)
      }
      logger.progress(s"Scanned remote path [$i/$total]", (base / p).toString())
      val newRemotePaths = remotePaths ++ mapping.find(_._2 == base).map(_._1 / p)
      RemoteScanning(localPaths, newRemotePaths, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Ack()) | SyncActor.LocalScanComplete() =>
      scansComplete match{
        case 0 => RemoteScanning(localPaths, remotePaths, vfsArr, scansComplete + 1)
        case 1 =>
          logger.info(
            s"Initial Scans Complete",
            s"${localPaths.size} local paths, ${remotePaths.size} remote paths"
          )
          val failures = SyncFiles.executeSync(
            mapping,
            skipper,
            signatureTransformer,
            localPaths ++ remotePaths,
            vfsArr.map(_._2),
            logger,
            (p, logged) => agentReadWriter.send(AgentReadWriteActor.Send(p, logged)),
            s => agentReadWriter.send(AgentReadWriteActor.SendChunks(s))
          )
          if (failures.nonEmpty) this.send(SyncActor.Events(failures.toSet))
          else agentReadWriter.send(AgentReadWriteActor.Send(Rpc.Complete(), "Sync Complete"))
          Waiting(vfsArr.map(_._2))
      }
  })

  case class Waiting(vfsArr: Seq[Vfs[Signature]]) extends State({
    case SyncActor.Events(paths) =>
      val failures = SyncFiles.executeSync(
        mapping,
        skipper,
        signatureTransformer,
        paths,
        vfsArr,
        logger,
        (p, logged) => agentReadWriter.send(AgentReadWriteActor.Send(p, logged)),
        s => agentReadWriter.send(AgentReadWriteActor.SendChunks(s))
      )
      if (failures.nonEmpty) this.send(SyncActor.Events(failures.toSet))
      else agentReadWriter.send(AgentReadWriteActor.Send(Rpc.Complete(), "Sync Complete"))
      Waiting(vfsArr)
    case SyncActor.Receive(Response.Ack()) => Waiting(vfsArr) // do nothing
    case SyncActor.Debounced(debounceToken2) => Waiting(vfsArr) // do nothing
  })
}

object DebounceActor{
  sealed trait Msg
  case class Paths(values: Set[os.Path]) extends Msg
  case class Trigger(count: Int) extends Msg
}

class DebounceActor(handle: Set[os.Path] => Unit,
                    statusActor: => StatusActor,
                    debounceMillis: Int)
                   (implicit ac: ActorContext)
  extends SimpleActor[DebounceActor.Msg]{
  val buffer = mutable.Set.empty[os.Path]
  def run(msg: DebounceActor.Msg) = msg match{
    case DebounceActor.Paths(values) =>
      if (values.exists(p => p.last != "index.lock")) {
        logChanges(values, if (buffer.isEmpty) "Detected" else "Ongoing")
        buffer.addAll(values)
        val count = buffer.size
        scala.concurrent.Future {
          Thread.sleep(debounceMillis)
          this.send(DebounceActor.Trigger(count))
        }
      }
    case DebounceActor.Trigger(count) =>
      if (count == buffer.size) {
        logChanges(buffer, "Syncing")
        handle(buffer.toSet)
        buffer.clear()
      }
  }
  def logChanges(paths: Iterable[os.Path], verb: String) = {
    val suffix =
      if (paths.size == 1) ""
      else s"\nand ${paths.size - 1} other files"

    statusActor.send(StatusActor.Syncing(s"$verb changes to\n${paths.head.relativeTo(os.pwd)}$suffix"))
  }
}
object StatusActor{
  sealed trait Msg
  case class Syncing(msg: String) extends Msg
  case class FilesAndBytes(files: Int, bytes: Long) extends Msg
  case class Done() extends Msg
  case class Error(msg: String) extends Msg
  case class Close() extends Msg
}
class StatusActor(agentReadWriteActor: => AgentReadWriteActor)
                 (implicit ac: ActorContext) extends BatchActor[StatusActor.Msg]{
  val Seq(blueSync, greenTick, redCross) =
    for(name <- Seq("blue-sync", "green-tick", "red-cross"))
    yield java.awt.Toolkit.getDefaultToolkit().getImage(getClass.getResource(s"/$name.png"))

  val icon = new java.awt.TrayIcon(blueSync)

  val tray = java.awt.SystemTray.getSystemTray()
  tray.add(icon)

  icon.addMouseListener(new MouseListener {
    def mouseClicked(e: MouseEvent): Unit = {
      agentReadWriteActor.send(AgentReadWriteActor.ForceRestart())
    }

    def mousePressed(e: MouseEvent): Unit = ()

    def mouseReleased(e: MouseEvent): Unit = ()

    def mouseEntered(e: MouseEvent): Unit = ()

    def mouseExited(e: MouseEvent): Unit = ()
  })

  val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())
  var image = blueSync
  var tooltip = ""
  var syncedFiles = 0
  var syncedBytes = 0L
  def runBatch(msgs: Seq[StatusActor.Msg]) = {
    val lastImage = image
    val lastTooltip = tooltip
    msgs.foreach{
      case StatusActor.Syncing(msg) =>
        image = blueSync
        tooltip = msg

      case StatusActor.FilesAndBytes(nFiles, nBytes) =>
        syncedFiles += nFiles
        syncedBytes += nBytes

      case StatusActor.Done() =>
        image = greenTick
        tooltip =
          s"Syncing Complete\n" +
          s"$syncedFiles files $syncedBytes bytes\n" +
          s"${formatter.format(java.time.Instant.now())}"

        syncedFiles = 0
        syncedBytes = 0
      case StatusActor.Error(msg) =>
        image = redCross
        tooltip = msg

      case StatusActor.Close() => tray.remove(icon)
    }

    if (lastImage != image) icon.setImage(image)
    if (lastTooltip != tooltip) icon.setToolTip(tooltip)
    Thread.sleep(100)
  }
}