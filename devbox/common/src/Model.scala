package devbox.common
import upickle.default.{ReadWriter, macroRW}


class Bytes(val value: Array[Byte]){
  override def hashCode() = java.util.Arrays.hashCode(value)
  override def equals(other: Any) = other match{
    case o: Bytes => java.util.Arrays.equals(value, o.value)
    case _ => false
  }

  override def toString = {
    val cutoff = 10
    val hex = upickle.core.Util.bytesToString(value.take(cutoff))
    val dots = if(value.length > cutoff) "..." else ""
    s"Bytes(${value.length}, $hex$dots)"
  }
}

object Bytes{
  implicit val rw: ReadWriter[Bytes] =
    upickle.default.readwriter[Array[Byte]].bimap(_.value, new Bytes(_))
}

case class RpcException(wrapped: RemoteException) extends Exception("", wrapped)
case class RemoteException(clsName: String,
                           msg: String,
                           stack: Seq[StackTraceElement],
                           parent: Option[RemoteException]) extends Exception(
  clsName + ": " + msg,
  parent.orNull
){
  this.setStackTrace(stack.toArray[StackTraceElement])
}
object RemoteException {
  def create(e: Throwable): RemoteException = RemoteException(
    e.getClass.getName,
    e.getMessage,
    e.getStackTrace,
    Option(e.getCause).map(create)
  )

  implicit val stackTraceRW = upickle.default.readwriter[ujson.Obj].bimap[StackTraceElement](
    ste => ujson.Obj(
      "declaringClass" -> ujson.Str(ste.getClassName),
      "methodName" -> ujson.Str(ste.getMethodName),
      "fileName" -> ujson.Str(ste.getFileName),
      "lineNumber" -> ujson.Num(ste.getLineNumber)
    ),
    { case json: ujson.Obj =>
      new StackTraceElement(
        json("declaringClass").str.toString,
        json("methodName").str.toString,
        json("fileName").str.toString,
        json("lineNumber").num.toInt
      )
    }
  )

  implicit val rw: ReadWriter[RemoteException] = macroRW
}