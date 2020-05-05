package cmdproxy

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

import scala.util.Using

import devbox.logger.FileLogger
import os.RelPath
import ujson.ParseException
import upickle.default.{macroRW, ReadWriter}

case class Request(workingDir: String, cmd: Seq[String])
object Request {
  implicit val rw: ReadWriter[Request] = macroRW
}

case class Response(exitCode: Int, output: String)
object Response {
  implicit val rw: ReadWriter[Response] = macroRW
}

/**
 * Simple server that runs commands on the local system and responds with the output of that command.
 *
 * The server is single-threaded and synchronous: each request is handled before the next one is accepted.
 *
 * Protocol is a simple JSON protocol:
 *
 * Request is one line, terminated with \n: "
 *   {"workingDir":"universe","cmd":["git","status"]}
 * Response:
 *   {"exitCode":0,"output":"bla bla"}
 */
class ProxyServer(dirMapping: Seq[(os.Path, os.RelPath)], port: Int = ProxyServer.DEFAULT_PORT)(implicit logger: FileLogger) {

  // this may throw when binding if the socket is used, but for the moment we just assume there is no other
  // syncer process on this machine
  val socket = new ServerSocket(port, /* backlog */ 2, InetAddress.getLoopbackAddress)

  /** Map remote directories to local ones. */
  val localDir: Map[os.RelPath, os.Path] = dirMapping.map(_.swap).toMap

  def start(): Unit = {
    logger.info(s"Starting command proxy server, listening at ${socket.getInetAddress}:${socket.getLocalPort}")
    while (true) {
      Using(socket.accept()) { handleConnection } recover {
        case e: Exception =>
          logger.error(s"Error handling request ${e.getMessage}")
      }
    }
  }

  def handleConnection(conn: Socket): Unit = try {
    logger.info(s"Accepting connection from ${conn.getInetAddress}")
    val in = new BufferedReader(new InputStreamReader(conn.getInputStream, ProxyServer.CHARSET_NAME))
    val out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream, ProxyServer.CHARSET_NAME))

    upickle.default.read[Request](in.readLine()) match {
      case Request(dir, args) =>
        val workingDir = localDir.getOrElse(RelPath(dir), os.home / RelPath(dir))

        // being cautious here and only execute "git" commands
        if (args.headOption.exists((_ == "git"))) {
          logger.info(s"Executing `${args.mkString(" ")}` in $workingDir")

          val proc = os.proc(args).call(
            workingDir,
            mergeErrIntoOut = true,
            check = false,
            timeout = 10000)

          out.println(
            upickle.default.write(Response(proc.exitCode, proc.out.string(ProxyServer.CHARSET_NAME))))
        } else {
          val msg = s"Not executing non-git commend: `${args.mkString(" ")}`."
          logger.info(msg)
          out.println(upickle.default.write(Response(-1, msg)))
        }

        out.flush()
    }
  } catch {
    case e: ParseException => logger.error(s"Error parsing incoming json request: ${e.getMessage}")
  }
}

object ProxyServer {
  val DEFAULT_PORT = 20280
  val CHARSET_NAME = "UTF-8"
}
