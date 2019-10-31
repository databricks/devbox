package devbox
import devbox.SyncActor.Msg
import devbox.common.{ActorContext, PathSet, Response, Sig, Skipper, StateMachineActor, SyncLogger}

import scala.concurrent.Future
object SkipScanActor{
  sealed trait Msg
  case class Paths(values: PathSet) extends Msg
  case class StartScan() extends Msg
  case class ScanComplete() extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
}
class SkipScanActor(mapping: Seq[(os.Path, os.RelPath)],
                    ignoreStrategy: String,
                    sendToSigActor: SigActor.Msg => Unit,
                    logger: SyncLogger)
                   (implicit ac: ActorContext) extends StateMachineActor[SkipScanActor.Msg]{

  def initialState = Scanning(
    new PathSet(),
    mapping.map(_ => Skipper.fromString(ignoreStrategy)),
    0
  )

  case class Scanning(buffered: PathSet,
                      skippers: Seq[Skipper],
                      scansComplete: Int) extends State({
    case SkipScanActor.StartScan() =>
      Future{
        common.InitialScan.initialSkippedScan(mapping.map(_._1), skippers){
          (base, sub, attrs) => sendToSigActor(SigActor.SinglePath(base, sub))
        }
      }.onComplete{ res =>
        this.send(SkipScanActor.ScanComplete())
      }
      Scanning(buffered, skippers, scansComplete)


    case SkipScanActor.Receive(Response.Scanned(base, p, sig)) =>
      val local = mapping.find(_._2 == base).get._1
      sendToSigActor(SigActor.SinglePath(local, p))
      sendToSigActor(SigActor.RemotePath(base, p, sig))
      Scanning(buffered, skippers, scansComplete)

    case SkipScanActor.Receive(Response.Ack()) => scanComplete(buffered, skippers, scansComplete)
    case SkipScanActor.Paths(values) =>
      val newBuffered = buffered.withPaths(values.walk(Nil))
      Scanning(newBuffered, skippers, scansComplete)

    case SkipScanActor.ScanComplete() =>
      if (buffered.size > 0){
        val grouped =
          for(((src, paths), skipper) <- group(buffered).zip(skippers))
          yield (src, skipper.processBatch(src, paths))

        sendToSigActor(SigActor.ManyPaths(grouped.toMap))
      }

      scanComplete(buffered, skippers, scansComplete)

  })

  case class Active(skippers: Seq[Skipper]) extends State({
    case SkipScanActor.Receive(Response.Ack()) => Active(skippers)// do nothing
    case SkipScanActor.Paths(values) =>

      val grouped =
        for(((src, paths), skipper) <- group(values).zip(skippers))
        yield (src, skipper.processBatch(src, paths))

      sendToSigActor(SigActor.ManyPaths(grouped.toMap))

      Active(skippers)
  })

  def scanComplete(buffered: PathSet, skippers: Seq[Skipper], scansComplete: Int) = {
    scansComplete match{
      case 0 => Scanning(buffered, skippers, 1)
      case 1 =>
        sendToSigActor(SigActor.InitialScansComplete())
        Active(skippers)
    }

  }
  def group(paths: PathSet) = {
    for((src, dest) <- mapping)
    yield (
      src,
      paths
        .walkSubPaths(src.segments)
        .map{ subSegments =>
          val sub = os.SubPath(subSegments)
          (sub, os.isDir(src / sub))
        }
        .toSet
    )
  }
}
