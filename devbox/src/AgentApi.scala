package devbox

import java.io._


trait AgentApi {
  def isAlive(): Boolean
  def destroy(): Unit
  def stderr: InputStream with DataInput
  def stdout: InputStream with DataInput
  def stdin: OutputStream with DataOutput
  def start(logPrepOutput: String => Unit): Boolean
}

class ReliableAgent(prep: Seq[String], cmd: Seq[String], cwd: os.Path) extends AgentApi {
  var process: os.SubProcess = _

  override def start(logPrepOutput: String => Unit): Boolean = {
    assert(process == null)

    val prepPassed =
      if (prep == Nil) true
      else {
        val prepResult = os.proc(prep)
          .call(
            cwd = cwd,
            stderr = os.Pipe,
            mergeErrIntoOut = true,
            stdout = os.ProcessOutput.ReadlineOutput{ line =>
              println(line)
              logPrepOutput(line)
            }
          )

        prepResult.exitCode == 0
      }
    if (prepPassed) process = os.proc(cmd).spawn(stderr = os.Pipe, cwd = cwd)

    prepPassed
  }
  def stderr = process.stderr
  def stdout = process.stdout
  def stdin = process.stdin
  override def isAlive(): Boolean = process.isAlive
  override def destroy(): Unit = {
    assert(process != null)
    process.destroy()
    process.destroyForcibly()
    process.waitFor()
    process = null
  }
}

object AgentApi{
}
