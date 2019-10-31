package devbox.logger

import java.time.Duration

import devbox.common.{ActorContext, PathSet, StateMachineActor, Util}
object StatusActor{
  sealed trait Msg
  case class SetIcon(iconName: String, msg: Seq[String]) extends Msg
  case class Debounce() extends Msg
}

class StatusActor(setImage: String => Unit,
                  setTooltip: String => Unit)
                 (implicit ac: ActorContext) extends StateMachineActor[StatusActor.Msg]{

  def initialState = StatusState(
    StatusActor.SetIcon("blue-tick", Seq("Devbox initializing")),
    DebounceIdle(),
  )

  sealed trait DebounceState
  case class DebounceIdle() extends DebounceState
  case class DebounceCooldown() extends DebounceState
  case class DebounceFull(value: StatusActor.SetIcon) extends DebounceState

  case class StatusState(icon: StatusActor.SetIcon,
                         debounced: DebounceState) extends State{
    override def run = {
      case msg: StatusActor.SetIcon => debounceReceive(msg)
      case StatusActor.Debounce() =>
        debounced match{
          case DebounceFull(n) => statusMsgToState(DebounceIdle(), n)
          case ds => this.copy(debounced = DebounceIdle())
        }
    }

    def debounceReceive(statusMsg: StatusActor.SetIcon): State = {
      if (debounced == DebounceIdle()) {
        ac.scheduleMsg(StatusActor.this, StatusActor.Debounce(), Duration.ofMillis(100))
        statusMsgToState(DebounceCooldown(), statusMsg)
      } else {
        StatusState(icon, DebounceFull(statusMsg))
      }
    }

    def statusMsgToState(newDebounced: DebounceState,
                         statusMsg: StatusActor.SetIcon): StatusState = {
      setIcon(icon, statusMsg)

      this.copy(debounced = newDebounced, icon = statusMsg)
    }
  }

  def syncCompleteMsg(syncFiles: PathSet, syncBytes: Long) = Seq(
    s"Syncing Complete",
    s"${Util.formatInt(syncFiles.size)} files ${Util.readableBytesSize(syncBytes)}",
    s"${Util.timeFormatter.format(java.time.Instant.now())}"
  )


  def setIcon(icon: StatusActor.SetIcon, nextIcon: StatusActor.SetIcon) = {
    if (icon.iconName != nextIcon.iconName) setImage(nextIcon.iconName)
    if (icon.msg != nextIcon.msg) setTooltip(nextIcon.msg.mkString("\n"))
  }
}