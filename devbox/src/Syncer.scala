package devbox
import java.io.{DataInputStream, DataOutputStream}
import java.nio.ByteBuffer
import java.util.concurrent._

import devbox.common._

import scala.annotation.tailrec
import scala.collection.mutable

class Syncer(commandRunner: os.SubProcess,
             mapping: Seq[(os.Path, Seq[String])],
             skip: os.Path => Boolean,
             debounceTime: Int,
             onComplete: () => Unit,
             verbose: Boolean) {

  private[this] val eventQueue = new LinkedBlockingQueue[Array[String]]()
  private[this] val watcher = new FSEventsWatcher(mapping.map(_._1), p => {
    eventQueue.add(p)
    if (verbose) for(path <- p) println("FSEVENT " + p)
  })

  private[this] var watcherThread: Thread = null
  private[this] var syncThread: Thread = null

  @volatile private[this] var running: Boolean = false

  @volatile private[this] var asyncException: Throwable = null

  @volatile var writeCount = 0

  def start() = {
    running = true
    watcherThread = new Thread(
      () =>
        try watcher.start()
        catch {case e: Throwable =>
          asyncException = e
          onComplete()
        },
      "DevboxWatcherThread"
    )

    syncThread = new Thread(
      () =>
        try Syncer.syncAllRepos(
          commandRunner,
          mapping,
          onComplete,
          eventQueue,
          skip,
          debounceTime,
          () => running,
          verbose,
          writeCount += _
        ) catch {case e: Throwable =>
          asyncException = e
          onComplete()
        },
      "DevboxSyncThread"
    )

    watcherThread.start()
    syncThread.start()
  }

  def close() = {
    running = false
    watcher.stop()
    watcherThread.join()
    syncThread.interrupt()
    syncThread.join()
    if (asyncException != null) throw asyncException
  }
}

object Syncer{
  def syncAllRepos(commandRunner: os.SubProcess,
                   mapping: Seq[(os.Path, Seq[String])],
                   onComplete: () => Unit,
                   eventQueue: BlockingQueue[Array[String]],
                   skip: os.Path => Boolean,
                   debounceTime: Int,
                   continue: () => Boolean,
                   verbose: Boolean,
                   countWrite: Int => Unit) = {

    val vfsArr = for (_ <- mapping.indices) yield new Vfs[(Long, Seq[Bytes]), Int](0)

    if (verbose) println("Initial Sync")

    val client = new RpcClient(
      new DataOutputStream(commandRunner.stdin),
      new DataInputStream(commandRunner.stdout) 
    )

    // initial sync
    for (((src, dest), i) <- mapping.zipWithIndex) {

      client.writeMsg(Rpc.FullScan(""))
      val initial = client.readMsg[Seq[(String, Signature)]]()

      val vfs = vfsArr(i)
      for((p, sig) <- initial) sig match{
        case Signature.File(perms, hashes, size) =>
          val (name, folder) = vfs.resolveParent(p).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.File(perms, (size, hashes))

        case Signature.Dir(perms) =>
          val (name, folder) = vfs.resolveParent(p).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.Folder(perms, mutable.LinkedHashMap.empty[String, Vfs.Node[(Long, Seq[Bytes]), Int]])

        case Signature.Symlink(dest) =>
          val (name, folder) = vfs.resolveParent(p).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.Symlink(dest)
      }

      val count = Syncer.syncRepo(
        client,
        src,
        dest,
        vfsArr(i),
        os.walk(src, p => skip(p) || !os.isDir(p, followLinks = false), includeTarget = true),
        skip,
        verbose
      )

      countWrite(count)
    }

    if (eventQueue.isEmpty) onComplete()

    while (continue()) {


      if (verbose) println("Incremental Sync")

      val interestingBasesOpt =
        try Some(Syncer.debouncedDeque(eventQueue, debounceTime))
        catch{case e: InterruptedException => None}

      for(interestingBases <- interestingBasesOpt){
        for (((src, dest), i) <- mapping.zipWithIndex) {
          val srcEventDirs = interestingBases
            .map(os.Path(_))
            .filter(_.startsWith(src))
            .filter(!skip(_))
            .distinct

          if (srcEventDirs.nonEmpty) {
            val count = Syncer.syncRepo(client, src, dest, vfsArr(i), srcEventDirs, skip, verbose)
            countWrite(count)
          }
        }
      }

      if (eventQueue.isEmpty) onComplete()
    }
  }

  def debouncedDeque[T](eventQueue: BlockingQueue[Array[T]], debounceTime: Int) = {
    val interestingBases = mutable.Buffer.empty[T]

    /**
      * Keep pulling stuff out of the [[eventQueue]], until the queue has
      * stopped changing and is empty twice in a row.
      */
    @tailrec def await(first: Boolean, alreadyRetried: Boolean): Unit = {
      if (first) {
        interestingBases.appendAll(eventQueue.take())
        await(false, false)
      } else eventQueue.poll() match {
        case null =>
          if (alreadyRetried) () // terminate
          else {
            Thread.sleep(debounceTime)
            await(false, true)
          }
        case arr =>
          interestingBases.appendAll(arr)
          await(false, false)
      }
    }

    await(true, false)

    interestingBases
  }

