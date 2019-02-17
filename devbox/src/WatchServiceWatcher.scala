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

class WatchServiceWatcher(root: os.Path,
                          onEvent: Array[String] => Unit,
                          ignorePaths: (os.Path, Boolean) => Boolean,
                          logger: Logger,
                          settleDelay: Double) extends AutoCloseable{
  val nioWatchService = FileSystems.getDefault.newWatchService()
  val currentlyWatchedPaths = mutable.Map.empty[os.Path, WatchKey]
  val bufferedEvents = mutable.Buffer.empty[os.Path]
  val isRunning = new AtomicBoolean(false)


  isRunning.set(true)
  try {
    val walk = os.walk.stream.attrs(
      root,
      skip = (p, attr) => ignorePaths(p, attr.isDir),
      includeTarget = true
    )

    for ((p, attrs) <- walk){
      try watchPath(p)
      catch {case e: IOException => println("IO Error when registering watch", e)}
    }
  }catch {case e: IOException => println("IO error when registering watches")}

  def watchPath(p: os.Path): Unit = {
    bufferedEvents.append(p)
    if (!currentlyWatchedPaths.contains(p) && os.isDir(p, followLinks = false)) {
      try{
        currentlyWatchedPaths.put(
          p,
          p.toNIO.register(
            nioWatchService,
            Array[WatchEvent.Kind[_]](ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW),
            SensitivityWatchEventModifier.HIGH
          )
        )
        for(sub <- os.list.stream(p)){
          watchPath(sub)
        }
      } catch{case e: java.nio.file.NotDirectoryException =>
        // do nothing
      }
    }
  }
  def processWatchKey(watchKey: WatchKey) = {
    val p = os.Path(watchKey.watchable().asInstanceOf[java.nio.file.Path], os.pwd)
    logger("ProcessWatchKey", p)
    bufferedEvents.append(p)
    val events = watchKey.pollEvents().asScala
    logger("WKE", events.map(_.context()))

    for(e <- events if e.kind() != ENTRY_DELETE){
      val c = os.Path(e.context().asInstanceOf[java.nio.file.Path], p)

      watchPath(c)
      logger("ProcessWatchKey C", c)
    }

    watchKey.reset()
  }

  def start(): Unit = {
    while (isRunning.get()) try {
      logger("Watched", currentlyWatchedPaths)
      val watchKey0 = nioWatchService.take()
      if (watchKey0 != null){
        processWatchKey(watchKey0)
        while({
          nioWatchService.poll() match{
            case null => false
            case watchKey =>
              processWatchKey(watchKey)
              true
          }
        })()

        debouncedTriggerListener()

        // cleanup stale watches
        for(p <- currentlyWatchedPaths.keySet if !os.exists(p, followLinks = false)){
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

  private def debouncedTriggerListener(): Unit = {
    logger("bufferedEvents", bufferedEvents)
    val strings = bufferedEvents.iterator
      .map{p => if (os.isDir(p, followLinks = false)) p else p / os.up}
      .map(_.toString)
      .toArray
    logger("DTL strings", strings)
    onEvent(strings)
    bufferedEvents.clear()
  }
}
