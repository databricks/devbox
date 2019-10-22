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
          (tag, t) => logger("AGNT " + tag, t))
        mainLoop(logger, skipper, client, os.Path(config.workingDir, os.pwd), config.exitOnError, idempotent = true)
    }
  }
  def mainLoop(logger: Logger,
               skipper: Skipper,
               client: RpcClient,
               wd: os.Path,
               exitOnError: Boolean,
               idempotent: Boolean) = {


    val buffer = new Array[Byte](Util.blockSize)
    while (true) try client.readMsg[Rpc]() match {
      case Rpc.FullScan(paths) =>
        val vfsRoots = for(path <- paths) yield {
          val vfs = new Vfs[Signature](Signature.Dir(0))

          val scanRoot = wd / path
          val skip = skipper.initialize(scanRoot)
          if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

          val fileStream = os.walk.stream.attrs(
            scanRoot,
            (p, attrs) => skip(p, attrs.isDir)
          )

          for {
            (p, attrs) <- fileStream
            sig <- Signature.compute(p, buffer, attrs.fileType)
          } {
            Vfs.updateVfs(path, sig, vfs)
          }

          vfs.root
        }
        client.writeMsg(vfsRoots)

      case rpc @ Rpc.Remove(root, path) =>
        os.remove.all(wd / root / path)
        client.writeMsg(Response.Ack(rpc.hashCode()))

      case rpc @ Rpc.PutFile(root, path, perms) =>
        val targetPath = wd / root / path
        if (!idempotent || !os.exists(targetPath)) {
          os.write(targetPath, "", perms)
        }
        client.writeMsg(Response.Ack(rpc.hashCode()))

      case rpc @ Rpc.PutDir(root, path, perms) =>
        val targetPath = wd / root / path
        if (!idempotent || !os.exists(targetPath)) {
          os.makeDir(targetPath, perms)
        }
        client.writeMsg(Response.Ack(rpc.hashCode()))

      case rpc @ Rpc.PutLink(root, path, dest) =>
        val targetPath = wd / root / path
        if (!idempotent || !os.exists(targetPath)) {
          os.symlink(targetPath, os.FilePath(dest))
        }
        client.writeMsg(Response.Ack(rpc.hashCode()))

      case rpc @ Rpc.WriteChunk(root, path, offset, data, hash) =>
        val p = wd / root / path
        withWritable(p){
          os.write.write(p, data.value, Seq(StandardOpenOption.WRITE), 0, offset)
        }
        client.writeMsg(Response.Ack(rpc.hashCode()))

      case rpc @ Rpc.SetSize(root, path, offset) =>
        val p = wd / root / path
        withWritable(p) {
          os.truncate(p, offset)
        }
        client.writeMsg(Response.Ack(rpc.hashCode()))

      case rpc @ Rpc.SetPerms(root, path, perms) =>
        os.perms.set.apply(wd / root / path, perms)
        client.writeMsg(Response.Ack(rpc.hashCode()))

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
