package devbox
import java.io._
import java.nio.ByteBuffer
import java.util.concurrent._

import devbox.common._
import os.Path
import upickle.default

import scala.annotation.tailrec
import scala.collection.mutable

/**
  * The Syncer class instances contain all the stateful, close-able parts of
  * the syncing logic: event queues, threads, filesystem watchers, etc. All the
  * stateless call-and-forget logic is pushed into static methods on the Syncer
  * companion object
  */
class Syncer(agent: AgentApi,
             mapping: Seq[(os.Path, Seq[String])],
             skip: (os.Path, os.Path) => Boolean,
             debounceTime: Int,
             onComplete: () => Unit,
             logger: Logger) extends AutoCloseable{

  private[this] val eventQueue = new LinkedBlockingQueue[Array[String]]()
  private[this] val watcher = new FSEventsWatcher(mapping.map(_._1), eventQueue.add, logger)

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
        try {
          val str = agent.stderr.readLine()
          if (str != null) logger.write(ujson.read(str).str)
        }catch{
          case e: InterruptedIOException => //do nothing
          case e: InterruptedException => //do nothing
        }
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
    if (asyncException != null) throw new Exception("Syncer thread failed", asyncException)
  }
}

object Syncer{
  class VfsRpcClient(val client: RpcClient, stateVfs: Vfs[Signature]){
    def clearOutstandingMsgs() = client.clearOutstandingMsgs()
    def drainOutstandingMsgs() = client.drainOutstandingMsgs()
    def apply[T <: Action: default.Writer](p: T) = {
      client.writeMsg(p)
      Vfs.updateVfs(p, stateVfs)
    }
  }

  def syncAllRepos(agent: AgentApi,
                   mapping: Seq[(os.Path, Seq[String])],
                   onComplete: () => Unit,
                   eventQueue: BlockingQueue[Array[String]],
                   skip: (os.Path, os.Path) => Boolean,
                   debounceTime: Int,
                   continue: () => Boolean,
                   logger: Logger,
                   countWrite: Int => Unit) = {

    val vfsArr = for (_ <- mapping.indices) yield new Vfs[Signature](Signature.Dir(0))
    val buffer = new Array[Byte](Util.blockSize)

    val client = new RpcClient(agent.stdin, agent.stdout, (tag, t) => logger("SYNC " + tag, t))

    logger("SYNC SCAN")
    for (((src, dest), i) <- mapping.zipWithIndex) {

      client.writeMsg(Rpc.FullScan(dest.mkString("/")))
      while({
        client.readMsg[Option[(String, Signature)]]() match{
          case None =>
            logger("SYNC SCAN DONE")
            false
          case Some((p, sig)) =>
            logger("SYNC SCAN ITEM", (p, sig))
            Vfs.updateVfs(p, sig, vfsArr(i))
            true
        }
      })()

      val initialLocal = os.walk.stream(
        src,
        p => skip(p, src) || !os.isDir(p, followLinks = false),
        includeTarget = true
      )
      eventQueue.add(initialLocal.map(_.toString).toArray)
    }

    while (continue()) {
      logger("SYNC LOOP")

      for{
        interestingBases <-
          try Some(Syncer.drainUntilStable(eventQueue, debounceTime))
          catch{case e: InterruptedException =>
            logger("SYNC INTERRUPT")
            None
          }

        // We need to .distinct after we convert the strings to paths, in order
        // to ensure the inputs are canonicalized and don't have meaningless
        // differences such as trailing slashes
        allSrcEventDirs = interestingBases.flatten.sorted.map(os.Path(_)).distinct
        _ = logger("SYNC EVENTS", allSrcEventDirs)
        ((src, dest), i) <- mapping.zipWithIndex

        srcEventDirs = allSrcEventDirs.filter(p => p.startsWith(src) && !skip(p, src))

        if srcEventDirs.nonEmpty

        _ = logger("SYNC BASE", srcEventDirs.map(_.relativeTo(src).toString()))

        signatureMapping <- restartOnFailure(
          logger, interestingBases, eventQueue,
          computeSignatures(srcEventDirs, buffer, vfsArr(i), skip, src)
        )

        _ = logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

        sortedSignatures = sortSignatureChanges(signatureMapping)

        vfsRpcClient = new VfsRpcClient(client, vfsArr(i))

        count = Syncer.syncMetadata(vfsRpcClient, sortedSignatures, src, dest, vfsArr(i), logger, buffer)

        _ <- restartOnFailure(
          logger, interestingBases, eventQueue,
          streamAllFileContents(logger, vfsArr(i), vfsRpcClient, src, signatureMapping)
        )
      } {
        countWrite(count)
      }

      if (eventQueue.isEmpty) {
        logger("SYNC COMPLETE")
        onComplete()
      }else{
        logger("SYNC PARTIAL")
      }
    }
  }

