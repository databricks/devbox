package devbox
import java.io._
import java.nio.ByteBuffer
import java.nio.file.{Files, LinkOption}
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent._

import devbox.common._
import os.Path
import upickle.default
import Util.relpathRw
import geny.Generator

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}

/**
  * The Syncer class instances contain all the stateful, close-able parts of
  * the syncing logic: event queues, threads, filesystem watchers, etc. All the
  * stateless call-and-forget logic is pushed into static methods on the Syncer
  * companion object
  */
class Syncer(agent: AgentApi,
             mapping: Seq[(os.Path, os.RelPath)],
             skipper: Skipper,
             debounceTime: Int,
             onComplete: () => Unit,
             logger: Logger,
             signatureTransformer: (os.RelPath, Signature) => Signature) extends AutoCloseable{

  private[this] val eventQueue = new LinkedBlockingQueue[Array[String]]()
  private[this] val watcher = new FSEventsWatcher(
    mapping.map(_._1),
    eventQueue.add,
    logger,
    0.05
  )

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
        skipper,
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
                   mapping: Seq[(os.Path, os.RelPath)],
                   onComplete: () => Unit,
                   eventQueue: BlockingQueue[Array[String]],
                   skipper: Skipper,
                   debounceTime: Int,
                   continue: () => Boolean,
                   logger: Logger,
                   signatureTransformer: (os.RelPath, Signature) => Signature) = {

    val vfsArr = for (_ <- mapping.indices) yield new Vfs[Signature](Signature.Dir(0))
    val skipArr = for ((src, dest) <- mapping.toArray) yield skipper.initialize(src)

    val buffer = new Array[Byte](Util.blockSize)

    val client = new RpcClient(agent.stdin, agent.stdout, (tag, t) => logger("SYNC " + tag, t))

    logger("SYNC SCAN")
    logger.info(s"Performing initial scan on ${mapping.length} repos", mapping.map(_._1).mkString("\n"))

    for (((src, dest), i) <- mapping.zipWithIndex) {
      initialRemoteScan(logger, vfsArr, client, dest, i)
      initialLocalScan(src, eventQueue, logger, skipArr(i))
    }

    var messagedSync = true
    val allChangedPaths = mutable.Buffer.empty[os.Path]
    var allSyncedBytes = 0L
    while (continue() && agent.isAlive()) {
      logger("SYNC LOOP")

      for (interestingBases <- Syncer.drainUntilStable(eventQueue, debounceTime) ){
        // We need to .distinct after we convert the strings to paths, in order
        // to ensure the inputs are canonicalized and don't have meaningless
        // differences such as trailing slashes
        val allSrcEventDirs = interestingBases.flatten.sorted.map(os.Path(_)).distinct
        logger("SYNC EVENTS", allSrcEventDirs)

        for (((src, dest), i) <- mapping.zipWithIndex) {
          val srcEventDirs = allSrcEventDirs.filter(p =>
            p.startsWith(src) && !skipArr(i)(p, true)
          )

          logger("SYNC BASE", srcEventDirs.map(_.relativeTo(src).toString()))

          val exitCode = for{
            _ <- if (srcEventDirs.isEmpty) Left(NoOp: ExitCode) else Right(())
            _ <- updateSkipPredicate(
              srcEventDirs, skipper, vfsArr(i), src, buffer, logger,
              signatureTransformer, skipArr(i) = _
            )
            res <- synchronizeRepo(
              logger, vfsArr(i), skipArr(i), src, dest,
              client, buffer, srcEventDirs, signatureTransformer
            )
          } yield res

          exitCode match{
            case Right((streamedByteCount, changedPaths)) =>
              allChangedPaths.appendAll(changedPaths)
              allSyncedBytes += streamedByteCount
            case Left(NoOp) => // do nothing
            case Left(SyncFail(value)) =>
              eventQueue.add(srcEventDirs.map(_.toString).toArray)
              val x = new StringWriter()
              val p = new PrintWriter(x)
              value.printStackTrace(p)
              logger("SYNC FAILED", x.toString)
          }
        }

        if (eventQueue.isEmpty) {
          logger("SYNC COMPLETE")
          onComplete()
          if (allChangedPaths.nonEmpty){
            logger.info(
              s"Finished syncing ${allChangedPaths.length} paths, $allSyncedBytes bytes",
              s"${allChangedPaths.head}" +
                (if(allChangedPaths.length == 1) "" else s" and ${allChangedPaths.length-1} others")
            )
            allSyncedBytes = 0
            allChangedPaths.clear()
          }else if (messagedSync){
            logger.info("Nothing to sync", "watching for changes")
          }
          messagedSync = false
        }else{
          logger.info(
            "Ongoing changes detected",
            eventQueue.peek().head
          )
          logger("SYNC PARTIAL")
          messagedSync = true
        }
      }
    }
  }

  def updateSkipPredicate(srcEventDirs: Seq[os.Path],
                          skipper: Skipper,
                          vfs: Vfs[Signature],
                          src: os.Path,
                          buffer: Array[Byte],
                          logger: Logger,
                          signatureTransformer: (os.RelPath, Signature) => Signature,
                          setSkip: ((os.Path, Boolean) => Boolean) => Unit) = {
    val allModifiedSkipFiles = for{
      p <- srcEventDirs
      pathToCheck <- skipper.checkReset(p)
      newSig =
        if (!os.exists(pathToCheck)) None
        else Signature.compute(pathToCheck, buffer, os.FileType.Dir).map(signatureTransformer(pathToCheck.relativeTo(src), _))
      oldSig = vfs.resolve(pathToCheck.relativeTo(src)).map(_.value)
      if newSig != oldSig
    } yield (pathToCheck, oldSig, newSig)

    if (allModifiedSkipFiles.isEmpty) Right(())
    else{
      logger.info(
        "Gitignore file changed, re-scanning repository",
        allModifiedSkipFiles.head._1.toString
      )
      logger("SYNC GITIGNORE", allModifiedSkipFiles)
      restartOnFailure{setSkip(skipper.initialize(src))}
    }
  }
  def initialRemoteScan(logger: Logger,
                        vfsArr: immutable.IndexedSeq[Vfs[Signature]],
                        client: RpcClient,
                        dest: os.RelPath,
                        i: Int): Unit = {
    client.writeMsg(Rpc.FullScan(dest))
    val total = client.readMsg[Int]()
    var n = 0
    while ( {
      client.readMsg[Option[(os.RelPath, Signature)]]() match {
        case None =>
          logger("SYNC SCAN DONE")
          false
        case Some((p, sig)) =>
          n += 1
          logger("SYNC SCAN REMOTE", (p, sig))
          logger.progress(s"Scanning remote file [$n/$total]", p.toString())
          Vfs.updateVfs(p, sig, vfsArr(i))
          true
      }
    }) ()
  }

  def initialLocalScan(src: os.Path,
                       eventQueue: BlockingQueue[Array[String]],
                       logger: Logger,
                       skip: (os.Path, Boolean) => Boolean) = {
    eventQueue.add(
      os.walk
        .stream.attrs(
          src,
          (p, attrs) => skip(p, attrs.isDir) || !attrs.isDir,
          includeTarget = true
        )
        .map(_._1.toString())
        .toArray
    )
  }

  /**
    * Represents the various ways a repo synchronization can exit early.
    */
  sealed trait ExitCode

  /**
    * Something failed with an exception, and we want to re-try the sync
    */
  case class SyncFail(value: Exception) extends ExitCode

  /**
    * There was nothing to do so we stopped.
    */
  case object NoOp extends ExitCode

  def synchronizeRepo(logger: Logger,
                      vfs: Vfs[Signature],
                      skip: (os.Path, Boolean) => Boolean,
                      src: os.Path,
                      dest: os.RelPath,
                      client: RpcClient,
                      buffer: Array[Byte],
                      srcEventDirs: mutable.Buffer[Path],
                      signatureTransformer: (os.RelPath, Signature) => Signature): Either[ExitCode, (Long, Seq[os.Path])] = {
    for{
      signatureMapping <- restartOnFailure(
        computeSignatures(srcEventDirs, buffer, vfs, skip, src, logger, signatureTransformer)
      )

      _ = logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

      sortedSignatures = sortSignatureChanges(signatureMapping)

      filteredSignatures = sortedSignatures.filter{case (p, lhs, rhs) => lhs != rhs}

      _ <- if (filteredSignatures.nonEmpty) Right(()) else Left(NoOp)

      _ = logger.info(s"${filteredSignatures.length} paths changed", s"$src")

      vfsRpcClient = new VfsRpcClient(client, vfs)

      _ = Syncer.syncMetadata(vfsRpcClient, filteredSignatures, src, dest, vfs, logger, buffer)

      streamedByteCount <- restartOnFailure(
        streamAllFileContents(logger, vfs, vfsRpcClient, src, dest, filteredSignatures)
      )
    } yield (streamedByteCount, filteredSignatures.map(_._1))
  }

  def sortSignatureChanges(sigs: Seq[(os.Path, Option[Signature], Option[Signature])]) = {
    sigs.sortBy { case (p, local, remote) =>
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

  def restartOnFailure[T](t: => T)  = {
    try Right(t)
    catch{
      case e: RpcException => throw e
      case e: Exception => Left(SyncFail(e))
    }
  }

  def streamAllFileContents(logger: Logger,
                            stateVfs: Vfs[Signature],
                            client: VfsRpcClient,
                            src: Path,
                            dest: os.RelPath,
                            signatureMapping: Seq[(Path, Option[Signature], Option[Signature])]) = {
    val total = signatureMapping.length
    var byteCount = 0L
    draining(client) {
      for (((p, Some(Signature.File(_, blockHashes, size)), otherSig), n) <- signatureMapping.zipWithIndex) {
        val segments = p.relativeTo(src)
        val (otherHashes, otherSize) = otherSig match {
          case Some(Signature.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
          case _ => (Nil, 0L)
        }
        logger("SYNC CHUNKS", segments)
        byteCount += streamFileContents(
          logger,
          client,
          stateVfs,
          dest,
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
                   dest: os.RelPath,
                   stateVfs: Vfs[Signature],
                   logger: Logger,
                   buffer: Array[Byte]): Unit = {

    val total = signatureMapping.length
    draining(client) {
      for (((p, localSig, remoteSig), i) <- signatureMapping.zipWithIndex) {
        val segments = p.relativeTo(src)
        logger.progress(s"Syncing path [$i/$total]", segments.toString())
        (localSig, remoteSig) match {
          case (None, _) =>
            client(Rpc.Remove(dest, segments))
          case (Some(Signature.Dir(perms)), remote) =>
            remote match {
              case None =>
                client(Rpc.PutDir(dest, segments, perms))
              case Some(Signature.Dir(remotePerms)) =>
                client(Rpc.SetPerms(dest, segments, perms))
              case Some(_) =>
                client(Rpc.Remove(dest, segments))
                client(Rpc.PutDir(dest, segments, perms))
            }

          case (Some(Signature.Symlink(target)), remote) =>
            remote match {
              case None =>
                client(Rpc.PutLink(dest, segments, target))
              case Some(_) =>
                client(Rpc.Remove(dest, segments))
                client(Rpc.PutLink(dest, segments, target))
            }
          case (Some(Signature.File(perms, blockHashes, size)), remote) =>
            if (remote.exists(!_.isInstanceOf[Signature.File])) client(Rpc.Remove(dest, segments))

            remote match {
              case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
                if (perms != otherPerms) client(Rpc.SetPerms(dest, segments, perms))

              case _ => client(Rpc.PutFile(dest, segments, perms))
            }
        }
      }
    }
  }

  def streamFileContents(logger: Logger,
                         client: VfsRpcClient,
                         stateVfs: Vfs[Signature],
                         dest: os.RelPath,
                         p: Path,
                         segments: os.RelPath,
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
        val hashMsg = if (blockHashes.size > 1) s" $i/${blockHashes.length}" else ""
        logger.progress(
          s"Syncing file chunk [$fileIndex/$fileTotalCount$hashMsg]",
          segments.toString()
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
            dest,
            segments,
            i * Util.blockSize,
            new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr),
            blockHashes(i)
          )
        )
      }
    }

    if (size != otherSize) client(Rpc.SetSize(dest, segments, size))
    byteCount
  }

  def computeSignatures(interestingBases: Seq[Path],
                        buffer: Array[Byte],
                        stateVfs: Vfs[Signature],
                        skip: (os.Path, Boolean) => Boolean,
                        src: os.Path,
                        logger: Logger,
                        signatureTransformer: (os.RelPath, Signature) => Signature): Seq[(os.Path, Option[Signature], Option[Signature])] = {
    interestingBases.zipWithIndex.flatMap { case (p, i) =>
      logger.progress(
        s"Scanning local folder [$i/${interestingBases.length}]",
        p.relativeTo(src).toString()
      )
      val listedGen =
        if (!os.isDir(p, followLinks = false)) os.Generator.apply()
        else for{
          p <- os.list.stream(p)
          // avoid using os.stat to avoid making two filesystem calls
          attrs = Files.readAttributes(p.wrapped, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if !skip(p, attrs.isDirectory)
        } yield (
          p.last,
          if (attrs.isRegularFile) os.FileType.File
          else if (attrs.isDirectory) os.FileType.Dir
          else if (attrs.isSymbolicLink) os.FileType.SymLink
          else if (attrs.isOther) os.FileType.Other
          else ???
        )

      val listed = listedGen.toArray
      val listedNames = listed.map(_._1).toSet

      val virtual = stateVfs.resolve(p.relativeTo(src)) match {
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

      val virtualWithFileType = virtual.map{ case (k, node) =>
        (
          k,
          node.value match{
            case _: Signature.File => os.FileType.File
            case _: Signature.Dir => os.FileType.Dir
            case _: Signature.Symlink => os.FileType.SymLink
          }
        )
      }

      val allKeys = listedNames ++ virtualWithFileType.keys
      val fileTypeLookup = (virtualWithFileType ++ listed).toMap
      // We de-dup the combined list of names listed from the filesystem and
      // listed from the VFS, because they often have overlaps, and use
      // `listedNames` to check for previously-present-but-now-deleted files
      for(k <- allKeys.toArray.sorted)
      yield {
        val fileType = fileTypeLookup(k)
        (
          p / k,
          if (!listedNames(k)) None
          else Signature.compute(p / k, buffer, fileType).map(signatureTransformer((p / k).relativeTo(src), _)),
          virtual.get(k).map(_.value)
        )
      }
    }
  }
}
