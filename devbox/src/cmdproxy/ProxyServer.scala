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

/**
 * Simple server that runs commands on the local system and responds with the output of that command.
 *
 * The server is single-threaded and synchronous: each request is handled before the next one is accepted.
 *
 * Protocol is a simple JSON protocol:
 *
 * Request is one line, terminated with \n: "
 *   {"workingDir":"universe","cmd":["git","status"]}
 *
 * Response is either a Left(line: String) for lines of output, or Right(exitCode: Int) for
 * the end of the output stream
 */
class ProxyServer(dirMapping: Seq[(os.Path, os.RelPath)],
                  port: Int = ProxyServer.DEFAULT_PORT)
                 (implicit logger: FileLogger) {

  // this may throw when binding if the socket is used, but for the moment we just assume there is no other
  // syncer process on this machine
  val socket = new ServerSocket(port, /* backlog */ 2, InetAddress.getLoopbackAddress)

  /** Map remote directories to local ones. */
  val localDir: Map[os.RelPath, os.Path] = dirMapping.map(_.swap).toMap

  def start(): Unit = {
    logger.info(s"Starting command proxy server, listening at ${socket.getInetAddress}:${socket.getLocalPort}")
    (new Thread("Git Proxy Thread") {
      override def run(): Unit = {
        while (!socket.isClosed) {
          Using(socket.accept()) { handleConnection } recover {
            case e: Exception =>
              logger.error(s"Error handling request ${e.getMessage}")
            case e: java.net.SocketException if e.getMessage == "Socket closed" =>
              logger.error(s"Git proxy socket closed")
          }
        }
      }
    }).start()

  }

  def handleConnection(conn: Socket): Unit = try {
    logger.info(s"Accepting connection from ${conn.getInetAddress}")
    val in = new BufferedReader(new InputStreamReader(conn.getInputStream, ProxyServer.CHARSET_NAME))
    val out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream, ProxyServer.CHARSET_NAME))

    upickle.default.read[Request](in.readLine()) match {
      case Request(dir, args) =>
        val workingDir = localDir
          .collect{case (remote, local) if RelPath(dir).startsWith(remote) =>
            local / RelPath(dir).relativeTo(remote)
          }
          .head

        // being cautious here and only execute "git" commands
        if (args.headOption.exists((_ == "git"))) {
          logger.info(s"Executing `${args.mkString(" ")}` in $workingDir")

          val proc = os.proc(args).call(
            workingDir,
            mergeErrIntoOut = true,
            stdout = os.ProcessOutput.Readlines(str =>
              out.println(upickle.default.write(Left[String, Int](str)))
            ),
            check = false,
            timeout = 10000
          )

          out.println(upickle.default.write(Right[String, Int](proc.exitCode)))
        } else {
          val msg = s"Not executing non-git commend: `${args.mkString(" ")}`."
          logger.info(msg)
          out.println(upickle.default.write(Right[String, Int](1)))
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
