package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

import devbox.common.Cli
import devbox.common.Cli.Arg
import devbox.common._

object Agent {
  case class Config(verbose: Boolean = false,
                    help: Boolean = false,
                    ignoreStrategy: String = "")
  def main(args: Array[String]): Unit = {
    val signature = Seq(
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
    Cli.groupArgs(args.toList, signature, Config()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)

      case Right((config, remaining)) =>
        if (config.verbose) System.err.println("Starting agent in " + os.pwd)

        val skip = Util.ignoreCallback(config.ignoreStrategy)

        val client = new RpcClient(
          new DataOutputStream(System.out),
          new DataInputStream(System.in)
        )

        val buffer = new Array[Byte](Signature.blockSize)

        try {
          while (true) {
            val msg = client.readMsg[Rpc]()
            if (config.verbose) System.err.println("AGENT " + msg)
            msg match {
              case Rpc.FullScan(path) =>
                val osPath = os.Path(path, os.pwd)
                val scanned = os.walk(
                  osPath,
                  p => skip(p, osPath) && !os.isDir(p, followLinks = false)
                ).map(
                  p => (p.relativeTo(os.pwd).toString, Some(Signature.compute(p, buffer)))
                )
                client.writeMsg(scanned)

              case Rpc.Remove(path) =>
                val p = os.Path(path, os.pwd)
                if (os.isLink(p)) os.remove(p)
                else os.remove.all(p)
                client.writeMsg(0)

              case Rpc.PutFile(path, perms) =>
                os.write(os.Path(path, os.pwd), "", perms, createFolders = false)
                client.writeMsg(0)

              case Rpc.PutDir(path, perms) =>
                os.makeDir(os.Path(path, os.pwd), perms)
                client.writeMsg(0)

              case Rpc.PutLink(path, dest) =>
                Files.createSymbolicLink(
                  os.Path(path, os.pwd).toNIO,
                  Paths.get(dest)
                )
                client.writeMsg(0)

              case Rpc.WriteChunk(path, offset, data, hash) =>
                os.write.write(os.Path(path, os.pwd), data.value, Seq(StandardOpenOption.WRITE), 0, offset)
                client.writeMsg(0)

              case Rpc.Truncate(path, offset) =>
                Util.autoclose(FileChannel.open(os.Path(path, os.pwd) toNIO, StandardOpenOption.WRITE)){ channel =>
                  channel.truncate(offset)
                }
                client.writeMsg(0)

              case Rpc.SetPerms(path, perms) =>
                os.perms.set.apply(os.Path(path, os.pwd), perms)
                client.writeMsg(0)
            }
          }
        } catch{ case e: Throwable =>
          client.writeMsg(RemoteException.create(e), false)
          System.exit(1)
        }
    }
  }
}
