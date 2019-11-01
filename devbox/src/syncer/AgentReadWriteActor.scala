package devbox.syncer

import java.nio.ByteBuffer
import java.time.Duration

import devbox.common._
import devbox.logger.SyncLogger


object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: SyncFiles.Msg) extends Msg
  case class ForceRestart() extends Msg
  case class ReadFailed() extends Msg
  case class AttemptReconnect() extends Msg
  case class Receive(data: Response) extends Msg
  case class StartFile() extends Msg
  case class Close() extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          onResponse: Response => Unit)
                         (implicit ac: ActorContext,
                          logger: SyncLogger)
  extends StateMachineActor[AgentReadWriteActor.Msg](){

  def initialState = Active(Vector())

  type Buffered = Either[AgentReadWriteActor.StartFile, SyncFiles.Msg]
  val byteArr = new Array[Byte](Util.blockSize)
  val buf = ByteBuffer.wrap(byteArr)

  case class Active(buffer: Vector[Buffered]) extends State({
    case AgentReadWriteActor.StartFile() =>
      logger.filesAndBytes(1, 0)
      Active(buffer)
    case AgentReadWriteActor.Send(msg) =>
      logStatusMsgForRpc(msg)
      msg match{
        case r: SyncFiles.Msg =>
          getRpcFor(r, buf) match{
            case None => Active(buffer)
            case Some(rpc) =>
              val newBuffer = buffer :+ Right(r)
              ac.reportSchedule()
              sendRpc(newBuffer, 0, rpc).getOrElse(Active(newBuffer))
          }

        case _ => Active(buffer)
      }

    case AgentReadWriteActor.ReadFailed() =>
      restart(buffer, 0)

    case AgentReadWriteActor.ForceRestart() =>
      restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) =>
      onResponse(data)
      if (!data.isInstanceOf[Response.Ack]) Active(buffer)
      else {
        ac.reportComplete()

        // Drop all the StartFile nodes from the buffer; since we've gotten a
        // response, from a later Msg, we are guaranteed to not need to replay
        // them, and they do not require any logging to be done since they are
        // logged on receive.
        val newBuffer = buffer.dropWhile(_.isLeft)

        val Right(msg) = newBuffer.head

        if (newBuffer.tail.nonEmpty) logStatusMsgForRpc(msg, "(Complete)")
        else if (msg == SyncFiles.Complete()) logger.done()

        Active(newBuffer.tail)
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class RestartSleeping(buffer: Vector[Buffered], retryCount: Int) extends State({
    case AgentReadWriteActor.StartFile() =>
      RestartSleeping(buffer :+ Left(AgentReadWriteActor.StartFile()), retryCount)

    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      RestartSleeping(buffer ++ Some(Right(msg)), retryCount)

    case AgentReadWriteActor.ReadFailed() => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.ForceRestart() => restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.AttemptReconnect() =>

      logger.info("Restarting Devbox agent", s"Attempt #$retryCount")
      val started = agent.start(s =>
        logger.info("Restarting Devbox agent", s"Attempt #$retryCount\n$s")
      )
      val startError = if (started) None else Some(restart(buffer, retryCount))


      startError.getOrElse{
        spawnReaderThread()
        val newMsg =
          if (buffer.nonEmpty) None
          else{
            ac.reportSchedule()
            Some(Right(SyncFiles.Complete()))
          }

        val newBuffer = buffer ++ newMsg
        val failState = newBuffer.foldLeft(Option.empty[State]){
          case (Some(end), _) => Some(end)
          case (None, buffered) =>
            buffered match{
              case Right(msg) =>
                logStatusMsgForRpc(msg, "(Replaying)")
                getRpcFor(msg, buf) match{
                  case None => None
                  case Some(rpc) => sendRpc(newBuffer, retryCount, rpc)
                }
              case Left(startFile) =>
                logger.filesAndBytes(1, 0)
                None
            }
        }

        failState.getOrElse(Active(newBuffer))
      }
    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class GivenUp(buffer: Vector[Buffered]) extends State({
    case AgentReadWriteActor.StartFile() =>
      GivenUp(buffer :+ Left(AgentReadWriteActor.StartFile()))

    case AgentReadWriteActor.Send(msg) =>
      logger.grey(
        "Unable to connect to devbox, gave up after 5 attempts;",
        "click on this logo to try again"
      )
      ac.reportSchedule()
      GivenUp(buffer :+ Right(msg))

    case AgentReadWriteActor.ForceRestart() =>
      logger.info("Syncing Restarted", "")
      restart(buffer, 0)

    case AgentReadWriteActor.ReadFailed() => GivenUp(buffer)

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class Closed() extends State({
    case _ => Closed()
  })

  val client = new RpcClient(agent.stdin, agent.stdout, logger.apply(_, _))

  def logStatusMsgForRpc(msg: SyncFiles.Msg, suffix0: String = "") = {
    val suffix = if (suffix0 == "") "" else "\n" + suffix0
    msg match{
      case SyncFiles.Complete() =>
        logger.progress("Syncing Complete", "waiting for confirmation from Devbox")
      case SyncFiles.RemoteScan(paths) =>
        logger.info("Scanning directories", paths.mkString("\n"))

      case SyncFiles.RpcMsg(rpc) =>
        logger.syncingFile("Syncing path [", s"]:\n${rpc.path}$suffix")

      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>
        val chunkMsg = if (chunkCount > 1) s" chunk [$chunkIndex/$chunkCount]" else ""
        logger.syncingFile("Syncing path [", s"]$chunkMsg:\n$subPath$suffix")
    }
  }
  def getRpcFor(msg: SyncFiles.Msg, buf: ByteBuffer): Option[Rpc] = {
    msg match{
      case SyncFiles.Complete() => Some(Rpc.Complete())
      case SyncFiles.RemoteScan(paths) => Some(Rpc.FullScan(paths))
      case SyncFiles.RpcMsg(rpc) => Some(rpc)

      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>


        try {
          Util.autoclose(os.read.channel(src / subPath)) { channel =>
            buf.rewind()
            channel.position(chunkIndex * Util.blockSize)
            var n = 0
            while ( {
              if (n == Util.blockSize) false
              else channel.read(buf) match {
                case -1 => false
                case d =>
                  n += d
                  true
              }
            }) ()

            val msg = Rpc.WriteChunk(
              dest,
              subPath,
              chunkIndex * Util.blockSize,
              new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr)
            )
            logger.filesAndBytes(0, n)
            Some(msg)
          }
        }catch{case e: java.nio.file.NoSuchFileException =>
          None
        }
    }
  }

  def spawnReaderThread() = {
    new Thread(() => {
      while ( {
        val strOpt =
          try Some(agent.stderr.readLine())
          catch{
            case e: java.io.EOFException => None
            case e: java.io.IOException => None
          }
        strOpt match{
          case None | Some(null)=> false
          case Some(str) =>
            try {
              val s = ujson.read(str).str
              logger.apply(
                "AGENT OUT",
                new Object {
                  override def toString: String = s
                }
              )
              true
            } catch {
              case e: ujson.ParseException =>
                println(str)
                false
            }
        }
      })()
    }, "DevboxAgentLoggerThread").start()

    new Thread(() => {
      while(try{
        val response = client.readMsg[Response]()
        this.send(AgentReadWriteActor.Receive(response))
        true
      }catch{
        case e: java.io.IOException =>
          this.send(AgentReadWriteActor.ReadFailed())
          false
      })()
    }, "DevboxAgentOutputThread").start()
  }

  def sendRpc(buffer: Vector[Buffered], retryCount: Int, msg: Rpc): Option[State] = {
    try {
      client.writeMsg(msg)
      None
    } catch{ case e: java.io.IOException =>
      Some(restart(buffer, retryCount))
    }
  }

  def restart(buffer: Vector[Buffered], retryCount: Int): State = {

    try agent.destroy()
    catch{case e: Throwable => /*donothing*/}

    if (retryCount < 5) {
      val seconds = math.pow(2, retryCount).toInt
      logger.error(
        s"Unable to connect to devbox",
        s"trying again after $seconds seconds"
      )
      ac.scheduleMsg(
        this,
        AgentReadWriteActor.AttemptReconnect(),
        Duration.ofSeconds(seconds)
      )

      RestartSleeping(buffer, retryCount + 1)
    } else {
      logger.grey(
        "Unable to connect to devbox, gave up after 5 attempts;",
        "click on this logo to try again"
      )

      GivenUp(buffer)
    }
  }
}