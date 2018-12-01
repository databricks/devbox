package devbox
import devbox.common.{Cli, Util}
import devbox.common.Cli.{Arg, showArg}

object DevboxMain {
  case class Config(repo: List[String] = Nil,
                    stride: Int = 1,
                    debounceMillis: Int = 100,
                    help: Boolean = false,
                    verbose: Boolean = false,
                    ignoreStrategy: String = "")

  def main(args: Array[String]): Unit = {

    val signature = Seq(
      Arg[Config, String](
        "repo", None,
        "Which repository to sync",
        (c, v) => c.copy(repo = v :: c.repo)
      ),
      Arg[Config, Int](
        "debounce", None,
        "How many milliseconds to wait for the filesystem to stabilize before syncing",
        (c, v) => c.copy(stride = v)
      ),
      Arg[Config, Unit](
        "help", None,
        "Print this message",
        (c, v) => c.copy(help = true)
      ),
      Arg[Config, Unit](
        "verbose", None,
        "Enable verbose logging",
        (c, v) => c.copy(verbose = true)
      ),
      Arg[Config, String](
        "ignore-strategy", None,
        "",
        (c, v) => c.copy(ignoreStrategy = v)
      )
    )


    Cli.groupArgs(args.toList, signature, Config()) match{
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((config, remaining)) =>
        if (config.help){
          val leftMargin = signature.map(showArg(_).length).max + 2
          System.out.println(Cli.formatBlock(signature, leftMargin).mkString("\n"))
        }else {
          val skip = Util.ignoreCallback(config.ignoreStrategy)
          val agent = os.proc(remaining).spawn()
          Util.autoclose(new Syncer(
            agent,
            for(s <- config.repo)
            yield s.split(':') match{
              case Array(src) => (os.Path(src, os.pwd), Seq(os.Path(src, os.pwd).last))
              case Array(src, dest) => (os.Path(src, os.pwd), dest.split('/').toSeq)
            },
            skip,
            config.debounceMillis,
            () => (),
            config.verbose

          )){syncer =>
            syncer.start()
          }
        }
        System.exit(0)
    }

  }
}
