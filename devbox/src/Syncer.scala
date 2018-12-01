package devbox
import java.io._
import java.nio.ByteBuffer
import java.util.concurrent._

import devbox.common._
import os.Path
import upickle.default

import scala.annotation.tailrec
import scala.collection.mutable

class Syncer(agent: os.SubProcess,
             mapping: Seq[(os.Path, Seq[String])],
             skip: (os.Path, os.Path) => Boolean,
             debounceTime: Int,
             onComplete: () => Unit,
             logger: Logger) extends AutoCloseable{

  private[this] val eventQueue = new LinkedBlockingQueue[Array[String]]()
  private[this] val watcher = new FSEventsWatcher(mapping.map(_._1), p => {
    eventQueue.add(p)
    logger("FSEVENT", p)
  })

  private[this] var watcherThread: Thread = null
  private[this] var syncThread: Thread = null
  private[this] var agentLoggerThread: Thread = null

  @volatile private[this] var running: Boolean = false

  @volatile private[this] var asyncException: Throwable = null

  @volatile var writeCount = 0

  def makeLoggedThread(name: String)(t: => Unit) = new Thread(
    () =>
      try t
      catch {case e: Throwable =>
        e.printStackTrace()
        asyncException = e
        onComplete()
      },
    name
  )

  def start() = {
    running = true
    watcherThread = makeLoggedThread("DevboxWatcherThread") {
      watcher.start()
    }

    agentLoggerThread = makeLoggedThread("DevboxAgentLoggerThread") {
      while (running && agent.isAlive()) {
        val str = agent.stderr.readLine()
        if (str != null && str != "") logger.write(str)
      }
    }

    syncThread = makeLoggedThread("DevboxSyncThread") {
      Syncer.syncAllRepos(
        agent,
        mapping,
        onComplete,
        eventQueue,
        skip,
        debounceTime,
        () => running,
        logger,
        writeCount += _
      )
    }

    watcherThread.start()
    syncThread.start()
    agentLoggerThread.start()
  }

  def close() = {
    running = false
    watcher.stop()
    watcherThread.join()
    syncThread.interrupt()
    syncThread.join()
    agent.destroy()
    agentLoggerThread.interrupt()
    agentLoggerThread.join()
    if (asyncException != null) throw asyncException
  }
}

