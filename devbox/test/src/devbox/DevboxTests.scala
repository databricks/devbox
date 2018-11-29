package devbox
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
        "Path list difference" + ((srcRelPaths -- destRelPaths) ++ (destRelPaths -- srcRelPaths))
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
  def tests = Tests{
    'hello - {
      val src = os.pwd / "out" / "scratch" / "edges" / "src"
      val dest = os.pwd / "out" / "scratch" / "edges" / "dest"
      os.remove.all(src)
      os.makeDir.all(src)
      os.remove.all(dest)
      os.makeDir.all(dest)

      println("SRC: " + src)
      println("DEST: " + dest)
      val agentExecutable = System.getenv("AGENT_EXECUTABLE")

      val repo = Git.cloneRepository()
        .setURI(getClass.getResource("/edge.bundle").toURI.toString)
        .setDirectory(src.toIO)
        .call()

      val commits = repo.log().call().asScala.toSeq.reverse
      val agent = os.proc(agentExecutable).spawn(cwd = dest, stderr = os.Inherit)

      for(commit <- commits){
        println("="*80)
        println("Checking " + commit.getName + " " + commit.getFullMessage)
        repo.checkout().setName(commit.getName).call()
        println("syncRepo")
        Main.syncRepo(agent, src, dest.segments.toSeq, os.walk(src, _.segments.contains(".git")))

        validate(src, dest, _.segments.contains(".git"))
      }
    }
  }
}
