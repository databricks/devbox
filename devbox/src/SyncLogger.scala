package devbox
import java.awt.event.{MouseEvent, MouseListener}

import devbox.common.{ActorContext, BaseLogger, Logger, SimpleActor}
trait SyncLogger{
  def apply(tag: String, x: Any = Logger.NoOp): Unit
  def info(chunks: String*): Unit
  def error(chunks: String*): Unit
  def grey(chunks: String*): Unit
  def progress(chunks: String*): Unit
  def done(): Unit
  def syncingFile(prefix: String, suffix: String): Unit
  def incrementFileTotal(base: os.Path, subs: Set[os.SubPath]): Unit
  def filesAndBytes(files: Set[os.Path], bytes: Long): Unit
}
object SyncLogger{

  class Impl(val dest: String => os.Path, val rotationSize: Long, val truncate: Boolean)
            (implicit ac: ActorContext) extends BaseLogger with SyncLogger{

    var lastProgressTimestamp = 0L

    def logOut(s: String) = {}
    def apply(tag: String, x: Any = Logger.NoOp): Unit = ConsoleLogger.send(Logger.PPrinted(tag, x))

    def info(chunks: String*): Unit = {

      ConsoleLogger.send(Logger.Info(chunks))
    }
    def error(chunks: String*): Unit = {
      ConsoleLogger.send(Logger.Info(chunks))
    }
    def grey(chunks: String*): Unit = {
      ConsoleLogger.send(Logger.Info(chunks))
    }
    def progress(chunks: String*): Unit = {
      ConsoleLogger.send(Logger.Progress(chunks))
    }

    def done() = statusActor.send(StatusActor.Done())
    def filesAndBytes(files: Set[os.Path], bytes: Long) = {
      statusActor.send(StatusActor.FilesAndBytes(files, bytes))
    }
    def incrementFileTotal(base: os.Path, subs: Set[os.SubPath]) = {
      statusActor.send(StatusActor.IncrementFileTotal(base, subs))
    }
    def syncingFile(prefix: String, suffix: String) = {
      statusActor.send(StatusActor.SyncingFile(prefix, suffix))
    }
    val statusActor = new StatusActor(
      //    _ => (), _ => (),
      imageName => IconHandler.icon.setImage(IconHandler.images(imageName)),
      tooltip => IconHandler.icon.setToolTip(tooltip)
    )

    object IconHandler{

      val images = Seq("blue-sync", "green-tick", "red-cross", "grey-dash")
        .map{name => (name, java.awt.Toolkit.getDefaultToolkit().getImage(getClass.getResource(s"/$name.png")))}
        .toMap
      val icon = new java.awt.TrayIcon(images("blue-sync"))

      icon.setToolTip("Devbox Initializing")

      val tray = java.awt.SystemTray.getSystemTray()


      icon.addMouseListener(new MouseListener {
        def mouseClicked(e: MouseEvent): Unit = {
//          agentReadWriter.send(AgentReadWriteActor.ForceRestart())
        }

        def mousePressed(e: MouseEvent): Unit = ()
        def mouseReleased(e: MouseEvent): Unit = ()
        def mouseEntered(e: MouseEvent): Unit = ()
        def mouseExited(e: MouseEvent): Unit = ()
      })
    }
    object ConsoleLogger extends SimpleActor[Logger.Msg] {
      def run(msg: Logger.Msg): Unit = msg match {
        case Logger.PPrinted(tag, value) =>
          assert(tag.length <= Logger.margin)

          val msgStr =
            fansi.Color.Magenta(tag.padTo(Logger.margin, ' ')) ++ " | " ++
              pprint.apply(value, height = Int.MaxValue)

          write(msgStr.toString().replace("\n", Logger.marginStr))

        case Logger.Info(chunks) =>
          println(chunks.mkString(", "))
          lastProgressTimestamp = System.currentTimeMillis()

        case Logger.Progress(chunks) =>
          val now = System.currentTimeMillis()
          if (now - lastProgressTimestamp > 5000) {
            println(chunks.mkString(", "))
            lastProgressTimestamp = now
          }
      }
    }

  }
}