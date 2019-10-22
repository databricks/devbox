package devbox

import java.io._

trait AgentApi {
  def isAlive(): Boolean
  def destroy(): Unit
  def stderr: InputStream with DataInput
  def stdout: InputStream with DataInput
  def stdin: OutputStream with DataOutput
  def start(): Unit
}

class ReliableAgent(cmd: Seq[String], cwd: os.Path) extends AgentApi {
  var process: os.SubProcess = _

  override def start(): Unit = {
    process = os.proc(cmd).spawn(cwd = cwd)
  }
  override def isAlive(): Boolean = process.isAlive
  override def destroy(): Unit = process.destroy()
  override def stderr: InputStream with DataInput = new DataInputStream(process.stderr)
  override def stdout: InputStream with DataInput = new DataInputStream(process.stdout)
  override def stdin: OutputStream with DataOutput = new DataOutputStream(process.stdin)
}

object AgentApi{
  implicit class SubProcessApi(sub: os.SubProcess) extends AgentApi{
    def isAlive() = sub.isAlive()
    def destroy(): Unit = sub.destroy()
    def stderr = sub.stderr
    def stdout = sub.stdout
    def stdin = sub.stdin
    def start(): Unit = println()
  }
}
