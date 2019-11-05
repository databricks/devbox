import mill._
import mill.define.Ctx
import scalalib._

trait DevboxModule extends ScalaModule{
  def scalaVersion = "2.13.1"
  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.2.0")
  def scalacOptions = Seq(
    "-P:acyclic:force",
  )
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.2.0")
}
object devbox extends DevboxModule{

  def moduleDeps = Seq(common)
  object common extends DevboxModule{
    def scalaVersion = "2.13.1"
    def ivyDeps = Agg(
      ivy"com.lihaoyi::sourcecode:0.1.8",
      ivy"com.lihaoyi::os-lib:0.4.2",
      ivy"com.lihaoyi::os-lib-watch:0.4.2",
      ivy"com.lihaoyi::upickle:0.8.0",
      ivy"com.lihaoyi::cask-actor:0.3.5",
      ivy"com.google.re2j:re2j:1.2",
      ivy"com.lihaoyi::pprint:0.5.6",
      ivy"com.github.scopt::scopt:3.7.1",
      ivy"net.java.dev.jna:jna:5.0.0",
      ivy"org.slf4j:slf4j-simple:1.7.25",
      ivy"org.eclipse.jgit:org.eclipse.jgit:5.5.1.201910021850-r",
    )
  }

  object agent extends DevboxModule{
    def scalaVersion = "2.13.1"
    def moduleDeps = Seq(common)
  }

  object test extends Tests{
    def moduleDeps = super.moduleDeps ++ Seq(agent)
    def bundleRepo(url: String, name: String, hash: String)
                  (implicit ctx: util.Ctx.Dest) = {
      os.proc("git", "clone", url)
        .call(cwd = ctx.dest)

      os.proc("git", "checkout", hash)
        .call(cwd = ctx.dest / name)

      os.proc("git", "bundle", "create", s"$name.bundle", "--all")
        .call(cwd = ctx.dest / name)

      PathRef(ctx.dest / name / s"$name.bundle")
    }

    def scalatagsBundle = T {
      bundleRepo("git@github.com:lihaoyi/scalatags.git", "scalatags", "f66d4216ac0a00e52acae21a336aec24d68c1e97")
    }

    def oslibBundle = T {
      bundleRepo("git@github.com:lihaoyi/os-lib.git", "os-lib", "3806c2e03fd7fa600469d5d82b549fd27ac28e3a")
    }

    def millBundle = T{
      bundleRepo("git@github.com:lihaoyi/mill.git", "mill", "3cc21b24e4c16a934cb76edb2937045b41662f46")
    }

    def testFrameworks = Seq("devbox.UTestFramework")
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest:0.7.1"
    )
    def forkEnv = Map(
      "AGENT_EXECUTABLE" -> agent.assembly().path.toString,
      "SCALATAGS_BUNDLE" -> scalatagsBundle().path.toString,
      "OSLIB_BUNDLE" -> oslibBundle().path.toString,
      "MILL_BUNDLE" -> millBundle().path.toString
    )
  }
}
