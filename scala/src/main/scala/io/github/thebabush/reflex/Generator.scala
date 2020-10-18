package io.github.thebabush.reflex

import scala.util.Random

object Generator {
  val rand: Random = Random

  def generate(r: Regex): String = {
    r match {
      case Empty() => ""
      case Epsilon() => ""
      case Literal(c) => "" + c
      case OneOrMore(cr) => List.fill(1 + rand.nextInt(3))(cr).map(generate).mkString("")
      case Optional(cr) => if (rand.nextBoolean) "" else generate(cr)
      case Or(a, b) => if (rand.nextBoolean) generate(a) else generate(b)
      case RESet(cc) => "" + cc.toSeq(rand.nextInt(cc.size))
      case Star(c) => if (rand.nextBoolean) "" else generate(c)
      case Then(a, b) => generate(a) + generate(b)
    }
  }
}
