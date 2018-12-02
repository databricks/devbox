package devbox

import java.io.{DataInput, DataOutput, InputStream, OutputStream}

trait AgentApi {
  def isAlive(): Boolean
  def destroy(): Unit
  def stderr: InputStream with DataInput
  def stdout: InputStream with DataInput
  def stdin: OutputStream with DataOutput
}
object AgentApi{
  implicit class SubProcessApi(sub: os.SubProcess) extends AgentApi{
    def isAlive() = sub.isAlive()

    def destroy(): Unit = sub.destroy()
    def stderr = sub.stderr
    def stdout = sub.stdout
    def stdin = sub.stdin
  }
}
