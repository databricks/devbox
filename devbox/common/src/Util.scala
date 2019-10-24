package devbox.common

object Util {
  val blockSize = 4 * 1024 * 1024

  def gzip(bytes: Array[Byte]): Array[Byte] = {
    val boas = new java.io.ByteArrayOutputStream
    val gzipped = new java.util.zip.GZIPOutputStream(boas)
    gzipped.write(bytes)
    gzipped.close()
    boas.toByteArray
  }
  def gunzip(bytes: Array[Byte]): Array[Byte] = {
    val bais = new java.io.ByteArrayInputStream(bytes)
    val gunzipped = new java.util.zip.GZIPInputStream(bais)
    val baos = new java.io.ByteArrayOutputStream
    os.Internals.transfer(gunzipped, baos)
    baos.toByteArray
  }
  implicit val permsetRw: upickle.default.ReadWriter[os.PermSet] =
    upickle.default.readwriter[String].bimap[os.PermSet](
      _.toString(),
      os.PermSet.fromString
    )
  implicit val relpathRw: upickle.default.ReadWriter[os.RelPath] =
    upickle.default.readwriter[String].bimap[os.RelPath](
      _.toString(),
      os.RelPath(_)
    )

  def autoclose[T <: AutoCloseable, V](x: T)(f: T => V) = {
    try f(x)
    finally x.close()
  }

}
