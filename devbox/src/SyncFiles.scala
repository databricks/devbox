package devbox

import java.io.{PrintWriter, StringWriter}
import java.nio.file.{Files, LinkOption}
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue

import devbox.common.{Action, Bytes, Logger, Rpc, RpcException, Signature, Skipper, Util, Vfs}
import os.Path

import scala.collection.mutable
import scala.concurrent.ExecutionContext

object SyncFiles {
  def executeSync(mapping: Seq[(os.Path, os.RelPath)],
                  skipper: Skipper,
                  signatureTransformer: (os.RelPath, Signature) => Signature,
                  changedPaths: Set[os.Path],
                  vfsArr: Seq[Vfs[Signature]],
                  logger: Logger,
                  send: (Rpc, String) => Unit,
                  stream: SendChunks => Unit
                 )(implicit ec: ExecutionContext) = {

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    logger("SYNC EVENTS", changedPaths)

    val failed = mutable.Set.empty[os.Path]
    for (((src, dest), i) <- mapping.zipWithIndex) {
      logger.info("Preparing to Skip", "")
      val skip = skipper.prepare(src)
      logger.info("Skipping", "")
      val eventPaths = changedPaths.filter(p =>
        p.startsWith(src) && !skip(p.relativeTo(src), true)
      )

      logger("SYNC BASE", eventPaths.map(_.relativeTo(src).toString()))

      val exitCode =
        if (eventPaths.isEmpty) Left(SyncFiles.NoOp)
        else SyncFiles.synchronizeRepo(
          logger, vfsArr(i), src, dest,
          eventPaths, signatureTransformer,
          send, stream
        )

      exitCode match {
        case Right(_) =>
        case Left(SyncFiles.NoOp) => // do nothing
        case Left(SyncFiles.SyncFail(value)) =>
          val x = new StringWriter()
          val p = new PrintWriter(x)
          value.printStackTrace(p)
          logger("SYNC FAILED", x.toString)
          failed.addAll(eventPaths)
      }
    }


    failed
  }

