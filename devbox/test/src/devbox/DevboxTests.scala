package devbox
import java.util.concurrent.Semaphore

import devbox.common.Signature
import org.eclipse.jgit.api.Git

import collection.JavaConverters._
import utest._

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

    repo.checkout().setName(commits.head.getName).call()

    val workCount = new Semaphore(0)

    val syncer = new Syncer(
      agent,
      Seq(src -> Nil),
      _.segments.contains(".git"),
      100,
      () => workCount.release()
    )

    syncer.start()
    workCount.acquire()
    validate(src, dest, _.segments.contains(".git"))

    try {
      for ((commit, i) <- commits.drop(1).zipWithIndex) {
        println("=" * 80)
        println(s"[$i/${commits.length - 1}] Checking ${commit.getName} ${commit.getFullMessage}")

        repo.checkout().setName(commit.getName).call()

        workCount.acquire()

        // Allow validation not-every-commit, because validation is really slow
        // and hopefully if something gets messed up it'll get caught in a later
        // validation anyway.
        if (i % stride == 0) validate(src, dest, _.segments.contains(".git"))
      }
    }finally{
      println("Closing Syncer")
      syncer.close()
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
