package devbox.syncer

import devbox.common._
import devbox.logger.SyncLogger

import scala.concurrent.Future

object SyncActor{
  sealed trait Msg
  case class Events(paths: Map[os.Path, Map[os.SubPath, Option[Sig]]]) extends Msg
  case class RemoteScanned(base: os.RelPath, sub: os.SubPath, sig: Sig) extends Msg
  case class InitialScansComplete() extends Msg
  case class Done() extends Msg
}
class SyncActor(sendAgentMsg: AgentReadWriteActor.Msg => Unit,
                mapping: Seq[(os.Path, os.RelPath)])
               (implicit ac: ActorContext,
                logger: SyncLogger)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = RemoteScanning(
    Map(),
    Map(),
    0, 0,
    mapping.map(_._2 -> new Vfs[Sig](Sig.Dir(0)))
  )


  case class RemoteScanning(localPaths: Map[os.Path, Map[os.SubPath, Option[Sig]]],
                            remotePaths: Map[os.RelPath, Set[os.SubPath]],
                            localPathCount: Int,
                            remotePathCount: Int,
                            vfsArr: Seq[(os.RelPath, Vfs[Sig])]) extends State({

    case SyncActor.Events(paths) =>
      val newLocalPathCount = localPathCount + paths.map(_._2.size).sum
      logger.progress(
        s"Scanning local [$newLocalPathCount] remote [$remotePathCount]",
        paths.head._2.head._1.toString()
      )
      val joined = Util.joinMaps2(localPaths, paths)
      RemoteScanning(joined, remotePaths, newLocalPathCount, remotePathCount, vfsArr)

    case SyncActor.RemoteScanned(base, subPath, sig) =>
      vfsArr.collectFirst{case (b, vfs) if b == base =>
        Vfs.overwriteUpdateVfs(subPath, sig, vfs)
      }
      val newRemotePathCount = remotePathCount + 1
      logger.progress(
        s"Scanning local [$localPathCount] remote [$newRemotePathCount]",
        (base / subPath).toString()
      )
      val newRemotePaths = Util.joinMaps(remotePaths, Map(base -> Set(subPath)))
      RemoteScanning(localPaths, newRemotePaths, localPathCount, newRemotePathCount, vfsArr)

    case SyncActor.InitialScansComplete() =>
      logger.info(
        s"Initial Scans Complete",
        s"${localPaths.size} local paths, ${remotePaths.size} remote paths"
      )

      executeSync(
        localPaths,
        vfsArr.map(_._2)
      )
  })

  case class Idle(vfsArr: Seq[Vfs[Sig]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths, vfsArr)
  })

  case class Busy(buffered: Map[os.Path, Map[os.SubPath, Option[Sig]]], vfsArr: Seq[Vfs[Sig]]) extends State({
    case SyncActor.Events(paths) => Busy(Util.joinMaps2(paths, buffered), vfsArr)
    case SyncActor.Done() => executeSync(buffered, vfsArr)
  })

  def executeSync(paths: Map[os.Path, Map[os.SubPath, Option[Sig]]], vfsArr: Seq[Vfs[Sig]]) = {
    if (paths.forall(_._2.isEmpty)) Idle(vfsArr)
    else {
      Future {
        SyncFiles.executeSync(
          mapping,
          paths,
          vfsArr,
          logger,
          m => sendAgentMsg(AgentReadWriteActor.Send(m)),
          () => sendAgentMsg(AgentReadWriteActor.StartFile())
        )

      }.foreach{_ =>
        sendAgentMsg(AgentReadWriteActor.Send(SyncFiles.Complete()))
        this.send(SyncActor.Done())
      }

      Busy(Map.empty, vfsArr)
    }
  }
}
