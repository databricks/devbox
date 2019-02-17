package devbox

import java.nio.file._
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.util.concurrent.TimeUnit

import com.sun.nio.file.SensitivityWatchEventModifier

import scala.collection.mutable
import collection.JavaConverters._

class WatchServiceWatcher(root: os.Path,
                          onEvent: Array[String] => Unit,
                          ignorePaths: (os.Path, Boolean) => Boolean,
                          settleDelay: Double) extends AutoCloseable{
  val nioWatchService = FileSystems.getDefault.newWatchService()
  val currentlyWatchedPaths = mutable.Map.empty[os.Path, WatchKey]
  val bufferedEvents = mutable.Buffer.empty[os.Path]
  val isRunning = new AtomicBoolean(false)


  println("WatchServiceWatcher 0")
  isRunning.set(true)
  println("WatchServiceWatcher 1")
  walkTreeAndSetWatches()
  println("WatchServiceWatcher 2")

  def processWatchKey(watchKey: WatchKey) = {
    println("WatchServiceWatcher.processWatchKey")
    val p = os.Path(watchKey.watchable().asInstanceOf[java.nio.file.Path], os.pwd)
    pprint.log(p)
    bufferedEvents.append(p)
    val events = watchKey.pollEvents()
    pprint.log(p)
    pprint.log(events.asScala)
    watchKey.reset()
  }

  def watchEventsOccurred(): Unit = {
    for(p <- currentlyWatchedPaths.keySet if !os.exists(p, followLinks = false)){
      println("REMOVING DEAD WATCH " + p)
      currentlyWatchedPaths.remove(p).foreach(_.cancel())
    }
    walkTreeAndSetWatches()
  }

  def walkTreeAndSetWatches(): Unit = {
    println("WatchServiceWatcher walkTreeTreeAndSetWatches 0 " + root)

    try {
      for {
        (p, attrs) <- os.walk.stream.attrs(root, skip = (p, attr) => ignorePaths(p, attr.isDir), includeTarget = true)
        if attrs.isDir && !currentlyWatchedPaths.contains(p)
      } {
        pprint.log(p)
        try currentlyWatchedPaths.put(
          p,
          p.toNIO.register(
            nioWatchService,
            Array[WatchEvent.Kind[_]](ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW)
//            SensitivityWatchEventModifier.HIGH
          )
        ) catch {
          case e: IOException => println("IO Error when registering watch", e)
        }
      }
    }catch {
      case e: IOException => println("IO error when registering watches")
    }
    pprint.log(currentlyWatchedPaths)
    println("WatchServiceWatcher walkTreeTreeAndSetWatches 1")
  }

  def start(): Unit = {
    println("WatchServiceWatcher.start 0")
    while (isRunning.get()) try {
//      println("WatchServiceWatcher.start 1")
//      pprint.log(currentlyWatchedPaths)
      nioWatchService.poll(100, TimeUnit.MILLISECONDS) match{
        case null => //continue
        case watchKey0 =>
//          println("WatchServiceWatcher.start 2")
          processWatchKey(watchKey0)
//          println("WatchServiceWatcher.start 3")
          while({
            nioWatchService.poll() match{
              case null => false
              case watchKey =>
//                println("WatchServiceWatcher.start 4")
                processWatchKey(watchKey)
                true
            }
          })()

//          println("WatchServiceWatcher.start 5")
          debouncedTriggerListener()
          watchEventsOccurred()
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
    println("WatchServiceWalker.close")
    try {
      isRunning.set(false)
      nioWatchService.close()
    } catch {
      case e: IOException => println("Error closing watcher", e)
    }
  }

  private def debouncedTriggerListener(): Unit = {
    onEvent(
      bufferedEvents.iterator
        .map{p => if (os.isDir(p, followLinks = false)) p else p / os.up}
        .map(_.toString)
        .toArray
    )
    bufferedEvents.clear()
  }
}