object Syncer{
  def syncAllRepos(agent: os.SubProcess,
                   mapping: Seq[(os.Path, Seq[String])],
                   onComplete: () => Unit,
                   eventQueue: BlockingQueue[Array[String]],
                   skip: (os.Path, os.Path) => Boolean,
                   debounceTime: Int,
                   continue: () => Boolean,
                   logger: Logger,
                   countWrite: Int => Unit) = {

    val vfsArr = for (_ <- mapping.indices) yield new Vfs[(Long, Seq[Bytes]), Int](0)
    val buffer = new Array[Byte](Signature.blockSize)

    val client = new RpcClient(
      new DataOutputStream(agent.stdin),
      new DataInputStream(agent.stdout)
    )

    // initial sync
    for (((src, dest), i) <- mapping.zipWithIndex) {

      client.writeMsg(Rpc.FullScan(""))
      val initialRemote = client.readMsg[Seq[(String, Signature)]]()
      val initialLocal = os.walk(
        src,
        p => skip(p, src) || !os.isDir(p, followLinks = false),
        includeTarget = true
      )
      eventQueue.add(initialLocal.map(_.toString).toArray)

      val vfs = vfsArr(i)
      for((p, sig) <- initialRemote) sig match{
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
    }

    while (continue()) {
      logger("SYNC")

      val interestingBasesOpt =
        try Some(Syncer.debouncedDeque(eventQueue, debounceTime))
        catch{case e: InterruptedException => None}

      for(interestingBases <- interestingBasesOpt){
//        try {
        for (((src, dest), i) <- mapping.zipWithIndex) {
          val srcEventDirs = interestingBases
            .map(os.Path(_))
            .filter(_.startsWith(src))
            .filter(!skip(_, src))
            .distinct

          if (srcEventDirs.nonEmpty) {
            logger("BASE", srcEventDirs.map(_.relativeTo(src).toString()))
            val signatureMapping = computeSignatures(
              srcEventDirs,
              buffer,
              vfsArr(i),
              skip,
              src
            )

            logger("SIGNATURE", signatureMapping)

            val count = Syncer.syncMetadata(
              client,
              signatureMapping,
              src,
              dest,
              vfsArr(i),
              logger,
              buffer
            )

            signatureMapping.collect{
              case (p, Some(Signature.File(_, blockHashes, _)), otherSig) =>
                val otherHashes = otherSig match{
                  case Some(Signature.File(_, otherBlockHashes, _)) => otherBlockHashes
                  case None =>  Nil
                }
                logger("STREAM CHUNKS", p.relativeTo(src).toString)
                streamFileContents(
                  client,
                  vfsArr(i),
                  p,
                  p.relativeTo(src).toString,
                  blockHashes,
                  otherHashes
                )

            }

            countWrite(count)
          }
        }
//        }catch{case e: Throwable =>
//          // There are a lot of odd reasons that can cause the syncer to crash,
//          // often due to the filesstem changing underneath it. In those cases
//          val x = new StringWriter()
//          val p = new PrintWriter(x)
//          e.printStackTrace(p)
//          logger("SYNC FAILED", x.toString)
//          eventQueue.add(interestingBases.toArray)
//        }
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

  def syncMetadata(client: RpcClient,
                   signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: Seq[String],
                   stateVfs: Vfs[(Long, Seq[Bytes]), Int],
                   logger: Logger,
                   buffer: Array[Byte]): Int = {

    var totalWrites = 0
    var pipelinedWrites = 0

    for((p, localSig, remoteSig) <- signatureMapping){
      pipelinedWrites += 1
      totalWrites += 1
      val segments = p.relativeTo(src).toString
      if (localSig != remoteSig) (localSig, remoteSig) match{
        case (None, _) =>
          performAction(client, stateVfs, Rpc.Remove(segments))
        case (Some(Signature.Dir(perms)), remote) =>
          remote match{
            case None =>
              performAction(client, stateVfs, Rpc.PutDir(segments, perms))
            case Some(Signature.Dir(remotePerms)) =>
              performAction(client, stateVfs, Rpc.SetPerms(segments, perms))
            case Some(_) =>
              performAction(client, stateVfs, Rpc.Remove(segments))
              performAction(client, stateVfs, Rpc.PutDir(segments, perms))
          }

        case (Some(Signature.Symlink(dest)), remote) =>
          remote match {
            case None =>
              performAction(client, stateVfs, Rpc.PutLink(segments, dest))
            case Some(_) =>
              performAction(client, stateVfs, Rpc.Remove(segments))
              performAction(client, stateVfs, Rpc.PutLink(segments, dest))
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          prepareRemoteFile(client, stateVfs, p, segments, perms, blockHashes, size, remote)
      }

      if (pipelinedWrites == 1000){
        for(i <- 0 until pipelinedWrites) assert(client.readMsg[Int]() == 0)
        pipelinedWrites = 0
      }
    }

    for(i <- 0 until pipelinedWrites) assert(client.readMsg[Int]() == 0)

    totalWrites
  }

  def prepareRemoteFile(client: RpcClient,
                        stateVfs: Vfs[(Long, Seq[Bytes]), Int],
                        p: Path,
                        segments: String,
                        perms: Int,
                        blockHashes: Seq[Bytes],
                        size: Long,
                        remote: Option[Signature]) = {
    if (remote.exists(!_.isInstanceOf[Signature.File])) {
      performAction(client, stateVfs, Rpc.Remove(segments))
    }

    remote match {
      case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
        if (perms != otherPerms) performAction(client, stateVfs, Rpc.SetPerms(segments, perms))
        if (otherSize > size) performAction(client, stateVfs, Rpc.Truncate(segments, size))
      case _ =>
        performAction(client, stateVfs, Rpc.PutFile(segments, perms))
    }
  }

  def streamFileContents(client: RpcClient,
                         stateVfs: Vfs[(Long, Seq[Bytes]), Int],
                         p: Path,
                         segments: String,
                         blockHashes: Seq[Bytes],
                         otherHashes: Seq[Bytes]) = {
    val byteArr = new Array[Byte](Signature.blockSize)
    val buf = ByteBuffer.wrap(byteArr)
    Util.autoclose(p.toSource.getChannel()) { channel =>
      for {
        i <- blockHashes.indices
        if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
      } {
        buf.rewind()
        channel.position(i * Signature.blockSize)
        var n = 0
        while ( {
          if (n == Signature.blockSize) false
          else channel.read(buf) match {
            case -1 => false
            case d =>
              n += d
              true
          }
        }) ()

        performAction(client, stateVfs,
          Rpc.WriteChunk(
            segments,
            i * Signature.blockSize,
            new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr),
            blockHashes(i)
          )
        )
      }
    }
  }

  def performAction[T <: Action: default.Writer](client: RpcClient,
                                                 stateVfs: Vfs[(Long, Seq[Bytes]), Int],
                                                 p: T) = {
    client.writeMsg(p)

    // Update stateVfs according to the given action
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

  }

  def computeSignatures(interestingBases: Seq[Path],
                        buffer: Array[Byte],
                        stateVfs: Vfs[(Long, Seq[Bytes]), Int],
                        skip: (os.Path, os.Path) => Boolean,
                        src: os.Path) = {
    def compute(p: Path, forceNone: Boolean) = {
      (
        p,
        if (forceNone) None else Some(Signature.compute(p, buffer)),
        stateVfs.resolve(p.relativeTo(src).toString()).map {
          case f: Vfs.File[(Long, Seq[Bytes]), Int] => Signature.File(f.metadata, f.value._2, f.value._1)
          case f: Vfs.Folder[(Long, Seq[Bytes]), Int] => Signature.Dir(f.metadata)
          case f: Vfs.Symlink => Signature.Symlink(f.value)
        }
      )
    }

    val signatureMapping = interestingBases
      // Only bother looking at paths which are canonical; changes to non-
      // canonical paths can be ignored because we'd also get the canonical
      // path that we can operate on.
      .filter(p => os.followLink(p).contains(p))
      .flatMap { p =>
        val listed =
          if (!os.exists(p, followLinks = false)) Nil
          else os.list(p).filter(!skip(_, src)).map(_.last)

        val listedNames = listed.toSet

        val virtual = stateVfs.resolve(p.relativeTo(src).toString) match {
          // We only care about the case where the there interesting path
          // points to a folder within the Vfs.
          case Some(f: Vfs.Folder[_, _]) => f.value.keys.toSeq
          // If it points to a non-folder, or a non-existent path, we assume
          // that previously there must have been a folder at that path that
          // got replaced by a non-folder or deleted. In which case the
          // enclosing folder should have it's own interestingBase
          case Some(_) => Nil
          case None => Nil
        }

        // We de-dup the combined list of names listed from the filesystem and
        // listed from the VFS, because they often have overlaps, and use
        // `listedNames` to check for previously-present-but-now-deleted files
        (listed ++ virtual).distinct.map(k => compute(p / k, !listedNames(k)))
      }

    val sortedSignatures = signatureMapping.sortBy { case (p, local, remote) =>
      (
        // First, sort by how deep the path is. We want to operate on the
        // shallower paths first before the deeper paths, so folders can be
        // created before their contents is written.
        p.segmentCount,
        // Next, we want to perform the deletions before any other operations.
        // If a file is renamed to a different case, we must make sure the old
        // file is deleted before the new file is written to avoid conflicts
        // on case-insensitive filesystems
        local.isDefined,
        // Lastly, we sort by the stringified path, for neatness
        p.toString
      )
    }

    sortedSignatures
  }
}
