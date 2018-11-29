package devbox
import collection.JavaConverters._
import java.io.{DataInputStream, DataOutputStream}
import java.util.concurrent.atomic.AtomicReference

import devbox.common.{Bytes, Rpc, Signature, Util}
import io.methvin.watcher.DirectoryWatcher

object Main {
  def syncRepo(commandRunner: os.SubProcess,
               src: os.Path,
               dest: Seq[String],
               paths: Seq[os.Path]) = {
    val localSignatures = for(p <- paths) yield (p, Signature.compute(p))
    val dataOut = new DataOutputStream(commandRunner.stdin)
    val dataIn = new DataInputStream(commandRunner.stdout)
    for((p, _) <- localSignatures) {
      Util.writeMsg(dataOut, Rpc.CheckHash(p.relativeTo(src).toString))
    }
    val remoteSignatures = for(p <- paths) yield {
      Util.readMsg[Option[Signature]](dataIn)
    }

    val signatureMapping = localSignatures.zip(remoteSignatures)

    var writes = 0
    for(((p, localSig), remoteSig) <- signatureMapping){
      val segments = p.relativeTo(src).toString
      if (localSig != remoteSig) (localSig, remoteSig) match{
        case (None, _) =>
          Util.writeMsg(dataOut, Rpc.Remove(segments))
          writes += 1
        case (Some(Signature.Dir(perms)), remote) =>
          remote match{
            case None =>
              Util.writeMsg(dataOut, Rpc.PutDir(segments, perms))
              writes += 1
            case Some(Signature.Dir(remotePerms)) =>
              Util.writeMsg(dataOut, Rpc.SetPerms(segments, perms))
              writes += 1
            case Some(_) =>
              Util.writeMsg(dataOut, Rpc.Remove(segments))
              writes += 1
              Util.writeMsg(dataOut, Rpc.PutDir(segments, perms))
              writes += 1
          }

        case (Some(Signature.Symlink(dest)), remote) =>
          remote match {
            case None =>
              Util.writeMsg(dataOut, Rpc.PutLink(segments, dest))
              writes += 1
            case Some(_) =>
              Util.writeMsg(dataOut, Rpc.Remove(segments))
              writes += 1
              Util.writeMsg(dataOut, Rpc.PutLink(segments, dest))
              writes += 1
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          if (remote.exists(!_.isInstanceOf[Signature.File])){
            Util.writeMsg(dataOut, Rpc.Remove(segments))
            writes += 1
          }

          val otherHashes = remote match{
            case Some(Signature.File(otherPerms, otherBlockHashes, _)) =>
              if (perms != otherPerms) {
                Util.writeMsg(dataOut, Rpc.SetPerms(segments, perms))
                writes += 1
              }
              otherBlockHashes
            case _ =>
              Util.writeMsg(dataOut, Rpc.PutFile(segments, perms))
              writes += 1
              Nil
          }

          for{
            i <- blockHashes.indices
            if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
          }{
            Util.writeMsg(
              dataOut,
              Rpc.WriteChunk(
                segments,
                i * Signature.blockSize,
                Bytes(os.read.bytes(p, i * Signature.blockSize, (i + 1) * Signature.blockSize))
              )
            )
            writes += 1
          }

          Util.writeMsg(dataOut, Rpc.Truncate(segments, size))
          writes += 1
      }
    }
    for(i <- 0 until writes) assert(Util.readMsg[Int](dataIn) == 0)
  }

  def syncAllRepos(commandRunner: os.SubProcess,
                   mapping: Seq[(os.Path, Seq[String])],
                   skip: os.Path => Boolean) = {

    // initial sync
    for((src, dest) <- mapping){
      syncRepo(commandRunner, src, dest, os.walk(src))
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
              syncRepo(commandRunner, src, dest, srcEventDirs.toSeq.flatMap(os.list))
            }
          }
        }
      }
    }
  }
  def main(args: Array[String]): Unit = {

  }
}
