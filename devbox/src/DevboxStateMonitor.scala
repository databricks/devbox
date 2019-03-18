package devbox

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import devbox.common.{Logger, RpcClient}

import scala.util.control.NonFatal

class DevboxStateMonitor(logger: Logger,
                         agent: AgentApi,
                         healthCheckInterval: Int,
                         retryInterval: Int) {

  // State
  private var connectionAlive: Boolean = true
  private var retryAttempted: Boolean = false
  private var draining: Boolean = false
  private var lastRetryTime: Long = 0
  private var startTime: Long = 0

  // Rpc client
  var client: RpcClient = _

  // Threads
  private var ex: ScheduledThreadPoolExecutor = null
  private var task: Runnable = null
  private var backgroundDrainerThread: Thread = null

  def setConnectionAlive(): Unit = {
    logger("CONNECTION", "PONG")
    if (!connectionAlive && retryAttempted) {
      logger.info("Connection reestablished", "Devbox is back alive", Some(Console.GREEN))
      retryAttempted = false
    }
    connectionAlive = true
  }

  def setDraining(draining: Boolean): Unit = this.draining = draining

  def getNextRetry(timestamp: Long): Long = {
    retryInterval - getSecondsSinceLastRetry(timestamp)
  }

  def getSecondsSinceLastRetry(timestamp: Long): Long = {
    (1.0 * (timestamp - lastRetryTime) / 1000).round
  }

  def initHealthChecker(): Unit = {
    ex = new ScheduledThreadPoolExecutor(1)
    task = () => {
      try {
        logger("CONNECT", "MONITOR 1s")
        val timestamp = System.currentTimeMillis()
        val timeElapsed = (1.0 * (timestamp - startTime) / 1000).round
        if (!connectionAlive && retryAttempted) {
          print(s"${Console.RESET}${Console.BOLD}Next retry in ${getNextRetry(timestamp)} seconds ⌛️${Console.RESET}\r")
        }
        if (timeElapsed % healthCheckInterval == 0) {
          if (!connectionAlive && getSecondsSinceLastRetry(timestamp) >= retryInterval) {
            logger.info("Connection drop detected", "Retry on connection and flush buffer", Some(Console.YELLOW))
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
            logger("CONNECTION", "SET SHOULD FLUSH")
          } else if (!connectionAlive && retryAttempted) {
            logger("CONNECTION", "TIMEOUT")
          } else {
            logger("CONNECTION", "PING")
            connectionAlive = false
            client.ping()
          }
        }
      } catch {
        case NonFatal(ex) => logger.info("ERROR", s"Exception in DevboxStateMonitor $ex")
      }
    }
  }

  def initBackgroundDrainer(): Unit = {
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
    initHealthChecker()
    initBackgroundDrainer()
    this.client = client
    if (healthCheckInterval != 0 && retryInterval != 0) {
      ex.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS)
      backgroundDrainerThread.start()
      startTime = System.currentTimeMillis()
      lastRetryTime = System.currentTimeMillis()
    }
  }
}