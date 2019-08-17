package devbox
import java.io.{DataInputStream, DataOutputStream, PipedInputStream, PipedOutputStream}
import java.util.concurrent.Semaphore

import devbox.common._
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
      'git - walkValidate("edge-git", cases("edge"), 1, 50, 0, ignoreStrategy = "")
      'restart - walkValidate("edge-restart", cases("edge"), 1, 50, 0, restartSyncer = true)
    }

    'oslib - {
      * - walkValidate("oslib", cases("oslib"), 1, 100, 0)
      'git - walkValidate("oslib-git", cases("oslib"), 1, 100, 0, ignoreStrategy = "")
      'restart - walkValidate("oslib-restart", cases("oslib"), 1, 50, 0, restartSyncer = true)
    }

    'scalatags - {
      * - walkValidate("scalatags", cases("scalatags"), 1, 250, 0)
      'restart - walkValidate("scalatags-restart", cases("scalatags"), 1, 250, 0, restartSyncer = true)
    }
    'mill - {
      * - walkValidate("mill", cases("mill"), 4, 100, 0)
      'restart - walkValidate("mill-restart", cases("mill"), 4, 100, 0, restartSyncer = true)
    }
    'ammonite - {
      * - walkValidate("ammonite", cases("ammonite"), 5, 200, 0)
      'restart - walkValidate("ammonite-restart", cases("ammonite"), 5, 200, 0, restartSyncer = true)
    }
  }


  def walkValidate(label: String,
                   uri: String,
                   stride: Int,
                   debounceMillis: Int,
                   initialCommit: Int,
                   commitIndicesToCheck0: Seq[Int] = Nil,
                   ignoreStrategy: String = "dotgit",
                   restartSyncer: Boolean = false) = {

    val (src, dest, log, commits, workCount, skipper, commitsIndicesToCheck, repo) =
      initializeWalk(label, uri, stride, commitIndicesToCheck0, ignoreStrategy)

    val logger = Logger.File(log, toast = false)

    def createSyncer() = instantiateSyncer(
      src, dest, skipper, debounceMillis, () => workCount.release(),
      logger, ignoreStrategy, restartSyncer,
      exitOnError = true,
      signatureMapping = (_, sig) => sig
    )
    var syncer = createSyncer()
    try{
      printBanner(initialCommit, commits.length, 0, commitsIndicesToCheck.length, commits(initialCommit))
      syncer.start()


      for ((i, count) <- commitsIndicesToCheck.zipWithIndex) {
        val commit = commits(i)
        printBanner(i, commits.length, count+1, commitsIndicesToCheck.length, commit)
        logger("TEST CHECKOUT", commit.getShortMessage)
        repo.checkout().setName(commit.getName).call()

        logger("TEST CHECKOUT DONE", commit.getShortMessage)

        if (restartSyncer && syncer == null){
          logger("TEST RESTART SYNCER")
          syncer = createSyncer()
          syncer.start()
        }

        workCount.acquire()

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (count % stride == 0) {
          if (restartSyncer){
            logger("TEST STOP SYNCER")
            syncer.close()
            syncer = null
          }

          logger("TEST VALIDATE")
          validate(src, dest, skipper)
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

    val skipper = Skipper.fromString(ignoreStrategy)
    (src, dest, log, commits, workCount, skipper, commitsIndicesToCheck, repo)
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
                        skipper: Skipper,
                        debounceMillis: Int,
                        onComplete: () => Unit,
                        logger: Logger,
                        ignoreStrategy: String,
                        inMemoryAgent: Boolean,
                        exitOnError: Boolean,
                        signatureMapping: (os.RelPath, Signature) => Signature) = {
    new Syncer(
      if (inMemoryAgent) new InMemoryAgent(dest, skipper, exitOnError = exitOnError)
      else os.proc(
        System.getenv("AGENT_EXECUTABLE"),
        "--ignore-strategy", ignoreStrategy,
        "--working-dir", dest,
        if (exitOnError) Seq("--exit-on-error") else Nil
      ).spawn(cwd = dest),
      Seq(src -> Nil),
      skipper,
      debounceMillis,
      onComplete,
      logger,
      signatureMapping
    )
  }

  def validate(src: os.Path, dest: os.Path, skipper: Skipper) = {
    println("Validating...")
    val srcPaths = os.walk.attrs(src, (p, attrs) => skipper.initialize(src)(p, attrs.isDir))
    val destPaths = os.walk.attrs(dest, (p, attrs) => skipper.initialize(dest)(p, attrs.isDir))

    val srcRelPaths = srcPaths.map(_._1.relativeTo(src)).toSet
    val destRelPaths = destPaths.map(_._1.relativeTo(dest)).toSet

    if (srcRelPaths != destRelPaths){
      throw new Exception(
        "Path list difference, src: " + (srcRelPaths -- destRelPaths) + ", dest: " + (destRelPaths -- srcRelPaths)
      )
    }
    val buffer = new Array[Byte](Util.blockSize)

    val differentSigs = srcPaths.zip(destPaths).flatMap{ case ((s, sAttrs), (d, dAttrs)) =>
      val srcSig = Signature.compute(s, buffer, sAttrs.fileType)
      val destSig = Signature.compute(d, buffer, dAttrs.fileType)

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
