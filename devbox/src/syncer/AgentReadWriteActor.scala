package devbox.syncer

import java.nio.ByteBuffer
import java.time.Duration

import devbox.common._
import devbox.logger.SyncLogger

import cask.actor

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
                         (implicit ac: actor.Context,
                          logger: SyncLogger)
  extends actor.StateMachineActor[AgentReadWriteActor.Msg](){

  def initialState = Active(collection.immutable.Queue())

  type Buffered = Either[AgentReadWriteActor.StartFile, (SyncFiles.Msg, cask.actor.Context.Token)]
  val byteArr = new Array[Byte](Util.blockSize)
  val buf = ByteBuffer.wrap(byteArr)

  case class Active(buffer: collection.immutable.Queue[Buffered]) extends State({
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

              val newBuffer = buffer.appended(Right(r -> ac.reportSchedule()))

              if (sendRpc(rpc)) Active(newBuffer)
              else restart(newBuffer, 0)
          }

        case _ => Active(buffer)
      }

    case AgentReadWriteActor.ReadFailed() => restart(buffer, 0)

    case AgentReadWriteActor.ForceRestart() => restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) =>
      onResponse(data)
      if (!data.isInstanceOf[Response.Ack]) Active(buffer)
      else {


        // Drop all the StartFile nodes from the buffer; since we've gotten a
        // response, from a later Msg, we are guaranteed to not need to replay
        // them, and they do not require any logging to be done since they are
        // logged on receive.
        val filtered1 = buffer.dropWhile(_.isLeft)
        val (Right((msg, token)), filtered2) = filtered1.dequeue
        ac.reportComplete(token)
        if (filtered2.isEmpty) logger.done()

        Active(filtered2)
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class RestartSleeping(buffer: collection.immutable.Queue[Buffered], retryCount: Int) extends State({
    case AgentReadWriteActor.StartFile() =>
      RestartSleeping(buffer.appended(Left(AgentReadWriteActor.StartFile())), retryCount)

    case AgentReadWriteActor.Send(msg) =>

      RestartSleeping(buffer.appended(Right(msg -> ac.reportSchedule())), retryCount)

    case AgentReadWriteActor.ReadFailed() => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.ForceRestart() => restart(buffer, 0)

    case AgentReadWriteActor.Receive(data) => RestartSleeping(buffer, retryCount)

    case AgentReadWriteActor.AttemptReconnect() =>

      logger.info("Restarting Devbox agent", s"Attempt #$retryCount")
      val started = agent.start(s =>
        logger.info("Restarting Devbox agent", s"Attempt #$retryCount\n$s")
      )
      if (!started) restart(buffer, retryCount)
      else {
        spawnReaderThread()

        var remaining = buffer.appended(Right(SyncFiles.Complete() -> ac.reportSchedule()))
        var processed = collection.immutable.Queue.empty[Buffered]
        var failState = Option.empty[State]
        while(remaining.nonEmpty && failState.isEmpty){
          val (buffered, newCurrent) = remaining.dequeue
          remaining = newCurrent
          failState = failState.orElse{
            buffered match{
              case Right((msg, token)) =>
                logStatusMsgForRpc(msg, "(Replaying)")
                getRpcFor(msg, buf) match{
                  case None => None
                  case Some(rpc) =>
                    processed = processed.enqueue(buffered)
                    if (sendRpc(rpc)) None
                    else Some(restart(remaining, retryCount))
                }

              case Left(startFile) =>
                processed = processed.enqueue(buffered)
                logger.filesAndBytes(1, 0)
                None
            }
          }
        }

        failState.getOrElse(Active(processed.enqueueAll(remaining)))
      }

    case AgentReadWriteActor.Close() =>
      agent.destroy()
      Closed()
  })

  case class GivenUp(buffer: collection.immutable.Queue[Buffered]) extends State({
    case AgentReadWriteActor.StartFile() =>
      GivenUp(buffer :+ Left(AgentReadWriteActor.StartFile()))

    case AgentReadWriteActor.Send(msg) =>
      logger.grey(
        "Unable to connect to devbox, gave up after 5 attempts;",
        "click on this logo to try again"
      )

      GivenUp(buffer :+ Right(msg -> ac.reportSchedule()))

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

      case SyncFiles.RemoteScan(paths) =>
        logger.info("Scanning directories", paths.mkString("\n"))

      case SyncFiles.RpcMsg(rpc) =>
        logger.syncingFile("", rpc.path.toString, suffix)

      case SyncFiles.SendChunkMsg(src, dest, subPath, chunkIndex, chunkCount) =>
        val chunkMsg = if (chunkCount > 1) s" chunk [$chunkIndex/$chunkCount]" else ""
        logger.syncingFile(chunkMsg, subPath.toString, suffix)
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

  def sendRpc(msg: Rpc): Boolean = {
    try {
      client.writeMsg(msg)
      true
    }catch{case e: java.io.IOException =>
      false
    }
  }

  def restart(buffer: collection.immutable.Queue[Buffered], retryCount: Int): State = {

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