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
  case class SendChunkMsg(src: os.Path,
                          dest: os.RelPath,
                          subPath: os.SubPath,
                          chunkIndex: Int,
                          logged: String) extends Msg


  def executeSync(mapping: Seq[(os.Path, os.RelPath)],
                  signatureTransformer: (os.SubPath, Signature) => Signature,
                  changedPaths0: Map[os.Path, Set[os.SubPath]],
                  vfsArr: Seq[Vfs[Signature]],
                  logger: SyncLogger,
                  send: Msg => Unit)
                 (implicit ec: ExecutionContext) = {

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    logger("SYNC EVENTS", changedPaths0)

    val failed = Future.sequence(
      for {
        ((src, dest), i) <- mapping.zipWithIndex
        eventPaths <- changedPaths0.get(src)
      } yield {
        logger("SYNC BASE", eventPaths)

        val exitCode =
          if (eventPaths.isEmpty) Future.successful(Left(SyncFiles.NoOp))
          else SyncFiles.synchronizeRepo(
            logger, vfsArr(i), src, dest,
            eventPaths, signatureTransformer,
            send
          )

        exitCode.map{
          case Right(_) => Map.empty[os.Path, Set[os.SubPath]]
          case Left(SyncFiles.NoOp) => Map.empty[os.Path, Set[os.SubPath]]
          case Left(SyncFiles.SyncFail(value)) =>
            val x = new StringWriter()
            val p = new PrintWriter(x)
            value.printStackTrace(p)
            logger("SYNC FAILED", x.toString)
            Map(src -> eventPaths)
        }
      }
    )

    failed
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
                      eventPaths: Set[os.SubPath],
                      signatureTransformer: (os.SubPath, Signature) => Signature,
                      send: Msg => Unit)
                     (implicit ec: ExecutionContext): Future[Either[ExitCode, Unit]] = {
    logger.info("Checking for changes", s"in ${eventPaths.size} paths")
    computeSignatures(eventPaths, vfs, src, logger, signatureTransformer).transform{
      case scala.util.Success(signatureMapping) =>
        if (signatureMapping.isEmpty) scala.util.Success(Left(NoOp))
        else {
          logger("SYNC SIGNATURE", signatureMapping)

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

  def sortSignatureChanges(sigs: Seq[(os.SubPath, Option[Signature], Option[Signature])]) = {
    sigs.sortBy { case (p, local, remote) =>
      (
        // First, sort by how deep the path is. We want to operate on the
        // shallower paths first before the deeper paths, so folders can be
        // created before their contents is written.
        p.segments.size,
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
                            signatureMapping: Seq[(os.SubPath, Option[Signature], Option[Signature])]) = {
    val total = signatureMapping.length
    for (((subPath, Some(Signature.File(_, blockHashes, size)), otherSig), n) <- signatureMapping.zipWithIndex) {

      val (otherHashes, otherSize) = otherSig match {
        case Some(Signature.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
        case _ => (Nil, 0L)
      }
      logger("SYNC CHUNKS", (subPath, size, otherSize, otherSig))
      val chunkIndices = for {
        i <- blockHashes.indices
        if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
      } yield {
        Vfs.updateVfs(
          Action.WriteChunk(subPath, i, blockHashes(i)),
          stateVfs
        )
        i
      }
      for(chunkIndex <- chunkIndices){
        val hashMsg = if (blockHashes.size > 1) s" $chunkIndex/${blockHashes.size}" else ""
        send(
          SendChunkMsg(
            src,
            dest,
            subPath,
            chunkIndex,
            s"Syncing file chunk [$n/$total$hashMsg]:\n$subPath"
          )
        )
      }

      if (size != otherSize) {
        val msg = Rpc.SetSize(dest, subPath, size)
        Vfs.updateVfs(msg, stateVfs)
        send(RpcMsg(msg, s"Syncing file chunk [$n/$total]:\n$subPath"))
      }
    }
  }

  def syncMetadata(vfs: Vfs[Signature],
                   send: Msg => Unit,
                   signatureMapping: Seq[(os.SubPath, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: os.RelPath,
                   logger: SyncLogger): Unit = {

    logger.apply("SYNC META", signatureMapping)
    def client(rpc: Rpc with Action, logged: String) = {
      send(RpcMsg(rpc, logged))
      Vfs.updateVfs(rpc, vfs)
    }
    val total = signatureMapping.length
    for (((subPath, localSig, remoteSig), i) <- signatureMapping.zipWithIndex) {

      val logged = s"Syncing path [$i/$total]:\n$subPath"
      (localSig, remoteSig) match {
        case (None, _) =>
          client(Rpc.Remove(dest, subPath), logged)
        case (Some(Signature.Dir(perms)), remote) =>
          remote match {
            case None =>
              client(Rpc.PutDir(dest, subPath, perms), logged)
            case Some(Signature.Dir(remotePerms)) =>
              client(Rpc.SetPerms(dest, subPath, perms), logged)
            case Some(_) =>
              client(Rpc.Remove(dest, subPath), logged)
              client(Rpc.PutDir(dest, subPath, perms), logged)
          }

        case (Some(Signature.Symlink(target)), remote) =>
          remote match {
            case None =>
              client(Rpc.PutLink(dest, subPath, target), logged)
            case Some(_) =>
              client(Rpc.Remove(dest, subPath), logged)
              client(Rpc.PutLink(dest, subPath, target), logged)
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          if (remote.exists(!_.isInstanceOf[Signature.File])) {
            client(Rpc.Remove(dest, subPath), logged)
          }

          remote match {
            case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
              if (perms != otherPerms) client(Rpc.SetPerms(dest, subPath, perms), logged)

            case _ => client(Rpc.PutFile(dest, subPath, perms), logged)
          }
      }
    }
  }


  def computeSignatures(eventPaths: Set[os.SubPath],
                        stateVfs: Vfs[Signature],
                        src: os.Path,
                        logger: SyncLogger,
                        signatureTransformer: (os.SubPath, Signature) => Signature)
                       (implicit ec: ExecutionContext)
  : Future[Seq[(os.SubPath, Option[Signature], Option[Signature])]] = {

    val eventPathsLinks = eventPaths.map(p => (p, os.isLink(src / p)))
    // Existing under a differently-cased name counts as not existing.
    // The only way to reliably check for a mis-cased file on OS-X is
    // to list the parent folder and compare listed names
    val preListed = eventPathsLinks
      .filter(_._2)
      .map(_._1 / os.up)
      .map(dir =>
        (
          dir,
          if (!os.isDir(src / dir, followLinks = false)) Set[String]()
          else os.list(src / dir).map(_.last).toSet
        )
      )
      .toMap

    val buffers = new LinkedBlockingQueue[Array[Byte]]()
    for(i <- 0 until 6) buffers.add(new Array[Byte](Util.blockSize))
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
    val total = eventPathsLinks.size
    val futures = eventPathsLinks
      .iterator
      .map{ case (sub, isLink) =>
        Future {
          val abs = src / sub
          val newSig =
            if ((isLink && !preListed(sub / os.up).contains(sub.last)) ||
               (!isLink && !os.followLink(abs).contains(abs))) None
            else {
              val attrs = os.stat(abs, followLinks = false)
              val buffer = buffers.take()
              try Signature
                .compute(abs, buffer, attrs.fileType)
                .map(signatureTransformer(sub, _))
              finally buffers.put(buffer)
            }
          val oldSig = stateVfs.resolve(sub).map(_.value)
          logger.progress(
            s"Checking for changes [${count.getAndIncrement()}/$total]",
            sub.toString()
          )

          if (newSig == oldSig) None
          else Some(Tuple3(sub, newSig, oldSig))
        }
      }

    val sequenced = scala.concurrent.Future.sequence(futures.toSeq)
    sequenced.map(_.flatten)
  }
}
