package devbox.common

import geny.Generator
import org.eclipse.jgit.ignore.FastIgnoreRule
import os.Path

object GitIgnore{
  def checkGitIgnore(toCheckFor: os.Path, repoDir: os.Path): Boolean = {
    if (!toCheckFor.startsWith(repoDir)) {
      throw new IllegalArgumentException(
        "file must be inside the repositories working directory! " + toCheckFor
      )
    }

    parentsStream(toCheckFor, repoDir).exists { p =>
      val gitignore = p / ".gitignore"
      os.isFile(gitignore) &&
      os.read.lines.stream(gitignore).exists(parseLine(_, p, toCheckFor, repoDir))
    }
  }

  def parentsStream(toCheckFor: os.Path, repoDir: os.Path): os.Generator[os.Path] = {
    new os.Generator[os.Path]{
      def generate(handleItem: Path => Generator.Action) = {
        var currentAction: Generator.Action = Generator.Continue
        var currentPath = toCheckFor
        while(currentAction == Generator.Continue && toCheckFor.startsWith(repoDir)){
          currentAction = handleItem(currentPath)
          currentPath = currentPath / os.up
        }
        currentAction
      }
    }
  }

  def parseLine(line: String, currentDir: os.Path, toCheckFor: os.Path, repoDir: os.Path): Boolean = {
    val rule = new FastIgnoreRule(line)
    rule.isMatch(toCheckFor.relativeTo(repoDir).toString, os.isDir(toCheckFor)) ^ rule.getResult
  }
}