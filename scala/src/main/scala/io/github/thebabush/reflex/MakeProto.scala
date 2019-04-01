package io.github.thebabush.reflex

class MakeProto {

  private var messages = Seq[String]()
  private val counter = new Counter;

  def serialize(regexes: Seq[Regex]): String = {
    val sb = new StringBuilder

    sb ++= "message RootHelper {\n"
    sb ++= "oneof T {\n"
    regexes.zipWithIndex.foreach((ri) => {
      val (child, i) = ri
      val childName = counter.mkMessage(child)
      serialize(childName, child)
      sb ++= s"${childName} ${childName.toLowerCase} = ${i + 1};"
    })
    sb ++= "};\n"
    sb ++= "}\n"
    sb ++=
      s"""
      |message Root {
      |  repeated RootHelper rh = 1;
      |}
      |""".stripMargin

    messages :+= sb.toString()

    "syntax = \"proto2\";\n\n" + messages.mkString("\n")
  }

  private def serialize(name: String, r: Regex): Unit = {
    var sb = new StringBuilder
    sb ++= s"message ${name} {\n"
    r match {
      case Empty() => throw new IllegalArgumentException("serialize => Empty()")
      case Epsilon() => throw new IllegalArgumentException("serialize => Epsilon()")
      case Literal(v) =>
        sb ++=
          s"""
          |enum Literal {
          |  ${mkLiteral(v)} = ${v.asInstanceOf[Int]};
          |};
          |required Literal literal = 1;
          |""".stripMargin
      case RESet(vv) =>
        sb ++= s"enum Set {\n"
        vv.toSeq.foreach(c => {
          sb ++= s"${mkLiteral(c)} = ${c.asInstanceOf[Int]};\n"
        })
        sb ++= "};\n"
        sb ++= "required Set set = 1;"
      case OneOrMore(child) =>
        val childName = counter.mkMessage(child)
        sb ++=
          s"""
             |required ${childName} one  = 1;
             |required ${childName} more = 2;
             |""".stripMargin
        serialize(childName, child)
      case Optional(child) =>
        var childName = counter.mkMessage(child)
        sb ++= s"optional ${childName} child = 1;\n"
        serialize(childName, child)
      case Or(left, right) =>
        sb ++= "oneof T {\n"
        Seq(left, right).zipWithIndex.foreach((args) => {
          val (child, i) = args
          val childName = counter.mkMessage(child)
          serialize(childName, child)
          sb ++= s"${childName} ${childName.toLowerCase()} = ${i + 1};\n";
        })
        sb ++= "};\n"
      case Star(child) =>
        val childName = counter.mkMessage(child)
        sb ++= s"repeated ${childName} child = 1;\n"
        serialize(childName, child)
      case Then(left, right) =>
        Seq(left, right).zipWithIndex.foreach((args) => {
          val (child, i) = args
          val childName = counter.mkMessage(child)
          serialize(childName, child)
          sb ++= s"required ${childName} ${childName.toLowerCase()} = ${i + 1};\n";
        })
    }
    sb ++= "}\n"

    messages :+= sb.toString()
  }

  private def mkLiteral(ch: Char): String = {
    f"L${ch.asInstanceOf[Int]}%02X"
  }
}
