package devbox
import java.util.concurrent.Semaphore

import devbox.common.{Logger, Signature, Util}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import os.Path

import collection.JavaConverters._
import utest._

object DevboxTests extends TestSuite{
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

  def printBanner(commitIndex: Int, commitCount: Int, trialIndex: Int, trialCount: Int, commit: RevCommit) = {
    println("=" * 80)
    println(s"[$commitIndex/$commitCount $trialIndex/$trialCount] Checking ${commit.getName.take(8)} ${commit.getShortMessage}")
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

  def instantiateSyncer(src: os.Path,
                        dest: os.Path,
                        log: os.Path,
                        skip: (os.Path, os.Path) => Boolean,
                        debounceMillis: Int,
                        onComplete: () => Unit,
                        verbose: Boolean,
                        ignoreStrategy: String) = {
    new Syncer(
      os.proc(System.getenv("AGENT_EXECUTABLE"), "--ignore-strategy", ignoreStrategy).spawn(cwd = dest),
      Seq(src -> Nil),
      skip,
      debounceMillis,
      onComplete,
      if (verbose) Logger.Stdout else Logger.File(log)
    )
  }

  def initializeWalk(label: String,
                     uri: String,
                     stride: Int,
                     commitIndicesToCheck0: Seq[Int],
                     ignoreStrategy: String,
                     restartSyncer: Boolean) = {
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
        (if (restartSyncer) Nil else (1 until commits.length)) ++
        // Also jump between a bunch of random commits to test robustness against
        // huge edits modifying lots of different files
        (0 until 10 * stride).map(_ => random.nextInt(commits.length))

    val skip = Util.ignoreCallback(ignoreStrategy)
    (src, dest, log, commits, workCount, skip, commitsIndicesToCheck, repo)
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
      initializeWalk(label, uri, stride, commitIndicesToCheck0, ignoreStrategy, restartSyncer)


    var lastWriteCount = 0

    def createSyncer() = instantiateSyncer(
      src, dest, log,
      skip, debounceMillis, () => workCount.release(), verbose, ignoreStrategy
    )
    var syncer = createSyncer()
    try{
      printBanner(initialCommit, commits.length, 0, commitsIndicesToCheck.length, commits(initialCommit))
      syncer.start()
      workCount.acquire()
      println("Write Count: " + (syncer.writeCount - lastWriteCount))
      validate(src, dest, skip)

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
          workCount.acquire()
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
            syncer = null
          }
          validate(src, dest, skip)
        }
      }
    }finally{
      if (syncer != null) syncer.close()
    }
  }

  val cases = Map(
    "edge" -> getClass.getResource("/edge-cases.bundle").toURI.toString,
    "oslib" -> System.getenv("OSLIB_BUNDLE"),
    "scalatags" -> System.getenv("SCALATAGS_BUNDLE"),
    "mill" -> System.getenv("MILL_BUNDLE"),
    "ammonite" -> System.getenv("AMMONITE_BUNDLE")
  )

  def tests = Tests{
    def check(stride: Int, debounceMillis: Int, ignoreStrategy: String = "dotgit")
             (implicit tp: utest.framework.TestPath) = {
      walkValidate(tp.value.last, cases(tp.value.last), stride, debounceMillis, 0)
    }
    // A few example repositories to walk through and make sure the delta syncer
    // can function on every change of commit. Ordered by increasing levels of
    // complexity
    'edge - check(1, 50)
    'edgegit - walkValidate("edgegit", cases("edge"), 1, 50, 0, ignoreStrategy = "")
    'edgerestart - walkValidate("edgerestart", cases("edge"), 1, 50, 0, ignoreStrategy = "dotgit", restartSyncer = true)
    'oslib - check(2, 50)
    'oslibgit - walkValidate("oslibgit", cases("oslib"), 2, 50, 0, ignoreStrategy = "")
    'oslibrestart - walkValidate("oslibrestart", cases("oslib"), 2, 50, 0, ignoreStrategy = "dotgit", restartSyncer = true)
    'scalatags - check(3, 100)
    'mill - check(4, 100)
    'ammonite - check(5, 200)
  }
}
