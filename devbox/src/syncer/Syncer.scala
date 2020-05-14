package devbox.syncer

import devbox.common._
import devbox.common.Skipper.GitIgnore
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
             proxyGit: Boolean,
             signatureTransformer: (os.SubPath, Sig) => Sig,
             syncIgnore: Option[String])
            (implicit ac: castor.Context, logger: SyncLogger) extends AutoCloseable{


  val syncIgnoreRegex = syncIgnore.map(com.google.re2j.Pattern.compile(_))
  println(s"Syncing ${mapping.map{case (from, to) => s"$from:$to"}.mkString(", ")}")

  /** Skippers to use on each repository, used by the SkipScan actor and also sent to the remote endpoint */
  val skippers = mapping.map { case (base, _) => Skipper.fromString(ignoreStrategy, base, proxyGit) }

  val agentActor: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    x => skipActor.send(SkipScanActor.Receive(x)),
  )

  val syncActor = new SyncActor(
    agentActor,
    mapping
  )

  val sigActor = new SigActor(
    syncActor,
    signatureTransformer
  )

  val skipActor = new SkipScanActor(
    sigActor,
    mapping,
    skippers,
    syncIgnoreRegex
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
    agent.start(s => logger.info(fansi.Color.Blue("Initializing Devbox: ") + s))
    agentActor.spawnReaderThread()

    // get the force included paths as immutable path sets to be sent over the wire
    val allForceIncludes: Seq[PathSet] = skippers map {
      case gi: GitIgnore => gi.initForceInclude
      case _ => new PathSet()
    }

    agentActor.send(
      AgentReadWriteActor.Send(
        SyncFiles.RemoteScan(mapping.map(_._2), allForceIncludes, proxyGit, syncIgnore)
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
