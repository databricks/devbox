package devbox.common

import upickle.default.{ReadWriter, readwriter}

object CompressionMode extends Enumeration {
  type CompressionMode = Value
  val gzip, ssh, none = Value

  implicit val rw: ReadWriter[CompressionMode] = readwriter[Int].bimap[CompressionMode](_.id, apply(_))
}


