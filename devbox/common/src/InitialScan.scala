package devbox.common


object InitialScan {
  def initialSkippedScan(bases: Seq[os.Path], skippers: Seq[Skipper])
                        (f: (os.Path, os.SubPath, os.StatInfo) => Unit): Unit = {
    for((base, skipper) <- bases.zip(skippers)) {

      if (!os.isDir(base)) os.makeDir.all(base)

      skipper.initialScanIsPathSkipped(base, os.sub, true)

      val fileStream = os.walk.stream.attrs(
        base,
        (p, attrs) => skipper.initialScanIsPathSkipped(base, p.subRelativeTo(base), attrs.isDir)
      )

      fileStream.foreach { case (p, attrs) =>
        f(base, p.subRelativeTo(base), attrs)
      }
    }
  }
}
