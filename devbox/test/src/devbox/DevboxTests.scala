package devbox
import java.util.concurrent.Semaphore

import devbox.common.{Logger, Signature, Util}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit

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
    val buffer = new Array[Byte](Signature.blockSize)

    val differentSigs = srcPaths.zip(destPaths).flatMap{ case (s, d) =>
      val srcSig = if (os.exists(s, followLinks = false)) Some(Signature.compute(s, buffer)) else None
      val destSig = if (os.exists(d, followLinks = false)) Some(Signature.compute(d, buffer)) else None

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
    println(s"[$commitIndex/$commitCount $trialIndex/$trialCount] Checking ${commit.getName} ${commit.getShortMessage}")
  }

  def walkValidate(label: String,
                   uri: String,
                   stride: Int,
                   debounceMillis: Int,
                   initialCommit: Int,
                   commitIndicesToCheck0: Seq[Int] = Nil,
                   verbose: Boolean = false) = {
    val src = os.pwd / "out" / "scratch" / label / "src"
    val dest = os.pwd / "out" / "scratch" / label / "dest"
    val log = os.pwd / "out" / "scratch" / label / "events.log"

    os.remove.all(src)
    os.makeDir.all(src)
    os.remove.all(dest)
    os.makeDir.all(dest)
    os.remove.all(log)

    val agentExecutable = System.getenv("AGENT_EXECUTABLE")

    val repo = Git.cloneRepository()
      .setURI(uri)
      .setDirectory(src.toIO)
      .call()

    val commits = repo.log().call().asScala.toSeq.reverse

    repo.checkout().setName(commits.head.getName).call()

    val workCount = new Semaphore(0)

    var lastWriteCount = 0

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

    val sync = Util.ignoreCallback("dotgit")

    devbox.common.Util.autoclose(new Syncer(
      os.proc(agentExecutable, "--ignore-strategy", "dotgit").spawn(cwd = dest),
      Seq(src -> Nil),
      sync,
      debounceMillis,
      () => workCount.release(),
      if (verbose) Logger.Stdout else Logger.File(log)
    )){ syncer =>
      printBanner(initialCommit, commits.length, 0, commitsIndicesToCheck.length, commits(initialCommit))
      syncer.start()
      workCount.acquire()
      println("Write Count: " + (syncer.writeCount - lastWriteCount))
      validate(src, dest, sync)

      lastWriteCount = syncer.writeCount

      for ((i, count) <- commitsIndicesToCheck.zipWithIndex) {
        val commit = commits(i)
        printBanner(i, commits.length, count+1, commitsIndicesToCheck.length, commit)
        repo.checkout().setName(commit.getName).call()
        println("Checkout finished")

        workCount.acquire()
        println("Write Count: " + (syncer.writeCount - lastWriteCount))

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (count % stride == 0) validate(src, dest, sync)
        lastWriteCount = syncer.writeCount
      }
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
    def check(stride: Int, debounceMillis: Int)(implicit tp: utest.framework.TestPath) = {
      walkValidate(tp.value.last, cases(tp.value.last), stride, debounceMillis, 0)
    }
    // A few example repositories to walk through and make sure the delta syncer
    // can function on every change of commit. Ordered by increasing levels of
    // complexity
    'edge - check(1, 50)
    'oslib - check(2, 50)
    'scalatags - check(3, 100)
    'mill - check(4, 100)
    'ammonite - check(5, 200)
  }
}
