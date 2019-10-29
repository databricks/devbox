package devbox

import java.nio.ByteBuffer
import java.time.{Duration, ZoneId}
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{
  ActorContext,
  Bytes,
  Response,
  Rpc,
  RpcClient,
  Signature,
  SimpleActor,
  Skipper,
  StateMachineActor,
  SyncLogger,
  Util,
  Vfs
}


object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: SyncFiles.Msg) extends Msg
  case class ForceRestart() extends Msg
  case class ReadFailed() extends Msg
  case class AttemptReconnect() extends Msg
  case class Receive(data: Response) extends Msg
  case class Close() extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          syncer: => SyncActor,
                          statusActor: => StatusActor,
                          logger: SyncLogger)
                         (implicit ac: ActorContext)
  extends StateMachineActor[AgentReadWriteActor.Msg](){

  def initialState = Active(Vector())

  case class Active(buffer: Vector[SyncFiles.Msg]) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()

      val newBuffer = buffer :+ msg
      logStatusMsgForRpc(msg)
      sendRpcFor(newBuffer, 0, msg).getOrElse(Active(newBuffer))

    case AgentReadWriteActor.ReadFailed() =>
      restart(buffer, 0)

    case AgentReadWriteActor.ForceRestart() =>
      restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) =>
      syncer.send(SyncActor.Receive(data))

      if (!data.isInstanceOf[Response.Ack]) Active(buffer)
      else {
        ac.reportComplete()

        val msg = buffer.head
        if (buffer.tail.nonEmpty) logStatusMsgForRpc(msg, "(Complete)")
        else statusActor.send(StatusActor.Done())
        Active(buffer.tail)
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class RestartSleeping(buffer: Vector[SyncFiles.Msg], retryCount: Int) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      RestartSleeping(buffer :+ msg, retryCount)

    case AgentReadWriteActor.ReadFailed() => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.ForceRestart() => restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.AttemptReconnect() =>

      logger.info("Restarting Devbox agent", s"Attempt #$retryCount")
      val started = agent.start(s =>
        logger.info("Restarting Devbox agent", s"Attempt #$retryCount\n$s")
      )
      val startError = if (started) None else Some(restart(buffer, retryCount))

      startError.getOrElse{
        spawnReaderThread()
        val newMsg =
          if (buffer.nonEmpty) None
          else{
            ac.reportSchedule()
            Some(SyncFiles.Complete())
          }

        val newBuffer = buffer ++ newMsg
        val failState = newBuffer.foldLeft(Option.empty[State]){
          case (Some(end), _) => Some(end)
          case (None, msg) =>
            logStatusMsgForRpc(msg, "(Replaying)")
            sendRpcFor(newBuffer, retryCount, msg)
        }

        failState.getOrElse(Active(newBuffer))
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class GivenUp(buffer: Vector[SyncFiles.Msg]) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      statusActor.send(StatusActor.Greyed(
        "Unable to connect to devbox, gave up after 5 attempts;\n" +
        "click on this logo to try again"
      ))
      GivenUp(buffer :+ msg)

    case AgentReadWriteActor.ForceRestart() =>
      logger.info("Syncing Restarted", "")
      restart(buffer, 0)

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class Closed() extends State({
    case _ => Closed()
  })
  val client = new RpcClient(agent.stdin, agent.stdout, logger.apply(_, _))

  def logStatusMsgForRpc(msg: SyncFiles.Msg, suffix0: String = "") = {
    val suffix = if (suffix0 == "") "" else "\n" + suffix0
    msg match{
      case SyncFiles.Complete() =>
        logger.info("Syncing Complete", "waiting for confirmation from Devbox")
      case SyncFiles.RemoteScan(paths) =>
        logger.info("Scanning directories", paths.mkString("\n"))
      case SyncFiles.StartFile(files, totalFiles) =>
        statusActor.send(StatusActor.FilesAndBytes(files, totalFiles, 0))
      case SyncFiles.RpcMsg(rpc) =>
        StatusActor.SyncingFile("Syncing path [", s"]:\n${rpc.path}$suffix")
      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>
        val chunkMsg = if (chunkCount > 1) s" chunk [$chunkIndex/$chunkCount]" else ""
        StatusActor.SyncingFile("Syncing path [", s"]$chunkMsg:\n$subPath$suffix")
    }
  }
  def sendRpcFor(buffer: Vector[SyncFiles.Msg],
                 retryCount: Int,
                 msg: SyncFiles.Msg) = {
    msg match{
      case SyncFiles.StartFile(_, _) => sendRpc(buffer, retryCount, Rpc.Complete())
      case SyncFiles.Complete() => sendRpc(buffer, retryCount, Rpc.Complete())
      case SyncFiles.RemoteScan(paths) => sendRpc(buffer, retryCount, Rpc.FullScan(paths))
      case SyncFiles.RpcMsg(rpc) => sendRpc(buffer, retryCount, rpc)

      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>
        val byteArr = new Array[Byte](Util.blockSize)
        val buf = ByteBuffer.wrap(byteArr)

        Util.autoclose(os.read.channel(src / subPath)) { channel =>
          buf.rewind()
          channel.position(chunkIndex * Util.blockSize)
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

          val msg = Rpc.WriteChunk(
            dest,
            subPath,
            chunkIndex * Util.blockSize,
            new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr)
          )
          statusActor.send(StatusActor.FilesAndBytes(0, 0, n))
          sendRpc(buffer, retryCount, msg)
        }
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
              val s = ujson.read(str).str
              logger.apply(
                "AGENT OUT",
                new Object {
                  override def toString: String = s
                }
              )
              true
            } catch {
              case e: ujson.ParseException =>
                println(str)
                false
            }
        }
      })()
    }, "DevboxAgentLoggerThread").start()

    new Thread(() => {
      while(try{
        val response = client.readMsg[Response]()
        this.send(AgentReadWriteActor.Receive(response))
        true
      }catch{
        case e: java.io.IOException =>
          this.send(AgentReadWriteActor.ReadFailed())
          false
      })()
    }, "DevboxAgentOutputThread").start()
  }

  def sendRpc(buffer: Vector[SyncFiles.Msg], retryCount: Int, msg: Rpc): Option[State] = {
    try {
      client.writeMsg(msg)
      None
    } catch{ case e: java.io.IOException =>
      Some(restart(buffer, retryCount))
    }
  }

  def restart(buffer: Vector[SyncFiles.Msg], retryCount: Int): State = {

    try agent.destroy()
    catch{case e: Throwable => /*donothing*/}

    if (retryCount < 5) {
      val seconds = math.pow(2, retryCount).toInt
      statusActor.send(StatusActor.Error(
        s"Unable to connect to devbox, trying again after $seconds seconds"
      ))
      ac.scheduleMsg(
        this,
        AgentReadWriteActor.AttemptReconnect(),
        Duration.ofSeconds(seconds)
      )

      RestartSleeping(buffer, retryCount + 1)
    } else {
      statusActor.send(StatusActor.Greyed(
        "Unable to connect to devbox, gave up after 5 attempts;\n" +
          "click on this logo to try again"
      ))

      GivenUp(buffer)
    }
  }
}

