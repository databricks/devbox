package devbox.agent

import devbox.common.{BaseLogger, Logger}

class AgentLogger(val dest: String => os.Path, val rotationSize: Long)
                 (implicit ac: castor.Context)
extends castor.SimpleActor[Logger.PPrinted] with BaseLogger{

  def apply(tag: String, x: Any = Logger.NoOp): Unit = this.send(Logger.PPrinted(tag, x))

  def run(msg: Logger.PPrinted): Unit = {
    assert(msg.tag.length <= Logger.margin)

    val msgIterator =
      Iterator(msg.tag.padTo(Logger.margin, ' '), " | ") ++
      pprint.tokenize(msg.value, height = Int.MaxValue).map(_.plainText)

    for(chunk <- msgIterator) write(chunk.replace("\n", Logger.marginStr))
    write("\n")
  }
}
