package devbox

import java.awt.event.{ActionEvent, ActionListener, MouseEvent, MouseListener}
import java.io.{PrintWriter, StringWriter}
import java.nio.ByteBuffer
import java.time.ZoneId
import java.time.format.{DateTimeFormatter, FormatStyle}
import java.util.concurrent.ScheduledExecutorService

import devbox.common.SyncLogger
import devbox.common.{Bytes, Logger, Response, Rpc, RpcClient, Signature, Skipper, Util, Vfs}

import scala.collection.mutable
import devbox.common.{ActorContext, BatchActor, StateMachineActor}

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
      statusActor.send(StatusActor.Syncing(msg.logged))
      val newBuffer = buffer :+ msg
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
        statusActor.send(
          if (buffer.tail.nonEmpty) StatusActor.Syncing(msg.logged + "\n" + "(Complete)")
          else StatusActor.Done()
        )
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

      statusActor.send(StatusActor.Syncing(s"Restarting Devbox agent\nAttempt #$retryCount"))
      val started = agent.start(s =>
        statusActor.send(StatusActor.Syncing(
          s"Restarting Devbox agent\nAttempt #$retryCount\n$s"
        ))
      )
      val startError = if (started) None else Some(restart(buffer, retryCount))

      startError.getOrElse{
        spawnReaderThread()
        val newMsg =
          if (buffer.nonEmpty) None
          else{
            ac.reportSchedule()
            Some(SyncFiles.RpcMsg(Rpc.Complete(), "Re-connection Re-sync"))
          }

        val newBuffer = buffer ++ newMsg
        val failState = newBuffer.foldLeft(Option.empty[State]){
          case (Some(end), _) => Some(end)
          case (None, msg) => sendRpcFor(newBuffer, retryCount, msg)
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
      statusActor.send(StatusActor.Syncing("Syncing Restarted"))
      restart(buffer, 0)

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class Closed() extends State({
    case _ => Closed()
  })
  val client = new RpcClient(agent.stdin, agent.stdout, logger.apply(_, _))

  def sendRpcFor(buffer: Vector[SyncFiles.Msg],
                 retryCount: Int,
                 msg: SyncFiles.Msg) = msg match{
    case SyncFiles.RpcMsg(rpc, logged) => sendRpc(buffer, retryCount, rpc)
    case SyncFiles.SendChunkMsg(p, dest, segments, chunkIndex, logged) =>
      val byteArr = new Array[Byte](Util.blockSize)
      val buf = ByteBuffer.wrap(byteArr)

      Util.autoclose(os.read.channel(p)) { channel =>
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
          segments,
          chunkIndex * Util.blockSize,
          new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr)
        )
        statusActor.send(StatusActor.FilesAndBytes(1, n))
        sendRpc(buffer, retryCount, msg)
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
              logger.apply(
                "AGENT OUT",
                new Object {
                  override def toString: String = ujson.read(str).str
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
    catch{case e: Throwable =>
      e.printStackTrace()
      /*donothing*/
    }

    if (retryCount < 5) {
      val seconds = math.pow(2, retryCount).toInt
      statusActor.send(StatusActor.Error(
        s"Unable to connect to devbox, trying again after $seconds seconds"
      ))
      scala.concurrent.Future{
        Thread.sleep(1000 * seconds)
        this.send(AgentReadWriteActor.AttemptReconnect())
      }

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
                logger: SyncLogger,
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
          SyncFiles.RpcMsg(
            Rpc.FullScan(mapping.map(_._2)),
            "Remote Scanning " + mapping.map(_._2).mkString(", ")
          )
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
            m => agentReadWriter.send(AgentReadWriteActor.Send(m))
          )
          if (failures.nonEmpty) this.send(SyncActor.Events(failures.toSet))
          else agentReadWriter.send(
            AgentReadWriteActor.Send(
              SyncFiles.RpcMsg(
                Rpc.Complete(),
                "Sync Complete\nwaiting for confirmation from Devbox"
              )
            )
          )
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
        m => agentReadWriter.send(AgentReadWriteActor.Send(m))
      )
      if (failures.nonEmpty) this.send(SyncActor.Events(failures.toSet))
      else agentReadWriter.send(
        AgentReadWriteActor.Send(
          SyncFiles.RpcMsg(
            Rpc.Complete(),
            "Sync Complete\nwaiting for confirmation from Devbox"
          )
        )
      )
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
                    debounceMillis: Int,
                    logger: SyncLogger)
                   (implicit ac: ActorContext)
  extends StateMachineActor[DebounceActor.Msg]{

  def initialState: State = Idle()


  case class Idle() extends State({
    case DebounceActor.Paths(paths) =>
      if (!paths.exists(p => p.last != "index.lock")) Idle()
      else {
        logChanges(paths, "Detected")
        val count = paths.size
        scala.concurrent.Future {
          Thread.sleep(debounceMillis)
          this.send(DebounceActor.Trigger(count))
        }
        Debouncing(paths)
      }
    case DebounceActor.Trigger(count) => Idle()
  })

  case class Debouncing(paths: Set[os.Path]) extends State({
    case DebounceActor.Paths(morePaths) =>
      logChanges(morePaths, "Ongoing")
      val allPaths = paths ++ morePaths
      val count = allPaths.size
      scala.concurrent.Future {
        Thread.sleep(debounceMillis)
        this.send(DebounceActor.Trigger(count))
      }
      Debouncing(allPaths)
    case DebounceActor.Trigger(count) =>
      if (count != paths.size) Debouncing(paths)
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
    statusActor.send(StatusActor.Syncing(s"$verb changes to\n${paths.head.relativeTo(os.pwd)}$suffix"))
  }
}

object StatusActor{
  sealed trait Msg
  case class Syncing(msg: String) extends Msg
  case class FilesAndBytes(files: Int, bytes: Long) extends Msg
  case class Done() extends Msg
  case class Error(msg: String) extends Msg
  case class Greyed(msg: String) extends Msg
  case class Debounce() extends Msg
}
class StatusActor(setImage: String => Unit,
                  setTooltip: String => Unit)
                 (implicit ac: ActorContext) extends StateMachineActor[StatusActor.Msg]{

  val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

  def initialState = StatusState(IconState("blue-tick", "Devbox initializing"), None, 0, 0)
  case class IconState(image: String, tooltip: String)

  case class StatusState(icon: IconState,
                         debouncedNextIcon: Option[IconState],
                         syncFiles: Int,
                         syncBytes: Long) extends State({
    case StatusActor.Syncing(msg) =>
      debounce(icon, debouncedNextIcon.isDefined, IconState("blue-sync", msg), syncFiles, syncBytes)

    case StatusActor.FilesAndBytes(nFiles, nBytes) =>
      StatusState(icon, debouncedNextIcon, syncFiles + nFiles, syncBytes + nBytes)

    case StatusActor.Done() =>
      debounce(
        icon,
        debouncedNextIcon.isDefined,
        IconState("green-tick", syncCompleteMsg(syncFiles, syncBytes)),
        syncFiles = 0,
        syncBytes = 0
      )

    case StatusActor.Error(msg) =>
      debounce(icon, debouncedNextIcon.isDefined, IconState("red-cross", msg), syncFiles, syncBytes)

    case StatusActor.Greyed(msg) =>
      debounce(icon, debouncedNextIcon.isDefined, IconState("grey-dash", msg), syncFiles, syncBytes)

    case StatusActor.Debounce() =>
      setIcon(icon, debouncedNextIcon.get)
      StatusState(debouncedNextIcon.get, None, syncFiles, syncBytes)
  })

  def syncCompleteMsg(syncFiles: Int, syncBytes: Long) = {
    s"Syncing Complete\n" +
    s"$syncFiles files $syncBytes bytes\n" +
    s"${formatter.format(java.time.Instant.now())}"
  }

  def debounce(icon: IconState,
               debouncing: Boolean,
               nextIcon: IconState,
               syncFiles: Int,
               syncBytes: Long): State = {
    if (debouncing) StatusState(icon, Some(nextIcon), syncFiles, syncBytes)
    else{
      setIcon(icon, nextIcon)
      if (icon == nextIcon) StatusState(icon, None, syncFiles, syncBytes)
      else {
        scala.concurrent.Future{
          Thread.sleep(100)
          this.send(StatusActor.Debounce())
        }
        StatusState(nextIcon, Some(nextIcon), syncFiles, syncBytes)
      }
    }
  }

  def setIcon(icon: IconState, nextIcon: IconState) = {
    if (icon.image != nextIcon.image) setImage(nextIcon.image)
    if (icon.tooltip != nextIcon.tooltip) setTooltip(nextIcon.tooltip)
  }
}