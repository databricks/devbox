package devbox.agent

import java.io.{DataInputStream, DataOutputStream, EOFException}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption

import devbox.common.Cli
import devbox.common.Cli.Arg
import devbox.common._
import Cli.pathScoptRead
import Util.relpathRw
object DevboxAgentMain {
  case class Config(logFile: Option[os.Path] = None,
                    help: Boolean = false,
                    ignoreStrategy: String = "",
                    workingDir: String = "",
                    exitOnError: Boolean = false)

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
      ),
      Arg[Config, Unit](
        "exit-on-error", None,
        "",
        (c, v) => c.copy(exitOnError = true)
      )
    )

    Cli.groupArgs(args.toList, signature, Config()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)

      case Right((config, remaining)) =>
        os.makeDir.all(os.home / ".devbox")
        val logger = Logger.JsonStderr(os.home / ".devbox" / "log.txt")
        logger("AGNT START", config.workingDir)

        val skipper = Skipper.fromString(config.ignoreStrategy)
        val client = new RpcClient(
          new DataOutputStream(System.out),
          new DataInputStream(System.in),
          (tag, t) => logger("AGNT " + tag, t)
        )
        mainLoop(logger, skipper, client, os.Path(config.workingDir, os.pwd), config.exitOnError)
    }
  }
  def mainLoop(logger: Logger,
               skipper: Skipper,
               client: RpcClient,
               wd: os.Path,
               exitOnError: Boolean) = {


    val buffer = new Array[Byte](Util.blockSize)
    while (true) try client.readMsg[Rpc]() match {
      case Rpc.FullScan(path) =>
        val scanRoot = wd / path
        val skip = skipper.initialize(scanRoot)
        if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

        val fileStream = os.walk.stream.attrs(
          scanRoot,
          (p, attrs) => skip(p, attrs.isDir) && !attrs.isDir
        )
        client.writeMsg(fileStream.count())
        for {
          (p, attrs) <- fileStream
          sig <- Signature.compute(p, buffer, attrs.fileType)
        } {
          client.writeMsg(Some((p.relativeTo(scanRoot), sig: Signature)))
        }

        client.writeMsg(None)

      case Rpc.Remove(root, path) =>
        os.remove.all(wd / root / path)
        client.writeMsg(0)

      case Rpc.PutFile(root, path, perms) =>
        os.write(wd / root / path, "", perms)
        client.writeMsg(0)

      case Rpc.PutDir(root, path, perms) =>
        os.makeDir(wd / root / path, perms)
        client.writeMsg(0)

      case Rpc.PutLink(root, path, dest) =>
        os.symlink(wd / root / path, os.FilePath(dest))
        client.writeMsg(0)

      case Rpc.WriteChunk(root, path, offset, data, hash) =>
        val p = wd / root / path
        withWritable(p){
          os.write.write(p, data.value, Seq(StandardOpenOption.WRITE), 0, offset)
        }
        client.writeMsg(0)

      case Rpc.SetSize(root, path, offset) =>
        val p = wd / root / path
        withWritable(p) {
          os.truncate(p, offset)
        }
        client.writeMsg(0)

      case Rpc.SetPerms(root, path, perms) =>
        os.perms.set.apply(wd / root / path, perms)
        client.writeMsg(0)
    }catch{
      case e: EOFException => throw e // master process has closed up, exit
      case e: Throwable if !exitOnError =>
        logger("AGNT ERROR", e)
        client.writeMsg(RemoteException.create(e), false)
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
