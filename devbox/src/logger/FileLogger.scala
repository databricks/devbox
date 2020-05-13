package devbox.logger

import devbox.common.Logger.PPrinted
import devbox.logger.ConsoleLogger

/**
 * A simple file-based async logger that routes all messages to a file,
 * supporting log rotation and pretty printing of values.
 */
class FileLogger(dest: String => os.Path,
                 rotationSize: Long)
                (implicit ac: castor.Context) extends ConsoleLogger(dest, rotationSize) {

  def info(msg: String): Unit = this.send(PPrinted("info", msg))

  def error(msg: String): Unit = this.send(PPrinted("error", msg))

  def debug(msg: String): Unit = this.send(PPrinted("debug", msg))
}
