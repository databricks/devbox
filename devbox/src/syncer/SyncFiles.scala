package devbox.syncer

import devbox.common._
import devbox.logger.SyncLogger
import os.{Path, RelPath, SubPath}

import scala.concurrent.ExecutionContext

object SyncFiles {

  sealed trait Msg

  case class Complete() extends Msg
  case class RemoteScan(paths: Seq[os.RelPath]) extends Msg
  case class RpcMsg(value: Rpc with PathRpc) extends Msg

  case class SendChunkMsg(src: os.Path,
                          dest: os.RelPath,
                          subPath: os.SubPath,
                          chunkIndex: Int,
                          chunkCount: Int) extends Msg


  def executeSync(mapping: Seq[(os.Path, os.RelPath)],
                  changedPaths0: Map[os.Path, PathMap[Option[Sig]]],
                  vfsArr: Seq[Vfs[Sig]],
                  logger: SyncLogger,
                  send: Msg => Unit,
                  logStartFile: () => Unit)
                 (implicit ec: ExecutionContext) = {

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    logger("executeSync", changedPaths0)

    for {
      ((src, dest), i) <- mapping.zipWithIndex
      eventPaths <- changedPaths0.get(src)
    } yield {
      SyncFiles.synchronizeRepo(
        logger, vfsArr(i), src, dest,
        eventPaths,
        send,
        logStartFile
      )
    }
  }

  def synchronizeRepo(logger: SyncLogger,
                      vfs: Vfs[Sig],
                      src: os.Path,
                      dest: os.RelPath,
                      eventPaths: PathMap[Option[Sig]],
                      send: Msg => Unit,
                      logStartFile: () => Unit)
                     (implicit ec: ExecutionContext): Unit = {

    val signatureMapping0 = for{
      (sub0, newSig) <- eventPaths.walkValues()
      sub = os.SubPath(sub0)
      oldSig = vfs.resolve(sub).map(_.value)
      if newSig != oldSig
    } yield (sub, newSig, oldSig)

    val signatureMapping = signatureMapping0.toArray

    logger("signatureMapping", signatureMapping)

    logger.incrementFileTotal(src, PathSet.from(signatureMapping.map(_._1.segments)))
    val sortedSignatures = sortSignatureChanges(signatureMapping)

    syncAllFiles(vfs, send, sortedSignatures, src, dest, logger, logStartFile)
  }

  def sortSignatureChanges(sigs: Seq[(os.SubPath, Option[Sig], Option[Sig])]) = {
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

  def syncAllFiles(vfs: Vfs[Sig],
                   send: Msg => Unit,
                   signatureMapping: Seq[(os.SubPath, Option[Sig], Option[Sig])],
                   src: os.Path,
                   dest: os.RelPath,
                   logger: SyncLogger,
                   logStartFile: () => Unit): Unit = {

    def client(rpc: Rpc with Action with PathRpc) = {
      send(RpcMsg(rpc))
      Vfs.updateVfs(rpc, vfs)
    }
    for ((subPath, localSig, remoteSig) <- signatureMapping) {
      logger.apply("syncFile", (subPath, localSig, remoteSig))

      logStartFile()
      syncFileMetadata(dest, client, subPath, localSig, remoteSig)

      localSig match{
        case Some(Sig.File(_, blockHashes, size)) =>
          syncFileChunks(vfs, send, src, dest, logger, subPath, remoteSig, blockHashes, size)
        case _ =>
      }
    }
  }


  def syncFileChunks(vfs: Vfs[Sig],
                     send: Msg => Unit,
                     src: Path,
                     dest: RelPath,
                     logger: SyncLogger,
                     subPath: SubPath,
                     remoteSig: Option[Sig],
                     blockHashes: Seq[Bytes],
                     size: Long): Unit = {
    val (otherHashes, otherSizeOpt) = remoteSig match {
      case Some(Sig.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, Some(otherSize))
      case _ => (Nil, None)
    }

    for {
      i <- blockHashes.indices
      if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
    } {
      Vfs.updateVfs(
        Action.WriteChunk(subPath, i, blockHashes(i)),
        vfs
      )
      send(SendChunkMsg(src, dest, subPath, i, blockHashes.size))
    }

    // We need to update the Vfs with the Rpc.SetSize every time, because it
    // isn't smart enough to update the size automatically when we send it
    // Action.WriteChunk.
    val msg = Rpc.SetSize(dest, subPath, size)
    Vfs.updateVfs(msg, vfs)
    for(otherSize <- otherSizeOpt if otherSize > size) send(RpcMsg(msg))
  }

  def syncFileMetadata(dest: RelPath,
                       client: Rpc with Action with PathRpc => Unit,
                       subPath: SubPath,
                       localSig: Option[Sig],
                       remoteSig: Option[Sig]): Unit = {

    (localSig, remoteSig) match {
      case (None, _) =>
        client(Rpc.Remove(dest, subPath))
      case (Some(Sig.Dir(perms)), remote) =>
        remote match {
          case None =>
            client(Rpc.PutDir(dest, subPath, perms))
          case Some(Sig.Dir(remotePerms)) =>
            client(Rpc.SetPerms(dest, subPath, perms))
          case Some(_) =>
            client(Rpc.Remove(dest, subPath))
            client(Rpc.PutDir(dest, subPath, perms))
        }

      case (Some(Sig.Symlink(target)), remote) =>
        remote match {
          case None =>
            client(Rpc.PutLink(dest, subPath, target))
          case Some(_) =>
            client(Rpc.Remove(dest, subPath))
            client(Rpc.PutLink(dest, subPath, target))
        }
      case (Some(Sig.File(perms, blockHashes, size)), remote) =>
        if (remote.exists(!_.isInstanceOf[Sig.File])) {
          client(Rpc.Remove(dest, subPath))
        }

        remote match {
          case Some(Sig.File(otherPerms, otherBlockHashes, otherSize)) =>
            if (perms != otherPerms) client(Rpc.SetPerms(dest, subPath, perms))

          case _ => client(Rpc.PutFile(dest, subPath, perms))
        }
    }
  }

}