object SyncActor{
  sealed trait Msg
  case class ScanComplete(vfsArr: Seq[Vfs[Signature]]) extends Msg

  case class Events(paths: Map[os.Path, Set[os.SubPath]]) extends Msg
  case class LocalScanned(scanRoot: os.Path, sub: os.SubPath) extends Msg
  case class Debounced(debounceId: Object) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
  case class LocalScanComplete() extends Msg
}
class SyncActor(agentReadWriter: => AgentReadWriteActor,
                mapping: Seq[(os.Path, os.RelPath)],
                logger: SyncLogger,
                signatureTransformer: (os.SubPath, Signature) => Signature,
                ignoreStrategy: String,
                scheduledExecutorService: ScheduledExecutorService,
                statusActor: => StatusActor)
               (implicit ac: ActorContext)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = RemoteScanning(
    Map(),
    Map(),
    0, 0,
    mapping.map(_._2 -> new Vfs[Signature](Signature.Dir(0))),
    0
  )

  def joinMaps[K, V](left: Map[K, Set[V]], right: Map[K, Set[V]]) = {
    (left.keySet ++ right.keySet)
      .map{k => (k, left.getOrElse(k, Set()) ++ right.getOrElse(k, Set()))}
      .toMap
  }

  case class RemoteScanning(localPaths: Map[os.Path, Set[os.SubPath]],
                            remotePaths: Map[os.RelPath, Set[os.SubPath]],
                            localPathCount: Int,
                            remotePathCount: Int,
                            vfsArr: Seq[(os.RelPath, Vfs[Signature])],
                            scansComplete: Int) extends State({
    case SyncActor.LocalScanned(base, sub) =>
      logger.progress(s"Scanning local [${localPathCount + 1}] remote [$remotePathCount]", sub.toString())
      RemoteScanning(joinMaps(localPaths, Map(base -> Set(sub))), remotePaths, localPathCount + 1, remotePathCount, vfsArr, scansComplete)

    case SyncActor.Events(paths) =>
      RemoteScanning(joinMaps(localPaths, paths), remotePaths, localPathCount, remotePathCount, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Scanned(base, subPath, sig)) =>
      vfsArr.collectFirst{case (b, vfs) if b == base =>
        Vfs.overwriteUpdateVfs(subPath, sig, vfs)
      }
      logger.progress(s"Scanning local [$localPathCount] remote [${remotePathCount + 1}]", (base / subPath).toString())
      val newRemotePaths = joinMaps(remotePaths, Map(base -> Set(subPath)))
      RemoteScanning(localPaths, newRemotePaths, localPathCount, remotePathCount + 1, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Ack()) | SyncActor.LocalScanComplete() =>
      scansComplete match{
        case 0 => RemoteScanning(localPaths, remotePaths,localPathCount, remotePathCount,  vfsArr, scansComplete + 1)
        case 1 =>
          logger.info(
            s"Initial Scans Complete",
            s"${localPaths.size} local paths, ${remotePaths.size} remote paths"
          )
          val mappingMap = mapping.map(_.swap).toMap
          executeSync(
            joinMaps(localPaths, remotePaths.map{case (k, v) => (mappingMap(k), v)}),
            vfsArr.map(_._2)
          )
      }
  })

  case class Waiting(vfsArr: Seq[Vfs[Signature]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths, vfsArr)
    case SyncActor.Receive(Response.Ack()) => Waiting(vfsArr) // do nothing
    case SyncActor.Debounced(debounceToken2) => Waiting(vfsArr) // do nothing
  })

  def executeSync(paths: Map[os.Path, Set[os.SubPath]], vfsArr: Seq[Vfs[Signature]]) = {
    SyncFiles.executeSync(
      mapping,
      signatureTransformer,
      paths,
      vfsArr,
      logger,
      m => agentReadWriter.send(AgentReadWriteActor.Send(m))
    ).map{failures =>
      if (failures.nonEmpty) this.send(SyncActor.Events(failures.reduceLeft(joinMaps(_, _))))
      else agentReadWriter.send(AgentReadWriteActor.Send(SyncFiles.Complete()))
    }
    Waiting(vfsArr)
  }
}

