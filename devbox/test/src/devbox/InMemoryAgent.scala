package devbox

import java.io._

import devbox.common.{Logger, RpcClient}

/**
  * In-memory implementation of [[AgentApi]], so we can run unit tests on the
  * without paying the expensive JVM startup cost.
  */
class InMemoryAgent(dest: os.Path, skip: (os.Path, os.Path) => Boolean) extends AgentApi {
  var alive = true
  def isAlive() = alive

  def destroy(): Unit = {
    thread.interrupt()
    thread.join()
    alive = false
  }
  val stderr0 = new Pipe()
  val stderr = stderr0.in

  val stdout0 = new Pipe()
  val stdout = stdout0.in

  val stdin0 = new Pipe()
  val stdin = stdin0.out

  val logger = new Logger{
    def write(s: String): Unit = synchronized{
      stderr0.out.write((ujson.write(s) + "\n").getBytes())
    }
    def close() = () // do nothing
  }
  val thread = new Thread(() =>
    try devbox.agent.Agent.mainLoop(
      logger,
      skip,
      new RpcClient(stdout0.out, stdin0.in, (tag, t) => logger("AGNT " + tag, t)),
      dest
    ) catch{
      case e: java.lang.InterruptedException => () // do nothing
    },
    "DevboxMockAgentThread"
  )

  thread.start()
}