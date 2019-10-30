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
             signatureTransformer: (os.SubPath, Signature) => Signature)
            (implicit ac: ActorContext) extends AutoCloseable{

  private[this] val watcher = os.watch.watch(
    mapping.map(_._1),
    events => skipActor.send(
      SkipActor.Paths(
        new PathSet().withPaths(events.iterator.map(_.segments))
      )
    ),
    logger.apply(_, _)
  )

  val statusLogger = new SyncLogger{
    def apply(tag: String, x: Any = Logger.NoOp): Unit = logger.apply(tag, x)
    def info(chunks: String*) = {
      statusActor.send(StatusActor.Syncing(chunks.mkString("\n")))
      logger.info(chunks:_*)
    }
    def error(chunks: String*) = {
      statusActor.send(StatusActor.Error(chunks.mkString("\n")))
      logger.error(chunks:_*)
    }
    def grey(chunks: String*) = {
      statusActor.send(StatusActor.Greyed(chunks.mkString("\n")))
      logger.grey(chunks:_*)
    }
    def progress(chunks: String*) = {
      statusActor.send(StatusActor.Syncing(chunks.mkString("\n")))
      logger.progress(chunks:_*)
    }
  }
  val syncer: SyncActor = new SyncActor(
    agentReadWriter,
    mapping,
    statusLogger,
    ignoreStrategy,
    Executors.newSingleThreadScheduledExecutor(),
    statusActor
  )
  val agentReadWriter: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    syncer,
    statusActor,
    statusLogger
  )

  val signatureActor = new SignatureActor(
    syncer.send(_),
    signatureTransformer
  )

  val skipActor = new SkipActor(
    mapping,
    ignoreStrategy,
    signatureActor.send(_),
    statusLogger
  )

  val statusActor = new StatusActor(
//    _ => (), _ => (),
    imageName => IconHandler.icon.setImage(IconHandler.images(imageName)),
    tooltip => IconHandler.icon.setToolTip(tooltip),
    logger
  )

  var running = false
  object IconHandler{

    val images = Seq("blue-sync", "green-tick", "red-cross", "grey-dash")
      .map{name => (name, java.awt.Toolkit.getDefaultToolkit().getImage(getClass.getResource(s"/$name.png")))}
      .toMap
    val icon = new java.awt.TrayIcon(images("blue-sync"))

    icon.setToolTip("Devbox Initializing")

    val tray = java.awt.SystemTray.getSystemTray()


    icon.addMouseListener(new MouseListener {
      def mouseClicked(e: MouseEvent): Unit = {
        agentReadWriter.send(AgentReadWriteActor.ForceRestart())
      }

      def mousePressed(e: MouseEvent): Unit = ()
      def mouseReleased(e: MouseEvent): Unit = ()
      def mouseEntered(e: MouseEvent): Unit = ()
      def mouseExited(e: MouseEvent): Unit = ()
    })
  }
  def start() = {
    IconHandler.tray.add(IconHandler.icon)
    running = true
    agent.start(s =>
      statusActor.send(StatusActor.Syncing(s"Initializing Devbox\n$s"))
    )
    agentReadWriter.spawnReaderThread()

    agentReadWriter.send(
      AgentReadWriteActor.Send(
        SyncFiles.RemoteScan(mapping.map(_._2))
      )
    )
    skipActor.send(SkipActor.Scan())
  }

  def close() = {
    IconHandler.tray.remove(IconHandler.icon)
    running = false
    watcher.close()
    agentReadWriter.send(AgentReadWriteActor.Close())
  }
}
