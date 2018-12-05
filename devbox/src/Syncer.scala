package devbox
import java.io._
import java.nio.ByteBuffer
import java.util.concurrent._

import devbox.common._
import os.Path
import upickle.default

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}

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
             logger: Logger,
             signatureTransformer: Signature => Signature) extends AutoCloseable{

  private[this] val eventQueue = new LinkedBlockingQueue[Array[String]]()
  private[this] val watcher = new FSEventsWatcher(mapping.map(_._1), eventQueue.add, logger)

  private[this] var watcherThread: Thread = null
  private[this] var syncThread: Thread = null
  private[this] var agentLoggerThread: Thread = null

  @volatile private[this] var running: Boolean = false

  @volatile private[this] var asyncException: Throwable = null


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
        signatureTransformer
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
                   signatureTransformer: Signature => Signature) = {

    val vfsArr = for (_ <- mapping.indices) yield new Vfs[Signature](Signature.Dir(0))
    val buffer = new Array[Byte](Util.blockSize)

    val client = new RpcClient(agent.stdin, agent.stdout, (tag, t) => logger("SYNC " + tag, t))

    logger("SYNC SCAN")
    logger.info(s"Performing initial scan on ${mapping.length} repos", mapping.map(_._1).mkString("\n"))

    performInitialScans(mapping, eventQueue, skip, logger, vfsArr, client)
    var initialSync = true
    var pathCount: Int = 0
    var byteCount: Int = 0

    while (continue() && agent.isAlive()) {
      logger("SYNC LOOP")

      for (interestingBases <- Syncer.drainUntilStable(eventQueue, debounceTime) ){
        // We need to .distinct after we convert the strings to paths, in order
        // to ensure the inputs are canonicalized and don't have meaningless
        // differences such as trailing slashes
        val allSrcEventDirs = interestingBases.flatten.sorted.map(os.Path(_)).distinct
        logger("SYNC EVENTS", allSrcEventDirs)

        for {
          ((src, dest), i) <- mapping.zipWithIndex

          srcEventDirs = allSrcEventDirs.filter(p => p.startsWith(src) && !skip(p, src))

          if srcEventDirs.nonEmpty

          _ = logger("SYNC BASE", srcEventDirs.map(_.relativeTo(src).toString()))

          (streamedByteCount, signatureCount) <- synchronizeRepo(
            logger, eventQueue, vfsArr(i), skip, src, dest,
            client, buffer, interestingBases, srcEventDirs, signatureTransformer
          )

        }{
          pathCount += signatureCount
          byteCount += streamedByteCount
        }


        if (eventQueue.isEmpty) {
          logger("SYNC COMPLETE")
          onComplete()
          if (pathCount != 0 || byteCount != 0){
            logger.info("Syncing Complete", s"$pathCount paths, $byteCount bytes")
            byteCount = 0
            pathCount = 0
          }else if (initialSync){
            logger.info("Nothing to sync", "watching for changes")
          }
          initialSync = false
        }else{
          logger.info("˜Ongoing changes detected", "scanning for changes")
          logger("SYNC PARTIAL")
        }

      }
    }
  }

  def performInitialScans(mapping: Seq[(Path, Seq[String])],
                          eventQueue: BlockingQueue[Array[String]],
                          skip: (Path, Path) => Boolean,
                          logger: Logger,
                          vfsArr: immutable.IndexedSeq[Vfs[Signature]],
                          client: RpcClient) = {
    for (((src, dest), i) <- mapping.zipWithIndex) {

      client.writeMsg(Rpc.FullScan(dest.mkString("/")))
      var n = 0
      while ( {
        client.readMsg[Option[(String, Signature)]]() match {
          case None =>
            logger("SYNC SCAN DONE")
            false
          case Some((p, sig)) =>
            n += 1
            logger("SYNC SCAN REMOTE", (p, sig))
            logger.progress(s"Scanning remote file $n", p)
            Vfs.updateVfs(p, sig, vfsArr(i))
            true
        }
      }) ()

      val initialLocal = os.walk.stream(
        src,
        p => skip(p, src) || !os.isDir(p, followLinks = false),
        includeTarget = true
      )
      eventQueue.add(
        initialLocal
          .zipWithIndex
          .map { case (p, i) =>
            lazy val rel = p.relativeTo(src).toString
            logger.progress(s"Scanning local file $i", rel)
            logger("SYNC SCAN LOCAL", rel)
            p.toString
          }
          .toArray
      )
    }
  }

  def synchronizeRepo(logger: Logger,
                      eventQueue: BlockingQueue[Array[String]],
                      vfs: Vfs[Signature],
                      skip: (os.Path, os.Path) => Boolean,
                      src: os.Path,
                      dest: Seq[String],
                      client: RpcClient,
                      buffer: Array[Byte],
                      interestingBases: mutable.Buffer[Array[String]],
                      srcEventDirs: mutable.Buffer[Path],
                      signatureTransformer: Signature => Signature) = {
    for{
      signatureMapping <- restartOnFailure(
        logger, interestingBases, eventQueue,
        computeSignatures(srcEventDirs, buffer, vfs, skip, src, logger, signatureTransformer)
      )

      _ = logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

      sortedSignatures = sortSignatureChanges(signatureMapping)

      filteredSignatures = sortedSignatures.filter{case (p, lhs, rhs) => lhs != rhs}

      if filteredSignatures.nonEmpty

      _ = logger.info(s"${filteredSignatures.length} paths changed", s"$src")

      vfsRpcClient = new VfsRpcClient(client, vfs)

      _ = Syncer.syncMetadata(vfsRpcClient, filteredSignatures, src, dest, vfs, logger, buffer)

      streamedByteCount <- restartOnFailure(
        logger, interestingBases, eventQueue,
        streamAllFileContents(logger, vfs, vfsRpcClient, src, dest, filteredSignatures)
      )
    } yield (streamedByteCount, filteredSignatures.length)
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
                            dest: Seq[String],
                            signatureMapping: Seq[(Path, Option[Signature], Option[Signature])]) = {
    val total = signatureMapping.length
    var byteCount = 0
    draining(client) {
      for (((p, Some(Signature.File(_, blockHashes, size)), otherSig), n) <- signatureMapping.zipWithIndex) {
        val segments = (os.rel / dest / p.relativeTo(src)).toString
        val (otherHashes, otherSize) = otherSig match {
          case Some(Signature.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
          case _ => (Nil, 0L)
        }
        logger("SYNC CHUNKS", segments)
         byteCount += streamFileContents(
          logger,
          client,
          stateVfs,
          p,
          segments,
          blockHashes,
          otherHashes,
          size,
          otherSize,
          n,
          total
        )
      }
    }
    byteCount
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

    try {
      await(true, false)

      Some(interestingBases)
    }catch {
      case e: InterruptedException => None
    }
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
                   buffer: Array[Byte]): Unit = {

    val total = signatureMapping.length
    draining(client) {
      for (((p, localSig, remoteSig), i) <- signatureMapping.zipWithIndex) {
        val segments = (os.rel / dest / p.relativeTo(src)).toString
        logger.progress(s"Syncing path [$i/$total]", segments)
        (localSig, remoteSig) match {
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
            if (remote.exists(!_.isInstanceOf[Signature.File])) client(Rpc.Remove(segments))

            remote match {
              case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
                if (perms != otherPerms) client(Rpc.SetPerms(segments, perms))

              case _ => client(Rpc.PutFile(segments, perms))
            }
        }
      }
    }
  }

  def streamFileContents(logger: Logger,
                         client: VfsRpcClient,
                         stateVfs: Vfs[Signature],
                         p: Path,
                         segments: String,
                         blockHashes: Seq[Bytes],
                         otherHashes: Seq[Bytes],
                         size: Long,
                         otherSize: Long,
                         fileIndex: Int,
                         fileTotalCount: Int): Int = {
    val byteArr = new Array[Byte](Util.blockSize)
    val buf = ByteBuffer.wrap(byteArr)
    var byteCount = 0
    Util.autoclose(os.read.channel(p)) { channel =>
      for {
        i <- blockHashes.indices
        if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
      } {
        logger.progress(
          s"Syncing file chunk [$fileIndex/$fileTotalCount $i/${blockHashes.length}]",
          segments
        )
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
        byteCount += n
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
    byteCount
  }

  def computeSignatures(interestingBases: Seq[Path],
                        buffer: Array[Byte],
                        stateVfs: Vfs[Signature],
                        skip: (os.Path, os.Path) => Boolean,
                        src: os.Path,
                        logger: Logger,
                        signatureTransformer: Signature => Signature): Seq[(os.Path, Option[Signature], Option[Signature])] = {
    interestingBases.zipWithIndex.flatMap { case (p, i) =>
        logger.progress(
          s"Scanning local folder [$i/${interestingBases.length}]",
          p.relativeTo(src).toString()
        )
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
          if (!listedNames(k)) None else Signature.compute(p / k, buffer).map(signatureTransformer),
          virtual.get(k).map(_.value)
        )
      }
  }
}
