package devbox

import java.io.{PrintWriter, StringWriter}
import java.util.concurrent.LinkedBlockingQueue

import devbox.common.{Action, Bytes, Logger, PathRpc, Rpc, RpcException, Sig, Util, Vfs}
import os.{Path, RelPath, SubPath}

import scala.concurrent.{ExecutionContext, Future}

object SyncFiles {

  sealed trait Msg

  case class IncrementFileTotal(base: os.Path, subs: Set[os.SubPath]) extends Msg
  case class StartFile(p: os.Path) extends Msg

  sealed trait RemoteMsg extends Msg
  case class Complete() extends RemoteMsg
  case class RemoteScan(paths: Seq[os.RelPath]) extends RemoteMsg
  case class RpcMsg(value: Rpc with PathRpc) extends RemoteMsg
  case class SendChunkMsg(src: os.Path,
                          dest: os.RelPath,
                          subPath: os.SubPath,
                          chunkIndex: Int,
                          chunkCount: Int) extends RemoteMsg


  def executeSync(mapping: Seq[(os.Path, os.RelPath)],
                  changedPaths0: Map[os.Path, Map[os.SubPath, Option[Sig]]],
                  vfsArr: Seq[Vfs[Sig]],
                  logger: SyncLogger,
                  send: Msg => Unit)
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
        send
      )
    }
  }

  def synchronizeRepo(logger: SyncLogger,
                      vfs: Vfs[Sig],
                      src: os.Path,
                      dest: os.RelPath,
                      eventPaths: Map[os.SubPath, Option[Sig]],
                      send: Msg => Unit)
                     (implicit ec: ExecutionContext): Unit = {

    val signatureMapping = for{
      (sub, newSig) <- eventPaths
      oldSig = vfs.resolve(sub).map(_.value)
      if newSig != oldSig
    } yield (sub, newSig, oldSig)


    logger("signatureMapping", signatureMapping)

    if (signatureMapping.nonEmpty) {
      send(IncrementFileTotal(src, signatureMapping.map(_._1).toSet))
      val sortedSignatures = sortSignatureChanges(signatureMapping.toSeq)

      syncAllFiles(vfs, send, sortedSignatures, src, dest, logger)
    }
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
                   logger: SyncLogger): Unit = {

    def client(rpc: Rpc with Action with PathRpc) = {
      send(RpcMsg(rpc))
      Vfs.updateVfs(rpc, vfs)
    }
    for ((subPath, localSig, remoteSig) <- signatureMapping) {
      logger.apply("syncFile", (subPath, localSig, remoteSig))
      send(StartFile(src / subPath))
      syncFileMetadata(dest, client, subPath, localSig, remoteSig)

      localSig match{
        case Some(Sig.File(_, blockHashes, size)) =>
          syncFileChunks(vfs, send, src, dest, logger, subPath, remoteSig, blockHashes, size)
        case _ =>
      }
      logger.apply("syncFile end")

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
    val (otherHashes, otherSize) = remoteSig match {
      case Some(Sig.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
      case _ => (Nil, 0L)
    }
    logger("syncFileChunks", (subPath, size, otherSize, remoteSig))
    val chunkIndices = for {
      i <- blockHashes.indices
      if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
    } yield {
      Vfs.updateVfs(
        Action.WriteChunk(subPath, i, blockHashes(i)),
        vfs
      )
      i
    }
    for (chunkIndex <- chunkIndices) {
      send(SendChunkMsg(src, dest, subPath, chunkIndex, blockHashes.size))
    }

    if (size != otherSize) {
      val msg = Rpc.SetSize(dest, subPath, size)
      Vfs.updateVfs(msg, vfs)
      send(RpcMsg(msg))
    }
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
