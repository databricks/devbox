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
             skipper: Skipper,
             debounceMillis: Int,
             logger: SyncLogger,
             signatureTransformer: (os.RelPath, Signature) => Signature)
            (implicit ac: ActorContext) extends AutoCloseable{

  private[this] val watcher = os.watch.watch(mapping.map(_._1):_*)(
    events => debouncer.send(DebounceActor.Paths(events)),
    logger.apply(_, _)
  )

  val syncer: SyncActor = new SyncActor(
    agentReadWriter,
    mapping,
    new SyncLogger{
      def apply(tag: String, x: Any = Logger.NoOp): Unit = logger.apply(tag, x)
      def info(title: String, body: String, color: Option[String]) = {
        statusActor.send(StatusActor.Syncing(s"$title:\n$body"))
        logger.info(title, body, color)
      }
      def progress(title: String, body: String) = {
        statusActor.send(StatusActor.Syncing(s"$title:\n$body"))
        logger.progress(title, body)
      }
    },
    signatureTransformer,
    skipper,
    Executors.newSingleThreadScheduledExecutor(),
    statusActor
  )
  val agentReadWriter: AgentReadWriteActor = new AgentReadWriteActor(
    agent,
    syncer,
    statusActor,
    logger
  )

  val debouncer = new DebounceActor(
    paths => syncer.send(SyncActor.Events(paths)),
    statusActor,
    debounceMillis,
    logger
  )

  val statusActor = new StatusActor(
    imageName => icon.setImage(images(imageName)),
    tooltip => icon.setToolTip(tooltip),
  )

  var running = false
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
  def start() = {
    tray.add(icon)
    running = true
    agent.start(s =>
      statusActor.send(StatusActor.Syncing(s"Initializing Devbox\n$s"))
    )
    agentReadWriter.spawnReaderThread()

    syncer.send(SyncActor.Scan())

  }

  def close() = {
    tray.remove(icon)
    running = false
    watcher.close()
    agentReadWriter.send(AgentReadWriteActor.Close())
  }
}
