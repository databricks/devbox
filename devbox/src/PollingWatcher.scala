package devbox
import java.io.IOException
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicBoolean

import devbox.common.{Logger, Vfs}

import scala.collection.mutable


class PollingWatcher(root: os.Path,
                     onEvent: Array[String] => Unit,
                     ignorePaths: (os.Path, Boolean) => Boolean,
                     logger: Logger,
                     settleDelay: Double) extends AutoCloseable{
  val mtimes = new Vfs[FileTime](FileTime.fromMillis(0))
  val isRunning = new AtomicBoolean(false)


  isRunning.set(true)
  val walk = os.walk.stream.attrs(
    root,
    skip = (p, attr) => ignorePaths(p, attr.isDir),
    includeTarget = true
  )
  def makeNode(attrs: os.BasicStatInfo) = attrs.fileType match{
    case os.FileType.File => new Vfs.File[FileTime](attrs.mtime)
    case os.FileType.Dir => new Vfs.Dir[FileTime](attrs.mtime, mutable.LinkedHashMap.empty)
    case os.FileType.SymLink => new Vfs.File[FileTime](attrs.mtime)
    case os.FileType.Other => new Vfs.File[FileTime](attrs.mtime)
  }
  def fullScan() = {
    val seen = mutable.Set.empty[Seq[String]]
    for ((p, attrs) <- walk){
      seen.add(p.relativeTo(root).segments)
      if (p == root) {
        if (mtimes.root.value != attrs.mtime) {
          onEvent(Array((p / os.up).toString))
          mtimes.root.value = attrs.mtime
        }
      }else{
        val (name, dir) = mtimes.resolveParent(p.relativeTo(root)).get
        dir.children.get(name) match{
          case None =>
            println("None -> Some")
            println(p.relativeTo(root))
            println(name)
            println(dir)
            dir.children(name) = makeNode(attrs)
            println(dir.children)
            onEvent(Array((p / os.up).toString))
          case Some(child) =>
            val expectedType = attrs.fileType match{
              case os.FileType.File | os.FileType.SymLink | os.FileType.Other => classOf[Vfs.File[_]]
              case os.FileType.Dir => classOf[Vfs.Dir[_]]
            }

            if (child.getClass != expectedType){
              println(child.getClass + " -> " + expectedType)
              dir.children(name) = makeNode(attrs)
              onEvent(Array((p / os.up).toString))
            }else if(child.value != attrs.mtime){
              println("New Mtime " + p.relativeTo(root))
              child.value = attrs.mtime
              onEvent(Array((p / os.up).toString))
            }else{
              println("UNCHANGED " + p.relativeTo(root))
            }
        }
      }
    }

    for{
      (path, item, parentOpt) <- mtimes.walk()
      parent <- parentOpt
      if !seen.contains(path.reverse)
    }{
      println("DELETE")
      println(path)
      parent.children.remove(path.head)
      onEvent(Array((root / path.tail.reverse).toString))
    }
  }

  fullScan()



  def start(): Unit = {
    while (isRunning.get()) try {
      Thread.sleep((1000 * settleDelay).toLong)
      fullScan()
    }
  }

  def close(): Unit = {
    try {
      isRunning.set(false)
    } catch {
      case e: IOException => println("Error closing watcher", e)
    }
  }
}
