package devbox
import devbox.common.{PathSet, SyncLogger, ActorContext, StateMachineActor, Skipper}
object SkipActor{
  sealed trait Msg
  case class Paths(values: PathSet) extends Msg
  case class Scan() extends Msg
  case class Scanned() extends Msg
}
class SkipActor(mapping: Seq[(os.Path, os.RelPath)],
                ignoreStrategy: String,
                sendToSigActor: SigActor.Msg => Unit,
                logger: SyncLogger)
               (implicit ac: ActorContext) extends StateMachineActor[SkipActor.Msg]{

  def initialState = Scanning(new PathSet(), mapping.map(_ => Skipper.fromString(ignoreStrategy)))

  case class Scanning(buffered: PathSet, skippers: Seq[Skipper]) extends State({
    case SkipActor.Scan() =>
      common.InitialScan.initialSkippedScan(mapping.map(_._1), skippers){
        (scanRoot, sub, sig) => sendToSigActor(SigActor.SinglePath(scanRoot, sub))
      }
      this.send(SkipActor.Scanned())
      Scanning(buffered, skippers)

    case SkipActor.Paths(values) =>
      val newBuffered = buffered.withPaths(values.walk(Nil))
      Scanning(newBuffered, skippers)

    case SkipActor.Scanned() =>
      if (buffered.size > 0){
        val grouped =
          for(((src, paths), skipper) <- group(buffered).zip(skippers))
          yield (src, skipper.processBatch(src, paths))

        sendToSigActor(SigActor.ManyPaths(grouped.toMap))
      }
      sendToSigActor(SigActor.LocalScanComplete())
      Active(skippers)
  })

  case class Active(skippers: Seq[Skipper]) extends State({
    case SkipActor.Paths(values) =>

      val grouped =
        for(((src, paths), skipper) <- group(values).zip(skippers))
        yield (src, skipper.processBatch(src, paths))

      sendToSigActor(SigActor.ManyPaths(grouped.toMap))

      Active(skippers)
  })

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
