package devbox
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
             skipper: Skipper,
             debounceMillis: Int,
             logger: Logger,
             signatureTransformer: (os.RelPath, Signature) => Signature)
            (implicit ac: ActorContext) extends AutoCloseable{

  private[this] val watcher = System.getProperty("os.name") match{
    case "Linux" =>
      new WatchServiceWatcher(
        mapping.map(_._1),
        events => debouncer.send(DebounceActor.Paths(events)),
        logger
      )
    case "Mac OS X" =>
      new FSEventsWatcher(
        mapping.map(_._1),
        events => debouncer.send(DebounceActor.Paths(events)),
        logger,
        0.05
      )
  }

  val syncer: SyncActor = new SyncActor(
    for ((src, dest) <- mapping.toArray) yield skipper.initialize(src),
    agentReadWriter,
    mapping,
    new Logger {
      def write(s: String) = logger.write(s)

      def info(title: => String, body: => String, color: => Option[String]) = {
        statusActor.send(StatusActor.Syncing(s"$title: $body"))
        logger.info(title, body, color)
      }

      def progress(title: => String, body: => String) = {
        statusActor.send(StatusActor.Syncing(s"$title: $body"))
        logger.progress(title, body)
      }
      def close() = logger.close()
    },
    signatureTransformer,
    skipper,
    Executors.newSingleThreadScheduledExecutor(),
    statusActor
  )
  val agentReadWriter: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    syncer,
    statusActor
  )

  val debouncer = new DebounceActor(
    paths => syncer.send(SyncActor.Events(paths)),
    statusActor,
    debounceMillis
  )

  val statusActor = new StatusActor(agentReadWriter)

  val agentLoggerThread = new Thread(() => {
    while (try {
      val str = agent.stderr.readLine()
      if (str != null) logger.write(ujson.read(str).str)
      true
    } catch{
      case e: java.io.EOFException => false
      case e: java.io.IOException => false
    }) ()
  })

  val watcherThread = new Thread(() => watcher.start())

  var running = false
  def start() = {
    running = true
    agent.start()
    agentReadWriter.spawnReaderThread(
      agent,
      buf => agentReadWriter.send(AgentReadWriteActor.Receive(buf)),
      () => agentReadWriter.send(AgentReadWriteActor.ReadRestarted())
    )

    agentLoggerThread.start()
    watcherThread.start()
    syncer.send(SyncActor.Scan())

  }

  def close() = {
    running = false
    statusActor.send(StatusActor.Close())
    watcher.close()
    agent.destroy()
  }
}
