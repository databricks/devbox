package devbox

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import devbox.common.{Logger, RpcClient}

class DevboxStateMonitor(logger: Logger, agent: AgentApi) {

  // State
  private var connectionAlive: Boolean = true
  private var retryAttempted: Boolean = false
  private var draining: Boolean = false
  private var lastRetryTime: Long = 0

  // Rpc client
  var client: RpcClient = _

  // Threads
  private var ex: ScheduledThreadPoolExecutor = null
  private var task: Runnable = null
  private var backgroundDrainerThread: Thread = null

  def setConnectionAlive(): Unit = {
    logger("CONNECTION", "PONG")
    if (!connectionAlive && retryAttempted) {
      logger.info("Connection reestablished", "Devbox is back alive")
      retryAttempted = false
    }
    connectionAlive = true
  }

  def setDraining(draining: Boolean): Unit = this.draining = draining

  def startHealthChecker(): Unit = {
    ex = new ScheduledThreadPoolExecutor(1)
    task = () => {
      val timestamp = System.currentTimeMillis()
      if (!connectionAlive && timestamp - lastRetryTime > 5000) {
        logger.info("Connection drop detected", "Retry on connection and flush buffer")
        logger("CONNECTION", "RETRY CONNECT")
        lastRetryTime = timestamp
        connectionAlive = false
        retryAttempted = true

        // Re-establish connection
        agent.destroy()
        agent.start()
        client.resetIn(agent.stdout)
        client.resetOut(agent.stdin)

        // Send Ping and flush buffer
        client.ping()
        client.setShouldFlush()
      } else if (!connectionAlive && retryAttempted) {
        logger("CONNECTION", "TIMEOUT")
      } else {
        logger("CONNECTION", "PING")
        connectionAlive = false
        client.ping()
      }
    }
  }

  def startBackgroundDrainer(): Unit = {
    backgroundDrainerThread = new Thread(
      () => {
        while(true) {
          if (!draining) {
            client.drainOutstandingMsgs()
          } else {
            Thread.sleep(10)
          }
        }
      },
      "DevboxBackgroundDrainerThread"
    )
  }

  def start(client: RpcClient): Unit = {
    startHealthChecker()
    startBackgroundDrainer()
    this.client = client
    ex.scheduleAtFixedRate(task, 2, 1, TimeUnit.SECONDS)
    backgroundDrainerThread.start()
  }
}