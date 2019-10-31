package devbox
import java.awt.event.{MouseEvent, MouseListener}
import java.util.concurrent._

import devbox.common._

/**
  * The Syncer class instances contain all the stateful, close-able parts of
  * the syncing logic: event queues, threads, filesystem watchers, etc. All the
  * stateless call-and-forget logic is pushed into static methods on the Syncer
  * companion object
  */
class Syncer(agent: AgentApi,
             mapping: Seq[(os.Path, os.RelPath)],
             ignoreStrategy: String = "dotgit",
             debounceMillis: Int,
             logger: SyncLogger,
             signatureTransformer: (os.SubPath, Sig) => Sig)
            (implicit ac: ActorContext) extends AutoCloseable{

//  val statusLogger = new SyncLogger{
//    def apply(tag: String, x: Any = Logger.NoOp): Unit = logger.apply(tag, x)
//    def info(chunks: String*) = {
//      statusActor.send(StatusActor.Syncing(chunks.mkString("\n")))
//      logger.info(chunks:_*)
//    }
//    def error(chunks: String*) = {
//      statusActor.send(StatusActor.Error(chunks.mkString("\n")))
//      logger.error(chunks:_*)
//    }
//    def grey(chunks: String*) = {
//      statusActor.send(StatusActor.Greyed(chunks.mkString("\n")))
//      logger.grey(chunks:_*)
//    }
//    def progress(chunks: String*) = {
//      statusActor.send(StatusActor.Syncing(chunks.mkString("\n")))
//      logger.progress(chunks:_*)
//    }
//  }





  val agentReadWriter: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    x => skipActor.send(SkipScanActor.Receive(x)),
    logger
  )

  val syncer: SyncActor = new SyncActor(
    agentReadWriter.send,
    mapping,
    logger,
    ignoreStrategy,
    Executors.newSingleThreadScheduledExecutor()
  )

  val sigActor = new SigActor(
    syncer.send,
    signatureTransformer,
    logger
  )
  val skipActor = new SkipScanActor(
    mapping,
    ignoreStrategy,
    sigActor.send,
    logger
  )

  private[this] val watcher = os.watch.watch(
    mapping.map(_._1),
    events => skipActor.send(
      SkipScanActor.Paths(
        new PathSet().withPaths(events.iterator.map(_.segments))
      )
    ),
    logger.apply(_, _)
  )

  var running = false

  def start() = {
//    IconHandler.tray.add(IconHandler.icon)
    running = true
    agent.start(s =>
      logger.info(s"Initializing Devbox\n$s")
    )
    agentReadWriter.spawnReaderThread()

    agentReadWriter.send(
      AgentReadWriteActor.Send(
        SyncFiles.RemoteScan(mapping.map(_._2))
      )
    )
    skipActor.send(SkipScanActor.StartScan())
  }

  def close() = {
//    IconHandler.tray.remove(IconHandler.icon)
    running = false
    watcher.close()
    agentReadWriter.send(AgentReadWriteActor.Close())
  }
}
