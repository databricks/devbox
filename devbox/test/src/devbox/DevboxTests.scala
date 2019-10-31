package devbox
import java.io.{DataInputStream, DataOutputStream, PipedInputStream, PipedOutputStream}
import java.util.concurrent.{Executors, Semaphore}

import devbox.common._
import logger.SyncLogger
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import os.Path
import syncer.{ReliableAgent, Syncer}

import collection.JavaConverters._
import utest._
import utest.framework.TestPath

import scala.concurrent.ExecutionContext

object DevboxTests extends TestSuite{

  val cases = Map(
    "simple" -> getClass.getResource("/edge-cases.bundle").toURI.toString,
    "oslib" -> System.getenv("OSLIB_BUNDLE"),
    "scalatags" -> System.getenv("SCALATAGS_BUNDLE"),
    "mill" -> System.getenv("MILL_BUNDLE"),
    "ammonite" -> System.getenv("AMMONITE_BUNDLE")
  )

  def tests = Tests {
    // A few example repositories to walk through and make sure the delta syncer
    // can function on every change of commit. Ordered by increasing levels of
    // complexity
    test("simple") {
      test - testCase(1)
      test("git") - testCase(1, ignoreStrategy = "")
      test("restart") - testCase(1, restartInterval = Some(1))
      test("reconnect") - testCase(1, randomKillInterval = Some(50))
    }

    test("oslib") {
      test - testCase(2)
      test("git") - testCase(2, ignoreStrategy = "")
      test("restart") - testCase(2, restartInterval = Some(4))
      test("reconnect") - testCase(2, randomKillInterval = Some(200))
    }

    test("scalatags") {
      test - testCase(3)
      test("git") - testCase(3, ignoreStrategy = "")
      test("restart") - testCase(3, restartInterval = Some(16))
      //      test("reconnect") testCase(3, randomKillInterval = Some(800))
    }
    test("mill") {
      test - testCase(4)
      test("git") - testCase(4, ignoreStrategy = "")
      test("restart") - testCase(4, restartInterval = Some(64))
      //      test("reconnect") testCase(4, randomKillInterval = Some(3200))
    }
    test("parallel") {
      test("simple") {
        test - testCase(1)
        test("git") - testCase(1, ignoreStrategy = "")
        test("restart") - testCase(1, restartInterval = Some(1))
        test("reconnect") - testCase(1, randomKillInterval = Some(50))
      }

      test("oslib") {
        test - testCase(2)
        test("git") - testCase(2, ignoreStrategy = "")
        test("restart") - testCase(2, restartInterval = Some(4))
        test("reconnect") - testCase(2, randomKillInterval = Some(200))
      }

      test("scalatags") {
        test - testCase(3)
        test("git") - testCase(3, ignoreStrategy = "")
        test("restart") - testCase(3, restartInterval = Some(16))
        //      test("reconnect") testCase(3, randomKillInterval = Some(800))
      }
      test("mill") {
        test - testCase(4)
        test("git") - testCase(4, ignoreStrategy = "")
        test("restart") - testCase(4, restartInterval = Some(64))
        //      'reconnect - testCase(4, randomKillInterval = Some(3200))
      }
    }
  }


