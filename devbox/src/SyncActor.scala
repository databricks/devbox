package devbox
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{ActorContext, Response, Sig, StateMachineActor, SyncLogger, Util, Vfs}

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
