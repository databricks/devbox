package devbox

import java.io.{PrintWriter, StringWriter}
import java.nio.file.{Files, LinkOption}
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.LinkedBlockingQueue

import devbox.common.{Action, Bytes, Logger, Rpc, RpcException, Signature, Skipper, SyncLogger, Util, Vfs}
import os.Path

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

object SyncFiles {

  sealed trait Msg{def logged: String}
  case class RpcMsg(value: Rpc, logged: String) extends Msg
  case class SendChunkMsg(p: os.Path,
                          dest: os.RelPath,
                          segments: os.RelPath,
                          chunkIndex: Int,
                          logged: String) extends Msg


  def executeSync(mapping: Seq[(os.Path, os.RelPath)],
                  skipper: Skipper,
                  signatureTransformer: (os.RelPath, Signature) => Signature,
                  changedPaths: Set[os.Path],
                  vfsArr: Seq[Vfs[Signature]],
                  logger: SyncLogger,
                  send: Msg => Unit
                 )(implicit ec: ExecutionContext) = {

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    logger("SYNC EVENTS", changedPaths)

    val failed = Future.sequence(
      for (((src, dest), i) <- mapping.zipWithIndex) yield {
        logger.info("Analyzing ignored files", src.toString())
        val skip = skipper.prepare(src)
        val eventPaths = changedPaths.filter(p =>
          p.startsWith(src) && !skip(p.relativeTo(src), true)
        )

        logger("SYNC BASE", eventPaths.map(_.relativeTo(src).toString()))

        val exitCode =
          if (eventPaths.isEmpty) Future.successful(Left(SyncFiles.NoOp))
          else SyncFiles.synchronizeRepo(
            logger, vfsArr(i), src, dest,
            eventPaths, signatureTransformer,
            send
          )

        exitCode.map{
          case Right(_) => Set()
          case Left(SyncFiles.NoOp) => Set()
          case Left(SyncFiles.SyncFail(value)) =>
            val x = new StringWriter()
            val p = new PrintWriter(x)
            value.printStackTrace(p)
            logger("SYNC FAILED", x.toString)
            eventPaths
        }
      }
    )

    failed.map(_.flatten)
  }

  /**
    * Represents the various ways a repo synchronization can exit early.
    */
  sealed trait ExitCode

  /**
    * Something failed with an exception, and we want to re-try the sync
    */
  case class SyncFail(value: Throwable) extends ExitCode

  /**
    * There was nothing to do so we stopped.
    */
  case object NoOp extends ExitCode

  def synchronizeRepo(logger: SyncLogger,
                      vfs: Vfs[Signature],
                      src: os.Path,
                      dest: os.RelPath,
                      eventPaths: Set[Path],
                      signatureTransformer: (os.RelPath, Signature) => Signature,
                      send: Msg => Unit)
                     (implicit ec: ExecutionContext): Future[Either[ExitCode, Unit]] = {
    logger.info("Checking for changes", s"in ${eventPaths.size} paths")
    computeSignatures(eventPaths, vfs, src, logger, signatureTransformer).transform{
      case scala.util.Success(signatureMapping) =>
        if (signatureMapping.isEmpty) scala.util.Success(Left(NoOp))
        else {
          logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

          val sortedSignatures = sortSignatureChanges(signatureMapping)

          logger.info(s"${sortedSignatures.length} paths changed", s"$src")

          syncMetadata(vfs, send, sortedSignatures, src, dest, logger)
          streamAllFileContents(logger, vfs, send, src, dest, sortedSignatures)
          scala.util.Success(Right(()))
        }
      case scala.util.Failure(ex) =>
        scala.util.Success(Left(SyncFail(ex)))
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

  def streamAllFileContents(logger: SyncLogger,
                            stateVfs: Vfs[Signature],
                            send: Msg => Unit,
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
      for(chunkIndex <- chunkIndices){
        val hashMsg = if (blockHashes.size > 1) s" $chunkIndex/${blockHashes.size}" else ""
        send(
          SendChunkMsg(
            p,
            dest,
            segments,
            chunkIndex,
            s"Syncing file chunk [$n/$total$hashMsg]:\n$segments"
          )
        )
      }

      if (size != otherSize) {
        val msg = Rpc.SetSize(dest, segments, size)
        Vfs.updateVfs(msg, stateVfs)
        send(RpcMsg(msg, s"Syncing file chunk [$n/$total]:\n$segments"))
      }
    }
  }

  def syncMetadata(vfs: Vfs[Signature],
                   send: Msg => Unit,
                   signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: os.RelPath,
                   logger: SyncLogger): Unit = {

    logger.apply("SYNC META", signatureMapping)
    def client(rpc: Rpc with Action, logged: String) = {
      send(RpcMsg(rpc, logged))
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
                        logger: SyncLogger,
                        signatureTransformer: (os.RelPath, Signature) => Signature)
                       (implicit ec: ExecutionContext)
  : Future[Seq[(os.Path, Option[Signature], Option[Signature])]] = {

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
        Future {
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
    sequenced.map(_.flatten)
  }
}