  def sortSignatureChanges(signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])]) = {
    signatureMapping.sortBy { case (p, local, remote) =>
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
  }

  def restartOnFailure[T](logger: Logger,
                          interestingBases: Seq[Array[String]],
                          eventQueue: BlockingQueue[Array[String]],
                          t: => T)  = {
    try Some(t)
    catch{
      case e: RpcException => throw e
      case e: Exception =>
        val x = new StringWriter()
        val p = new PrintWriter(x)
        e.printStackTrace(p)
        logger("SYNC FAILED", x.toString)
        interestingBases.foreach(eventQueue.add)
        None
    }
  }

  def streamAllFileContents(logger: Logger,
                            stateVfs: Vfs[Signature],
                            client: VfsRpcClient,
                            src: Path,
                            signatureMapping: Seq[(Path, Option[Signature], Option[Signature])]) = {
    draining(client) {
      for (((p, Some(Signature.File(_, blockHashes, size)), otherSig), n) <- signatureMapping.zipWithIndex) {
        val (otherHashes, otherSize) = otherSig match {
          case Some(Signature.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
          case _ => (Nil, 0L)
        }
        logger("SYNC CHUNKS", p.relativeTo(src).toString)
        streamFileContents(
          client,
          stateVfs,
          p,
          p.relativeTo(src).toString,
          blockHashes,
          otherHashes,
          size,
          otherSize
        )
      }
    }
  }

  def drainUntilStable[T](eventQueue: BlockingQueue[T], debounceTime: Int) = {
    val interestingBases = mutable.Buffer.empty[T]

    /**
      * Keep pulling stuff out of the [[eventQueue]], until the queue has
      * stopped changing and is empty twice in a row.
      */
    @tailrec def await(first: Boolean, alreadyRetried: Boolean): Unit = {
      if (first) {
        interestingBases.append(eventQueue.take())
        await(false, false)
      } else eventQueue.poll() match {
        case null =>
          if (alreadyRetried) () // terminate
          else {
            Thread.sleep(debounceTime)
            await(false, true)
          }
        case arr =>
          interestingBases.append(arr)
          await(false, false)
      }
    }

    await(true, false)

    interestingBases
  }

  def draining[T](client: VfsRpcClient)(t: => T): T = {
    client.clearOutstandingMsgs()
    @volatile var running = true
    val drainer = new Thread(
      () => {
        while(running || client.client.getOutstandingMsgs > 0){
          if (client.client.getOutstandingMsgs > 0) client.drainOutstandingMsgs()
          else Thread.sleep(5)
        }
      },
      "DevboxDrainerThread"
    )
    drainer.start()
    val res = t
    running = false
    drainer.join()

    res
  }

  def syncMetadata(client: VfsRpcClient,
                   signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: Seq[String],
                   stateVfs: Vfs[Signature],
                   logger: Logger,
                   buffer: Array[Byte]): Int = {

    var totalWrites = 0

    draining(client) {
      for (((p, localSig, remoteSig), i) <- signatureMapping.zipWithIndex) {
        totalWrites += 1
        val segments = p.relativeTo(src).toString
        if (localSig != remoteSig) (localSig, remoteSig) match {
          case (None, _) =>
            client(Rpc.Remove(segments))
          case (Some(Signature.Dir(perms)), remote) =>
            remote match {
              case None =>
                client(Rpc.PutDir(segments, perms))
              case Some(Signature.Dir(remotePerms)) =>
                client(Rpc.SetPerms(segments, perms))
              case Some(_) =>
                client(Rpc.Remove(segments))
                client(Rpc.PutDir(segments, perms))
            }

          case (Some(Signature.Symlink(dest)), remote) =>
            remote match {
              case None =>
                client(Rpc.PutLink(segments, dest))
              case Some(_) =>
                client(Rpc.Remove(segments))
                client(Rpc.PutLink(segments, dest))
            }
          case (Some(Signature.File(perms, blockHashes, size)), remote) =>
            prepareRemoteFile(client, stateVfs, p, segments, perms, blockHashes, size, remote)
        }

      }
    }


    totalWrites
  }

  def prepareRemoteFile(client: VfsRpcClient,
                        stateVfs: Vfs[Signature],
                        p: Path,
                        segments: String,
                        perms: os.PermSet,
                        blockHashes: Seq[Bytes],
                        size: Long,
                        remote: Option[Signature]) = {
    if (remote.exists(!_.isInstanceOf[Signature.File])) {
      client(Rpc.Remove(segments))
    }

    remote match {
      case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
        if (perms != otherPerms) client(Rpc.SetPerms(segments, perms))

      case _ => client(Rpc.PutFile(segments, perms))

    }
  }

  def streamFileContents(client: VfsRpcClient,
                         stateVfs: Vfs[Signature],
                         p: Path,
                         segments: String,
                         blockHashes: Seq[Bytes],
                         otherHashes: Seq[Bytes],
                         size: Long,
                         otherSize: Long) = {
    val byteArr = new Array[Byte](Util.blockSize)
    val buf = ByteBuffer.wrap(byteArr)
    Util.autoclose(os.read.channel(p)) { channel =>
      for {
        i <- blockHashes.indices
        if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
      } {
        buf.rewind()
        channel.position(i * Util.blockSize)
        var n = 0
        while ( {
          if (n == Util.blockSize) false
          else channel.read(buf) match {
            case -1 => false
            case d =>
              n += d
              true
          }
        }) ()

        client(
          Rpc.WriteChunk(
            segments,
            i * Util.blockSize,
            new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr),
            blockHashes(i)
          )
        )
      }
    }

    if (size != otherSize) client(Rpc.SetSize(segments, size))
  }

  def computeSignatures(interestingBases: Seq[Path],
                        buffer: Array[Byte],
                        stateVfs: Vfs[Signature],
                        skip: (os.Path, os.Path) => Boolean,
                        src: os.Path): Seq[(os.Path, Option[Signature], Option[Signature])] = {
    interestingBases.flatMap { p =>
        val listed =
          if (!os.isDir(p, followLinks = false)) os.Generator.apply()
          else os.list.stream(p).filter(!skip(_, src)).map(_.last)

        val listedNames = listed.toSet

        val virtual = stateVfs.resolve(p.relativeTo(src).toString) match {
          // We only care about the case where the there interesting path
          // points to a folder within the Vfs.
          case Some(f: Vfs.Dir[Signature]) => f.children
          // If it points to a non-folder, or a non-existent path, we assume
          // that previously there must have been a folder at that path that
          // got replaced by a non-folder or deleted. In which case the
          // enclosing folder should have it's own interestingBase
          case Some(_) => Map.empty[String, Vfs.Node[Signature]]
          case None => Map.empty[String, Vfs.Node[Signature]]
        }

        // We de-dup the combined list of names listed from the filesystem and
        // listed from the VFS, because they often have overlaps, and use
        // `listedNames` to check for previously-present-but-now-deleted files
        for(k <- (listedNames ++ virtual.keys).toArray.sorted)
        yield (
          p / k,
          if (!listedNames(k)) None else Signature.compute(p / k, buffer),
          virtual.get(k).map(_.value)
        )
      }
  }
}
