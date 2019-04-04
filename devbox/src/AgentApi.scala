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

class ReliableAgent(cmd: Seq[String]) extends AgentApi {
  var process: Process = _
  start()
  override def start(): Unit = {process = new java.lang.ProcessBuilder().command(cmd: _*).start()}
  override def isAlive(): Boolean = process.isAlive
  override def destroy(): Unit = process.destroy()
  override def stderr: InputStream with DataInput = new DataInputStream(process.getErrorStream)
  override def stdout: InputStream with DataInput = new DataInputStream(process.getInputStream)
  override def stdin: OutputStream with DataOutput = new DataOutputStream(process.getOutputStream)
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
