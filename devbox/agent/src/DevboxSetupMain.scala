package devbox.agent

import java.io.ByteArrayOutputStream

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

    allSetupFilesAndCommands.foreach{
      case Left((destination, bytes)) =>
        println("Writing file: " + destination)

        val expandedDestination = destination match{
          case s"~/$rest" => os.home / os.SubPath(rest)
          case dest => os.Path(dest)
        }
        os.write(expandedDestination, bytes)
        os.perms.set(expandedDestination, "rwxrwxrwx")
      case Right(cmd) =>
        println("Running remote command: " + cmd)
        os.proc("bash", "-c", cmd).call()
    }
  }
}
