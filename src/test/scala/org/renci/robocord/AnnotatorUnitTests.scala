package org.renci.robocord

import org.renci.robocord.annotator.Annotator

import utest._

/**
  * Unit tests for the RoboCORD Annotator class.
  */
object AnnotatorUnitTests extends TestSuite {
  val tests = Tests {
    test("Annotator") {
      test("#removeStopCharacters") {
        val examples = Map(
          "H1N1 virus infection\\:"    -> "H1N1 virus infection",
          "nephrotic syndrome."        -> "nephrotic syndrome",
          "\\(Figure"                  -> "Figure",
          "\\(C\\),"                   -> "C",
          "pig\\-tailed macaques \\(a" -> "pig-tailed macaques",
          ". And ACE2"                 -> "ACE2",
          "ACE2. (A)"         -> "ACE2",
          "HERC5 (the"-> "HERC5",
          "Herc5 (the"-> "Herc5",
          "(such as HERC5" -> "HERC5"
        )

        examples.foreach({
          case (example, expected) =>
            val obtained = Annotator.removeStopCharacters(example)
            assert(expected == obtained)
        })
      }
    }
  }
}
