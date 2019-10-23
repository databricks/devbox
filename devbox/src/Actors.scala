package devbox

import java.awt.event.{ActionEvent, ActionListener, MouseEvent, MouseListener}
import java.io.{PrintWriter, StringWriter}
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{Logger, Response, Rpc, Signature, Skipper, Util, Vfs}

import scala.collection.mutable


object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: Rpc, logged: Option[String]) extends Msg
  case class ForceRestart() extends Msg
  case class Restarted() extends Msg
  case class ReadRestarted() extends Msg
  case class Receive(data: Array[Byte]) extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          syncer: => SyncActor,
                          statusActor: => StatusActor)
                         (implicit ac: ActorContext)
  extends SimpleActor[AgentReadWriteActor.Msg](){
  private val buffer = mutable.ArrayDeque.empty[(Rpc, Option[String])]

  var sending = true
  var retryCount = 0

  def run(item: AgentReadWriteActor.Msg): Unit = item match{
    case AgentReadWriteActor.Send(msg, logged) =>
      ac.reportSchedule()
      if (logged.isDefined && buffer.isEmpty) {
        statusActor.send(StatusActor.Syncing(logged.get))
      }

      buffer.append((msg, logged))

      if (sending) sendRpcs(Seq(msg))

    case AgentReadWriteActor.ForceRestart() =>
      if (sending){
        retryCount = 0
        restart()
      }

    case AgentReadWriteActor.ReadRestarted() =>
      if (sending){
        if (retryCount < 5) restart()
        else statusActor.send(StatusActor.Error())
      }

    case AgentReadWriteActor.Restarted() =>
      if (!sending){

        sending = true
        spawnReaderThread(
          agent,
          buf => this.send(AgentReadWriteActor.Receive(buf)),
          () => this.send(AgentReadWriteActor.ReadRestarted())
        )
        sendRpcs(buffer.map(_._1).toSeq)
      }


    case AgentReadWriteActor.Receive(data) =>
      retryCount = 0
      val deserialized = upickle.default.readBinary[Response](data)
      syncer.send(SyncActor.Receive(deserialized))

      if (deserialized.isInstanceOf[Response.Ack]) {
        ac.reportComplete()
        buffer.dropInPlace(1)
        if (buffer.isEmpty) statusActor.send(StatusActor.Done())
        else {
          for(logged <- buffer.head._2) statusActor.send(StatusActor.Syncing(logged))
        }
      }
  }

  def spawnReaderThread(agent: AgentApi, out: Array[Byte] => Unit, restart: () => Unit) = {
    new Thread(() => {
      while(try{
        val s = agent.stdout.readBoolean()
        val n = agent.stdout.readInt()
        val buf = new Array[Byte](n)
        agent.stdout.readFully(buf)
        out(buf)
        true
      }catch{
        case e: java.io.EOFException =>
          restart()
          false
      })()
    }).start()
  }



  def sendRpcs(msgs: Seq[Rpc]) = {
    try {
      for(msg <- msgs){
        val blob = upickle.default.writeBinary(msg)
        agent.stdin.writeBoolean(true)
        agent.stdin.writeInt(blob.length)
        agent.stdin.write(blob)
        agent.stdin.flush()
      }
    }catch{ case e: java.io.IOException =>
      restart()
    }
  }

  def restart() = {
    retryCount += 1
    agent.destroy()
    agent.start()
    sending = false
    this.send(AgentReadWriteActor.Restarted())
  }
}

object SyncActor{
  sealed trait Msg
  case class Scan() extends Msg
  case class ScanComplete(vfsArr: Seq[Vfs[Signature]]) extends Msg

