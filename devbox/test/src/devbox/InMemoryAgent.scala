package devbox

import java.io.{DataInputStream, DataOutputStream, PipedInputStream, PipedOutputStream}

import devbox.common.{Logger, RpcClient}

/**
  * In-memory implementation of [[AgentApi]], so we can run unit tests on the
  * without paying the expensive JVM startup cost.
  */
class InMemoryAgent(dest: os.Path, skip: (os.Path, os.Path) => Boolean) extends AgentApi {
  var alive = true
  def isAlive() = alive

  def destroy(): Unit = {
    stderr0.close()
    stdout0.close()
    stdin0.close()
    thread.interrupt()
    thread.join()
    alive = false
  }
  val stderr0 = new PipedInputStream()
  val stderrWrite = new PipedOutputStream(stderr0)
  val stderr = new DataInputStream(stderr0)

  val stdout0 = new PipedInputStream()
  val stdoutWrite = new PipedOutputStream(stdout0)
  val stdout = new DataInputStream(stdout0)

  val stdin0 = new PipedOutputStream()
  val stdinRead = new PipedInputStream(stdin0)
  val stdin = new DataOutputStream(stdin0)

  val thread = new Thread(() =>
    try devbox.agent.Agent.mainLoop(
      new Logger{
        def write(s: String): Unit = synchronized{ stderrWrite.write((ujson.write(s) + "\n").getBytes()) }
        def close() = () // do nothing
      },
      skip,
      new RpcClient(new DataOutputStream(stdoutWrite), new DataInputStream(stdinRead)),
      dest
    ) catch{
      case e: java.io.EOFException => () // do nothing
    },
    "DevboxMockAgentThread"
  )

  thread.start()
}