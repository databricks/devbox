package devbox.common

import java.time.ZoneId
import java.time.format.{DateTimeFormatter, FormatStyle}

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
  implicit val subpathRw: upickle.default.ReadWriter[os.SubPath] =
    upickle.default.readwriter[String].bimap[os.SubPath](
      _.toString(),
      os.SubPath(_)
    )

  def autoclose[T <: AutoCloseable, V](x: T)(f: T => V) = {
    try f(x)
    finally x.close()
  }

  val timeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault())

  def formatInt(number: Long) = {
    val numberFormat = java.text.NumberFormat.getNumberInstance(java.util.Locale.US)
    numberFormat.format(number)
  }

  val bytesFormatter = new java.text.DecimalFormat("#,##0.#")
  def readableBytesSize(size: Long): String = {
    if (size <= 0) return "0"
    val units = Array[String]("B", "kB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size) / Math.log10(1024)).toInt
    bytesFormatter.format(size / Math.pow(1024, digitGroups)) + " " + units(digitGroups)
  }


  def joinMaps[K, V](left: Map[K, Set[V]], right: Map[K, Set[V]]) = {
    (left.keySet ++ right.keySet)
      .map{k => (k, left.getOrElse(k, Set()) ++ right.getOrElse(k, Set()))}
      .toMap
  }


  def joinMaps2[K, Z](left: Map[K, PathMap[Z]], right: Map[K, PathMap[Z]]) = {
    (left.keySet ++ right.keySet)
      .map{k =>
        (
          k,
          left.getOrElse(
            k,
            new PathMap[Z]()).withPaths(right.getOrElse(k, new PathMap[Z]()).walkValues()
          )
        )
      }
      .toMap
  }

  def joinMaps3[K](left: Map[K, PathSet], right: Map[K, PathSet]) = {
    (left.keySet ++ right.keySet)
      .map{k =>
        (
          k,
          left.getOrElse(k, new PathSet()).withPaths(right.getOrElse(k, new PathSet()).walk()))
      }
      .toMap
  }
  def sentryCapture(e: Throwable): Unit = {
    io.sentry.Sentry.getContext().addTag("whoami", System.getProperty("user.name"))
    io.sentry.Sentry.capture(e)
  }
}
