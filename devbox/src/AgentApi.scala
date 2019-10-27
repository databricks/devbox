package devbox

import java.io._

import os.SubProcess

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
            stdout = new os.ProcessOutput {
              def redirectTo = ProcessBuilder.Redirect.PIPE

              def processOutput(out: => SubProcess.OutputStream): Option[Runnable] = Some{ () =>
                val reader = new BufferedReader(new InputStreamReader(out))

                while({
                  scala.util.Try(reader.readLine())match{
                    case scala.util.Failure(_) | scala.util.Success(null) => false
                    case scala.util.Success(str) =>
                      println(str)
                      logPrepOutput(str)
                      true
                  }
                })()
              }
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
