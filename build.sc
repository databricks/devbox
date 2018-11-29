import mill._, scalalib._

object devbox extends ScalaModule{
  def scalaVersion = "2.12.7"
  def moduleDeps = Seq(common)
  object common extends ScalaModule{
    def scalaVersion = "2.12.7"
    def ivyDeps = Agg(
      ivy"com.lihaoyi::os-lib:0.2.2",
      ivy"com.lihaoyi::upickle:0.7.1",
      ivy"org.eclipse.jgit:org.eclipse.jgit:5.1.3.201810200350-r",
      ivy"io.methvin:directory-watcher:0.9.0"
    )
  }

  object agent extends ScalaModule{
    def scalaVersion = "2.12.7"
    def moduleDeps = Seq(common)
  }
  object test extends Tests{
    def testFrameworks = Seq("utest.runner.Framework")
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.5")
    def forkEnv = Map(
      "AGENT_EXECUTABLE" -> agent.assembly().path.toString
    )
  }
}
