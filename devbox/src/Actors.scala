package devbox

import java.io.{PrintWriter, StringWriter}
import java.util.concurrent.ScheduledExecutorService

import devbox.common.{Logger, Response, Rpc, Signature, Skipper, Util, Vfs}

import scala.collection.mutable


object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: Rpc) extends Msg
  case class Restarted() extends Msg
  case class ReadRestarted() extends Msg
  case class Receive(data: Array[Byte]) extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          syncer: => SyncActor)
                         (implicit ac: ActorContext)
  extends SimpleActor[AgentReadWriteActor.Msg](){
  private val buffer = mutable.ArrayDeque.empty[Rpc]

  var sending = true
  def run(item: AgentReadWriteActor.Msg): Unit = item match{
    case AgentReadWriteActor.Send(msg) =>
      ac.reportSchedule()
      buffer.append(msg)
      if (sending) sendRpcs(Seq(msg))

    case AgentReadWriteActor.ReadRestarted() =>
      if (sending){
        restart()
      }
    case AgentReadWriteActor.Restarted() =>
      if (!sending){

        sending = true
        spawnReaderThread(
          agent,
          buf => this.send(AgentReadWriteActor.Receive(buf)),
          () => this.send(AgentReadWriteActor.ReadRestarted())
        )
        sendRpcs(buffer.toSeq)
      }


    case AgentReadWriteActor.Receive(data) =>
      syncer.send(SyncActor.Receive(upickle.default.readBinary[Response](data)))
      ac.reportComplete()
      buffer.dropInPlace(1)
  }

  def spawnReaderThread(agent: AgentApi, out: Array[Byte] => Unit, restart: () => Unit) = {
    new Thread(() => {
      while(try{
        val s = agent.stdout.readBoolean()
        val n = agent.stdout.readInt()
        val buf = new Array[Byte](n)
        agent.stdout.readFully(buf)
        out(buf)
        true
      }catch{
        case e: java.io.EOFException =>
          restart()
          false
      })()
    }).start()
  }



  def sendRpcs(msgs: Seq[Rpc]) = {
    try {
      for(msg <- msgs){
        val blob = upickle.default.writeBinary(msg)
        agent.stdin.writeBoolean(true)
        agent.stdin.writeInt(blob.length)
        agent.stdin.write(blob)
        agent.stdin.flush()
      }
    }catch{ case e: java.io.IOException =>
      restart()
    }
  }

  def restart() = {
    agent.destroy()
    agent.start()
    sending = false
    this.send(AgentReadWriteActor.Restarted())
  }
}

object SyncActor{
  sealed trait Msg
  case class Scan() extends Msg

  case class Events(paths: Set[os.Path]) extends Msg
  case class Debounced(debounceId: Object) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
}
class SyncActor(skipArr: Array[(os.Path, Boolean) => Boolean],
                agentReadWriter: => AgentReadWriteActor,
                mapping: Seq[(os.Path, os.RelPath)],
                logger: Logger,
                signatureTransformer: (os.RelPath, Signature) => Signature,
                skipper: Skipper,
                scheduledExecutorService: ScheduledExecutorService)
               (implicit ac: ActorContext)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = Initializing(Set())

  case class Initializing(changedPaths: Set[os.Path]) extends State({
    case SyncActor.Events(paths) => Initializing(changedPaths ++ paths)
    case SyncActor.Scan() =>
      agentReadWriter.send(AgentReadWriteActor.Send(Rpc.FullScan(mapping.map(_._2))))
      val pathStream = for {
        ((src, dest), i) <- geny.Generator.from(mapping.zipWithIndex)
        (p, attrs) <- os.walk.stream.attrs(
          src,
          (p, attrs) => skipArr(i)(p, attrs.isDir),
          includeTarget = true
        )
      } yield p
      RemoteScanning(pathStream.toSet)
  })

  case class RemoteScanning(changedPaths: Set[os.Path]) extends State({
    case SyncActor.Events(paths) => RemoteScanning(changedPaths ++ paths)
    case SyncActor.Receive(Response.Scanned(pathLists)) =>
      val vfsArr = for(pathList <- pathLists) yield {
        val vfs = new Vfs[Signature](Signature.Dir(0))
        for((path, sig) <- pathList) Vfs.updateVfs(path, sig, vfs)
        vfs
      }

      val scanned = for{
        (pathList, root) <- pathLists.zip(mapping.map(_._1))
        (path, sig) <- pathList
      } yield root / path
      executeSync(changedPaths ++ scanned, vfsArr)
  })

  case class Waiting(vfsArr: Seq[Vfs[Signature]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths.toSet, vfsArr)
    case SyncActor.Receive(Response.Ack()) => Waiting(vfsArr) // do nothing
    case SyncActor.Debounced(debounceToken2) => Waiting(vfsArr) // do nothing
  })


  def executeSync(changedPaths: Set[os.Path], vfsArr: Seq[Vfs[Signature]]): State = {
    val buffer = new Array[Byte](Util.blockSize)

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    val allEventPaths = changedPaths.toSeq.sortBy(_.toString)
    logger("SYNC EVENTS", allEventPaths)

    val failed = mutable.Set.empty[os.Path]
    for (((src, dest), i) <- mapping.zipWithIndex) {
      val eventPaths = allEventPaths.filter(p =>
        p.startsWith(src) && !skipArr(i)(p, true)
      )

      logger("SYNC BASE", eventPaths.map(_.relativeTo(src).toString()))

      val exitCode = for {
        _ <- if (eventPaths.isEmpty) Left(SyncFiles.NoOp: SyncFiles.ExitCode) else Right(())
        _ <- SyncFiles.updateSkipPredicate(
          eventPaths, skipper, vfsArr(i), src, buffer, logger,
          signatureTransformer, skipArr(i) = _
        )
        res <- SyncFiles.synchronizeRepo(
          logger, vfsArr(i), skipArr(i), src, dest,
          buffer, eventPaths, signatureTransformer,
          p => agentReadWriter.send(AgentReadWriteActor.Send(p))
        )
      } yield res

      exitCode match {
        case Right((streamedByteCount, changedPaths)) =>
        //                  allChangedPaths.appendAll(changedPaths)
        //                  allSyncedBytes += streamedByteCount
        case Left(SyncFiles.NoOp) => // do nothing
        case Left(SyncFiles.SyncFail(value)) =>
          //          changedPaths.appendAll(eventPaths)
          val x = new StringWriter()
          val p = new PrintWriter(x)
          value.printStackTrace(p)
          logger("SYNC FAILED", x.toString)
          failed.addAll(eventPaths)
      }
    }

    if (failed.nonEmpty) this.send(SyncActor.Events(failed.toSet))
    Waiting(vfsArr)
  }
}

object DebounceActor{
  sealed trait Msg[T]
  case class Wrapped[T](value: T) extends Msg[T]
  case class Fire[T](items: Seq[T]) extends Msg[T]
}
class DebounceActor[T]
                   (handle: Seq[T] => Unit,
                    debounceMillis: Int)
                   (implicit ac: ActorContext) extends BatchActor[DebounceActor.Msg[T]]{
  def runBatch(items: Seq[DebounceActor.Msg[T]]) = {
    val values = items
      .flatMap{
        case DebounceActor.Wrapped(v) => Seq(v)
        case DebounceActor.Fire(vs) => vs
      }

    if (items.exists(_.isInstanceOf[DebounceActor.Fire[T]])){
      handle(values)
    }else{
      Thread.sleep(debounceMillis)
      this.send(DebounceActor.Fire(values))
    }
  }
}