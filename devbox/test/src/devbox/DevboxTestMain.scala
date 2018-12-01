package devbox

import devbox.Cli.{Arg, showArg}

object DevboxTestMain {
  case class Config(label: String = "edge",
                    stride: Int = 1,
                    debounceMillis: Int = 100,
                    help: Boolean = false,
                    verbose: Boolean = false)

  def main(args: Array[String]): Unit = {

    val signature = Seq(
      Arg[Config, String](
        "label", None,
        "Which repository's commits to use for this test",
        (c, v) => c.copy(label = v)
      ),
      Arg[Config, Int](
        "stride", None,
        "How often to perform validation, once every [stride] commits",
        (c, v) => c.copy(stride = v)
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
          val commits = remaining.map(_.toInt)

          DevboxTests.walkValidate(
            config.label,
            DevboxTests.cases(config.label),
            config.stride,
            config.debounceMillis,
            commits(0),
            commits.drop(1),
            config.verbose
          )
        }
        System.exit(0)
    }

  }
}
