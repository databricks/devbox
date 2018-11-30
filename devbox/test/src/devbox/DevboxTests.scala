package devbox
import java.util.concurrent.Semaphore

import devbox.common.Signature
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevCommit

import collection.JavaConverters._
import utest._

object DevboxTests extends TestSuite{
  def validate(src: os.Path, dest: os.Path, skip: os.Path => Boolean) = {
    println("Validating...")
    val srcPaths = os.walk(src, skip)
    val destPaths = os.walk(dest, skip)

    val srcRelPaths = srcPaths.map(_.relativeTo(src)).toSet
    val destRelPaths = destPaths.map(_.relativeTo(dest)).toSet

    if (srcRelPaths != destRelPaths){
      throw new Exception(
        "Path list difference, src: " + (srcRelPaths -- destRelPaths) + ", dest: " + (destRelPaths -- srcRelPaths)
      )
    }
    val buffer = new Array[Byte](Signature.blockSize)

    val differentSigs = srcPaths.zip(destPaths).flatMap{ case (s, d) =>
      val srcSig = Signature.compute(s, buffer)
      val destSig = Signature.compute(d, buffer)

      if(srcSig == destSig) None
      else Some((s.relativeTo(src), srcSig, destSig))
    }

    if (differentSigs.nonEmpty){
      throw new Exception(
        "Signature list difference" + differentSigs
      )
    }
  }

  def printBanner(commitIndex: Int, commitCount: Int, trialIndex: Int, trialCount: Int, commit: RevCommit) = {
    println("=" * 80)
    println(s"[$commitIndex/$commitCount $trialIndex/$trialCount] Checking ${commit.getName} ${commit.getShortMessage}")
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

    val verbose = false
    val commits = repo.log().call().asScala.toSeq.reverse
    val agent = os.proc(agentExecutable, verbose.toString).spawn(cwd = dest, stderr = os.Inherit)
    try{
      repo.checkout().setName(commits.head.getName).call()

      val workCount = new Semaphore(0)

      val syncer = new Syncer(
        agent,
        Seq(src -> Nil),
        _.segments.contains(".git"),
        100,
        () => workCount.release(),
        verbose
      )

      var lastWriteCount = 0

      // Fixed random to make the random jumps deterministic
      val random = new scala.util.Random(31337)

      val commitsIndicesToCheck =
        // Step through the commits in order to test "normal" edits
        (1 until commits.length) ++
        // Also jump between a bunch of random commits to test robustness against
        // huge edits modifying lots of different files
        (0 until 10 * stride).map(_ => random.nextInt(commits.length))


      printBanner(0, commits.length, 0, commitsIndicesToCheck.length, commits(0))
      syncer.start()
      workCount.acquire()
      println("Write Count: " + (syncer.writeCount - lastWriteCount))
      validate(src, dest, _.segments.contains(".git"))

      lastWriteCount = syncer.writeCount

      try {

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
          if (count % stride == 0) validate(src, dest, _.segments.contains(".git"))
          lastWriteCount = syncer.writeCount
        }
      }finally{
        println("Closing Syncer")
        syncer.close()
      }
    }finally{
      agent.destroy()
    }
  }

  def tests = Tests{
    // A few example repositories to walk through and make sure the delta syncer
    // can function on every change of commit. Ordered by increasing levels of
    // complexity
    'edge - check("edge-cases", getClass.getResource("/edge-cases.bundle").toURI.toString, 1)
    'oslib - check("oslib", System.getenv("OSLIB_BUNDLE"), 2)
    'scalatags - check("scalatags", System.getenv("SCALATAGS_BUNDLE"), 3)
    'mill - check("mill", System.getenv("MILL_BUNDLE"), 4)
    'ammonite - check("ammonite", System.getenv("AMMONITE_BUNDLE"), 5)
  }
}
