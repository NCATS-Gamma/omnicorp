package org.renci.robocord

import org.renci.robocord.annotator.Annotator

import utest._

/**
  * Unit tests for the RoboCORD Annotator class.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.TryPartial",       // We use Try.get() in a
    "org.wartremover.warts.NonUnitStatements" // non-unit statements to test whether parsing fails correctly.
  )
)
object AnnotatorUnitTests extends TestSuite {
  val tests = Tests {
    test("Annotator") {
      test("#removeStopCharacters") {
        val examples = Map(
          "H1N1 virus infection\\:" -> "H1N1 virus infection",
          "nephrotic syndrome." -> "nephrotic syndrome",
          "\\(Figure" -> "Figure",
          "\\(C\\)," -> "C"
        )

        examples.foreach({ case (example, expected) =>
          val obtained = Annotator.removeStopCharacters(example)
          assert(expected == obtained)
        })
      }
    }
  }
}
