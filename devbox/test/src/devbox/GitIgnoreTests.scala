package devbox

import utest._

object GitIgnoreTests extends TestSuite {
  val tests = Tests{
    def checkIgnore(gitIgnoreLine: String, gitIgnorePrefix: String, path: String) = {
      val regexString = devbox.common.Util.gitIgnoreLineToRegex(gitIgnoreLine, gitIgnorePrefix)
//      pprint.log(regexString)
      com.google.re2j.Pattern.compile(regexString).matches(path)
    }

    'skipper - {
      val base = os.temp.dir()
      'root - {
        os.write(base / ".gitignore", "out")
        os.write(base / "out" / "lols", "lols", createFolders = true)
        val prep = common.Skipper.GitIgnore.prepare(base)
        prep(os.rel / "out", true) ==> true
        prep(os.rel / "out" / "lols", false) ==> true
        prep(os.rel / "lols", true) ==> false
        prep(os.rel / "lols", false) ==> false
      }
      'nested - {
        os.write(base / "folder" / ".gitignore", "nested", createFolders = true)
        os.write(base / "nested", "nested")
        os.write(base / "folder" / "nested", "nested")
        os.write(base / "folder" / "directory" / "nested", "nested", createFolders = true)

        val prep2 = common.Skipper.GitIgnore.prepare(base)
        prep2(os.rel / "folder", true) ==> false
        prep2(os.rel / "folder" / "nested", true) ==> true
        prep2(os.rel / "folder" / "directory" / "nested", true) ==> true
        prep2(os.rel / "folder" / "directory", true) ==> false
        prep2(os.rel / "nested", true) ==> false
      }
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
      checkIgnore("/world", "hello", "hello/world/cow") ==> false
      checkIgnore("/world", "hello", "world/cow") ==> true
      checkIgnore("/world", "hello", "hello/nested/world/cow") ==> false
    }
  }
}
