package devbox.agent

import java.io.ByteArrayOutputStream

import scala.util.control.NonFatal

/**
  * Simple program that allows the devbox launcher to send over multiple
  * commands or files to write all in one go, saving the round trips that
  * would result in performing each command or file-write in a separate
  * SSH/SCP session
  */
object DevboxSetupMain {

  def main(args: Array[String]): Unit = {
    val baos = new ByteArrayOutputStream()
    os.Internals.transfer(System.in, baos)
    val buffer = baos.toByteArray
    val allSetupFilesAndCommands =
      upickle.default.readBinary[Seq[Either[(String, Array[Byte]), String]]](buffer)

    val userName = sys.env.getOrElse("DEVBOX_USER", os.proc("whoami").call().out.trim)

    allSetupFilesAndCommands.foreach{
      case Left((destination, bytes)) =>

        // we run as root, so we need to expand ~ to DEVBOX_USER here
        val expandedDestination = destination match{
          case s"~/$rest" => os.root / "home" / userName / os.SubPath(rest)
          case dest => os.Path(dest)
        }
        try {
          os.write.over(expandedDestination, bytes, createFolders = true)
          os.perms.set(expandedDestination, "rwxrwxrwx")
        } catch {
          case NonFatal(e) =>
            println(s"Error writing file $destination: ${e.getMessage}")
        }
      case Right(cmd) =>
        println("Running remote command: " + cmd)
        os.proc("bash", "-c", cmd).call()
    }
  }
}
