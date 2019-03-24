package devbox

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}

import devbox.common.{Logger, RpcClient}

class DevboxState(logger: Logger,
                  agent: AgentApi,
                  client: RpcClient,
                  healthCheckInterval: Int) {

  private var connectionAlive: Boolean = true
  private var lastAck: Long = System.currentTimeMillis()
  private var nextCheck: Long = 0
  private val startTime: Long = System.currentTimeMillis()

  private val ex: ScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1)

  private val task: Runnable = () => {
    val timestamp = System.currentTimeMillis()
    val timeElapsed = (1.0 * (timestamp - startTime) / 1000).round
    if (!connectionAlive) {
      print(s"${Console.RESET}${Console.BOLD}Next retry in ${nextCheck - timeElapsed} seconds ⌛️${Console.RESET}\r")
    }
    if (timeElapsed >= nextCheck) {
      nextCheck += healthCheckInterval
      if (timestamp - lastAck > healthCheckInterval) {
        connectionAlive = false
        logger.info("CONNECTION", "Health check failed, reconnect and flush")
        agent.destroy()
        agent.start()
        client.resetIn(agent.stdout)
        client.resetOut(agent.stdin)
        client.flushOutstandingMsgs()
      }
    }
  }

  def updateState() = {
    if (healthCheckInterval != 0) {
      lastAck = System.currentTimeMillis()
      if (!connectionAlive) {
        logger.info("Connection re-established", "Devbox is back alive", Some(Console.GREEN))
      }
      connectionAlive = true
    }
  }

  def start() = {
    if (healthCheckInterval != 0) {
      ex.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS)
    }
  }

  def join() = {
    if (healthCheckInterval != 0) {
      ex.shutdown()
    }
  }
}