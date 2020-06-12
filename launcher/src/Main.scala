package launcher

import cmdproxy.ProxyServer
import devbox.DevboxMain
import devbox.common.Cli

object Main {
  def main(args: Array[String]): Unit = {
    os.makeDir.all(os.home / ".devbox")

    Cli.groupArgs2(
      args.toList,
      DevboxMain.signature, DevboxMain.Config(),
      EnsureInstanceRunning.signature, EnsureInstanceRunning()
    ) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((config, ensureInstanceRunning, remaining)) =>
        if (config.help) {
          println(
            Cli
              .formatBlock(DevboxMain.signature ++ EnsureInstanceRunning.signature, 30)
              .mkString("\n")
          )
        }
        else {
          if (!remaining.isEmpty) {
            println(s"Unknown arguments: ${remaining.mkString(", ")}")
            System.exit(1)
          }
          val portFwdArgs =
            if (config.proxyGit)
              Seq("-R", s"${ProxyServer.DEFAULT_PORT}:localhost:${ProxyServer.DEFAULT_PORT}")
            else
              Seq()
          devbox.DevboxMain.main0(
            Some(ensureInstanceRunning.url),
            config,
            log => ensureInstanceRunning.prepareInstanceCommand match {
              case None =>
                try {
                  ensureInstanceRunning.main0 (log)
                  true
                } catch {
                  case e: Throwable =>
                    e.printStackTrace ()
                    false
                }
              case Some(cmd) =>
                val prepResult = os.proc(cmd).call(
                  cwd = os.pwd,
                  stderr = os.Pipe,
                  mergeErrIntoOut = true,
                  stdout = os.ProcessOutput.Readlines(log),
                  check = false
                )

                prepResult.exitCode == 0
            },
            Seq(
              "ssh", "-C",
              "-o", "ExitOnForwardFailure=yes",
              "-o", "ServerAliveInterval=4",
              "-o", "ServerAliveCountMax=4") ++
              portFwdArgs ++ Seq(
              ensureInstanceRunning.url,
              s"java -cp ~/.devbox/agent.jar devbox.agent.DevboxAgentMain --log-path ~/.devbox/log.txt --ignore-strategy gitignore --proxy-git-commands ${config.proxyGit}"
            )
          )
        }
    }
  }
}