object SkipActor{
  sealed trait Msg
  case class Paths(values: Set[os.Path]) extends Msg
  case class Scan() extends Msg
}
class SkipActor(mapping: Seq[(os.Path, os.RelPath)],
                ignoreStrategy: String,
                sendToSyncActor: SyncActor.Msg => Unit,
                logger: SyncLogger)
               (implicit ac: ActorContext) extends SimpleActor[SkipActor.Msg]{
  val skippers = mapping.map(_ => Skipper.fromString(ignoreStrategy))
  def run(msg: SkipActor.Msg) = msg match{
    case SkipActor.Scan() =>
      common.InitialScan.initialSkippedScan(mapping.map(_._1), skippers){
        (scanRoot, sub, sig) => sendToSyncActor(SyncActor.LocalScanned(scanRoot, sub))
      }.foreach{ _ =>
        sendToSyncActor(SyncActor.LocalScanComplete())
      }

    case SkipActor.Paths(values) =>

      val grouped = for(((src, dest), skipper) <- mapping.zip(skippers)) yield (
        src,
        skipper.process(
          src,
          for{
            value <- values
            if value.startsWith(src)
          } yield (value.subRelativeTo(src), os.isDir(value))
        )
      )
      sendToSyncActor(SyncActor.Events(grouped.toMap))
  }
}

object DebounceActor{
  sealed trait Msg
  case class Paths(values: Set[os.Path]) extends Msg
  case class Trigger(count: Int) extends Msg
}

class DebounceActor(handle: Set[os.Path] => Unit,
                    debounceMillis: Int,
                    pathsFlushCount: Int,
                    logger: SyncLogger)
                   (implicit ac: ActorContext)
  extends StateMachineActor[DebounceActor.Msg]{

  def initialState: State = Idle()


  case class Idle() extends State({
    case DebounceActor.Paths(paths) =>
      logChanges(paths, "Detected")
      ac.scheduleMsg(
        this,
        DebounceActor.Trigger(paths.size),
        Duration.ofMillis(debounceMillis)
      )
      Debouncing(paths)
    case DebounceActor.Trigger(count) => Idle()
  })

  case class Debouncing(paths: Set[os.Path]) extends State({
    case DebounceActor.Paths(morePaths) =>
      logChanges(morePaths, "Ongoing")
      val allPaths = paths ++ morePaths

      ac.scheduleMsg(
        this,
        DebounceActor.Trigger(allPaths.size),
        Duration.ofMillis(debounceMillis)
      )
      Debouncing(allPaths)
    case DebounceActor.Trigger(count) =>
      if (count != paths.size && paths.size < pathsFlushCount) Debouncing(paths)
      else{
        logChanges(paths, "Syncing")
        handle(paths)
        Idle()
      }
  })
  def logChanges(paths: Iterable[os.Path], verb: String) = {
    val suffix =
      if (paths.size == 1) ""
      else s"\nand ${paths.size - 1} other files"

    logger("Debounce " + verb, paths)
    logger.info(s"$verb changes to", s"${paths.head.relativeTo(os.pwd)}$suffix")
  }
}

