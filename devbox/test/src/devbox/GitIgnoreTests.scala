package devbox

import devbox.common.{PathMap, PathSet, Skipper}
import utest._

object GitIgnoreTests extends TestSuite {
  class Checker(base: os.Path) {
    val skip = new Skipper.GitIgnore()
    val existingFiles =
      for((p, attrs) <- os.walk.attrs(base))
      yield (p.subRelativeTo(base).segments, attrs.isDir)

    skip.batchRemoveSkippedPaths(base, PathMap.from(existingFiles))

    def apply(p: os.SubPath, isDir: Boolean): Boolean = {
      val res1 = skip.batchRemoveSkippedPaths(base, PathMap(p.segments -> isDir))
      val res2 = skip.initialScanIsPathSkipped(base, p, isDir)
      assert((res1.size == 0) == res2)
      res2
    }
  }

  val tests = Tests{
    'skipper - {
      val base = os.temp.dir()
      'root - {
        os.write(base / ".gitignore", "out")
        os.write(base / "out" / "lols", "lols", createFolders = true)
        val check = new Checker(base)
        check(os.sub / ".gitignore", false) ==> false
        check(os.sub / "out" / "lols", false) ==> true


        check(os.sub / "out", true) ==> true
        check(os.sub / "out" / "lols", false) ==> true
        check(os.sub / "lols", true) ==> false
        check(os.sub / "lols", false) ==> false
      }
      'nested - {
        os.write(base / "folder" / ".gitignore", "nested", createFolders = true)
        os.write(base / "nested", "nested")
        os.write(base / "folder" / "nested", "nested")
        os.write(base / "folder" / "directory" / "nested", "nested", createFolders = true)

        val check = new Checker(base)
        check(os.sub / "folder", true) ==> false
        check(os.sub / "folder" / "nested", true) ==> true
        check(os.sub / "folder" / "directory" / "nested", true) ==> true
        check(os.sub / "folder" / "directory", true) ==> false
        check(os.sub / "nested", true) ==> false
      }
      'remove - {
        os.write(base / ".gitignore", "out")
        os.write(base / "target" / ".gitignore", "lols", createFolders = true)
        os.write(base / "out" / "lols", "lols", createFolders = true)
        os.write(base / "target" / "lols", "lols", createFolders = true)
        val check = new Checker(base)

        check(os.sub / "out", true) ==> true
        check(os.sub / "out" / "lols", false) ==> true
        check(os.sub / "target", true) ==> false
        check(os.sub / "target" / "lols", false) ==> true

        os.remove(base / ".gitignore")

        check(os.sub / "out", true) ==> false
        check(os.sub / "out" / "lols", false) ==> false
        check(os.sub / "target", true) ==> false
        check(os.sub / "target" / "lols", false) ==> true

        os.remove(base / "target" / ".gitignore")

        check(os.sub / "out", true) ==> false
        check(os.sub / "out" / "lols", false) ==> false
        check(os.sub / "target", true) ==> false
        check(os.sub / "target" / "lols", false) ==> false
      }
    }
    def checkIgnore(gitIgnoreLine: String, gitIgnorePrefix: String, path: String) = {
      val skip = new Skipper.GitIgnore()
      val base = os.temp.dir()
      val gitIgnorePath = os.SubPath(gitIgnorePrefix) / ".gitignore"
      os.write(base / gitIgnorePath, gitIgnoreLine, createFolders = true)
      skip.batchRemoveSkippedPaths(base, PathMap(gitIgnorePath.segments -> false))

      val res1 = skip.batchRemoveSkippedPaths(base, PathMap(os.SubPath(path).segments -> (path.last == '/')))
      val res2 = skip.initialScanIsPathSkipped(base, os.SubPath(path), path.last == '/')
      assert((res1.size == 0) == res2)
      res2
    }

    'simple - {
      'file - {
        checkIgnore("hello", "", "hello") ==> true
        checkIgnore("hello", "", "lol/hello") ==> true
        checkIgnore("hello", "", "hello/lol") ==> true
        checkIgnore("hello", "", "ello") ==> false
        checkIgnore("hello", "", "hell") ==> false
        checkIgnore("hello", "", "hell") ==> false
      }
    }
    'folder - {
      checkIgnore("hello/", "", "hello") ==> false
      checkIgnore("hello/", "", "lol/hello") ==> false
      checkIgnore("hello/", "", "lol/hello/") ==> true
      checkIgnore("hello/", "", "hello/lol") ==> true
      checkIgnore("hello/", "", "ello") ==> false
      checkIgnore("hello/", "", "hell") ==> false
      checkIgnore("hello/", "", "hell") ==> false
    }
    'wildcard - {
      'trailing - {
        checkIgnore("hello*", "", "hello") ==> true
        checkIgnore("hello*", "", "helloworld") ==> true
        checkIgnore("hello*", "", "worldhello") ==> false
      }
      'leading - {
        checkIgnore("*hello", "", "hello") ==> true
        checkIgnore("*hello", "", "worldhello") ==> true
        checkIgnore("*hello", "", "helloworld") ==> false
      }
      'both - {
        checkIgnore("*hello*", "", "hello") ==> true
        checkIgnore("*hello*", "", "worldhello") ==> true
        checkIgnore("*hello*", "", "helloworld") ==> true
      }
      'middle - {
        checkIgnore("he*llo", "", "hello") ==> true
        checkIgnore("he*llo", "", "worldhello") ==> false
        checkIgnore("he*llo", "", "helloworld") ==> false
        checkIgnore("he*llo", "", "hellollollo") ==> true
      }
      'folder - {
        checkIgnore("hello/*", "", "hello") ==> false
        checkIgnore("hello/*", "", "hello/world") ==> true
        checkIgnore("hello/*", "", "hello/world/cow") ==> true
        checkIgnore("hello/*", "", "moo/world/cow") ==> false

        checkIgnore("*/hello", "", "cow/hello") ==> true
        checkIgnore("*/hello", "", "world/hello") ==> true
        checkIgnore("*/hello", "", "world/cow") ==> false
        checkIgnore("*/hello", "", "hello/world") ==> false
      }
    }
    'prefix - {
      'slashed - {
        checkIgnore("world/cow", "hello", "hello/world/cow") ==> true
        checkIgnore("world/cow", "hello", "world/cow") ==> false
        checkIgnore("world/cow", "hello", "hello/nested/world/cow") ==> false
        checkIgnore("world/cow", "", "hello/nested/world/cow") ==> false
        checkIgnore("world/cow", "", "world/cow/hello/nested") ==> true
      }
      'rooted - {
        checkIgnore("world/", "hello", "hello/world/cow") ==> true
        checkIgnore("world/", "hello", "world/cow") ==> false
        checkIgnore("world/", "hello", "hello/nested/world/cow") ==> true
      }
      'noslash - {
        checkIgnore("world", "hello", "hello/world/cow") ==> true
        checkIgnore("world", "hello", "world/cow") ==> false
        checkIgnore("world", "hello", "hello/nested/world/cow") ==> true
      }
    }
    'root - {
      checkIgnore("/world", "hello", "hello/world/cow") ==> true
      checkIgnore("/world", "hello", "world/cow") ==> false
      checkIgnore("/world", "hello", "hello/nested/world/cow") ==> false
    }
  }
}
