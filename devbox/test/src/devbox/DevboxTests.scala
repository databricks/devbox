package devbox
import java.io.{DataInputStream, DataOutputStream, PipedInputStream, PipedOutputStream}
import java.util.concurrent.{Executors, Semaphore}

import devbox.common._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit
import os.Path

import collection.JavaConverters._
import utest._

import scala.concurrent.ExecutionContext

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
      * - walkValidate("edge", cases("edge"), 1, 0)
      'git - walkValidate("edge-git", cases("edge"), 1, 0, ignoreStrategy = "")
      'restart - walkValidate("edge-restart", cases("edge"), 1, 0, restartSyncerEvery = Some(1))
      'reconnect - walkValidate("edge-reconnect", cases("edge"), 1, 0, randomKill = Some(20))
    }

    'oslib - {
      * - walkValidate("oslib", cases("oslib"), 1, 0)
      'git - walkValidate("oslib-git", cases("oslib"), 1, 0, ignoreStrategy = "")
      'restart - walkValidate("oslib-restart", cases("oslib"), 1, 0, restartSyncerEvery = Some(4))
      'reconnect - walkValidate("oslib", cases("oslib"), 1, 0, randomKill = Some(50))
    }

    'scalatags - {
      * - walkValidate("scalatags", cases("scalatags"), 1, 0)
      'restart - walkValidate("scalatags-restart", cases("scalatags"), 1, 0, restartSyncerEvery = Some(16))
    }
    'mill - {
      * - walkValidate("mill", cases("mill"), 4, 0)
      'restart - walkValidate("mill-restart", cases("mill"), 4, 0, restartSyncerEvery = Some(64))
    }
    'ammonite - {
//      * - walkValidate("ammonite", cases("ammonite"), 5, 200, 0)
//      'reconnect - walkValidate("ammonite-reconnect", cases("ammonite"), 1, 500, 0, randomKillConnection = true)
//      'restart - walkValidate("ammonite-restart", cases("ammonite"), 5, 200, 0, restartSyncer = true)
    }
  }


  def walkValidate(label: String,
                   uri: String,
                   stride: Int,
                   initialCommit: Int,
                   commitIndicesToCheck0: Seq[Int] = Nil,
                   ignoreStrategy: String = "dotgit",
                   restartSyncerEvery: Option[Int] = None,
                   randomKill: Option[Int] = None) = {

    val (src, dest, log, commits, commitsIndicesToCheck, repo) =
      initializeWalk(label, uri, stride, commitIndicesToCheck0)

    val s"$logFileName.$logFileExt" = log.last
    val logFileBase = log / os.up

    def createSyncer() = {
      implicit val ac = new ActorContext.Test(
//        ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()),
        ExecutionContext.global,
        _.printStackTrace()
      )
      val logger = new SyncLogger.Impl(
        n => logFileBase / s"$logFileName-$n.$logFileExt",
        5 * 1024 * 1024,
        truncate = false
      )

      (logger, ac, instantiateSyncer(
        src, dest, 50,
        logger, ignoreStrategy,
        exitOnError = true,
        signatureMapping = (_, sig) => sig,
        randomKill = randomKill
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
        Thread.sleep(100)
        logger("TEST CHECKOUT DONE", commit.getShortMessage)

        if (syncer == null) {
          logger("TEST RESTART SYNCER")
          val (newLogger, newAc, newSyncer) = createSyncer()
          logger = newLogger
          syncer = newSyncer
          ac = newAc
          syncer.start()
        }


        ac.waitForInactivity()

        if (restartSyncerEvery.exists(count % _ == 0)) {
          logger("TEST STOP SYNCER")
          syncer.close()
          syncer = null
          ac.waitForInactivity()
        }

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (count % stride == 0) {
          logger("TEST VALIDATE")
          validate(src, dest, ignoreStrategy)
        }
      }
    }catch{case e =>
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

  def printBanner(commitIndex: Int, commitCount: Int, trialIndex: Int, trialCount: Int, commit: RevCommit) = {
    println("=" * 80)
    println(s"[$commitIndex/$commitCount $trialIndex/$trialCount] Checking ${commit.getName.take(8)} ${commit.getShortMessage}")
  }

  def instantiateSyncer(src: os.Path,
                        dest: os.Path,
                        debounceMillis: Int,
                        logger: SyncLogger,
                        ignoreStrategy: String,
                        exitOnError: Boolean,
                        signatureMapping: (os.SubPath, Signature) => Signature,
                        healthCheckInterval: Int = 0,
                        randomKill: Option[Int] = None)
                       (implicit ac: ActorContext) = {

    val syncer = new Syncer(
      new ReliableAgent(
        Nil,
        Seq(
          System.getenv("AGENT_EXECUTABLE"),
          "--ignore-strategy", ignoreStrategy,
          "--working-dir", dest.toString
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

  def validate(src: os.Path, dest: os.Path, ignoreStrategy: String) = {
    println("Validating...")
    val skipSrc = Skipper.fromString(ignoreStrategy)
    val skipDest = Skipper.fromString(ignoreStrategy)
    val srcRawPaths = os.walk.attrs(src).map{case (p, attrs) => (p.subRelativeTo(src), attrs.isDir)}.toSet
    val destRawPaths = os.walk.attrs(dest).map{case (p, attrs) => (p.subRelativeTo(dest), attrs.isDir)}.toSet
    val srcPaths = skipSrc.process(src, srcRawPaths)
    val destPaths = skipDest.process(dest, destRawPaths)
    pprint.log(srcRawPaths)
    pprint.log(destRawPaths)

    if (srcPaths != destPaths){
      throw new Exception(
        "Path list difference, src: " + (srcPaths -- destPaths) + ", dest: " + (destPaths -- srcPaths)
      )
    }
    val buffer = new Array[Byte](Util.blockSize)

    val differentSigs = srcPaths.zip(destPaths).flatMap{ case (s, d) =>
      val srcSig = Signature.compute(src / s, buffer, os.stat(src / s).fileType)
      val destSig = Signature.compute(dest / d, buffer, os.stat(dest / d).fileType)

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
