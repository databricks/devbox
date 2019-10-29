package devbox.common

import java.nio.file.StandardOpenOption.{CREATE, WRITE, TRUNCATE_EXISTING, APPEND}
trait BaseLogger extends AutoCloseable{
  def rotationSize: Long
  def dest: String => os.Path
  def truncate: Boolean

  var size = 0L
  var output: java.io.OutputStream = _

  def logOut(s: String): Unit
  def write(s: String) = {
    logOut(s)

    if (output == null || size > rotationSize) {
      if (output != null) output.close()
      os.remove.all(dest("-old"))
      if (os.exists(dest(""))) os.copy(dest(""), dest("-old"))
      output = os.write.outputStream(
        dest(""),
        openOptions =
          if (truncate) Seq(CREATE, WRITE, TRUNCATE_EXISTING)
          else Seq(CREATE, WRITE, APPEND)
      )
      size = 0

    }
    val bytes = fansi.Str(s).plainText.getBytes("UTF-8")
    output.write(bytes)
    output.write('\n')
    size += bytes.length + 1
  }

  def close() = output.close()
}
object Logger{

  object NoOp {
    override def toString = ""
  }
  val margin = 20
  val marginStr = "\n" + (" " * margin) + " | "


  sealed trait Msg
  case class PPrinted(tag: String, value: Any) extends Msg
  case class Info(title: String, body: String, color: Option[String]) extends Msg
  case class Progress(title: String, body: String) extends Msg
  case class Close() extends Msg

}

trait SyncLogger{
  def apply(tag: String, x: Any = Logger.NoOp): Unit
  def info(title: String, body: String, color: Option[String] = None): Unit
  def progress(title: String, body: String): Unit
}
object SyncLogger{

  class Impl(val dest: String => os.Path, val rotationSize: Long, val truncate: Boolean)
            (implicit ac: ActorContext) extends SimpleActor[Logger.Msg] with BaseLogger with SyncLogger{

    var lastProgressTimestamp = 0L

    def logOut(s: String) = {}
    def apply(tag: String, x: Any = Logger.NoOp): Unit = this.send(Logger.PPrinted(tag, x))

    def info(title: String, body: String, color: Option[String] = None): Unit = {
      this.send(Logger.Info(title, body, color))
    }
    def progress(title: String, body: String): Unit = {
      this.send(Logger.Progress(title, body))
    }

    def run(msg: Logger.Msg): Unit = msg match{
      case Logger.PPrinted(tag, value) =>
        assert(tag.length <= Logger.margin)

        val msgStr =
          fansi.Color.Magenta(tag.padTo(Logger.margin, ' ')) ++ " | " ++
          pprint.apply(value, height = Int.MaxValue)

        write(msgStr.toString().replace("\n", Logger.marginStr))

      case Logger.Info(title, body, color) =>
        val title0 = title
        color match {
          case Some(c) => println(s"${Console.RESET}$c$title0: $body${Console.RESET}")
          case None => println(s"$title0: $body")
        }
        lastProgressTimestamp = System.currentTimeMillis()

      case Logger.Progress(title, body) =>
        val now = System.currentTimeMillis()
        if (now - lastProgressTimestamp > 5000){
          val title0 = title
          println(s"$title0: $body")
          lastProgressTimestamp = now
        }
    }

  }
}

class AgentLogger(val dest: String => os.Path, val rotationSize: Long)
                 (implicit ac: ActorContext) extends SimpleActor[Logger.PPrinted] with BaseLogger{

  def truncate = true
  def apply(tag: String, x: Any = Logger.NoOp): Unit = this.send(Logger.PPrinted(tag, x))

  def logOut(s: String) = {
    System.err.println(ujson.write(s))
  }
  def run(msg: Logger.PPrinted): Unit = {
    assert(msg.tag.length <= Logger.margin)

    val msgStr =
      fansi.Color.Magenta(msg.tag.padTo(Logger.margin, ' ')) ++ " | " ++
        pprint.apply(msg.value, height = Int.MaxValue)

    write(msgStr.toString().replace("\n", Logger.marginStr))
  }
}