package devbox
import devbox.common.{Sig, SyncLogger, ActorContext, Util, StateMachineActor}
import scala.concurrent.Future
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
