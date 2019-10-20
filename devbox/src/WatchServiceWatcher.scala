package devbox

import java.nio.file._
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.util.concurrent.TimeUnit

import com.sun.nio.file.SensitivityWatchEventModifier
import devbox.common.Logger

import scala.collection.mutable
import collection.JavaConverters._

class WatchServiceWatcher(roots: Seq[os.Path],
                          onEvent: Array[String] => Unit,
                          logger: Logger) extends Watcher{

  val nioWatchService = FileSystems.getDefault.newWatchService()
  val currentlyWatchedPaths = mutable.Map.empty[os.Path, WatchKey]
  val newlyWatchedPaths = mutable.Buffer.empty[os.Path]
  val bufferedEvents = mutable.Buffer.empty[os.Path]
  val isRunning = new AtomicBoolean(false)

  isRunning.set(true)

  roots.foreach(watchSinglePath)
  recursiveWatches()

  def watchSinglePath(p: os.Path) = {
    logger("watchSinglePath", p)
    if (os.isDir(p, followLinks = false)) {
      logger("watchSinglePath", "dir")
      currentlyWatchedPaths.put(
        p,
        p.toNIO.register(
          nioWatchService,
          Array[WatchEvent.Kind[_]](ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW),
          SensitivityWatchEventModifier.HIGH
        )
      )
      newlyWatchedPaths.append(p)
    }
    bufferedEvents.append(p)
  }

  def processEvent(watchKey: WatchKey) = {
    val p = os.Path(watchKey.watchable().asInstanceOf[java.nio.file.Path], os.pwd)
    logger("ProcessWatchKey", p)

    val events = watchKey.pollEvents().asScala

    logger("EVENT CONTEXTS", events.map(_.context()))


    logger("EVENT KINDS", events.map(_.kind()))

    for(e <- events){
      bufferedEvents.append(p / e.context().toString)
    }

    for(e <- events if e.kind() == ENTRY_CREATE){
      watchSinglePath(p / e.context().toString)
    }

    watchKey.reset()
  }

  def recursiveWatches() = {
    while(newlyWatchedPaths.nonEmpty){
      val top = newlyWatchedPaths.remove(newlyWatchedPaths.length - 1)
      val listing = try os.list(top) catch {case e: java.nio.file.NotDirectoryException => Nil }
      for(p <- listing) watchSinglePath(p)
      bufferedEvents.append(top)
    }
  }

  def start(): Unit = {
    while (isRunning.get()) try {
      logger("Watched", currentlyWatchedPaths)
      val watchKey0 = nioWatchService.take()
      if (watchKey0 != null){
        logger("watchKey0", watchKey0.watchable())
        processEvent(watchKey0)
        while({
          nioWatchService.poll() match{
            case null => false
            case watchKey =>
              logger("watchKey", watchKey.watchable())
              processEvent(watchKey)
              true
          }
        })()

        recursiveWatches()
        triggerListener()

        // cleanup stale watches
        for(p <- currentlyWatchedPaths.keySet if !os.isDir(p, followLinks = false)){
          logger("cancel", p)
          currentlyWatchedPaths.remove(p).foreach(_.cancel())
        }
      }

    } catch {
      case e: InterruptedException =>
        println("Interrupted, exiting", e)
        isRunning.set(false)
      case e: ClosedWatchServiceException =>
        println("Watcher closed, exiting", e)
        isRunning.set(false)
    }
  }

  def close(): Unit = {
    try {
      isRunning.set(false)
      nioWatchService.close()
    } catch {
      case e: IOException => println("Error closing watcher", e)
    }
  }

  private def triggerListener(): Unit = {
    logger("bufferedEvents", bufferedEvents)
    val strings = bufferedEvents.iterator
      .map(_.toString)
      .toArray
      .distinct
    logger("triggered strings", strings)
    onEvent(strings)
    bufferedEvents.clear()
  }
}
