package devbox.syncer

import devbox.common._
import devbox.common
import devbox.logger.SyncLogger

import scala.concurrent.Future
object SkipScanActor{
  sealed trait Msg
  case class Paths(values: PathSet) extends Msg
  case class StartScan() extends Msg
  case class ScanComplete() extends Msg
  case class LocalScanned(base: os.Path, sub: os.SubPath) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Done() extends Msg
}
class SkipScanActor(sendToSigActor: SigActor.Msg => Unit,
                    mapping: Seq[(os.Path, os.RelPath)],
                    ignoreStrategy: String)
                   (implicit ac: ActorContext,
                    logger: SyncLogger) extends StateMachineActor[SkipScanActor.Msg]{

  def initialState = Scanning(
    new PathSet(),
    new PathSet(),
    mapping.map(_ => Skipper.fromString(ignoreStrategy)),
    0
  )

  case class Scanning(buffered: PathSet,
                      initialScanned: PathSet,
                      skippers: Seq[Skipper],
                      scansComplete: Int) extends State({
    case SkipScanActor.StartScan() =>
      Future{
        common.InitialScan.initialSkippedScan(mapping.map(_._1), skippers){
          (base, sub, attrs) => this.send(SkipScanActor.LocalScanned(base, sub))
        }
      }.onComplete{ res =>
        this.send(SkipScanActor.ScanComplete())
      }
      Scanning(buffered, initialScanned, skippers, scansComplete)


    case SkipScanActor.LocalScanned(base, sub) =>
      handleLocalScanned(buffered, initialScanned, skippers, scansComplete, base, sub)

    case SkipScanActor.Receive(Response.Scanned(base, sub, sig)) =>
      val localBase = mapping.find(_._2 == base).get._1
      sendToSigActor(SigActor.RemotePath(base, sub, sig))
      handleLocalScanned(buffered, initialScanned, skippers, scansComplete, localBase, sub)

    case SkipScanActor.Receive(Response.Ack()) =>
      scanComplete(buffered, initialScanned, skippers, scansComplete)

    case SkipScanActor.Paths(values) =>
      val newBuffered = buffered.withPaths(values.walk())
      Scanning(newBuffered, initialScanned, skippers, scansComplete)

    case SkipScanActor.ScanComplete() =>
      scanComplete(buffered, initialScanned, skippers, scansComplete)

  })

  case class Idle(skippers: Seq[Skipper]) extends State({
    case SkipScanActor.Receive(Response.Ack()) => Idle(skippers)
    case SkipScanActor.Paths(values) => flushPathsDownstream(values, skippers)
  })

  case class Busy(buffered: PathSet, skippers: Seq[Skipper]) extends State({
    case SkipScanActor.Receive(Response.Ack()) => Busy(buffered, skippers)
    case SkipScanActor.Paths(values) => Busy(buffered.withPaths(values.walk()), skippers)
    case SkipScanActor.Done() => flushPathsDownstream(buffered, skippers)
  })

  def handleLocalScanned(buffered: PathSet,
                         initialScanned: PathSet,
                         skippers: Seq[Skipper],
                         scansComplete: Int,
                         base: os.Path,
                         sub: os.SubPath) = {
    val segments = (base / sub).segments
    if (!initialScanned.contains(segments)){
      sendToSigActor(SigActor.SinglePath(base, sub))
      Scanning(buffered, initialScanned.withPath(segments), skippers, scansComplete)
    }else{
      Scanning(buffered, initialScanned, skippers, scansComplete)
    }
  }

  def scanComplete(buffered: PathSet,
                   initialScanned: PathSet,
                   skippers: Seq[Skipper],
                   scansComplete: Int) = {
    scansComplete match{
      case 0 => Scanning(buffered, initialScanned, skippers, 1)
      case 1 =>
        sendToSigActor(SigActor.InitialScansComplete())
        flushPathsDownstream(buffered, skippers)
    }
  }

  def flushPathsDownstream(paths: PathSet, skippers: Seq[Skipper]) = {
    if (paths.size == 0) Idle(skippers)
    else {
      Future {
        val groups =
          for ((src, dest) <- mapping)
            yield (
              src,
              PathMap.from(
                paths
                  .walkSubPaths(src.segments)
                  .map { subSegments =>(subSegments, os.isDir(src / subSegments))}
              )
            )

        for (((src, paths), skipper) <- groups.zip(skippers))
        yield (src, skipper.batchRemoveSkippedPaths(src, paths))

      }.onComplete{ case scala.util.Success(processedGroups) =>
        sendToSigActor(SigActor.ManyPaths(processedGroups.toMap))
        this.send(SkipScanActor.Done())
      }
      Busy(new PathSet(), skippers)
    }
  }
}
