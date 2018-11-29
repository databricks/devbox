package devbox
import collection.JavaConverters._
import java.io.{DataInputStream, DataOutputStream}
import java.util.concurrent.atomic.AtomicReference

import devbox.common._
import io.methvin.watcher.DirectoryWatcher

import scala.collection.mutable

object Main {
  def syncRepo(commandRunner: os.SubProcess,
               src: os.Path,
               dest: Seq[String],
               stateVfs: Vfs[(Long, Seq[Bytes]), Int],
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
    def performAction[T <: Action: upickle.default.Writer](p: T) = {
      Util.writeMsg(dataOut, p)
      p match{
        case Rpc.PutFile(path, perms) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.File(perms, (0, Nil))
        case Rpc.Remove(path) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          folder.value.remove(name)
        case Rpc.PutDir(path, perms) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.Folder(perms, mutable.LinkedHashMap.empty[String, Vfs.Node[(Long, Seq[Bytes]), Int]])
        case Rpc.PutLink(path, dest) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.Symlink(dest)
        case Rpc.WriteChunk(path, offset, bytes, hash) =>
          assert(offset % Signature.blockSize == 0)
          val index = offset / Signature.blockSize
          val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[(Long, Seq[Bytes]), Int]]
          currentFile.value = (
            currentFile.value._1,
            if (index < currentFile.value._2.length) currentFile.value._2.updated(index.toInt, hash)
            else if (index == currentFile.value._2.length) currentFile.value._2 :+ hash
            else ???
          )
        case Rpc.Truncate(path, offset) =>
          val currentFile = stateVfs.resolve(path).get.asInstanceOf[Vfs.File[(Long, Seq[Bytes]), Int]]
          currentFile.value = (offset, currentFile.value._2)
        case Rpc.SetPerms(path, perms) =>
          stateVfs.resolve(path) match{
            case Some(f @ Vfs.File(_, _)) => f.metadata = perms
            case Some(f @ Vfs.Folder(_, _)) => f.metadata = perms
          }
      }
      writes += 1
    }

    for(((p, localSig), remoteSig) <- signatureMapping){
      val segments = p.relativeTo(src).toString
      if (localSig != remoteSig) (localSig, remoteSig) match{
        case (None, _) =>
          performAction(Rpc.Remove(segments))
        case (Some(Signature.Dir(perms)), remote) =>
          remote match{
            case None =>
              performAction(Rpc.PutDir(segments, perms))
            case Some(Signature.Dir(remotePerms)) =>
              performAction(Rpc.SetPerms(segments, perms))
            case Some(_) =>
              performAction(Rpc.Remove(segments))
              performAction(Rpc.PutDir(segments, perms))
          }

        case (Some(Signature.Symlink(dest)), remote) =>
          remote match {
            case None =>
              performAction(Rpc.PutLink(segments, dest))
            case Some(_) =>
              performAction(Rpc.Remove(segments))
              performAction(Rpc.PutLink(segments, dest))
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          if (remote.exists(!_.isInstanceOf[Signature.File])){
            performAction(Rpc.Remove(segments))
          }

          val otherHashes = remote match{
            case Some(Signature.File(otherPerms, otherBlockHashes, _)) =>
              if (perms != otherPerms) {
                performAction(Rpc.SetPerms(segments, perms))
              }
              otherBlockHashes
            case _ =>
              performAction(Rpc.PutFile(segments, perms))
              Nil
          }

          for{
            i <- blockHashes.indices
            if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
          }{
            performAction(
              Rpc.WriteChunk(
                segments,
                i * Signature.blockSize,
                Bytes(os.read.bytes(p, i * Signature.blockSize, (i + 1) * Signature.blockSize)),
                blockHashes(i)
              )
            )
          }

          performAction(Rpc.Truncate(segments, size))
      }
    }
    for(i <- 0 until writes) assert(Util.readMsg[Int](dataIn) == 0)
  }
//
//  def syncAllRepos(commandRunner: os.SubProcess,
//                   mapping: Seq[(os.Path, Seq[String])],
//                   skip: os.Path => Boolean) = {
//
//    // initial sync
//    for((src, dest) <- mapping){
//      syncRepo(commandRunner, src, dest, os.walk(src))
//    }
//    // watch and incremental syncs
//    val eventDirs = new AtomicReference(Set.empty[os.Path])
//    val watcher = DirectoryWatcher
//      .builder
//      .paths(mapping.map(_._1.toNIO).asJava)
//      .listener{ event =>
//        val dir = os.Path(event.path())
//        if (!skip(dir)) {
//          while(!eventDirs.compareAndSet(eventDirs.get, eventDirs.get + dir)) Thread.sleep(1)
//        }
//      }
//      .build
//    watcher.watchAsync()
//
//    while(true){
//      Thread.sleep(50)
//      if (eventDirs.get.nonEmpty){
//        val startEventDirs = eventDirs.get
//        Thread.sleep(50)
//        if (startEventDirs eq eventDirs.get){
//          val currentEventDirs = eventDirs.getAndSet(Set.empty)
//          for((src, dest) <- mapping){
//            val srcEventDirs = currentEventDirs.filter(_.startsWith(src))
//            if (srcEventDirs.nonEmpty){
//              syncRepo(commandRunner, src, dest, srcEventDirs.toSeq.flatMap(os.list))
//            }
//          }
//        }
//      }
//    }
//  }
  def main(args: Array[String]): Unit = {

  }
}