  case class SendChunks(p: os.Path,
                        dest: os.RelPath,
                        segments: os.RelPath,
                        chunkIndices: Seq[Int],
                        fileIndex: Int,
                        fileTotalCount: Int,
                        blockHashes: Seq[Bytes])
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
                      src: os.Path,
                      dest: os.RelPath,
                      eventPaths: Set[Path],
                      signatureTransformer: (os.RelPath, Signature) => Signature,
                      send: (Rpc, String) => Unit,
                      stream: SendChunks => Unit)
                     (implicit ec: ExecutionContext): Either[ExitCode, Unit] = {
    logger.info("Checking for changes", s"in ${eventPaths.size} paths")
    for{
      signatureMapping <- restartOnFailure(
        computeSignatures(eventPaths, vfs, src, logger, signatureTransformer)
      )

      _ <- if (signatureMapping.nonEmpty) Right(()) else Left(NoOp)

    } yield {
      logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

      val sortedSignatures = sortSignatureChanges(signatureMapping)

      logger.info(s"${sortedSignatures.length} paths changed", s"$src")

      syncMetadata(vfs, send, sortedSignatures, src, dest, logger)
      streamAllFileContents(logger, vfs, send, stream, src, dest, sortedSignatures)
    }
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
        p
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
                            send: (Rpc, String) => Unit,
                            stream: SendChunks => Unit,
                            src: Path,
                            dest: os.RelPath,
                            signatureMapping: Seq[(Path, Option[Signature], Option[Signature])]) = {
    val total = signatureMapping.length
    for (((p, Some(Signature.File(_, blockHashes, size)), otherSig), n) <- signatureMapping.zipWithIndex) {
      val segments = p.relativeTo(src)
      val (otherHashes, otherSize) = otherSig match {
        case Some(Signature.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
        case _ => (Nil, 0L)
      }
      logger("SYNC CHUNKS", (segments, size, otherSize, otherSig))
      val chunkIndices = for {
        i <- blockHashes.indices
        if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
      } yield {
        Vfs.updateVfs(
          Action.WriteChunk(segments, i, blockHashes(i)),
          stateVfs
        )
        i
      }
      stream(
        SendChunks(
          p,
          dest,
          segments,
          chunkIndices,
          n,
          total,
          blockHashes
        )
      )

      if (size != otherSize) {
        val msg = Rpc.SetSize(dest, segments, size)
        Vfs.updateVfs(msg, stateVfs)
        send(msg, s"Syncing file [$n/$total]:\n$segments")
      }
    }
  }

  def syncMetadata(vfs: Vfs[Signature],
                   send: (Rpc, String) => Unit,
                   signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: os.RelPath,
                   logger: Logger): Unit = {

    logger.apply("SYNC META", signatureMapping)
    def client(rpc: Rpc with Action, logged: String) = {
      send(rpc, logged)
      Vfs.updateVfs(rpc, vfs)
    }
    val total = signatureMapping.length
    for (((p, localSig, remoteSig), i) <- signatureMapping.zipWithIndex) {
      val segments = p.relativeTo(src)
      val logged = s"Syncing path [$i/$total]:\n$segments"
      (localSig, remoteSig) match {
        case (None, _) =>
          client(Rpc.Remove(dest, segments), logged)
        case (Some(Signature.Dir(perms)), remote) =>
          remote match {
            case None =>
              client(Rpc.PutDir(dest, segments, perms), logged)
            case Some(Signature.Dir(remotePerms)) =>
              client(Rpc.SetPerms(dest, segments, perms), logged)
            case Some(_) =>
              client(Rpc.Remove(dest, segments), logged)
              client(Rpc.PutDir(dest, segments, perms), logged)
          }

        case (Some(Signature.Symlink(target)), remote) =>
          remote match {
            case None =>
              client(Rpc.PutLink(dest, segments, target), logged)
            case Some(_) =>
              client(Rpc.Remove(dest, segments), logged)
              client(Rpc.PutLink(dest, segments, target), logged)
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          if (remote.exists(!_.isInstanceOf[Signature.File])) {
            client(Rpc.Remove(dest, segments), logged)
          }

          remote match {
            case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
              if (perms != otherPerms) client(Rpc.SetPerms(dest, segments, perms), logged)

            case _ => client(Rpc.PutFile(dest, segments, perms), logged)
          }
      }
    }
  }


  def computeSignatures(eventPaths: Set[Path],
                        stateVfs: Vfs[Signature],
                        src: os.Path,
                        logger: Logger,
                        signatureTransformer: (os.RelPath, Signature) => Signature)
                       (implicit ec: ExecutionContext)
  : Seq[(os.Path, Option[Signature], Option[Signature])] = {

    val eventPathsLinks = eventPaths.map(p => (p, os.isLink(p)))
    // Existing under a differently-cased name counts as not existing.
    // The only way to reliably check for a mis-cased file on OS-X is
    // to list the parent folder and compare listed names
    val preListed = eventPathsLinks
      .filter(_._2)
      .map(_._1 / os.up)
      .map(dir =>
        (dir, if (!os.isDir(dir, followLinks = false)) Set[String]() else os.list(dir).map(_.last).toSet)
      )
      .toMap

    val buffers = new LinkedBlockingQueue[Array[Byte]]()
    for(i <- 0 until 4) buffers.add(new Array[Byte](Util.blockSize))
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val total = eventPathsLinks.size
    val futures = eventPathsLinks
      .iterator
      .map{ case (p, isLink) =>
        scala.concurrent.Future {
          val newSig =
            if ((isLink && !preListed(p / os.up).contains(p.last)) ||
               (!isLink && !os.followLink(p).contains(p))) None
            else {
              val attrs = Files.readAttributes(p.wrapped, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
              val fileType =
                if (attrs.isRegularFile) os.FileType.File
                else if (attrs.isDirectory) os.FileType.Dir
                else if (attrs.isSymbolicLink) os.FileType.SymLink
                else if (attrs.isOther) os.FileType.Other
                else ???

              val buffer = buffers.take()
              try Signature
                .compute(p, buffer, fileType)
                .map(signatureTransformer(p.relativeTo(src), _))
              finally buffers.put(buffer)
            }
          val oldSig = stateVfs.resolve(p.relativeTo(src)).map(_.value)
          logger.progress(
            s"Checking for changes [${count.getAndIncrement()}/$total]",
            p.relativeTo(src).toString()
          )
          if (newSig == oldSig) None
          else Some(Tuple3(p, newSig, oldSig))
        }
      }

    val sequenced = scala.concurrent.Future.sequence(futures.toSeq)
    scala.concurrent.Await.result(sequenced, scala.concurrent.duration.Duration.Inf).flatten
  }
}
