package devbox.common


object InitialScan {
  def initialSkippedScan(scanRoots: Seq[os.Path], skippers: Seq[Skipper])
                        (f: (os.Path, os.SubPath, Sig) => Unit): Unit = {
    val buffer = new Array[Byte](Util.blockSize)
    for((scanRoot, skipper) <- scanRoots.zip(skippers)) {

      if (!os.isDir(scanRoot)) os.makeDir.all(scanRoot)

      skipper.processInitialScanSingle(scanRoot, os.sub, true)

      val fileStream = os.walk.stream.attrs(
        scanRoot,
        (p, attrs) => skipper.processInitialScanSingle(scanRoot, p.subRelativeTo(scanRoot), attrs.isDir)
      )

      fileStream
        .map { case (p, attrs) =>
          try Sig.compute(p, buffer, attrs.fileType).map((scanRoot, p.subRelativeTo(scanRoot), _))
          catch {case e: Throwable => None}
        }
        .flatten
        .foreach { case (p, s, sig) => f(p, s, sig) }
    }
  }
}