object StatusActor{
  sealed trait Msg
  sealed trait StatusMsg extends Msg
  case class Syncing(msg: String) extends StatusMsg
  case class SyncingFile(prefix: String, suffix: String) extends StatusMsg
  case class FilesAndBytes(files: Int, totalFiles: Int, bytes: Long) extends Msg
  case class Done() extends StatusMsg
  case class Error(msg: String) extends StatusMsg
  case class Greyed(msg: String) extends StatusMsg
  case class Debounce() extends Msg
}
class StatusActor(setImage: String => Unit,
                  setTooltip: String => Unit,
                  logger: SyncLogger)
                 (implicit ac: ActorContext) extends StateMachineActor[StatusActor.Msg]{



  def initialState = StatusState(
    IconState("blue-tick", "Devbox initializing"),
    DebounceIdle(),
    0,
    0,
    0
  )
  case class IconState(image: String, tooltip: String)

  sealed trait DebounceState
  case class DebounceIdle() extends DebounceState
  case class DebounceCooldown() extends DebounceState
  case class DebounceFull(value: StatusActor.StatusMsg) extends DebounceState
  case class StatusState(icon: IconState,
                         debounced: DebounceState,
                         syncFiles: Int,
                         totalFiles: Int,
                         syncBytes: Long) extends State{
    override def run = {
      case msg: StatusActor.Syncing => debounceReceive(msg)
      case msg: StatusActor.Error => debounceReceive(msg)
      case msg: StatusActor.Greyed => debounceReceive(msg)

      case msg: StatusActor.Done =>
        val Array(header, body) = syncCompleteMsg(syncFiles, syncBytes).split("\n", 2)
        logger.info(header, body)
        debounceReceive(msg)

      case msg: StatusActor.SyncingFile =>
        val Array(header, body) = s"${msg.prefix}$syncFiles/$totalFiles${msg.suffix}".split("\n", 2)
        logger.progress(header, body)
        debounceReceive(msg)

      case StatusActor.FilesAndBytes(nFiles, nTotalFiles, nBytes) =>
        this.copy(
          syncFiles = syncFiles + nFiles,
          totalFiles = totalFiles + nTotalFiles,
          syncBytes = syncBytes + nBytes
        )

      case StatusActor.Debounce() =>
        debounced match{
          case DebounceFull(n) => statusMsgToState(DebounceIdle(), n)
          case ds => this.copy(debounced = DebounceIdle())
        }
    }

    def debounceReceive(statusMsg: StatusActor.StatusMsg): State = {
      if (debounced == DebounceIdle()) {
        ac.scheduleMsg(StatusActor.this, StatusActor.Debounce(), Duration.ofMillis(100))
        statusMsgToState(DebounceCooldown(), statusMsg)
      } else {
        StatusState(icon, DebounceFull(statusMsg), syncFiles, totalFiles, syncBytes)
      }
    }

    def statusMsgToState(newDebounced: DebounceState,
                         statusMsg: StatusActor.StatusMsg): StatusState = {
      val statusState = statusMsg match {
        case StatusActor.Syncing(msg) => this.copy(icon = IconState("blue-sync", msg))
        case StatusActor.Error(msg) => this.copy(icon = IconState("red-cross", msg))
        case StatusActor.Greyed(msg) => this.copy(icon = IconState("grey-dash", msg))

        case StatusActor.SyncingFile(prefix, suffix) =>
          this.copy(icon = IconState("blue-sync", s"$prefix$syncFiles/$totalFiles$suffix"))

        case StatusActor.Done() =>
          StatusState(IconState("green-tick", syncCompleteMsg(syncFiles, syncBytes)), newDebounced, 0, 0, 0)
      }

      setIcon(icon, statusState.icon)
      statusState.copy(debounced = newDebounced)
    }
  }

  def syncCompleteMsg(syncFiles: Int, syncBytes: Long) = {
    s"Syncing Complete\n" +
    s"${Util.formatInt(syncFiles)} files ${Util.readableBytesSize(syncBytes)}\n" +
    s"${Util.timeFormatter.format(java.time.Instant.now())}"
  }


  def setIcon(icon: IconState, nextIcon: IconState) = {
    if (icon.image != nextIcon.image) setImage(nextIcon.image)
    if (icon.tooltip != nextIcon.tooltip) setTooltip(nextIcon.tooltip)
  }
}