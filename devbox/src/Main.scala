package devbox
import collection.JavaConverters._
import java.io.{DataInputStream, DataOutputStream}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import devbox.common._
import io.methvin.watcher.DirectoryWatcher

import scala.collection.mutable

object Main {
  def syncRepo(commandRunner: os.SubProcess,
               src: os.Path,
               dest: Seq[String],
               stateVfs: Vfs[(Long, Seq[Bytes]), Int],
               interestingBases: Seq[os.Path]) = {

    val dataOut = new DataOutputStream(commandRunner.stdin)
    val dataIn = new DataInputStream(commandRunner.stdout)
    // interestingBases.foreach(x => println("BASE " + x))
    val signatureMapping = interestingBases
      // Only bother looking at paths which are canonical; changes to non-
      // canonical paths can be ignored because we'd also get the canonical
      // path that we can operate on.
      .filter(p => os.followLink(p).contains(p))
      .sortBy(_.segmentCount)
      .flatMap { p =>
        val listed =
          if (!os.exists(p, followLinks = false)) Nil
          else os.list(p).map(_.relativeTo(src).toString)

        val virtual = stateVfs.resolve(p.relativeTo(src).toString).fold(Seq[String]()) {
          case f: Vfs.File[_, _] => Seq(p.relativeTo(src).toString)
          case f: Vfs.Folder[_, _] => f.value.keys.map(k => (p.relativeTo(src) / k).toString).toSeq
          case f: Vfs.Symlink => Seq(p.relativeTo(src).toString)
        }

        (listed ++ virtual).map { p1 =>
          (
            src / os.RelPath(p1),
            Signature.compute(src / os.RelPath(p1)),
            stateVfs.resolve(p1).map {
              case f: Vfs.File[(Long, Seq[Bytes]), Int] => Signature.File(f.metadata, f.value._2, f.value._1)
              case f: Vfs.Folder[(Long, Seq[Bytes]), Int] => Signature.Dir(f.metadata)
              case f: Vfs.Symlink => Signature.Symlink(f.value)
            }
          )
        }
      }

    var totalWrites = 0
    var pipelinedWrites = 0
    def performAction[T <: Action: upickle.default.Writer](p: T) = {
      Util.writeMsg(dataOut, p)
      p match{
        case Rpc.PutFile(path, perms) =>
          val (name, folder) = stateVfs.resolveParent(path).get
          assert(!folder.value.contains(name))
          folder.value(name) = Vfs.File(perms, (0, Nil))

        case Rpc.Remove(path) =>
          stateVfs.resolveParent(path).foreach{
            case (name, folder) => folder.value.remove(name)
          }

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
      pipelinedWrites += 1
      totalWrites += 1
    }
    // signatureMapping.map(_._1).foreach(x => println("SIG " + x))
    for((p, localSig, remoteSig) <- signatureMapping.sortBy(x => (x._1.segmentCount, x._1.toString))){

      val segments = p.relativeTo(src).toString
      if (localSig != remoteSig) {
        (localSig, remoteSig) match{
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

            val (otherHashes, otherSize) = remote match{
              case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
                if (perms != otherPerms) {
                  performAction(Rpc.SetPerms(segments, perms))
                }
                otherBlockHashes -> otherSize
              case _ =>
                performAction(Rpc.PutFile(segments, perms))
                Nil -> 0L
            }

            val channel = p.toSource.getChannel()
            val byteArr = new Array[Byte](Signature.blockSize)
            val buf = ByteBuffer.wrap(byteArr)
            try {
              for {
                i <- blockHashes.indices
                if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
              } {
                buf.rewind()
                channel.position(i * Signature.blockSize)
                var n = 0
                while({
                  if (n == Signature.blockSize) false
                  else channel.read(buf) match{
                    case -1 => false
                    case d =>
                      n += d
                      true
                  }
                })()


                performAction(
                  Rpc.WriteChunk(
                    segments,
                    i * Signature.blockSize,
                    Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr),
                    blockHashes(i)
                  )
                )
              }
            }finally{
              channel.close()
            }

            performAction(Rpc.Truncate(segments, size))
        }
      }
      if (pipelinedWrites == 1000){
        for(i <- 0 until pipelinedWrites) assert(Util.readMsg[Int](dataIn) == 0)
        pipelinedWrites = 0
      }
    }
    for(i <- 0 until pipelinedWrites) assert(Util.readMsg[Int](dataIn) == 0)

    println("Total writes: " + totalWrites)
  }

  def syncAllRepos(commandRunner: os.SubProcess,
                   mapping: Seq[(os.Path, Seq[String])],
                   skip: os.Path => Boolean) = {

    // initial sync
    for((src, dest) <- mapping){
//      syncRepo(commandRunner, src, dest, os.walk(src))
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
//              syncRepo(commandRunner, src, dest, srcEventDirs.toSeq.flatMap(os.list))
            }
          }
        }
      }
    }
  }
  def main(args: Array[String]): Unit = {

  }
}