  def testCase(validateInterval: Int,
               ignoreStrategy: String = "dotgit",
               restartInterval: Option[Int] = None,
               randomKillInterval: Option[Int] = None,
               parallel: Boolean = true)
              (implicit tp: TestPath) = {
    walkValidate(
      tp.value.mkString("-"),
      cases(tp.value.dropWhile(_ == "parallel").head),
      validateInterval,
      0,
      ignoreStrategy = ignoreStrategy,
      restartInterval = restartInterval,
      randomKillInterval = randomKillInterval,
      parallel = parallel
    )
  }
  def walkValidate(label: String,
                   uri: String,
                   validateInterval: Int,
                   initialCommit: Int,
                   commitIndicesToCheck0: Seq[Int] = Nil,
                   ignoreStrategy: String = "dotgit",
                   restartInterval: Option[Int] = None,
                   randomKillInterval: Option[Int] = None,
                   parallel: Boolean = true) = {

    val (src, dest, log, commits, commitsIndicesToCheck, repo) =
      initializeWalk(label, uri, validateInterval, commitIndicesToCheck0)

    val s"$logFileName.$logFileExt" = log.last
    val logFileBase = log / os.up

    def createSyncer() = {
      implicit val ac = new ActorContext.Test(
        if (parallel) ExecutionContext.global
        else ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()),
        _.printStackTrace()
      )
      val logger = new SyncLogger.Impl(
        n => logFileBase / s"$logFileName$n.$logFileExt",
        5 * 1024 * 1024,
        truncate = false
      )

      (logger, ac, instantiateSyncer(
        src, dest, 50,
        logger, ignoreStrategy,
        exitOnError = true,
        signatureMapping = (_, sig) => sig,
        randomKill = randomKillInterval
      ))
    }
    var (logger, ac, syncer) = createSyncer()
    try {
      printBanner(initialCommit, commits.length, 0, commitsIndicesToCheck.length, commits(initialCommit))
      syncer.start()


      for ((i, count) <- commitsIndicesToCheck.zipWithIndex) {
        val commit = commits(i)
        printBanner(i, commits.length, count + 1, commitsIndicesToCheck.length, commit)
        logger("TEST CHECKOUT", commit.getShortMessage)
        repo.checkout().setName(commit.getName).call()

        // Make sure we wait a moment so the filesystem has time to notice the
        // changes and put the events back into our ActorSystem. Otherwise if we
        // waitForInactivity too early, we may stop waiting too early as the
        // system is inactive since the filesystem events haven't occurred yet

        logger("TEST CHECKOUT DONE", commit.getShortMessage)
        Thread.sleep(50)
        if (syncer == null) {
          logger("TEST RESTART SYNCER")
          val (newLogger, newAc, newSyncer) = createSyncer()
          logger = newLogger
          syncer = newSyncer
          ac = newAc
          syncer.start()
        }


        ac.waitForInactivity()
        logger("TEST INACTIVE")
        if (restartInterval.exists(count % _ == 0)) {
          logger("TEST STOP SYNCER")
          syncer.close()
          syncer = null
          ac.waitForInactivity()
        }

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (count % validateInterval == 0) {
          logger("TEST VALIDATE")
          validate(src, dest, ignoreStrategy)
        }
      }
    }catch{case e: Throwable =>
      e.printStackTrace()
      throw e
    }finally{
      if (syncer != null) {
        syncer.close()
        syncer = null
        ac.waitForInactivity()
      }
    }
  }

  def initializeWalk(label: String,
                     uri: String,
                     stride: Int,
                     commitIndicesToCheck0: Seq[Int]) = {
    val (src, dest, log) = prepareFolders(label)
    val repo = Git.cloneRepository()
      .setURI(uri)
      .setDirectory(src.toIO)
      .call()

    val commits = repo.log().call().asScala.toSeq.reverse

    repo.checkout().setName(commits.head.getName).call()


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

    (src, dest, log, commits, commitsIndicesToCheck, repo)
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

  def printBanner(commitIndex: Int,
                  commitCount: Int,
                  trialIndex: Int,
                  trialCount: Int,
                  commit: RevCommit) = {
    println("=" * 80)
    println(
      s"[$commitIndex/$commitCount $trialIndex/$trialCount] "+
      s"Checking ${commit.getName.take(8)} ${commit.getShortMessage}"
    )
  }

  def instantiateSyncer(src: os.Path,
                        dest: os.Path,
                        debounceMillis: Int,
                        logger: SyncLogger,
                        ignoreStrategy: String,
                        exitOnError: Boolean,
                        signatureMapping: (os.SubPath, Sig) => Sig,
                        healthCheckInterval: Int = 0,
                        randomKill: Option[Int] = None)
                       (implicit ac: ActorContext) = {

    val syncer = new Syncer(
      new ReliableAgent(
        Nil,
        Seq(
          System.getenv("AGENT_EXECUTABLE"),
          "--ignore-strategy", ignoreStrategy,
          "--working-dir", dest.toString,
          "--log-path", (dest / os.up / "agent-log.txt").toString(),
        ) ++
        (if (exitOnError) Seq("--exit-on-error") else Nil) ++
        (randomKill match{
          case Some(n) => Seq("--random-kill", n.toString)
          case None => Nil
        }),
        dest
      ),
      Seq(src -> os.rel),
      ignoreStrategy,
      debounceMillis,
      logger,
      signatureMapping
    )
    syncer
  }

  def walkNonSkipped(base: os.Path, ignoreStrategy: String) = {
    val skip = Skipper.fromString(ignoreStrategy)
    val rawPaths = os
      .walk
      .attrs(base)
      .map{case (p, attrs) => (p.subRelativeTo(base), attrs.isDir)}
      .toSet

    skip.processBatch(base, rawPaths)
  }

  def computeSig(p: os.Path, buffer: Array[Byte]) = {
    Sig.compute(p, buffer, os.stat(p, followLinks = false).fileType)
  }
  def validate(src: os.Path, dest: os.Path, ignoreStrategy: String) = {
    println("Validating...")


    val srcPaths = walkNonSkipped(src, ignoreStrategy)
    val destPaths = walkNonSkipped(dest, ignoreStrategy)

    if (srcPaths != destPaths){
      throw new Exception(
        "Path list difference, src: " + (srcPaths -- destPaths) + ", dest: " + (destPaths -- srcPaths)
      )
    }
    val buffer = new Array[Byte](Util.blockSize)

    val differentSigs = srcPaths.toSeq.sorted.zip(destPaths.toSeq.sorted).flatMap{ case (s, d) =>
      val srcSig = computeSig(src / s, buffer)
      val destSig = computeSig(dest / d, buffer)

      if(srcSig == destSig) None
      else Some((s, srcSig, destSig))
    }

    if (differentSigs.nonEmpty){
      throw new Exception(
        "Signature list difference " + differentSigs
      )
    }
  }

}
