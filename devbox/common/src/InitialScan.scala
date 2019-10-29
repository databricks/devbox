package devbox.common
object InitialScan {
  def initialSkippedScan(scanRoots: Seq[os.Path], skippers: Seq[Skipper])
                        (f: (os.Path, os.Path, Signature, Int, Int) => Unit) = {
    val buffer = new Array[Byte](Util.blockSize)
    for((scanRoot, skipper) <- scanRoots.zip(skippers)) {

      if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

      val fileStream = os.walk.stream.attrs(
        scanRoot,
        (p, attrs) => {
          skipper.process(scanRoot, Set(p.subRelativeTo(scanRoot) -> attrs.isDir)).isEmpty
        }
      )

      val total = fileStream.count()
      for {
        ((p, attrs), i) <- fileStream.zipWithIndex
        sig <- Signature.compute(p, buffer, attrs.fileType)
      } f(scanRoot, p, sig, i, total)
    }
  }
}
