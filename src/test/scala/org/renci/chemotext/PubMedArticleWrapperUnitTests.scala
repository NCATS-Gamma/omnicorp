package org.renci.chemotext

import java.time.{LocalDate, Year, YearMonth}

import utest._

import scala.util.Success

/**
  * Unit tests for the PubMedArticleWrapper class.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.TryPartial",       // We use Try.get() in a
    "org.wartremover.warts.NonUnitStatements" // non-unit statements to test whether parsing fails correctly.
  )
)
object PubMedArticleWrapperUnitTests extends TestSuite {
  val tests = Tests {
    test("#parseDate") {
      test("Test processing of valid dates") {
        val datesTested = Seq(
          (Year.of(2006), <PubDate><Year>2006</Year></PubDate>),
          (YearMonth.of(2006, 10), <PubDate><Year>2006</Year><Month>Oct</Month></PubDate>),
          (
            LocalDate.of(2006, 10, 21),
            <PubDate><Year>2006</Year><Day>21</Day><Month>Oct</Month></PubDate>
          ),
          (Year.of(1998), <PubDate><MedlineDate>1998 Dec-1999 Jan</MedlineDate></PubDate>),
          (
            LocalDate.of(2006, 10, 21),
            <PubDate><Year>2006</Year><Day>21</Day><Month>10</Month></PubDate>
          ),
          (Year.of(2016), <PubDate><MedlineDate>Summer 2016</MedlineDate></PubDate>)
        )

        datesTested.foreach({
          case (expected, xmlNode) =>
            assert(PubMedArticleWrapper.parseDate(xmlNode) == Success(expected))
        })
      }
      test("Test processing of invalid dates") {
        // Trying to parse a date and get a result should fail.
        val inter1 = (intercept[IllegalArgumentException] {
          PubMedArticleWrapper.parseDate(<PubDate><MedlineDate>199 Dec-199 Jan</MedlineDate></PubDate>).get
        })
        assert(inter1.getMessage == "Could not parse MedlineDate: <MedlineDate>199 Dec-199 Jan</MedlineDate>"
        )

        // Empty dates should not work.
        val inter2 = (intercept[IllegalArgumentException] {
          PubMedArticleWrapper.parseDate(<PubDate></PubDate>).get
        })
        assert(inter2.getMessage == "Could not extract year from node: <PubDate></PubDate>")

        // Dates without a month should fail.
        val inter3 = (intercept[RuntimeException] {
          PubMedArticleWrapper.parseDate(<PubDate><Year>2019</Year><Day>12</Day></PubDate>).get
        })
        assert(
          inter3.getMessage == "Could not extract month from node: <PubDate><Year>2019</Year><Day>12</Day></PubDate>"
        )

        // Flattening a list of parsed dates should not cause an error.
        assert(
          Seq(PubMedArticleWrapper.parseDate(<PubDate><MedlineDate>199 Dec-199 Jan</MedlineDate></PubDate>))
            .map(_.toOption)
            .flatten
          == Seq()
        )
      }
    }
  }
}
