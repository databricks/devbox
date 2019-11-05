package devbox.agent

import java.io.{DataInputStream, DataOutputStream, EOFException}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.StandardOpenOption

import devbox.common.Cli
import devbox.common.Cli.Arg
import devbox.common._
import Cli.pathScoptRead
import Util.relpathRw
import devbox.common

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
object DevboxAgentMain {
  case class Config(logFile: Option[os.Path] = None,
                    help: Boolean = false,
                    ignoreStrategy: String = "",
                    workingDir: String = "",
                    exitOnError: Boolean = false,
                    randomKill: Option[Int] = None)

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
      Arg[Config, os.Path](
        "log-path", None,
        "",
        (c, v) => c.copy(logFile = Some(v))
      ),
      Arg[Config, Unit](
        "exit-on-error", None,
        "",
        (c, v) => c.copy(exitOnError = true)
      ),
      Arg[Config, Int](
        "random-kill", None,
        "",
        (c, v) => c.copy(randomKill = Some(v))
      ),
    )

    Cli.groupArgs(args.toList, signature, Config()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)

      case Right((config, remaining)) =>
        os.makeDir.all(os.home / ".devbox")
        implicit val ac = new cask.actor.Context.Simple(
          ExecutionContext.Implicits.global,
          _.printStackTrace()
        )
        val logger = new AgentLogger(
          n => {
            val logPath = os.Path(
              config.logFile.getOrElse(throw new Exception("config.logFile is None")),
              os.pwd
            )
            val s"$logFileName.$logFileExt" = logPath.last
            logPath / os.up / s"$logFileName$n.$logFileExt"
          },
          5 * 1024 * 1024
        )
        logger("AGNT START", config.workingDir)


        val client = new RpcClient(
          new DataOutputStream(System.out),
          new DataInputStream(System.in),
          (tag, t) => logger("AGNT " + tag, t))
        mainLoop(
          logger,
          client,
          os.Path(config.workingDir, os.pwd),
          config.exitOnError,
          config.randomKill,
          config.ignoreStrategy
        )
    }
  }
  def mainLoop(logger: AgentLogger,
               client: RpcClient,
               wd: os.Path,
               exitOnError: Boolean,
               randomKill: Option[Int],
               ignoreStrategy: String) = {


    var count = 0
    while (randomKill match{
      case Some(n) if count == n =>
        logger("AGENT EXIT", n)
        false
      case _ => true
    }) {
      count += 1
      try client.readMsg[Rpc]() match {
        case Rpc.FullScan(paths) =>
          val skippers = collection.mutable.Map.empty[os.RelPath, Skipper]
          val newSkippers =
            for(p <- paths)
            yield skippers.getOrElseUpdate(p, Skipper.fromString(ignoreStrategy))

          val buffer = new Array[Byte](Util.blockSize)
          InitialScan.initialSkippedScan(paths.map(wd / _), newSkippers){ (base, p, attrs) =>
            for(sig <- Sig.compute(base / p, buffer, attrs.fileType)){
              client.writeMsg(Response.Scanned(base.relativeTo(wd), p, sig))
            }
          }

          client.writeMsg(Response.Ack())

        case Rpc.Remove(root, path) =>
          os.remove.all(wd / root / path)
          client.writeMsg(Response.Ack())

        case Rpc.PutFile(root, path, perms) =>
          val targetPath = wd / root / path

          if (!os.exists(targetPath, followLinks = false)) {
            os.write(targetPath, "", perms)
          }
          client.writeMsg(Response.Ack())

        case Rpc.PutDir(root, path, perms) =>
          val targetPath = wd / root / path
          if (!os.exists(targetPath, followLinks = false)) {
            os.makeDir(targetPath, perms)
          }
          client.writeMsg(Response.Ack())

        case Rpc.PutLink(root, path, dest) =>
          val targetPath = wd / root / path
          if (!os.exists(targetPath, followLinks = false)) {
            os.symlink(targetPath, os.FilePath(dest))
          }
          client.writeMsg(Response.Ack())

        case Rpc.WriteChunk(root, path, offset, data) =>
          val p = wd / root / path

          withWritable(p){
            os.write.write(p, data.value, Seq(StandardOpenOption.WRITE), 0, offset)
          }
          client.writeMsg(Response.Ack())

        case Rpc.SetSize(root, path, offset) =>
          val p = wd / root / path
          withWritable(p) {
            os.truncate(p, offset)
          }
          client.writeMsg(Response.Ack())

        case Rpc.SetPerms(root, path, perms) =>
          os.perms.set.apply(wd / root / path, perms)
          client.writeMsg(Response.Ack())

        case Rpc.Complete() => client.writeMsg(Response.Ack())

      }catch{
        case e: EOFException => throw e // master process has closed up, exit
        case e: Throwable if !exitOnError =>
          import java.io.PrintWriter
          import java.io.StringWriter
          val sw = new StringWriter()
          val pw = new PrintWriter(sw)
          e.printStackTrace(pw)
          pw.flush()
          logger("AGNT ERROR", sw.toString)
      }
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
