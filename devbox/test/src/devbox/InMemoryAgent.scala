package devbox

import java.io._

import devbox.common.{Logger, RpcClient, Skipper}

/**
  * In-memory implementation of [[AgentApi]], so we can run unit tests on the
  * without paying the expensive JVM startup cost.
  */
class InMemoryAgent(dest: os.Path,
                    skipper: Skipper,
                    exitOnError: Boolean) extends AgentApi {
  var alive = true
  def isAlive() = alive
  var thread: Thread = _
  start()

  override def start(): Unit = {
    thread = new Thread(() =>
      try devbox.agent.DevboxAgentMain.mainLoop(
        logger,
        skipper,
        new RpcClient(stdout0.out, stdin0.in, (tag, t) => logger("AGNT " + tag, t)),
        dest,
        exitOnError
      ) catch{
        case e: java.lang.InterruptedException => () // do nothing
      },
      "DevboxMockAgentThread"
    )
    thread.start()
  }

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
    def info(title: => String, body: => String): Unit = ???

    def progress(title: => String, body: => String): Unit = ???
  }
}