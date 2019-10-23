package devbox

import java.nio.ByteBuffer
import java.nio.file.{Files, LinkOption}
import java.nio.file.attribute.BasicFileAttributes

import devbox.common.{Action, Bytes, Logger, Rpc, RpcException, Signature, Skipper, Util, Vfs}
import os.Path
import upickle.default

import scala.util.control.NonFatal

object SyncFiles {
  class VfsRpcClient(stateVfs: Vfs[Signature], logger: Logger,
                     send: (Rpc, String) => Unit){
    def apply[T <: Action with Rpc: default.Writer](p: T, logged: String) = {
      try {
        send(p, logged)
        Vfs.updateVfs(p, stateVfs)
      } catch {
        case NonFatal(ex) => logger.info("Connection", s"Message cannot be sent $ex")
      }
    }
  }

  def updateSkipPredicate(eventPaths: Seq[os.Path],
                          skipper: Skipper,
                          vfs: Vfs[Signature],
                          src: os.Path,
                          buffer: Array[Byte],
                          logger: Logger,
                          signatureTransformer: (os.RelPath, Signature) => Signature,
                          setSkip: ((os.Path, Boolean) => Boolean) => Unit) = {
    val allModifiedSkipFiles = for{
      p <- eventPaths
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
                      buffer: Array[Byte],
                      eventPaths: Seq[Path],
                      signatureTransformer: (os.RelPath, Signature) => Signature,
                      send: (Rpc, String) => Unit): Either[ExitCode, (Long, Seq[os.Path])] = {
    for{
      signatureMapping <- restartOnFailure(
        computeSignatures(eventPaths, buffer, vfs, skip, src, logger, signatureTransformer)
      )

      _ = logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

      sortedSignatures = sortSignatureChanges(signatureMapping)

      filteredSignatures = sortedSignatures.filter{case (p, lhs, rhs) => lhs != rhs}

      _ <- if (filteredSignatures.nonEmpty) Right(()) else Left(NoOp)

      _ = logger.info(s"${filteredSignatures.length} paths changed", s"$src")

      vfsRpcClient = new VfsRpcClient(vfs, logger, send)

      _ = syncMetadata(vfsRpcClient, filteredSignatures, src, dest, logger)

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
    byteCount
  }

  def syncMetadata(client: VfsRpcClient,
                   signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: os.RelPath,
                   logger: Logger): Unit = {
    logger.apply("SYNC META", signatureMapping)
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
        val logged = s"Syncing file chunk [$fileIndex/$fileTotalCount$hashMsg]:\n$segments"

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
          ),
          logged
        )
      }
    }

    if (size != otherSize) {
      client(
        Rpc.SetSize(dest, segments, size),
        s"Resizing file [$fileIndex/$fileTotalCount]:\n$segments"
      )
    }
    byteCount
  }

  def computeSignatures(eventPaths: Seq[Path],
                        buffer: Array[Byte],
                        stateVfs: Vfs[Signature],
                        skip: (os.Path, Boolean) => Boolean,
                        src: os.Path,
                        logger: Logger,
                        signatureTransformer: (os.RelPath, Signature) => Signature)
  : Seq[(os.Path, Option[Signature], Option[Signature])] = {

    eventPaths.zipWithIndex.map { case (p, i) =>
      logger.progress(
        s"Scanning local path [$i/${eventPaths.length}]",
        p.relativeTo(src).toString()
      )

      Tuple3(
        p,
        if (!os.exists(p, followLinks = false) ||
          // Existing under a differently-cased name counts as not existing.
          // The only way to reliably check for a mis-cased file on OS-X is
          // to list the parent folder and compare listed names
          !os.list(p / os.up).map(_.last).contains(p.last)) None
        else {
          val attrs = Files.readAttributes(p.wrapped, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          val fileType =
            if (attrs.isRegularFile) os.FileType.File
            else if (attrs.isDirectory) os.FileType.Dir
            else if (attrs.isSymbolicLink) os.FileType.SymLink
            else if (attrs.isOther) os.FileType.Other
            else ???

          Signature
            .compute(p, buffer, fileType)
            .map(signatureTransformer(p.relativeTo(src), _))
        },
        stateVfs.resolve(p.relativeTo(src)).map(_.value)
      )
    }

  }
}
