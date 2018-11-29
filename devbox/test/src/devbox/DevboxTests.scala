package devbox
import devbox.common.{Bytes, Signature}
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
  def check(label: String, uri: String) = {
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

    var lastInterestingFiles = Seq.empty[os.Path]

    for(commit <- commits){
//      println("="*80)
      println("Checking " + commit.getName + " " + commit.getFullMessage)
      repo.checkout().setName(commit.getName).call()
//      println("syncRepo")

      val interestingFiles = os.walk(src, _.segments.contains(".git"))

      Main.syncRepo(agent, src, dest.segments.toSeq, vfs, (lastInterestingFiles ++ interestingFiles).distinct)

      validate(src, dest, _.segments.contains(".git"))

      lastInterestingFiles = interestingFiles
    }
  }
  def tests = Tests{
    'edge - check("edge-cases", getClass.getResource("/edge-cases.bundle").toURI.toString)
    'scalatags - check("scalatags", System.getenv("SCALATAGS_BUNDLE"))
    'oslib - check("oslib", System.getenv("OSLIB_BUNDLE"))
    'mill - check("mill", System.getenv("MILL_BUNDLE"))
    'ammonite - check("ammonite", System.getenv("AMMONITE_BUNDLE"))
  }
}
