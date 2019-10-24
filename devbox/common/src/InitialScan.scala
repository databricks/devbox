package devbox.common
object InitialScan {


  def initialSkippedScan(scanRoots: Seq[os.Path], skipper: Skipper)
                        (f: (os.Path, os.Path, Signature, Int, Int) => Unit) = {
    val buffer = new Array[Byte](Util.blockSize)
    for(scanRoot <- scanRoots) {
      val skip = skipper.prepare(scanRoot)
      if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

      val fileStream = os.walk.stream.attrs(
        scanRoot,
        (p, attrs) => skip(p.relativeTo(scanRoot), attrs.isDir) && !attrs.isDir
      )

      val total = fileStream.count()
      for {
        ((p, attrs), i) <- fileStream.zipWithIndex
        sig <- Signature.compute(p, buffer, attrs.fileType)
      } f(scanRoot, p, sig, i, total)
    }
  }
}
