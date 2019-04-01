package io.github.thebabush.reflex

import dk.brics.automaton.{Automaton, State}

import scala.jdk.CollectionConverters._

sealed trait Regex
case class Empty() extends Regex
case class Epsilon() extends Regex
case class Literal(value: Char) extends Regex
case class OneOrMore(child: Regex) extends Regex
case class Optional(child: Regex) extends Regex
case class Or(left: Regex, right: Regex) extends Regex
case class RESet(children: Set[Char]) extends Regex
case class Star(child: Regex) extends Regex
case class Then(left: Regex, right: Regex) extends Regex

class DfaToRegex
object DfaToRegex {

  val MAX_CHARACTER = 256;

  def rangeToString(m: Int, M: Int): String = {
    (m to M).mkString
  }

  def mkThen(list: List[Regex]): Regex = list match {
    case x :: y :: xs   => Then(x, mkThen(y :: xs))
    case x :: xs        => x
    case Nil            => Empty()
  }

  def mkOr(list: List[Regex]): Regex = list match {
    case x :: y :: xs   => Or(x, mkOr(y :: xs))
    case x :: _         => x
    case Nil            => Empty()
  }

  def hopcroft(dfa: Automaton): Regex = {
    val n = dfa.getNumberOfStates + 1
    var out = Array.ofDim[Regex](n, n, n);

    val s2i: Map[State, Int] = dfa.getStates.asScala.zipWithIndex
      .map({ case (v, i) => (v, i + 1) }).toMap

    val start = s2i(dfa.getInitialState)
    val finals = Set.empty ++ dfa.getAcceptStates.asScala.filter(v => v.isAccept)

    def hopcroftBase(): Unit = {
      for (i <- 1 until n) {
        for (j <- 1 until n) {
          if (i == j) {
            out(0)(i)(j) = Epsilon()
          } else {
            out(0)(i)(j) = Empty()
          }
        }
      }

      dfa.getStates.asScala.foreach(beg => {
        beg.getTransitions.asScala.foreach(t => {
          val end: State = t.getDest
          val i = s2i(beg)
          val j = s2i(end)

          val rx = if (t.getMin == t.getMax)
            Literal(t.getMin)
          else
            RESet((t.getMin to t.getMax).toSet)

          out(0)(i)(j) = out(0)(i)(j) match {
            case Empty() => rx
            case x       => Or(x, rx)
          }
          out(0)(i)(j) = Simple.simplify(out(0)(i)(j))
        })
      })
    }
    hopcroftBase()

    def hopcroftInduction(): Unit = {
      for (k <- 1 until n) {
        for (i <- 1 until n) {
          for (j <- 1 until n) {
            val outVal = Or(
              out(k-1)(i)(j),
              mkThen(
                out(k-1)(i)(k)
                :: Star(out(k-1)(k)(k))
                :: out(k-1)(k)(j)
                :: List.empty
              )
            )
            out(k)(i)(j) = Simple.simplify(outVal)
          }
        }
      }
    }
    hopcroftInduction()

    var regexParts: Set[Regex] = Set.empty
    finals.foreach(f => regexParts += out(n-1)(start)(s2i(f)))
    val ret = mkOr(regexParts.toList)

    Simple.simplify(ret)
  }

}
