package devbox

import devbox.common.{PathMap, PathSet, Skipper}
import utest._

object GitIgnoreTests extends TestSuite {
  class Checker(base: os.Path) {
    val skip = new Skipper.GitIgnore()
    val existingFiles =
      for((p, attrs) <- os.walk.attrs(base))
      yield (p.subRelativeTo(base).segments, attrs.isDir)

    skip.processBatch(base, PathMap.from(existingFiles))

    def apply(p: os.SubPath, isDir: Boolean): Boolean = {
      skip.processBatch(base, PathMap(p.segments -> isDir)).size != 0
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
    }
    def checkIgnore(gitIgnoreLine: String, gitIgnorePrefix: String, path: String) = {
      val skip = new Skipper.GitIgnore()
      val base = os.temp.dir()
      val gitIgnorePath = os.SubPath(gitIgnorePrefix) / ".gitignore"
      os.write(base / gitIgnorePath, gitIgnoreLine, createFolders = true)
      skip.processBatch(base, PathMap(gitIgnorePath.segments -> false))

      skip.processBatch(base, PathMap(os.SubPath(path).segments -> (path.last == '/'))).size == 0
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
