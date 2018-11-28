package devbox
import collection.JavaConverters._
import java.io.{DataInputStream, DataOutputStream}
import java.util.concurrent.atomic.AtomicReference

import devbox.common.{Rpc, Signature, Util}
import io.methvin.watcher.DirectoryWatcher

object Main {
  def syncRepo(commandRunner: os.SubProcess,
               src: os.Path,
               paths: Seq[os.Path]) = {
    val localSignatures = for(p <- paths) yield (p, Signature.compute(p))
    val dataOut = new DataOutputStream(commandRunner.stdin)
    val dataIn = new DataInputStream(commandRunner.stdout)
    for((p, _) <- localSignatures) {
      val checkHash = upickle.default.writeBinary(Rpc.CheckHash(p.relativeTo(src).segments))

      dataOut.write(checkHash.length)
      dataOut.write(checkHash)
      dataOut.flush()
    }

    val remoteSignatures = for(p <- paths) yield {
      val length = dataIn.readInt()
      val bytes = new Array[Byte](length)
      dataIn.readFully(bytes)
      upickle.default.readBinary[Option[Signature]](bytes)
    }

    val signatureMapping = localSignatures.zip(remoteSignatures)

    for(((p, localSig), remoteSig) <- signatureMapping){
      if (localSig != remoteSig) (localSig, remoteSig) match{
        case (None, _) =>
          val bytes = upickle.default.writeBinary(Rpc.Remove(p.segments.toSeq))
          dataOut.writeInt(bytes.length)
          dataOut.write(bytes)

        case (Some(Signature.Dir(perms)), remote) =>
          remote match{
            case None => Util.writeMsg(dataOut, Rpc.PutDir(p.segments.toSeq, perms))
            case Some(Signature.Dir(remotePerms)) =>
              Util.writeMsg(dataOut, Rpc.SetPerms(p.segments.toSeq, perms))
            case Some(_) =>
              Util.writeMsg(dataOut, Rpc.Remove(p.segments.toSeq))
              Util.writeMsg(dataOut, Rpc.PutDir(p.segments.toSeq, perms))
          }

        case (Some(Signature.Symlink(dest)), remote) =>
          remote match {
            case None => Util.writeMsg(dataOut, Rpc.PutLink(p.segments.toSeq, dest))
            case Some(_) =>
              Util.writeMsg(dataOut, Rpc.Remove(p.segments.toSeq))
              Util.writeMsg(dataOut, Rpc.PutLink(p.segments.toSeq, dest))
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          if (remote.exists(!_.isInstanceOf[Signature.File])){
            Util.writeMsg(dataOut, Rpc.Remove(p.segments.toSeq))
          }
          val otherHashes = remote match{
            case Some(Signature.File(otherPerms, otherBlockHashes, _)) =>
              if (perms != otherPerms) Util.writeMsg(dataOut, Rpc.SetPerms(p.segments.toSeq, perms))
              otherBlockHashes
            case _ =>
              Util.writeMsg(dataOut, Rpc.PutFile(p.segments.toSeq, perms))
              Nil
          }

          for(i <- blockHashes.indices if blockHashes(i) != otherHashes(i)){
            val bytes = upickle.default.writeBinary(
              Rpc.WriteChunk(
                p.segments.toSeq,
                i * Signature.blockSize,
                os.read.bytes(p, i * Signature.blockSize, (i + 1) * Signature.blockSize)
              )
            )
            dataOut.writeInt(bytes.length)
            dataOut.write(bytes)
          }

          Util.writeMsg(dataOut, Rpc.Truncate(p.segments.toSeq, size))
      }
    }
  }

  def syncAllRepos(commandRunner: os.SubProcess,
                   mapping: Seq[(os.Path, Seq[String])],
                   skip: os.Path => Boolean) = {


    // initial sync
    for((src, dest) <- mapping){
      syncRepo(commandRunner, src, os.walk(src))
    }
    // watch and incremental syncs
    val eventDirs = new AtomicReference(Set.empty[os.Path])
    val watcher = DirectoryWatcher
      .builder
      .paths(mapping.map(_._1.toNIO).asJava)
      .listener{ event =>
        val dir = os.Path(event.path())
        if (!skip(dir)) {
          while(!eventDirs.compareAndSet(eventDirs.get, eventDirs.get + dir)) Thread.sleep(1)
        }
      }
      .build
    watcher.watchAsync()

    while(true){
      Thread.sleep(50)
      if (eventDirs.get.nonEmpty){
        val startEventDirs = eventDirs.get
        Thread.sleep(50)
        if (startEventDirs eq eventDirs.get){
          val currentEventDirs = eventDirs.getAndSet(Set.empty)
          for((src, dest) <- mapping){
            val srcEventDirs = currentEventDirs.filter(_.startsWith(src))
            if (srcEventDirs.nonEmpty){
              syncRepo(commandRunner, src, srcEventDirs.toSeq.flatMap(os.list))
            }
          }
        }
      }
    }
  }
  def main(args: Array[String]): Unit = {

  }
}
