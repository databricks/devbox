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
  var thread: Thread = _
  var randomKillThread: Thread = _
  val startTime: Long = System.currentTimeMillis()

  var stderr0: Pipe = _
  var stdout0: Pipe = _
  var stdin0: Pipe = _

  def isAlive() = alive
  var stderr: InputStream with DataInput = null
  var stdout: InputStream with DataInput = null
  var stdin: OutputStream with DataOutput = null

  start()

  override def start(): Unit = {
    stdin0 = new Pipe()
    stdout0 = new Pipe()
    stderr0 = new Pipe()

    stdin = stdin0.out
    stdout = stdout0.in
    stderr = stderr0.in

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
    alive = true
  }

  def destroy(): Unit = {
    thread.interrupt()
    thread.join()
    alive = false
  }


  val logger = new Logger{
    def write(s: String): Unit = synchronized{
      stderr0.out.write((ujson.write(s) + "\n").getBytes())
    }
    def close() = () // do nothing
    def info(title: => String, body: => String, color: => Option[String]): Unit = ???

    def progress(title: => String, body: => String): Unit = ???
  }
}