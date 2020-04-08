package devbox.common
import scala.annotation.tailrec


object Cli{
  /**
    * Additional [[scopt.Read]] instance to teach it how to read Ammonite paths
    */
  implicit def pathScoptRead: scopt.Read[os.Path] = scopt.Read.stringRead.map(os.Path(_, os.pwd))


  case class Arg[T, V](name: String,
                       shortName: Option[Char],
                       doc: String,
                       action: (T, V) => T)
                      (implicit val reader: scopt.Read[V]){
    def runAction(t: T, s: String) = action(t, reader.reads(s))
  }

  def showArg(arg: Arg[_, _]) =
    "  " + arg.shortName.fold("")("-" + _ + ", ") + "--" + arg.name

  def formatBlock(args: Seq[Arg[_, _]], leftMargin: Int) = {

    for(arg <- args) yield {
      showArg(arg).padTo(leftMargin, ' ').mkString +
        Predef.augmentString(arg.doc).lines.mkString("\n" + " " * leftMargin)
    }
  }

  def groupArgs[T](flatArgs: List[String],
                   args: Seq[Arg[T, _]],
                   initial: T): Either[String, (T, List[String])] = {

    val argsMap0: Seq[(String, Arg[T, _])] = args
      .flatMap{x => Seq(x.name -> x) ++ x.shortName.map(_.toString -> x)}

    val argsMap = argsMap0.toMap

    @tailrec def rec(keywordTokens: List[String],
                     current: T): Either[String, (T, List[String])] = {
      keywordTokens match{
        case head :: rest if head(0) == '-' =>
          val realName = if(head(1) == '-') head.drop(2) else head.drop(1)

          argsMap.get(realName) match {
            case Some(cliArg) =>
              if (cliArg.reader == scopt.Read.unitRead) {
                rec(rest, cliArg.runAction(current, ""))
              } else rest match{
                case next :: rest2 => rec(rest2, cliArg.runAction(current, next))
                case Nil => Left(s"Expected a value after argument $head")
              }

            case None => Right((current, keywordTokens))
          }

        case _ => Right((current, keywordTokens))

      }
    }
    rec(flatArgs, initial)
  }
  def groupArgs2[T, V](flatArgs: List[String],
                       args1: Seq[Arg[T, _]],
                       initial1: T,
                       args2: Seq[Arg[V, _]],
                       initial2: V): Either[String, (T, V, List[String])] = {

    val argsMap1: Map[String, Arg[T, _]] = args1
      .flatMap{x => Seq(x.name -> x) ++ x.shortName.map(_.toString -> x)}
      .toMap

    val argsMap2: Map[String, Arg[V, _]] = args2
      .flatMap{x => Seq(x.name -> x) ++ x.shortName.map(_.toString -> x)}
      .toMap

    @tailrec def rec(keywordTokens: List[String],
                     current1: T, current2: V): Either[String, (T, V, List[String])] = {
      keywordTokens match{
        case head :: rest if head(0) == '-' =>
          val realName = if(head(1) == '-') head.drop(2) else head.drop(1)

          (argsMap1.get(realName), argsMap2.get(realName)) match {
            case (Some(cliArg), None) =>
              if (cliArg.reader == scopt.Read.unitRead) {
                rec(rest, cliArg.runAction(current1, ""), current2)
              } else rest match{
                case next :: rest2 => rec(rest2, cliArg.runAction(current1, next), current2)
                case Nil => Left(s"Expected a value after argument $head")
              }
            case (None, Some(cliArg)) =>
              if (cliArg.reader == scopt.Read.unitRead) {
                rec(rest, current1, cliArg.runAction(current2, ""))
              } else rest match{
                case next :: rest2 => rec(rest2, current1, cliArg.runAction(current2, next))
                case Nil => Left(s"Expected a value after argument $head")
              }

            case (None, None) => Right((current1, current2, keywordTokens))
            case (Some(a1), Some(a2)) =>
              throw new Exception("Ambiguous argument: " + realName)
          }

        case _ => Right((current1, current2, keywordTokens))

      }
    }
    rec(flatArgs, initial1, initial2)
  }
}