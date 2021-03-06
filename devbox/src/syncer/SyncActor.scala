package devbox.syncer

import devbox.common._
import devbox.logger.SyncLogger

import scala.concurrent.Future
object SyncActor{
  sealed trait Msg
  case class Events(paths: Map[os.Path, PathMap[Option[Sig]]]) extends Msg
  case class RemoteScanned(base: os.RelPath, sub: os.SubPath, sig: Sig) extends Msg
  case class InitialScansComplete() extends Msg
  case class Done() extends Msg
}
class SyncActor(agentActor: castor.Actor[AgentReadWriteActor.Msg],
                mapping: Seq[(os.Path, os.RelPath)])
               (implicit ac: castor.Context,
                logger: SyncLogger)
  extends castor.StateMachineActor[SyncActor.Msg]() {

  def initialState = RemoteScanning(
    Map(),
    Map(),
    0, 0,
    mapping.map(_._2 -> new Vfs[Sig](Sig.Dir(0)))
  )


  case class RemoteScanning(localPaths: Map[os.Path, PathMap[Option[Sig]]],
                            remotePaths: Map[os.RelPath, PathSet],
                            localPathCount: Int,
                            remotePathCount: Int,
                            vfsArr: Seq[(os.RelPath, Vfs[Sig])]) extends State({

    case SyncActor.Events(paths) =>
      val newLocalPathCount = localPathCount + paths.map(_._2.size).sum
      logger.progress(
        s"Scanning local [$newLocalPathCount] remote [$remotePathCount]",
        paths.head._2.walk().head.mkString("/")
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
      val newRemotePaths = Util.joinMaps3(remotePaths, Map(base -> PathSet(subPath.segments)))
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

  case class Busy(buffered: Map[os.Path, PathMap[Option[Sig]]], vfsArr: Seq[Vfs[Sig]]) extends State({
    case SyncActor.Events(paths) => Busy(Util.joinMaps2(paths, buffered), vfsArr)
    case SyncActor.Done() => executeSync(buffered, vfsArr)
  })

  def executeSync(paths: Map[os.Path, PathMap[Option[Sig]]], vfsArr: Seq[Vfs[Sig]]) = {
    if (paths.map(_._2.size).sum == 0) Idle(vfsArr)
    else {
      val async = Future {
        SyncFiles.executeSync(
          mapping,
          paths,
          vfsArr,
          logger,
          m => agentActor.send(AgentReadWriteActor.Send(m)),
          () => agentActor.send(AgentReadWriteActor.StartFile())
        )
      }
      agentActor.sendAsync(async.map(_ => AgentReadWriteActor.Send(SyncFiles.Complete())))
      this.sendAsync(async.map(_ => SyncActor.Done()))

      Busy(Map.empty, vfsArr)
    }
  }
}
