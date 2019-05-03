package devbox

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import devbox.common.{Logger, RpcClient}

import scala.util.control.NonFatal

/**
  * Devbox state maintains a simple state machine of the underlying connection status
  * Health check is performed every [[healthCheckInterval]] seconds which checks if
  * the connection is healthy or not
  */
class DevboxState(logger: Logger,
                  agent: AgentApi,
                  client: RpcClient,
                  healthCheckInterval: Option[Int]) {

  private var connectionAlive: Boolean = true
  private var lastAck: Long = System.currentTimeMillis()
  private var nextCheck: Long = 0
  private val startTime: Long = System.currentTimeMillis()

  private val ex: ScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1)

  private def buildTaskRunnable(checkInterval: Int): Runnable = () => {
    try {
      val timestamp = System.currentTimeMillis()
      val timeElapsed = (1.0 * (timestamp - startTime) / 1000).round
      if (!connectionAlive) {
        print(s"${Console.RESET}${Console.BOLD}Next retry in ${nextCheck - timeElapsed} seconds ⌛️${Console.RESET}\r")
      }
      if (timeElapsed >= nextCheck) {
        nextCheck += checkInterval
        if (timestamp - lastAck > checkInterval) {
          connectionAlive = false
          logger.info("Connection", "Health check failed, reconnect and flush")
          agent.destroy()
          agent.start()
          client.resetIn(agent.stdout)
          client.resetOut(agent.stdin)
          client.flushOutstandingMsgs()
        }
      }
    } catch {
      case NonFatal(ex) => logger.info("Error in Devbox state monitor thread", ex.getMessage)
    }
  }

  def updateState() = {
    if (healthCheckInterval.isDefined) {
      lastAck = System.currentTimeMillis()
      if (!connectionAlive) {
        logger.info("Connection", "Reconnected - Devbox is back alive", Some(Console.GREEN))
      }
      connectionAlive = true
    }
  }

  def start() = {
    if (healthCheckInterval.isDefined) {
      val taskRunnable = buildTaskRunnable(healthCheckInterval.get)
      ex.scheduleAtFixedRate(taskRunnable, 0, 1, TimeUnit.SECONDS)
    }
  }

  def join() = {
    if (healthCheckInterval.isDefined) {
      ex.shutdown()
    }
  }
}