  case class Events(paths: Set[os.Path]) extends Msg
  case class Debounced(debounceId: Object) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
}
class SyncActor(skipArr: Array[(os.Path, Boolean) => Boolean],
                agentReadWriter: => AgentReadWriteActor,
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
        AgentReadWriteActor.Send(Rpc.FullScan(mapping.map(_._2)), None)
      )
      val pathStream = for {
        ((src, dest), i) <- geny.Generator.from(mapping.zipWithIndex)
        (p, attrs) <- os.walk.stream.attrs(
          src,
          (p, attrs) => skipArr(i)(p, attrs.isDir),
          includeTarget = true
        )
      } yield p
      RemoteScanning(
        pathStream.toSet,
        mapping.map(_._2 -> new Vfs[Signature](Signature.Dir(0)))
      )
  })

  case class RemoteScanning(changedPaths: Set[os.Path],
                            vfsArr: Seq[(os.RelPath, Vfs[Signature])]) extends State({
    case SyncActor.Events(paths) => RemoteScanning(changedPaths ++ paths, vfsArr)

    case SyncActor.Receive(Response.Scanned(base, p, sig)) =>
      vfsArr.collectFirst{case (b, vfs) if b == base => Vfs.updateVfs(p, sig, vfs)}
      logger.progress("Scanned remote path", (base / p).toString())
      RemoteScanning(changedPaths ++ mapping.find(_._2 == base).map(_._1 / p), vfsArr)

    case SyncActor.Receive(Response.Ack()) =>
      executeSync(changedPaths, vfsArr.map(_._2))
  })

  case class Waiting(vfsArr: Seq[Vfs[Signature]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths, vfsArr)
    case SyncActor.Receive(Response.Ack()) => Waiting(vfsArr) // do nothing
    case SyncActor.Debounced(debounceToken2) => Waiting(vfsArr) // do nothing
  })


  def executeSync(changedPaths: Set[os.Path], vfsArr: Seq[Vfs[Signature]]): State = {
    val buffer = new Array[Byte](Util.blockSize)

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    val allEventPaths = changedPaths.toSeq.sorted
    logger("SYNC EVENTS", allEventPaths)

    val failed = mutable.Set.empty[os.Path]
    for (((src, dest), i) <- mapping.zipWithIndex) {
      val eventPaths = allEventPaths.filter(p =>
        p.startsWith(src) && !skipArr(i)(p, true)
      )

      logger("SYNC BASE", eventPaths.map(_.relativeTo(src).toString()))

      val exitCode = for {
        _ <- if (eventPaths.isEmpty) Left(SyncFiles.NoOp: SyncFiles.ExitCode) else Right(())
        _ <- SyncFiles.updateSkipPredicate(
          eventPaths, skipper, vfsArr(i), src, buffer, logger,
          signatureTransformer, skipArr(i) = _
        )
        res <- SyncFiles.synchronizeRepo(
          logger, vfsArr(i), skipArr(i), src, dest,
          buffer, eventPaths, signatureTransformer,
          (p, logged) => agentReadWriter.send(AgentReadWriteActor.Send(p, Some(logged)))
        )
      } yield res

      exitCode match {
        case Right((streamedByteCount, changedPaths)) =>
          statusActor.send(StatusActor.FilesAndBytes(changedPaths.length, streamedByteCount))
        case Left(SyncFiles.NoOp) => // do nothing
        case Left(SyncFiles.SyncFail(value)) =>
          val x = new StringWriter()
          val p = new PrintWriter(x)
          value.printStackTrace(p)
          logger("SYNC FAILED", x.toString)
          failed.addAll(eventPaths)
      }
    }

    if (failed.nonEmpty) this.send(SyncActor.Events(failed.toSet))
    Waiting(vfsArr)
  }
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
      logChanges(values, if (buffer.isEmpty) "Detected" else "Ongoing")
      buffer.addAll(values)
      val count = buffer.size
      scala.concurrent.Future{
        Thread.sleep(debounceMillis)
        this.send(DebounceActor.Trigger(count))
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
  case class Error() extends Msg
  case class Close() extends Msg
}
class StatusActor(agentReadWriteActor: => AgentReadWriteActor)
                 (implicit ac: ActorContext) extends SimpleActor[StatusActor.Msg]{
  val Seq(blueSync, greenTick, redCross) =
    for(name <- Seq("blue-sync", "green-tick", "red-cross"))
    yield java.awt.Toolkit.getDefaultToolkit().getImage(getClass.getResource(s"/$name.png"))

  val icon = new java.awt.TrayIcon(blueSync)

  java.awt.SystemTray.getSystemTray().add(icon)

  icon.addMouseListener(new MouseListener {
    def mouseClicked(e: MouseEvent): Unit = {
      agentReadWriteActor.send(AgentReadWriteActor.ForceRestart())
    }

    def mousePressed(e: MouseEvent): Unit = ()

    def mouseReleased(e: MouseEvent): Unit = ()

    def mouseEntered(e: MouseEvent): Unit = ()

    def mouseExited(e: MouseEvent): Unit = ()
  })

  var lastIcon = blueSync
  var syncedFiles = 0
  var syncedBytes = 0L
  def run(msg: StatusActor.Msg) = msg match{
    case StatusActor.Syncing(msg) =>
      if (lastIcon ne blueSync) icon.setImage(blueSync)
      lastIcon = blueSync
      icon.setToolTip(msg)

    case StatusActor.FilesAndBytes(nFiles, nBytes) =>
      syncedFiles += nFiles
      syncedBytes += nBytes

    case StatusActor.Done() =>
      if (lastIcon ne greenTick) {
        icon.setImage(greenTick)
        lastIcon = greenTick
        icon.setToolTip(
          s"Syncing Complete\n$syncedFiles files $syncedBytes bytes\n${java.time.Instant.now()}"
        )
        syncedFiles = 0
        syncedBytes = 0
      }
    case StatusActor.Error() =>
      if (lastIcon ne redCross) icon.setImage(redCross)
      lastIcon = redCross
      icon.setToolTip(
        "Unable to connect to devbox, gave up after 5 attempts;\n" +
        "click on this logo to try again"
      )
    case StatusActor.Close() => java.awt.SystemTray.getSystemTray().remove(icon)
  }
}