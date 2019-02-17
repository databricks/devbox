package devbox

import java.nio.file._

import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}

import scala.collection.mutable
import collection.JavaConverters._

class WatchServiceWatcher(root: os.Path,
                          ignorePaths: os.Path => Boolean,
                          settleDelay: Int,
                          onEvent: Array[String] => Unit) extends AutoCloseable{
  val nioWatchService = FileSystems.getDefault.newWatchService()
  val currentlyWatchedPaths = mutable.Map.empty[os.Path, WatchKey]
  val bufferedEvents = mutable.Buffer.empty[os.Path]
  val isRunning = new AtomicBoolean(false)
  val watchServiceThread = new Thread(
    new Runnable() {
      override def run(): Unit = {
        isRunning.set(true)
        walkTreeAndSetWatches()

        while (isRunning.get()) try {
          val watchKey0 = nioWatchService.take
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
        } catch {
          case e: InterruptedException =>
            println("Interrupted, exiting", e)
            isRunning.set(false)
          case e: ClosedWatchServiceException =>
            println("Watcher closed, exiting", e)
            isRunning.set(false)
        }
      }
    },
    "watcher-thread-" + root.last
  )

  var timer: java.util.Timer = null

  def processWatchKey(watchKey: WatchKey) = {
    bufferedEvents.append(os.Path(watchKey.watchable().asInstanceOf[java.nio.file.Path], os.pwd))
    watchKey.pollEvents()
    watchKey.reset()
  }

  def watchEventsOccurred(): Unit = {
    walkTreeAndSetWatches()
    for(p <- currentlyWatchedPaths.keySet if !os.exists(p, followLinks = false)){
      currentlyWatchedPaths.remove(p).foreach(_.cancel())
    }
  }

  def walkTreeAndSetWatches(): Unit = {
    try {
      for {
        (p, attrs) <- os.walk.stream.attrs(root, skip = (p, attr) => ignorePaths(p))
        if attrs.isDir && !currentlyWatchedPaths.contains(p)
      } {
        try currentlyWatchedPaths.put(
          p,
          p.toNIO.register(
            nioWatchService,
            ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW
          )
        ) catch {
          case e: IOException => println("IO Error when registering watch", e)
        }
      }
    }catch {
      case e: IOException => println("IO error when registering watches")
    }
  }

  def start(): Unit = {
    watchServiceThread.start()
  }

  def close(): Unit = {
    if (watchServiceThread != null) try {
      isRunning.set(false)
      watchServiceThread.interrupt()
      nioWatchService.close()
    } catch {
      case e: IOException => println("Error closing watcher", e)
    }
  }

  private def debouncedTriggerListener(): Unit = {
    if (timer != null) {
      timer.cancel()
      timer = null
    }
    timer = new Timer("timer-" + root.last)
    timer.schedule(
      new TimerTask() {
        override def run(): Unit = {
          watchEventsOccurred()
          onEvent(bufferedEvents.map(_.toString).toArray)
        }
      },
      settleDelay
    )
  }
}
