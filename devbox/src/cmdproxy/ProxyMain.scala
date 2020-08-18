package cmdproxy

import castor.SimpleActor
import devbox.logger.FileLogger

/**
 * A main object to test the command proxy server locally.
 */
object ProxyMain {
  import castor.Context.Simple._

  val noopActor = new SimpleActor[Unit]() {
    override def run(msg: Unit): Unit = ()
  }

  def main(args: Array[String]): Unit = {
    implicit val logger = new FileLogger(
      s => os.home / ".devbox" / s"cmdproxy$s.log",
      1024 * 1024
    )

    new ProxyServer(dirMapping = Seq.empty, ProxyServer.DEFAULT_PORT)(logger).start()
  }
}
