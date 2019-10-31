package devbox.syncer

import java.util.concurrent._

import devbox.common._
import devbox.logger.SyncLogger

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

  val agentReadWriter: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    x => skipActor.send(SkipScanActor.Receive(x)),
    logger
  )

  val syncer = new SyncActor(
    agentReadWriter.send,
    mapping,
    logger,
    ignoreStrategy
  )

  val sigActor = new SigActor(
    syncer.send,
    signatureTransformer
  )
  val skipActor = new SkipScanActor(
    mapping,
    ignoreStrategy,
    sigActor.send
  )

  val watcher = os.watch.watch(
    mapping.map(_._1),
    events => skipActor.send(
      SkipScanActor.Paths(
        new PathSet().withPaths(events.iterator.map(_.segments))
      )
    ),
    logger.apply(_, _)
  )

  def start() = {
    logger.init()
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
    logger.close()
    watcher.close()
    agentReadWriter.send(AgentReadWriteActor.Close())
  }
}
