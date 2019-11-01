package devbox.syncer

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
             signatureTransformer: (os.SubPath, Sig) => Sig)
            (implicit ac: ActorContext, logger: SyncLogger) extends AutoCloseable{

  val agentActor: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    x => skipActor.send(SkipScanActor.Receive(x)),
  )

  val syncActor = new SyncActor(
    agentActor.send,
    mapping
  )

  val sigActor = new SigActor(
    syncActor.send,
    signatureTransformer
  )

  val skipActor = new SkipScanActor(
    sigActor.send,
    mapping,
    ignoreStrategy
  )

  val watcher = os.watch.watch(
    mapping.map(_._1),
    events => skipActor.send(
      SkipScanActor.Paths(PathSet.from(events.iterator.map(_.segments)))
    ),
    logger.apply
  )

  def start() = {
    logger.init()
    agent.start(s => logger.info(s"Initializing Devbox\n$s"))
    agentActor.spawnReaderThread()

    agentActor.send(
      AgentReadWriteActor.Send(
        SyncFiles.RemoteScan(mapping.map(_._2))
      )
    )
    skipActor.send(SkipScanActor.StartScan())
  }

  def close() = {
    logger.close()
    watcher.close()
    agentActor.send(AgentReadWriteActor.Close())
  }
}
