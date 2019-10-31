package devbox
import java.util.concurrent.LinkedBlockingQueue

import devbox.common.{ActorContext, Sig, StateMachineActor, SyncLogger, Util}

import scala.concurrent.{ExecutionContext, Future}

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
      yield SigActor.computeSignatures(vs, k, signatureTransformer).map((k, _))

    Future.sequence(computeFutures).foreach{ results =>
      sendToSyncActor(SyncActor.Events(results.map{case (k, vs) => (k, vs.toMap)}.toMap))
      this.send(SigActor.ComputeComplete())
    }
    Busy(Map())
  }
}
object SigActor{
  sealed trait Msg
  case class SinglePath(scanRoot: os.Path, sub: os.SubPath) extends Msg
  case class ManyPaths(group: Map[os.Path, Set[os.SubPath]]) extends Msg
  case class LocalScanComplete() extends Msg
  case class ComputeComplete() extends Msg


  def computeSignatures(eventPaths: Set[os.SubPath],
                        src: os.Path,
                        signatureTransformer: (os.SubPath, Sig) => Sig)
                       (implicit ec: ExecutionContext)
  : Future[Seq[(os.SubPath, Option[Sig])]] = {

    val eventPathsLinks = eventPaths.map(p => (p, os.isLink(src / p)))
    // Existing under a differently-cased name counts as not existing.
    // The only way to reliably check for a mis-cased file on OS-X is
    // to list the parent folder and compare listed names
    val preListed = eventPathsLinks
      .filter(_._2)
      .map(_._1 / os.up)
      .map(dir =>
        (
          dir,
          if (!os.isDir(src / dir, followLinks = false)) Set[String]()
          else os.list(src / dir).map(_.last).toSet
        )
      )
      .toMap

    val buffers = new LinkedBlockingQueue[Array[Byte]]()
    for(i <- 0 until 6) buffers.add(new Array[Byte](Util.blockSize))

    val futures = eventPathsLinks
      .iterator
      .map{ case (sub, isLink) =>
        Future {

          val abs = src / sub
          val newSig = try {
            if ((isLink && !preListed(sub / os.up).contains(sub.last)) ||
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