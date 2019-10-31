package devbox.logger

import devbox.common.{ActorContext, BaseLogger, Logger, SimpleActor}


class ConsoleLogger(val dest: String => os.Path,
                    val rotationSize: Long,
                    val truncate: Boolean,
                    val logOut0: String => Unit)
                   (implicit ac: ActorContext) extends SimpleActor[Logger.Msg] with BaseLogger{
  def logOut(s: String) = logOut0(s)
  var lastProgressTimestamp = 0L

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
