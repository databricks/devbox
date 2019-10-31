package devbox.common

import java.nio.file.StandardOpenOption.{APPEND, CREATE, TRUNCATE_EXISTING, WRITE}

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
  case class Info(chunks: Seq[String]) extends Msg
  case class Progress(chunks: Seq[String]) extends Msg
  case class Close() extends Msg

}
