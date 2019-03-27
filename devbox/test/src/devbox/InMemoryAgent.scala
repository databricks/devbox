package devbox

import java.io._

import devbox.common.{Logger, RpcClient, Skipper}

/**
  * In-memory implementation of [[AgentApi]], so we can run unit tests on the
  * without paying the expensive JVM startup cost.
  */
class InMemoryAgent(dest: os.Path,
                    skipper: Skipper,
                    exitOnError: Boolean,
                    randomKillConnection: Boolean = false) extends AgentApi {
  var alive = true
  var thread: Thread = _
  val startTime: Long = System.currentTimeMillis()

  var stderr0: Pipe = new Pipe()
  var stdin0: Pipe = new Pipe()
  var stdout0: Pipe = new Pipe()

  var stderr: InputStream with DataInput = stderr0.in
  var stdin: OutputStream with DataOutput = stdin0.out
  var stdout: InputStream with DataInput = stdout0.in

  def isAlive() = alive

  var killerThread = new Thread(() => {
    var count = 0
    while (true) {
      count += 1
      if (count % 7 == 6) {
        logger("KILLER", "KILL AGENT")
        thread.interrupt()
        thread.join()
      }
      Thread.sleep(1000)
    }
  })

  start()

  override def start(): Unit = {
    thread = new Thread(() =>
      try devbox.agent.DevboxAgentMain.mainLoop(
        logger,
        skipper,
        new RpcClient(stdout0.out, stdin0.in, (tag, t) => logger("AGNT " + tag, t)),
        dest,
        exitOnError,
        idempotent = false
      ) catch{
        case e: java.lang.InterruptedException => () // do nothing
      },
      "DevboxMockAgentThread"
    )
    thread.start()
    alive = true
    if (randomKillConnection) {
      killerThread.start()
    }
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