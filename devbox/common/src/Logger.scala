package devbox.common

import java.nio.file.StandardOpenOption.{CREATE, WRITE, APPEND}

trait Logger extends AutoCloseable{
  def write(s: String): Unit
  def info(title: => String, body: => String): Unit
  def progress(title: => String, body: => String): Unit

  def apply(tag: String, x: => Any = Logger.NoOp): Unit = {
    assert(tag.length <= Logger.margin)

    val msg =
      fansi.Color.Magenta(tag.padTo(Logger.margin, ' ')) ++ " | " ++
      pprint.apply(x, height = Int.MaxValue)
    write(msg.toString().replace("\n", Logger.marginStr))
  }
}

object Logger{
  object NoOp {
    override def toString = ""
  }
  val margin = 20
  val marginStr = "\n" + (" " * margin) + " | "
  case class File(dest: os.Path, toast: Boolean) extends Logger{
    val output = os.write.outputStream(dest, openOptions = Seq(CREATE, WRITE, APPEND))
    def write(s: String): Unit = synchronized{
      output.write(fansi.Str(s).plainText.getBytes("UTF-8"))
      output.write('\n')
    }
    def close() = output.close()
    var lastProgressTimestamp = 0L
    override def info(title: => String, body: => String): Unit = {
      val title0 = title
      val body0 = body
      println(s"$title0: $body")
      lastProgressTimestamp = System.currentTimeMillis()
      if (toast) os.proc("osascript", "-e", s"""display notification "$body0" with title "$title0" """).call()
    }
    override def progress(title: => String, body: => String): Unit = {
      val now = System.currentTimeMillis()
      if (now - lastProgressTimestamp > 5000){
        val title0 = title
        val body0 = body
        println(s"$title0: $body")
        lastProgressTimestamp = now
        if (toast) os.proc("osascript", "-e", s"""display notification "$body0" with title "$title0" """).call()
      }
    }
  }

  object JsonStderr extends Logger{
    def write(s: String) = synchronized{ System.err.println(ujson.write(s)) }
    def close() = () // do nothing
    def info(title: => String, body: => String): Unit = ???
    def progress(title: => String, body: => String): Unit = ???
  }
}
