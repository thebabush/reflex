package io.github.thebabush.reflex

import java.io.File
import java.nio.file.Files
import java.util

import dk.brics.automaton.{Automaton, State, Transition}
import io.circe
import io.circe._
import io.circe.syntax._
import scopt.OParser

import scala.jdk.CollectionConverters._
import scala.util.Random


sealed trait Command
case class Json()  extends Command
case class Print() extends Command
case class Proto() extends Command
case class Rust()  extends Command
case class Gen()  extends Command


case class Config(
                 debug:   Boolean   = false,
                 command: Command   = Print(),
                 files:   Seq[File] = Seq(),
                 out:     File      = null,
                 )

class Main
object Main {
  var IS_DEBUGGING = false;

  def parseArgs(args: Array[String]): Option[Config] = {
    val builder = OParser.builder[Config]
    val parser = {
      import builder._
      OParser.sequence(
        programName("jreflex"),
        head("jreflex", "0.0a"),
        opt[Unit]('d', "debug")
          .action((_, c) => c.copy(debug = true))
          .text("enables verbose output on stderr"),
        help("help")
          .text("print this usage text"),
        arg[File]("output")
          .action((x, c) => c.copy(out = x))
          .text("the output file"),
        arg[File]("<file>...")
          .unbounded()
          .action((f, c) => c.copy(files = c.files :+ f))
          .text("the input files"),
        note(""),
        cmd("print")
          .action((_, c) => c.copy(command = Print()))
          .text("prints an (hopefully) human-readable version of the regexp"),
        note(""),
        cmd("json")
          .action((_, c) => c.copy(command = Json()))
          .text("prints a json-encoded version of the regexp"),
        note(""),
        cmd("proto")
          .action((_, c) => c.copy(command = Proto()))
          .text("prints a proto2 definition of the regexp"),
        note(""),
        cmd("rust")
          .action((_, c) => c.copy(command = Rust()))
          .text("prints a rust definition of the regexp"),
        cmd("gen")
          .action((_, c) => c.copy(command = Gen()))
          .text("generate a valid token"),
      )
    }
    OParser.parse(parser, args, Config()) match {
      case Some(config) => if (config.files.nonEmpty) Some(config) else None
      case x => x
    }
  }

  def main(args: Array[String]): Unit = {
    parseArgs(args) match {
      case Some(config) => process(config)
      case _ => ()
    }
  }

  def process(config: Config): Unit = {

    // TODO: learn Scala :P
    implicit val objEncoder: Encoder[Regex] = new Encoder[Regex] {
      final def apply(regex: Regex): circe.Json = circe.Json.obj(
        ("type", regex.getClass.getSimpleName.asJson),
        ("children", regex match {
          case Empty()      => throw new IllegalArgumentException("Empty() in json encoding")
          case Epsilon()    => circe.Json.arr()
          case Literal(v)   => circe.Json.arr(circe.Json.fromString("" + v))
          case OneOrMore(v) => circe.Json.arr(apply(v))
          case Optional(v)  => circe.Json.arr(apply(v))
          case Or(a, b)     => circe.Json.arr(apply(a), apply(b))
          case RESet(xs)    => circe.Json.arr(circe.Json.fromString(xs.toArray.sorted.mkString))
          case Star(v)      => circe.Json.arr(apply(v))
          case Then(a, b)   => circe.Json.arr(apply(a), apply(b))
        })
      )
    }

    IS_DEBUGGING = config.debug;

    config.command match {
      case Print() =>
        val dfa = parse(config.files.head)
        val regexp = DfaToRegex.hopcroft(dfa)
        val out = Pretty.print(regexp)
        if (IS_DEBUGGING) {
          println(out)
        }
        Files.writeString(config.out.toPath, out.toString() + "\n")
      case Json() =>
        val dfa = parse(config.files.head)
        val regexp = DfaToRegex.hopcroft(dfa)
        val out = regexp.asJson(objEncoder).toString()
        if (IS_DEBUGGING) {
          Console.err.println(out)
        }
        Files.writeString(config.out.toPath, out)
      case Proto() =>
        val regexps = config.files.map(f => DfaToRegex.hopcroft(parse(f)))
        val out = (new MakeProto).serialize(regexps)
        if (IS_DEBUGGING) {
          println(out)
        }
        Files.writeString(config.out.toPath, out)
      case Rust() =>
        val regexps = config.files.map(f => DfaToRegex.hopcroft(parse(f)))
//        val regexps = Seq() :+ Then(Literal('A'), RESet("0123456789".toSet))
        val out = (new MakeRust).serialize(regexps)
        if (IS_DEBUGGING) {
          println(out)
        }
        Files.writeString(config.out.toPath, out)
      case Gen() =>
        val regexps = config.files.map(f => DfaToRegex.hopcroft(parse(f)))
        while (true) {
          println(Generator.generate(regexps(Random.nextInt(regexps.length))))
        }
    }
  }

  def parse(file: File): Automaton = {
    // TODO: Ugly, converted from the old java version
    val lines: Iterator[String] = Files.lines(file.toPath).iterator().asScala

    val stateMap: util.Map[String, State] = new util.HashMap[String, State]

    val start: String = lines.next
    val noNodes: Int = lines.next.toInt

    var ctr: Int = 0
    while (ctr < noNodes) {
      val tokens: Array[String] = lines.next.split("\\s+")

      assert(tokens.length == 2)

      val state: String = tokens(0)
      val accepting = tokens(1).toInt > 0

      val s: State = new State
      s.setAccept(accepting)
      stateMap.put(state, s)

      ctr += 1
    }

    val noEdges: Int = lines.next.toInt
    var edge: Int = 0
    while (edge < noEdges) {
      val tokens = lines.next.split("\\s+")
      val beg    = stateMap.get(tokens(0))
      val end    = stateMap.get(tokens(1))
      // Iterate on chars (skip first two as they are the node indices)
      util.Arrays.stream(tokens).skip(2).forEach((ch: String) => {
        val chars: Array[Char] = Character.toChars(ch.toInt)
        assert(chars.length == 1)
        beg.addTransition(new Transition(chars(0), end))
      })

      edge += 1
    }

    val dfa: Automaton = new Automaton
    dfa.setInitialState(stateMap.get(start))
    dfa.minimize()

    if (IS_DEBUGGING) {
      System.err.println("NODES: " + dfa.getNumberOfStates)
    }

    dfa
  }

  def mkTestDfa(): Automaton = {
    val test = new Automaton
    val s1 = new State
    val s2 = new State
    test.setInitialState(s1)
    s2.setAccept(true)
    s1.addTransition(new Transition('1', s1))
    s1.addTransition(new Transition('0', s2))
    s2.addTransition(new Transition('0', s2))
    s2.addTransition(new Transition('1', s2))
    test
  }
}
