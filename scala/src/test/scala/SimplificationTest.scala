import dk.brics.automaton.RegExp
import io.github.thebabush.reflex.{DfaToRegex, Main, Pretty, Simple}
import org.scalatest.FunSuite

class SimplificationTest extends FunSuite {
  test("SimplificationTest") {
    val reconstructed = Pretty.print(DfaToRegex.hopcroft(Main.mkTestDfa())).toString()

    val dfa1 = new RegExp("1*0(0|1)*").toAutomaton(false)
    val dfa2 = new RegExp(reconstructed).toAutomaton(false)

    assert(dfa1.equals(dfa2))
  }
}
