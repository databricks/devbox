package devbox
import java.io.{DataInputStream, DataOutputStream, PipedInputStream, PipedOutputStream}
import java.util.concurrent.Semaphore

import devbox.common.{Logger, RpcClient, Signature, Util}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import os.Path

import collection.JavaConverters._
import utest._

object DevboxTests extends TestSuite{

  val cases = Map(
    "edge" -> getClass.getResource("/edge-cases.bundle").toURI.toString,
    "oslib" -> System.getenv("OSLIB_BUNDLE"),
    "scalatags" -> System.getenv("SCALATAGS_BUNDLE"),
    "mill" -> System.getenv("MILL_BUNDLE"),
    "ammonite" -> System.getenv("AMMONITE_BUNDLE")
  )

  def tests = Tests{
    // A few example repositories to walk through and make sure the delta syncer
    // can function on every change of commit. Ordered by increasing levels of
    // complexity
    'edge - {
      * - walkValidate("edge", cases("edge"), 1, 50, 0)
      'git - walkValidate("edgegit", cases("edge"), 1, 50, 0, ignoreStrategy = "")
      'restart - walkValidate("edgerestart", cases("edge"), 1, 50, 0, restartSyncer = true)
    }

    'oslib - {
      * - walkValidate("oslib", cases("oslib"), 1, 50, 0)
      'git - walkValidate("oslibgit", cases("oslib"), 2, 50, 0, ignoreStrategy = "")
      'restart - walkValidate("oslibrestart", cases("oslib"), 2, 50, 0, restartSyncer = true)
    }

    'scalatags - {
      * - walkValidate("scalatags", cases("scalatags"), 3, 100, 0)
      'restart - walkValidate("scalatags", cases("scalatags"), 3, 100, 0, restartSyncer = true)
    }
    'mill - {
      * - walkValidate("mill", cases("mill"), 4, 100, 0)
      'restart - walkValidate("mill", cases("mill"), 4, 100, 0, restartSyncer = true)
    }
    'ammonite - {
      * - walkValidate("ammonite", cases("ammonite"), 5, 200, 0)
      'restart - walkValidate("ammonite", cases("ammonite"), 5, 200, 0, restartSyncer = true)
    }
  }


  def walkValidate(label: String,
                   uri: String,
                   stride: Int,
                   debounceMillis: Int,
                   initialCommit: Int,
                   commitIndicesToCheck0: Seq[Int] = Nil,
                   verbose: Boolean = false,
                   ignoreStrategy: String = "dotgit",
                   restartSyncer: Boolean = false) = {

    val (src, dest, log, commits, workCount, skip, commitsIndicesToCheck, repo) =
      initializeWalk(label, uri, stride, commitIndicesToCheck0, ignoreStrategy)


    var lastWriteCount = 0

    def createSyncer() = instantiateSyncer(
      src, dest, log,
      skip, debounceMillis, () => workCount.release(), verbose, ignoreStrategy,
      restartSyncer
    )
    var syncer = createSyncer()
    try{
      printBanner(initialCommit, commits.length, 0, commitsIndicesToCheck.length, commits(initialCommit))
      syncer.start()
      println("Write Count: " + (syncer.writeCount - lastWriteCount))

      lastWriteCount = syncer.writeCount

      for ((i, count) <- commitsIndicesToCheck.zipWithIndex) {
        val commit = commits(i)
        printBanner(i, commits.length, count+1, commitsIndicesToCheck.length, commit)

        repo.checkout().setName(commit.getName).call()

        println("Checkout finished")

        if (restartSyncer && syncer == null){
          lastWriteCount = 0
          println("Restarting Syncer")
          syncer = createSyncer()
          syncer.start()
        }

        workCount.acquire()
        println("Write Count: " + (syncer.writeCount - lastWriteCount))
        lastWriteCount = syncer.writeCount

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (count % stride == 0) {
          if (restartSyncer){
            println("Stopping Syncer")
            syncer.close()
            // Closing the syncer results in workCount being given a permit
            // that we need to clear before further use
            workCount.acquire()
            syncer = null
          }
          validate(src, dest, skip)
        }
      }
    }finally{
      if (syncer != null) syncer.close()
    }
  }

  def initializeWalk(label: String,
                     uri: String,
                     stride: Int,
                     commitIndicesToCheck0: Seq[Int],
                     ignoreStrategy: String) = {
    val (src, dest, log) = prepareFolders(label)
    val repo = Git.cloneRepository()
      .setURI(uri)
      .setDirectory(src.toIO)
      .call()

    val commits = repo.log().call().asScala.toSeq.reverse

    repo.checkout().setName(commits.head.getName).call()

    val workCount = new Semaphore(0)

    // Fixed random to make the random jumps deterministic
    val random = new scala.util.Random(31337)

    val commitsIndicesToCheck =
      if (commitIndicesToCheck0 != Nil) commitIndicesToCheck0
      else
        // Step through the commits in order to test "normal" edits
        (1 until commits.length) ++
        // Also jump between a bunch of random commits to test robustness against
        // huge edits modifying lots of different files
        (0 until 10 * stride).map(_ => random.nextInt(commits.length))

    val skip = Util.ignoreCallback(ignoreStrategy)
    (src, dest, log, commits, workCount, skip, commitsIndicesToCheck, repo)
  }

  def prepareFolders(label: String, preserve: Boolean = false) = {
    val src = os.pwd / "out" / "scratch" / label / "src"
    val dest = os.pwd / "out" / "scratch" / label / "dest"
    val log = os.pwd / "out" / "scratch" / label / "events.log"

    if (!preserve){
      os.remove.all(src)
      os.makeDir.all(src)
      os.remove.all(dest)
      os.makeDir.all(dest)
    }
    os.remove.all(log)

    (src, dest, log)
  }

  def printBanner(commitIndex: Int, commitCount: Int, trialIndex: Int, trialCount: Int, commit: RevCommit) = {
    println("=" * 80)
    println(s"[$commitIndex/$commitCount $trialIndex/$trialCount] Checking ${commit.getName.take(8)} ${commit.getShortMessage}")
  }

  def instantiateSyncer(src: os.Path,
                        dest: os.Path,
                        log: os.Path,
                        skip: (os.Path, os.Path) => Boolean,
                        debounceMillis: Int,
                        onComplete: () => Unit,
                        verbose: Boolean,
                        ignoreStrategy: String,
                        inMemoryAgent: Boolean) = {
    new Syncer(
      if (inMemoryAgent) new InMemoryAgent(dest, skip)
      else os.proc(
        System.getenv("AGENT_EXECUTABLE"),
        "--ignore-strategy", ignoreStrategy,
        "--working-dir", dest
      ).spawn(cwd = dest),
      Seq(src -> Nil),
      skip,
      debounceMillis,
      onComplete,
      if (verbose) Logger.Stdout else Logger.File(log)
    )
  }

  def validate(src: os.Path, dest: os.Path, skip: (os.Path, os.Path) => Boolean) = {
    println("Validating...")
    val srcPaths = os.walk(src, skip(_, src))
    val destPaths = os.walk(dest, skip(_, dest))

    val srcRelPaths = srcPaths.map(_.relativeTo(src)).toSet
    val destRelPaths = destPaths.map(_.relativeTo(dest)).toSet

    if (srcRelPaths != destRelPaths){
      throw new Exception(
        "Path list difference, src: " + (srcRelPaths -- destRelPaths) + ", dest: " + (destRelPaths -- srcRelPaths)
      )
    }
    val buffer = new Array[Byte](Util.blockSize)

    val differentSigs = srcPaths.zip(destPaths).flatMap{ case (s, d) =>
      val srcSig = if (os.exists(s, followLinks = false)) Signature.compute(s, buffer) else None
      val destSig = if (os.exists(d, followLinks = false)) Signature.compute(d, buffer) else None

      if(srcSig == destSig) None
      else Some((s.relativeTo(src), srcSig, destSig))
    }

    if (differentSigs.nonEmpty){
      throw new Exception(
        "Signature list difference " + differentSigs
      )
    }
  }

}
