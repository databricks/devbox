package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

import devbox.common.Cli
import devbox.common.Cli.Arg
import devbox.common._
import Cli.pathScoptRead
object Agent {
  case class Config(logFile: Option[os.Path] = None,
                    help: Boolean = false,
                    ignoreStrategy: String = "")
  def main(args: Array[String]): Unit = {
    val signature = Seq(
      Arg[Config, Unit](
        "help", None,
        "Print this message",
        (c, v) => c.copy(help = true)
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
        val logger = Logger.Stderr

        logger("START AGENT", os.pwd)

        val skip = Util.ignoreCallback(config.ignoreStrategy)
        val client = new RpcClient(
          new DataOutputStream(System.out),
          new DataInputStream(System.in)
        )
        try mainLoop(logger, skip, client)
        catch{ case e: Throwable =>
          logger("EXIT AGENT", e)
          client.writeMsg(RemoteException.create(e), false)
          System.exit(1)
        }
    }
  }
  def mainLoop(logger: Logger, skip: (os.Path, os.Path) => Boolean, client: RpcClient) = {


    val buffer = new Array[Byte](Util.blockSize)
    while (true) {
      val msg = client.readMsg[Rpc]()
      logger("AGENT ", msg)
      msg match {
        case Rpc.FullScan(path) =>
          val scanRoot = os.Path(path, os.pwd)
          val scanned = os
            .walk(scanRoot, p => skip(p, scanRoot) && !os.isDir(p, followLinks = false))
            .map(p =>(p.relativeTo(scanRoot).toString, Signature.compute(p, buffer)))

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

        case Rpc.SetSize(path, offset) =>
          Util.autoclose(FileChannel.open(os.Path(path, os.pwd).toNIO, StandardOpenOption.WRITE)){ channel =>
            channel.truncate(offset)
          }
          client.writeMsg(0)

        case Rpc.SetPerms(path, perms) =>
          os.perms.set.apply(os.Path(path, os.pwd), perms)
          client.writeMsg(0)
      }
    }
  }
}
