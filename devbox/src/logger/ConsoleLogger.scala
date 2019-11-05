package devbox.logger

import devbox.common.{BaseLogger, Logger}
import cask.actor

class ConsoleLogger(val dest: String => os.Path,
                    val rotationSize: Long)
                   (implicit ac: actor.Context)
extends actor.SimpleActor[Logger.Msg] with BaseLogger{
  var lastProgressTimestamp = 0L

  def run(msg: Logger.Msg): Unit = msg match {
    case Logger.PPrinted(tag, value) =>
      assert(tag.length <= Logger.margin)

      val msgIterator =
        Iterator(tag.padTo(Logger.margin, ' '), " | ") ++
        pprint.tokenize(value, height = Int.MaxValue).map(_.plainText)

      for(chunk <- msgIterator) write(chunk.replace("\n", Logger.marginStr))
      write("\n")

    case Logger.Info(chunks) =>
      println(chunks.mkString(", "))
      lastProgressTimestamp = System.currentTimeMillis()

    case Logger.Progress(chunks) =>
      val now = System.currentTimeMillis()
      if (now - lastProgressTimestamp > 5000) {
        println(chunks.mkString(", "))
        lastProgressTimestamp = now
      }

    case Logger.Close() => close()
  }
}
