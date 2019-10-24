package devbox

import utest._

object GitIgnoreTests extends TestSuite {
  val tests = Tests{
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
  }
}
