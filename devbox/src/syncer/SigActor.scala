package devbox.syncer

import java.util.concurrent.LinkedBlockingQueue

import devbox.common.{PathMap, PathSet, Sig, Util}
import devbox.logger.SyncLogger

import scala.concurrent.{ExecutionContext, Future}

class SigActor(syncActor: castor.Actor[SyncActor.Msg],
               signatureTransformer: (os.SubPath, Sig) => Sig)
              (implicit ac: castor.Context,
               logger: SyncLogger) extends castor.StateMachineActor[SigActor.Msg]{
  def initialState: State = Idle()

  val buffers = {
    val res = new LinkedBlockingQueue[Array[Byte]]()
    for(i <- 0 until 4) res.add(new Array[Byte](Util.blockSize))
    res
  }

  case class Idle() extends State({
    case SigActor.RemotePath(base, sub, sig) =>
      syncActor.send(SyncActor.RemoteScanned(base, sub, sig))
      Idle()
    case SigActor.SinglePath(base, sub) => compute(Map(base -> PathSet(sub.segments)))
    case SigActor.ManyPaths(grouped) => compute(grouped)
    case SigActor.InitialScansComplete() =>
      syncActor.send(SyncActor.InitialScansComplete())
      Idle()
  })

  case class Busy(buffered: Map[os.Path, PathSet]) extends State({
    case SigActor.RemotePath(base, sub, sig) =>
      syncActor.send(SyncActor.RemoteScanned(base, sub, sig))
      Busy(buffered)

    case SigActor.SinglePath(base, sub) =>
      Busy(Util.joinMaps3(buffered, Map(base -> PathSet(sub.segments))))

    case SigActor.ManyPaths(grouped) =>
      Busy(Util.joinMaps3(buffered, grouped))

    case SigActor.ComputeComplete() =>
      if (buffered.nonEmpty) compute(buffered)
      else Idle()

    case SigActor.InitialScansComplete() =>
      syncActor.send(SyncActor.InitialScansComplete())
      Busy(buffered)
  })

  def compute(groups: Map[os.Path, PathSet]) = {
    val computeFutures =
      for((k, vs) <- groups)
      yield SigActor.computeSignatures(vs, k, signatureTransformer, buffers).map((k, _))

    val combined = Future.sequence(computeFutures)
    this.sendAsync(combined.map(_ => SigActor.ComputeComplete()))
    syncActor.sendAsync(
      combined.map(results =>
        SyncActor.Events(
          results
            .map{case (k, vs) => (k, PathMap.from(vs.map{case (k, v) => (k.segments, v)}))}
            .toMap
        )
      )
    )

    Busy(Map())
  }
}
object SigActor{
  sealed trait Msg
  case class RemotePath(base: os.RelPath, sub: os.SubPath, sig: Sig) extends Msg
  case class SinglePath(base: os.Path, sub: os.SubPath) extends Msg
  case class ManyPaths(group: Map[os.Path, PathSet]) extends Msg
  case class InitialScansComplete() extends Msg
  case class ComputeComplete() extends Msg


  def computeSignatures(eventPaths: PathSet,
                        src: os.Path,
                        signatureTransformer: (os.SubPath, Sig) => Sig,
                        buffers: LinkedBlockingQueue[Array[Byte]])
                       (implicit ec: ExecutionContext)
  : Future[Seq[(os.SubPath, Option[Sig])]] = {

    val eventPathsLinks = eventPaths
      .walk()
      .map(p => (p, os.isLink(src / p)))
      .toVector
    // Existing under a differently-cased name counts as not existing.
    // The only way to reliably check for a mis-cased file on OS-X is
    // to list the parent folder and compare listed names
    val preListed = eventPathsLinks
      .filter(_._2)
      .map(_._1.init)
      .map(dir =>
        (
          dir,
          if (!os.isDir(src / dir, followLinks = false)) Set[String]()
          else os.list(src / dir).map(_.last).toSet
        )
      )
      .toMap

    val futures = eventPathsLinks
      .iterator
      .map{ case (sub0, isLink) =>
        Future {
          val sub = os.SubPath(sub0)
          val abs = src / sub
          val newSig = try {
            if ((isLink && !preListed(sub.segments.init).contains(sub.last)) ||
              (!isLink && !os.followLink(abs).contains(abs))) None
            else {
              val attrs = os.stat(abs, followLinks = false)
              val buffer = buffers.take()
              try Sig
                .compute(abs, buffer, attrs.fileType)
                .map(signatureTransformer(sub, _))
              finally buffers.put(buffer)
            }
          }catch{case e: Throwable => None}
          (sub, newSig)
        }
      }

    val sequenced = scala.concurrent.Future.sequence(futures.toSeq)
    sequenced
  }
}