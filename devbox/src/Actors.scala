package devbox

import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{ActorContext, Bytes, Response, Rpc, RpcClient, Sig, StateMachineActor, SyncLogger, Util, Vfs}

import scala.concurrent.Future


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

  case class Active(buffer: Vector[SyncFiles.RemoteMsg]) extends State({
    case AgentReadWriteActor.Send(msg) =>
      logStatusMsgForRpc(msg)
      msg match{
        case r: SyncFiles.RemoteMsg =>
          getRpcFor(r) match{
            case None => Active(buffer)
            case Some(rpc) =>
              val newBuffer = buffer :+ r
              ac.reportSchedule()
              sendRpc(newBuffer, 0, rpc).getOrElse(Active(newBuffer))
          }

        case _ => Active(buffer)
      }

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
        else {
          if (msg == SyncFiles.Complete()) statusActor.send(StatusActor.Done())
        }

        Active(buffer.tail)
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class RestartSleeping(buffer: Vector[SyncFiles.RemoteMsg], retryCount: Int) extends State({
    case AgentReadWriteActor.Send(msg) =>
      val newMsg = msg match {
        case r: SyncFiles.RemoteMsg =>
          ac.reportSchedule()
          Some(r)
        case _ => None
      }

      RestartSleeping(buffer ++ newMsg, retryCount)

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
            getRpcFor(msg) match{
              case None => None
              case Some(rpc) => sendRpc(newBuffer, retryCount, rpc)
            }
        }

        failState.getOrElse(Active(newBuffer))
      }
    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class GivenUp(buffer: Vector[SyncFiles.RemoteMsg]) extends State({
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      logger.grey(
        "Unable to connect to devbox, gave up after 5 attempts;",
        "click on this logo to try again"
      )
      msg match{
        case r: SyncFiles.RemoteMsg => GivenUp(buffer :+ r)
        case _ => GivenUp(buffer)
      }

    case AgentReadWriteActor.ForceRestart() =>
      logger.info("Syncing Restarted", "")
      restart(buffer, 0)

    case AgentReadWriteActor.ReadFailed() => GivenUp(buffer)

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
        logger.progress("Syncing Complete", "waiting for confirmation from Devbox")
      case SyncFiles.RemoteScan(paths) =>
        logger.info("Scanning directories", paths.mkString("\n"))

      case SyncFiles.IncrementFileTotal(totalFiles, example) =>
        statusActor.send(StatusActor.IncrementFileTotal(totalFiles, example))
      case SyncFiles.StartFile(p) => statusActor.send(StatusActor.FilesAndBytes(Set(p), 0))

      case SyncFiles.RpcMsg(rpc) =>
        statusActor.send(
          StatusActor.SyncingFile("Syncing path [", s"]:\n${rpc.path}$suffix")
        )
      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>
        val chunkMsg = if (chunkCount > 1) s" chunk [$chunkIndex/$chunkCount]" else ""
        statusActor.send(
          StatusActor.SyncingFile("Syncing path [", s"]$chunkMsg:\n$subPath$suffix")
        )
    }
  }
  def getRpcFor(msg: SyncFiles.RemoteMsg): Option[Rpc] = {
    msg match{
      case SyncFiles.Complete() => Some(Rpc.Complete())
      case SyncFiles.RemoteScan(paths) => Some(Rpc.FullScan(paths))
      case SyncFiles.RpcMsg(rpc) => Some(rpc)

      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>
        val byteArr = new Array[Byte](Util.blockSize)
        val buf = ByteBuffer.wrap(byteArr)

        try {
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
            statusActor.send(StatusActor.FilesAndBytes(Set(), n))
            Some(msg)
          }
        }catch{case e: java.nio.file.NoSuchFileException =>
          None
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

  def sendRpc(buffer: Vector[SyncFiles.RemoteMsg], retryCount: Int, msg: Rpc): Option[State] = {
    try {
      client.writeMsg(msg)
      None
    } catch{ case e: java.io.IOException =>
      Some(restart(buffer, retryCount))
    }
  }

  def restart(buffer: Vector[SyncFiles.RemoteMsg], retryCount: Int): State = {

    try agent.destroy()
    catch{case e: Throwable => /*donothing*/}

    if (retryCount < 5) {
      val seconds = math.pow(2, retryCount).toInt
      logger.error(
        s"Unable to connect to devbox",
        s"trying again after $seconds seconds"
      )
      ac.scheduleMsg(
        this,
        AgentReadWriteActor.AttemptReconnect(),
        Duration.ofSeconds(seconds)
      )

      RestartSleeping(buffer, retryCount + 1)
    } else {
      logger.grey(
        "Unable to connect to devbox, gave up after 5 attempts;",
        "click on this logo to try again"
      )

      GivenUp(buffer)
    }
  }
}

object SyncActor{
  sealed trait Msg
  case class ScanComplete(vfsArr: Seq[Vfs[Sig]]) extends Msg

  case class Events(paths: Map[os.Path, Map[os.SubPath, Option[Sig]]]) extends Msg
  case class LocalScanned(scanRoot: os.Path, sub: os.SubPath, sig: Option[Sig]) extends Msg
  case class Synced() extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
  case class LocalScanComplete() extends Msg
}
class SyncActor(agentReadWriter: => AgentReadWriteActor,
                sigActor: => SigActor,
                mapping: Seq[(os.Path, os.RelPath)],
                logger: SyncLogger,
                ignoreStrategy: String,
                scheduledExecutorService: ScheduledExecutorService,
                statusActor: => StatusActor)
               (implicit ac: ActorContext)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = RemoteScanning(
    Map(),
    Map(),
    0, 0,
    mapping.map(_._2 -> new Vfs[Sig](Sig.Dir(0))),
    0
  )


  case class RemoteScanning(localPaths: Map[os.Path, Map[os.SubPath, Option[Sig]]],
                            remotePaths: Map[os.RelPath, Set[os.SubPath]],
                            localPathCount: Int,
                            remotePathCount: Int,
                            vfsArr: Seq[(os.RelPath, Vfs[Sig])],
                            scansComplete: Int) extends State({
    case SyncActor.LocalScanned(base, sub, sig) =>
      logger.progress(s"Scanning local [${localPathCount + 1}] remote [$remotePathCount]", sub.toString())
      RemoteScanning(
        Util.joinMaps2(localPaths, Map(base -> Map(sub -> sig))),
        remotePaths,
        localPathCount + 1,
        remotePathCount,
        vfsArr,
        scansComplete
      )

    case SyncActor.Events(paths) =>
      RemoteScanning(Util.joinMaps2(localPaths, paths), remotePaths, localPathCount, remotePathCount, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Scanned(base, subPath, sig)) =>
      sigActor.send(SigActor.SinglePath(mapping.find(_._2 == base).get._1, subPath))
      vfsArr.collectFirst{case (b, vfs) if b == base =>
        Vfs.overwriteUpdateVfs(subPath, sig, vfs)
      }
      logger.progress(s"Scanning local [$localPathCount] remote [${remotePathCount + 1}]", (base / subPath).toString())
      val newRemotePaths = Util.joinMaps(remotePaths, Map(base -> Set(subPath)))
      RemoteScanning(localPaths, newRemotePaths, localPathCount, remotePathCount + 1, vfsArr, scansComplete)

    case SyncActor.Receive(Response.Ack()) | SyncActor.LocalScanComplete() =>
      scansComplete match{
        case 0 => RemoteScanning(localPaths, remotePaths,localPathCount, remotePathCount,  vfsArr, scansComplete + 1)
        case 1 =>
          logger.info(
            s"Initial Scans Complete",
            s"${localPaths.size} local paths, ${remotePaths.size} remote paths"
          )

          executeSync(
            localPaths,
            vfsArr.map(_._2)
          )
      }
  })

  case class Active(vfsArr: Seq[Vfs[Sig]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths, vfsArr)
    case SyncActor.Receive(Response.Ack()) => Active(vfsArr) // do nothing
  })

  def executeSync(paths: Map[os.Path, Map[os.SubPath, Option[Sig]]], vfsArr: Seq[Vfs[Sig]]) = {
    SyncFiles.executeSync(
      mapping,
      paths,
      vfsArr,
      logger,
      m => agentReadWriter.send(AgentReadWriteActor.Send(m))
    )
    agentReadWriter.send(AgentReadWriteActor.Send(SyncFiles.Complete()))

    Active(vfsArr)
  }
}

object SigActor{
  sealed trait Msg
  case class SinglePath(scanRoot: os.Path, sub: os.SubPath) extends Msg
  case class ManyPaths(group: Map[os.Path, Set[os.SubPath]]) extends Msg
  case class LocalScanComplete() extends Msg
  case class ComputeComplete() extends Msg
}
class SigActor(sendToSyncActor: SyncActor.Msg => Unit,
               signatureTransformer: (os.SubPath, Sig) => Sig,
               logger: SyncLogger)
              (implicit ac: ActorContext) extends StateMachineActor[SigActor.Msg]{
  def initialState: State = Idle()

  case class Idle() extends State({
    case SigActor.SinglePath(base, sub) => compute(Map(base -> Set(sub)))
    case SigActor.ManyPaths(grouped) => compute(grouped)
    case SigActor.LocalScanComplete() =>
      sendToSyncActor(SyncActor.LocalScanComplete())
      Idle()
  })

  case class Busy(buffered: Map[os.Path, Set[os.SubPath]]) extends State({
    case SigActor.SinglePath(base, sub) =>
      Busy(Util.joinMaps(buffered, Map(base -> Set(sub))))

    case SigActor.ManyPaths(grouped) =>
      Busy(Util.joinMaps(buffered, grouped))

    case SigActor.ComputeComplete() =>
      if (buffered.nonEmpty) compute(buffered)
      else Idle()

    case SigActor.LocalScanComplete() =>
      sendToSyncActor(SyncActor.LocalScanComplete())
      Busy(buffered)
  })

  def compute(groups: Map[os.Path, Set[os.SubPath]]) = {
    val computeFutures =
      for((k, vs) <- groups)
      yield SyncFiles.computeSignatures(vs, k, signatureTransformer).map((k, _))

    Future.sequence(computeFutures).foreach{ results =>
      sendToSyncActor(SyncActor.Events(results.map{case (k, vs) => (k, vs.toMap)}.toMap))
      this.send(SigActor.ComputeComplete())
    }
    Busy(Map())
  }
}
