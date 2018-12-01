package devbox.common

trait Logger extends AutoCloseable{
  def write(s: String): Unit
  def apply(tag: String, x: => Any = ""): Unit = {
    assert(tag.length < Logger.margin)

    val msg =
      fansi.Color.Magenta(tag.padTo(Logger.margin, ' ')) ++ " | " ++
      pprint.apply(x, height = Int.MaxValue)
    write(msg.toString().replace("\n", Logger.marginStr))
  }
}


object Logger{
  val margin = 15
  val marginStr = "\n" + (" " * margin) + " | "
  case class File(dest: os.Path) extends Logger{
    val output = os.write.outputStream(dest)
    def write(s: String): Unit = synchronized{
      output.write(fansi.Str(s).plainText.getBytes("UTF-8"))
      output.write('\n')
    }
    def close() = output.close()
  }
  object Stdout extends Logger{
    def write(s: String): Unit = synchronized{ System.out.println(s) }
    def close() = () // do nothing
  }
  object Stderr extends Logger{
    def write(s: String) = synchronized{ System.err.println(s) }
    def close() = () // do nothing
  }
}