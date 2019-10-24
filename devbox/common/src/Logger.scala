package devbox.common

import java.nio.file.StandardOpenOption.{CREATE, WRITE, TRUNCATE_EXISTING}

trait Logger extends AutoCloseable{
  def write(s: String): Unit
  def info(title: => String, body: => String, color: => Option[String] = None): Unit
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
  case class File(dest: os.Path) extends Logger{
    val output = os.write.outputStream(dest, openOptions = Seq(CREATE, WRITE, TRUNCATE_EXISTING))
    def write(s: String): Unit = synchronized{
      output.write(fansi.Str(s).plainText.getBytes("UTF-8"))
      output.write('\n')
    }
    def close() = output.close()
    var lastProgressTimestamp = 0L
    override def info(title: => String, body: => String, color: => Option[String]): Unit = {
      val title0 = title
      color match {
        case Some(c) => println(s"${Console.RESET}$c$title0: $body${Console.RESET}")
        case None => println(s"$title0: $body")
      }
      lastProgressTimestamp = System.currentTimeMillis()
    }
    override def progress(title: => String, body: => String): Unit = {
      val now = System.currentTimeMillis()
      if (now - lastProgressTimestamp > 5000){
        val title0 = title
        println(s"$title0: $body")
        lastProgressTimestamp = now
      }
    }
  }

  case class JsonStderr(dest: os.Path) extends Logger{
    val output = os.write.outputStream(dest, openOptions = Seq(CREATE, WRITE, TRUNCATE_EXISTING))
    def write(s: String) = synchronized{
      System.err.println(ujson.write(s))
      output.write(fansi.Str(s).plainText.getBytes("UTF-8"))
      output.write('\n')
    }
    def close() = output.close()
    def info(title: => String, body: => String, color: => Option[String]): Unit = ???
    def progress(title: => String, body: => String): Unit = ???
  }

  case class Stderr() extends Logger{
    def write(s: String) = System.err.println(s)
    def close() = ()
    def info(title: => String, body: => String, color: => Option[String]): Unit = ???
    def progress(title: => String, body: => String): Unit = ???
  }
}
