package devbox.agent

import java.io.{DataInputStream, DataOutputStream}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption

import devbox.common.Cli
import devbox.common.Cli.Arg
import devbox.common._
import Cli.pathScoptRead
object Agent {
  case class Config(logFile: Option[os.Path] = None,
                    help: Boolean = false,
                    ignoreStrategy: String = "",
                    workingDir: String = "")

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
      ),
      Arg[Config, String](
        "working-dir", None,
        "",
        (c, v) => c.copy(workingDir = v)
      )
    )

    Cli.groupArgs(args.toList, signature, Config()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)

      case Right((config, remaining)) =>
        val logger = Logger.JsonStderr

        logger("AGNT START", config.workingDir)

        val skip = Util.ignoreCallback(config.ignoreStrategy)
        val client = new RpcClient(
          new DataOutputStream(System.out),
          new DataInputStream(System.in),
          (tag, t) => logger("AGNT " + tag, t)
        )
        try mainLoop(logger, skip, client, os.Path(config.workingDir, os.pwd))
        catch{ case e: Throwable =>
          logger("AGNT EXIT", e)
          client.writeMsg(RemoteException.create(e), false)
          System.exit(1)
        }
    }
  }
  def mainLoop(logger: Logger,
               skip: (os.Path, os.Path) => Boolean,
               client: RpcClient,
               wd: os.Path) = {


    val buffer = new Array[Byte](Util.blockSize)
    while (true) client.readMsg[Rpc]() match {
      case Rpc.FullScan(path) =>
        val scanRoot = os.Path(path, wd)
        for {
          p <- os.walk.stream(scanRoot, p => skip(p, scanRoot) && ! os.isDir(p, followLinks = false))
          sig <- Signature.compute(p, buffer)
        } {
          client.writeMsg(Some((p.relativeTo(scanRoot).toString, sig)))
        }

        client.writeMsg(None)

      case Rpc.Remove(path) =>
        os.remove.all(os.Path(path, wd))
        client.writeMsg(0)

      case Rpc.PutFile(path, perms) =>
        os.write(os.Path(path, wd), "", perms)
        client.writeMsg(0)

      case Rpc.PutDir(path, perms) =>
        os.makeDir(os.Path(path, wd), perms)
        client.writeMsg(0)

      case Rpc.PutLink(path, dest) =>
        os.symlink(os.Path(path, wd), os.FilePath(dest))
        client.writeMsg(0)

      case Rpc.WriteChunk(path, offset, data, hash) =>
        val p = os.Path(path, wd)
        withWritable(p){
          os.write.write(p, data.value, Seq(StandardOpenOption.WRITE), 0, offset)
        }
        client.writeMsg(0)

      case Rpc.SetSize(path, offset) =>
        val p = os.Path(path, wd)
        withWritable(p) {
          os.truncate(os.Path(path, wd), offset)
        }
        client.writeMsg(0)

      case Rpc.SetPerms(path, perms) =>
        os.perms.set.apply(os.Path(path, wd), perms)
        client.writeMsg(0)
    }
  }
  def withWritable[T](p: os.Path)(t: => T) = {
    val perms = os.perms(p)
    if (perms.contains(PosixFilePermission.OWNER_WRITE)) {
      t
    }else{
      os.perms.set(p, perms + PosixFilePermission.OWNER_WRITE)
      val res = t
      os.perms.set(p, perms)
      res
    }
  }
}
