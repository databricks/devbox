package devbox
import java.io.{DataInputStream, DataOutputStream}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import devbox.common.{Bytes, Rpc, Signature, Util}
import com.sun.jna.{NativeLong, Pointer}
import io.methvin.watcher.DirectoryWatcher
import io.methvin.watchservice.jna.{CFIndex, CFStringRef, CarbonAPI, FSEventStreamRef}
import io.methvin.watchservice.jna.CarbonAPI.FSEventStreamCallback
import org.eclipse.jgit.api.Git

import collection.JavaConverters._
import utest._

import scala.collection.mutable
object DevboxTests extends TestSuite{
  def validate(src: os.Path, dest: os.Path, skip: os.Path => Boolean) = {
    val srcPaths = os.walk(src, skip)
    val destPaths = os.walk(dest, skip)

    val srcRelPaths = srcPaths.map(_.relativeTo(src)).toSet
    val destRelPaths = destPaths.map(_.relativeTo(dest)).toSet
    if (srcRelPaths != destRelPaths){
      throw new Exception(
        "Path list difference, src: " + (srcRelPaths -- destRelPaths) + ", dest: " + (destRelPaths -- srcRelPaths)
      )
    }

    val differentSigs = srcPaths.zip(destPaths).flatMap{ case (s, d) =>
      val srcSig = Signature.compute(s)
      val destSig = Signature.compute(d)

      if(srcSig == destSig) None
      else Some((s.relativeTo(src), srcSig, destSig))
    }
    if (differentSigs.nonEmpty){
      throw new Exception(
        "Signature list difference" + differentSigs
      )
    }
  }
  def check(label: String, uri: String, stride: Int) = {
    val src = os.pwd / "out" / "scratch" / label / "src"
    val dest = os.pwd / "out" / "scratch" / label / "dest"
    os.remove.all(src)
    os.makeDir.all(src)
    os.remove.all(dest)
    os.makeDir.all(dest)

    val agentExecutable = System.getenv("AGENT_EXECUTABLE")

    val repo = Git.cloneRepository()
      .setURI(uri)
      .setDirectory(src.toIO)
      .call()

    val commits = repo.log().call().asScala.toSeq.reverse
    val agent = os.proc(agentExecutable).spawn(cwd = dest, stderr = os.Inherit)

    val vfs = new Vfs[(Long, Seq[Bytes]), Int](0)
    repo.checkout().setName(commits.head.getName).call()


    // watch and incremental syncs

    println("="*80)
    println("Initial Sync")
    val dataOut = new DataOutputStream(agent.stdin)
    val dataIn = new DataInputStream(agent.stdout)
    Util.writeMsg(dataOut, Rpc.FullScan(""))
    val initial = Util.readMsg[Seq[(String, Signature)]](dataIn)

    for((p, sig) <- initial) sig match{
      case Signature.File(perms, hashes, size) =>
        val (name, folder) = vfs.resolveParent(p).get
        assert(!folder.value.contains(name))
        folder.value(name) = Vfs.File(perms, (size, hashes))

      case Signature.Dir(perms) =>
        val (name, folder) = vfs.resolveParent(p).get
        assert(!folder.value.contains(name))
        folder.value(name) = Vfs.Folder(perms, mutable.LinkedHashMap.empty[String, Vfs.Node[(Long, Seq[Bytes]), Int]])

      case Signature.Symlink(dest) =>
        val (name, folder) = vfs.resolveParent(p).get
        assert(!folder.value.contains(name))
        folder.value(name) = Vfs.Symlink(dest)

    }
    Main.syncRepo(
      agent,
      src,
      dest.segments.toSeq,
      vfs,
      os.walk(src, _.segments.contains(".git"), includeTarget = true).filter(os.isDir)
    )


    println("="*80)
    println("Incremental Sync")

    val eventQueue = new ConcurrentLinkedQueue[Array[String]]()

    val thread = new Thread(() => {
      val callback = new FSEventStreamCallback{
        def invoke(streamRef: FSEventStreamRef,
                   clientCallBackInfo: Pointer,
                   numEvents: NativeLong,
                   eventPaths: Pointer,
                   eventFlags: Pointer,
                   eventIds: Pointer) = {
          val length = numEvents.intValue
          eventQueue.add(eventPaths.getStringArray(0, length))

//          println("FSINVOKE " + arr.toSeq)
        }
      }
      val streamRef = CarbonAPI.INSTANCE.FSEventStreamCreate(
        Pointer.NULL,
        callback,
        Pointer.NULL,
        CarbonAPI.INSTANCE.CFArrayCreate(
          null,
          Array[Pointer](CFStringRef.toCFString(src.toString()).getPointer()),
          CFIndex.valueOf(1),
          null
        ),
        -1,
        0.01,
        0
      )
      CarbonAPI.INSTANCE.FSEventStreamScheduleWithRunLoop(
        streamRef,
        CarbonAPI.INSTANCE.CFRunLoopGetCurrent(),
        CFStringRef.toCFString("kCFRunLoopDefaultMode")
      )
      CarbonAPI.INSTANCE.FSEventStreamStart(streamRef)
      CarbonAPI.INSTANCE.CFRunLoopRun()
    })

    thread.start()

    Thread.sleep(50)
    try {
      for ((commit, i) <- commits.drop(1).zipWithIndex) {
        println("="*80)

        println(s"[$i/${commits.length}] Checking ${commit.getName} ${commit.getFullMessage}")
        repo.checkout().setName(commit.getName).call()
        println("syncRepo")

        val interestingBases = mutable.Buffer.empty[String]

        /**
          * Keep pulling stuff out of the [[eventQueue]], until the queue has
          * stopped changing and is empty twice in a row.
          */
        def await(onRetry: Boolean): Unit = {
          eventQueue.poll() match{
            case null =>
              if (onRetry){
                () // terminate
              }else{
                Thread.sleep(50)
                await(true)
              }
            case arr =>
              interestingBases.appendAll(arr)
              await(false)
          }
        }
        await(false)

        Main.syncRepo(
          agent,
          src,
          dest.segments.toSeq,
          vfs,
          interestingBases.distinct.map(os.Path(_)).filter(!_.segments.contains(".git"))
        )

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (i % stride == 0) validate(src, dest, _.segments.contains(".git"))
      }
    }finally{
//      Thread.sleep(999999)
//     watcher.close()
    }
  }

  def tests = Tests{
    'edge - check("edge-cases", getClass.getResource("/edge-cases.bundle").toURI.toString, 1)
    'oslib - check("oslib", System.getenv("OSLIB_BUNDLE"), 2)
    'scalatags - check("scalatags", System.getenv("SCALATAGS_BUNDLE"), 3)
    'mill - check("mill", System.getenv("MILL_BUNDLE"), 4)
    'ammonite - check("ammonite", System.getenv("AMMONITE_BUNDLE"), 5)
  }
}
