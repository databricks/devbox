package devbox

import java.io._

trait AgentApi {
  def isAlive(): Boolean
  def destroy(): Unit
  def stderr: InputStream with DataInput
  def stdout: InputStream with DataInput
  def stdin: OutputStream with DataOutput
  def start(logPrepOutput: String => Unit): Unit
}

class ReliableAgent(prep: Seq[String], cmd: Seq[String], cwd: os.Path) extends AgentApi {
  var process: java.lang.Process = _

  override def start(logPrepOutput: String => Unit): Unit = {
    assert(process == null)

    if (prep != Nil) {
      val prepProcess = new java.lang.ProcessBuilder()
        .command(prep:_*)
        .directory(cwd.toIO)
        .redirectErrorStream(true)
        .start()

      val reader = new BufferedReader(new InputStreamReader(prepProcess.getInputStream))

      while({
        scala.util.Try(reader.readLine())match{
          case scala.util.Failure(_) | scala.util.Success(null) => false
          case scala.util.Success(str) =>
            println(str)
            logPrepOutput(str)
            true
        }
      })()

      prepProcess.waitFor()
    }
    process = new java.lang.ProcessBuilder().command(cmd:_*).directory(cwd.toIO).start()
    stderr = new DataInputStream(process.getErrorStream)
    stdout = new DataInputStream(process.getInputStream)
    stdin = new DataOutputStream(process.getOutputStream)
  }
  override def isAlive(): Boolean = process.isAlive
  override def destroy(): Unit = {
    assert(process != null)
    process.destroy()
    process.destroyForcibly()
    process.waitFor()
    process = null
  }
  var stderr: InputStream with DataInput = _
  var stdout: InputStream with DataInput = _
  var stdin: OutputStream with DataOutput = _
}

object AgentApi{
}
