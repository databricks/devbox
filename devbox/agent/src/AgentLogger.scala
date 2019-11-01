package devbox.agent

import devbox.common.{ActorContext, BaseLogger, Logger, SimpleActor}

class AgentLogger(val dest: String => os.Path, val rotationSize: Long)
                 (implicit ac: ActorContext) extends SimpleActor[Logger.PPrinted] with BaseLogger{

  def truncate = true
  def apply(tag: String, x: Any = Logger.NoOp): Unit = this.send(Logger.PPrinted(tag, x))

  def logOut(s: String) = {
    System.err.println(ujson.write(s))
  }
  def run(msg: Logger.PPrinted): Unit = {
    assert(msg.tag.length <= Logger.margin)

    val msgIterator =
      Iterator(msg.tag.padTo(Logger.margin, ' '), " | ") ++
      pprint.tokenize(msg.value, height = Int.MaxValue).map(_.plainText)

    for(chunk <- msgIterator) write(chunk.replace("\n", Logger.marginStr))
    write("\n")
  }
}