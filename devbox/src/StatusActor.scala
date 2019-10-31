package devbox
import devbox.common.{SyncLogger, ActorContext, PathSet, StateMachineActor, Util}
import java.time.Duration
object StatusActor{
  sealed trait Msg
  sealed trait StatusMsg extends Msg
  case class Syncing(msg: String) extends StatusMsg
  case class SyncingFile(prefix: String, suffix: String) extends StatusMsg
  case class IncrementFileTotal(base: os.Path, subs: Set[os.SubPath]) extends Msg
  case class FilesAndBytes(files: Set[os.Path], bytes: Long) extends Msg
  case class Done() extends StatusMsg
  case class Error(msg: String) extends StatusMsg
  case class Greyed(msg: String) extends StatusMsg
  case class Debounce() extends Msg
}
class StatusActor(setImage: String => Unit,
                  setTooltip: String => Unit,
                  logger: SyncLogger)
                 (implicit ac: ActorContext) extends StateMachineActor[StatusActor.Msg]{



  def initialState = StatusState(
    IconState("blue-tick", "Devbox initializing"),
    DebounceIdle(),
    new PathSet(),
    new PathSet(),
    0
  )
  case class IconState(image: String, tooltip: String)

  sealed trait DebounceState
  case class DebounceIdle() extends DebounceState
  case class DebounceCooldown() extends DebounceState
  case class DebounceFull(value: StatusActor.StatusMsg) extends DebounceState
  case class StatusState(icon: IconState,
                         debounced: DebounceState,
                         syncFiles: PathSet,
                         totalFiles: PathSet,
                         syncBytes: Long) extends State{
    override def run = {
      case msg: StatusActor.Syncing => debounceReceive(msg)
      case msg: StatusActor.Error => debounceReceive(msg)
      case msg: StatusActor.Greyed => debounceReceive(msg)

      case msg: StatusActor.Done =>

        logger.progress(syncCompleteMsg(syncFiles, syncBytes).split("\n"):_*)
        debounceReceive(msg)

      case msg: StatusActor.SyncingFile =>

        logger.progress(s"${msg.prefix}${syncFiles.size}/${totalFiles.size}${msg.suffix}".split("\n"):_*)
        debounceReceive(msg)

      case StatusActor.IncrementFileTotal(base, subs) =>

        val newTotalFiles = totalFiles.withPaths(subs.map(s => (base / s).segments))
        logger.info(s"${newTotalFiles.size} paths changed", subs.head.toString())
        this.copy(totalFiles = newTotalFiles)


      case StatusActor.FilesAndBytes(nFiles, nBytes) =>
        this.copy(
          syncBytes = syncBytes + nBytes,
          syncFiles = syncFiles.withPaths(nFiles.map(_.segments))
        )

      case StatusActor.Debounce() =>
        debounced match{
          case DebounceFull(n) => statusMsgToState(DebounceIdle(), n)
          case ds => this.copy(debounced = DebounceIdle())
        }
    }

    def debounceReceive(statusMsg: StatusActor.StatusMsg): State = {
      if (debounced == DebounceIdle()) {
        ac.scheduleMsg(StatusActor.this, StatusActor.Debounce(), Duration.ofMillis(100))
        statusMsgToState(DebounceCooldown(), statusMsg)
      } else {
        StatusState(icon, DebounceFull(statusMsg), syncFiles, totalFiles, syncBytes)
      }
    }

    def statusMsgToState(newDebounced: DebounceState,
                         statusMsg: StatusActor.StatusMsg): StatusState = {
      val statusState = statusMsg match {
        case StatusActor.Syncing(msg) => this.copy(icon = IconState("blue-sync", msg))
        case StatusActor.Error(msg) => this.copy(icon = IconState("red-cross", msg))
        case StatusActor.Greyed(msg) => this.copy(icon = IconState("grey-dash", msg))

        case StatusActor.SyncingFile(prefix, suffix) =>
          this.copy(icon = IconState("blue-sync", s"$prefix${syncFiles.size}/${totalFiles.size}$suffix"))

        case StatusActor.Done() =>
          StatusState(
            IconState("green-tick", syncCompleteMsg(syncFiles, syncBytes)),
            newDebounced, new PathSet(), new PathSet(), 0
          )
      }

      setIcon(icon, statusState.icon)

      statusState.copy(debounced = newDebounced)
    }
  }

  def syncCompleteMsg(syncFiles: PathSet, syncBytes: Long) = {
    s"Syncing Complete\n" +
    s"${Util.formatInt(syncFiles.size)} files ${Util.readableBytesSize(syncBytes)}\n" +
    s"${Util.timeFormatter.format(java.time.Instant.now())}"
  }


  def setIcon(icon: IconState, nextIcon: IconState) = {
    if (icon.image != nextIcon.image) setImage(nextIcon.image)
    if (icon.tooltip != nextIcon.tooltip) setTooltip(nextIcon.tooltip)
  }
}