  def syncRepo(client: RpcClient,
               src: os.Path,
               dest: Seq[String],
               stateVfs: Vfs[(Long, Seq[Bytes]), Int],
               interestingBases: Seq[os.Path],
               skip: os.Path => Boolean,
               verbose: Boolean): Int = {

    if (verbose) for(p <- interestingBases) println("BASE " + p.relativeTo(src))

    // interestingBases.foreach(x => println("BASE " + x))
    val signatureMapping = interestingBases
      // Only bother looking at paths which are canonical; changes to non-
      // canonical paths can be ignored because we'd also get the canonical
      // path that we can operate on.
      .filter(p => os.followLink(p).contains(p))
      .sortBy(_.segmentCount)
      .flatMap { p =>
        val listed =
          if (!os.exists(p, followLinks = false)) Nil
          else os.list(p).filter(!skip(_)).map(_.relativeTo(src).toString)

        val virtual = stateVfs.resolve(p.relativeTo(src).toString).fold(Seq[String]()) {
          case f: Vfs.File[_, _] => Seq(p.relativeTo(src).toString)
          case f: Vfs.Folder[_, _] => f.value.keys.map(k => (p.relativeTo(src) / k).toString).toSeq
          case f: Vfs.Symlink => Seq(p.relativeTo(src).toString)
        }

        (listed ++ virtual).map { p1 =>
          (
            src / os.RelPath(p1),
            Signature.compute(src / os.RelPath(p1)),
            stateVfs.resolve(p1).map {
              case f: Vfs.File[(Long, Seq[Bytes]), Int] => Signature.File(f.metadata, f.value._2, f.value._1)
              case f: Vfs.Folder[(Long, Seq[Bytes]), Int] => Signature.Dir(f.metadata)
              case f: Vfs.Symlink => Signature.Symlink(f.value)
            }
          )
        }
      }

    var totalWrites = 0
    var pipelinedWrites = 0
    def performAction[T <: Action: upickle.default.Writer](p: T) = {
      client.writeMsg(p)
      p match{
        case Rpc.PutFile(path, perms) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.File(perms, (0, Nil))

        case Rpc.Remove(path) =>
          stateVfs.resolveParent(path).foreach{
            case (name, folder) => folder.value.remove(name)
          }

        case Rpc.PutDir(path, perms) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.Folder(perms, mutable.LinkedHashMap.empty[String, Vfs.Node[(Long, Seq[Bytes]), Int]])

        case Rpc.PutLink(path, dest) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.Symlink(dest)

        case Rpc.WriteChunk(path, offset, bytes, hash) =>
          assert(offset % Signature.blockSize == 0)
          val index = offset / Signature.blockSize
          val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[(Long, Seq[Bytes]), Int]]
          currentFile.value = (
            currentFile.value._1,
            if (index < currentFile.value._2.length) currentFile.value._2.updated(index.toInt, hash)
            else if (index == currentFile.value._2.length) currentFile.value._2 :+ hash
            else ???
          )

        case Rpc.Truncate(path, offset) =>
          val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[(Long, Seq[Bytes]), Int]]
          currentFile.value = (offset, currentFile.value._2)

        case Rpc.SetPerms(path, perms) =>
          stateVfs.resolve(path) match{
            case Some(f @ Vfs.File(_, _)) => f.metadata = perms
            case Some(f @ Vfs.Folder(_, _)) => f.metadata = perms
          }
      }
      pipelinedWrites += 1
      totalWrites += 1
    }

    // signatureMapping.map(_._1).foreach(x => println("SIG " + x))
    for((p, localSig, remoteSig) <- signatureMapping.sortBy(x => (x._1.segmentCount, x._1.toString))){

      val segments = p.relativeTo(src).toString
      if (localSig != remoteSig) {
        (localSig, remoteSig) match{
          case (None, _) =>
            performAction(Rpc.Remove(segments))
          case (Some(Signature.Dir(perms)), remote) =>
            remote match{
              case None =>
                performAction(Rpc.PutDir(segments, perms))
              case Some(Signature.Dir(remotePerms)) =>
                performAction(Rpc.SetPerms(segments, perms))
              case Some(_) =>
                performAction(Rpc.Remove(segments))
                performAction(Rpc.PutDir(segments, perms))
            }

          case (Some(Signature.Symlink(dest)), remote) =>
            remote match {
              case None =>
                performAction(Rpc.PutLink(segments, dest))
              case Some(_) =>
                performAction(Rpc.Remove(segments))
                performAction(Rpc.PutLink(segments, dest))
            }
          case (Some(Signature.File(perms, blockHashes, size)), remote) =>
            if (remote.exists(!_.isInstanceOf[Signature.File])){
              performAction(Rpc.Remove(segments))
            }

            val (otherHashes, otherSize) = remote match{
              case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
                if (perms != otherPerms) {
                  performAction(Rpc.SetPerms(segments, perms))
                }
                otherBlockHashes -> otherSize
              case _ =>
                performAction(Rpc.PutFile(segments, perms))
                Nil -> 0L
            }

            val channel = p.toSource.getChannel()
            val byteArr = new Array[Byte](Signature.blockSize)
            val buf = ByteBuffer.wrap(byteArr)
            try {
              for {
                i <- blockHashes.indices
                if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
              } {
                buf.rewind()
                channel.position(i * Signature.blockSize)
                var n = 0
                while({
                  if (n == Signature.blockSize) false
                  else channel.read(buf) match{
                    case -1 => false
                    case d =>
                      n += d
                      true
                  }
                })()

                performAction(
                  Rpc.WriteChunk(
                    segments,
                    i * Signature.blockSize,
                    Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr),
                    blockHashes(i)
                  )
                )
              }
            }finally{
              channel.close()
            }

            performAction(Rpc.Truncate(segments, size))
        }
      }
      if (pipelinedWrites == 1000){
        for(i <- 0 until pipelinedWrites) assert(client.readMsg[Int]() == 0)
        pipelinedWrites = 0
      }
    }

    for(i <- 0 until pipelinedWrites) assert(client.readMsg[Int]() == 0)

    totalWrites
  }
}
