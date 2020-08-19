package devbox.syncer

import java.io._


trait AgentApi {
  def isAlive(): Boolean
  def destroy(): Unit
  def stderr: InputStream with DataInput
  def stdout: InputStream with DataInput
  def stdin: OutputStream with DataOutput
  def start(logPrepOutput: String => Unit): Boolean
}

class ReliableAgent[T](prepareWithLogs: (String => Unit) => Option[T],
                    cmd: T => Seq[String],
                    cwd: os.Path) extends AgentApi {
  var process: os.SubProcess = _

  override def start(logPrepOutput: String => Unit): Boolean = {
    assert(process == null)

    prepareWithLogs(logPrepOutput) match {
      case Some(prepRes) =>
        process = os.proc(cmd(prepRes)).spawn(cwd = cwd)
        true
      case _ =>
        false
    }
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
