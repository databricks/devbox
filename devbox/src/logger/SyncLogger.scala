package devbox.logger

import java.awt.event.{MouseEvent, MouseListener}

import devbox.common.{Actor, ActorContext, BaseLogger, Logger, SimpleActor}
trait SyncLogger{
  def init(): Unit
  def close(): Unit
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

  class Impl(val dest: String => os.Path,
             val rotationSize: Long,
             val truncate: Boolean,
             onClick: => Actor[Unit])
            (implicit ac: ActorContext) extends SyncLogger{

    def logOut(s: String) = {}
    def init() = {
      IconHandler.tray.add(IconHandler.icon)
    }

    override def close() = {

      pprint.log(ac.getActive)
      consoleLogger.close()
      IconHandler.tray.remove(IconHandler.icon)
    }

    def apply(tag: String, x: Any = Logger.NoOp): Unit = {
      consoleLogger.send(Logger.PPrinted(tag, x))
    }

    def info(chunks: String*): Unit = {
      statusActor.send(StatusActor.Syncing(chunks.mkString("\n")))
      consoleLogger.send(Logger.Info(chunks))
    }
    def error(chunks: String*): Unit = {
      statusActor.send(StatusActor.Error(chunks.mkString("\n")))
      consoleLogger.send(Logger.Info(chunks))
    }
    def grey(chunks: String*): Unit = {
      statusActor.send(StatusActor.Greyed(chunks.mkString("\n")))
      consoleLogger.send(Logger.Info(chunks))
    }
    def progress(chunks: String*): Unit = {
      statusActor.send(StatusActor.Syncing(chunks.mkString("\n")))
      consoleLogger.send(Logger.Progress(chunks))
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
      imageName => IconHandler.icon.setImage(IconHandler.images(imageName)),
      tooltip => IconHandler.icon.setToolTip(tooltip)
    )

    val consoleLogger = new ConsoleLogger(dest, rotationSize, truncate, logOut)

    object IconHandler{
      val images = Seq("blue-sync", "green-tick", "red-cross", "grey-dash")
        .map{name => (name, java.awt.Toolkit.getDefaultToolkit().getImage(getClass.getResource(s"/$name.png")))}
        .toMap

      val icon = new java.awt.TrayIcon(images("blue-sync"))

      icon.setToolTip("Devbox Initializing")

      val tray = java.awt.SystemTray.getSystemTray()


      icon.addMouseListener(new MouseListener {
        def mouseClicked(e: MouseEvent): Unit = {
          onClick.send(())
        }

        def mousePressed(e: MouseEvent): Unit = ()
        def mouseReleased(e: MouseEvent): Unit = ()
        def mouseEntered(e: MouseEvent): Unit = ()
        def mouseExited(e: MouseEvent): Unit = ()
      })
    }

  }
}