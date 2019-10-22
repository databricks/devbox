package devbox

import java.nio.file.attribute.PosixFilePermission

import devbox.DevboxMain.Config
import devbox.DevboxTests.{instantiateSyncer, prepareFolders}
import devbox.common._
import devbox.common.Cli.{Arg, showArg}

object DevboxTestMain {
  case class Config(label: String = "manual",
                    stride: Int = 1,
                    debounceMillis: Int = 100,
                    help: Boolean = false,
                    verbose: Boolean = false,
                    ignoreStrategy: String = "dotgit",
                    preserve: Boolean = false,
                    toast: Boolean = false,
                    readOnlyRemote: Boolean = false,
                    inMemoryAgent: Boolean = false)

  def main(args: Array[String]): Unit = {

    val signature = Seq(
      Arg[Config, String](
        "label", None,
        "Which repository's commits to use for this test",
        (c, v) => c.copy(label = v)
      ),
      Arg[Config, String](
        "ignore-strategy", None,
        "Which files to ignore",
        (c, v) => c.copy(ignoreStrategy = v)
      ),
      Arg[Config, Int](
        "stride", None,
        "How often to perform validation, once every [stride] commits",
        (c, v) => c.copy(stride = v)
      ),
      Arg[Config, Unit](
        "toast", None,
        "Enable Mac-OS toast notifications",
        (c, v) => c.copy(toast = true)
      ),
      Arg[Config, Int](
        "debounce", None,
        "How many milliseconds to wait for the filesystem to stabilize before syncing",
        (c, v) => c.copy(debounceMillis = v)
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
      Arg[Config, Unit](
        "preserve", None,
        "Preserve starting folder contents",
        (c, v) => c.copy(preserve = true)
      ),
      Arg[Config, Unit](
        "readonly-remote", None,
        "",
        (c, v) => c.copy(readOnlyRemote = true)
      ),
      Arg[Config, Unit](
        "in-memory-agent", None,
        "",
        (c, v) => c.copy(inMemoryAgent = true)
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

          if (config.label == "manual"){
            val (src, dest, log) = prepareFolders(config.label, config.preserve)
            val logger = new Logger.File(log, config.toast)
            val skip = Skipper.fromString(config.ignoreStrategy)
            val syncer = instantiateSyncer(
              src, dest,
              skip,
              config.debounceMillis,
              logger,
              config.ignoreStrategy,
              exitOnError = false,
              if (!config.readOnlyRemote) {(p, sig) => sig}
              else {
                case (p, Signature.File(perms, blockHashes, size)) =>
                  Signature.File(
                    perms
                      - PosixFilePermission.GROUP_WRITE
                      - PosixFilePermission.OTHERS_WRITE
                      - PosixFilePermission.OWNER_WRITE,
                    blockHashes,
                    size
                  )
                case (p, sig) => sig
              }
            )
            try {
              syncer.start()
              Thread.sleep(9999999)
            }
            finally syncer.close()
          }else{

            DevboxTests.walkValidate(
              config.label,
              DevboxTests.cases(config.label),
              config.stride,
              commits(0),
              commits.drop(1),
              config.ignoreStrategy
            )
          }
        }
        System.exit(0)
    }

  }
}
