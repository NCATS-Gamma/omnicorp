package org.renci.chemotext

import java.io.{ByteArrayOutputStream, StringReader, StringWriter, File}
import java.nio.file.Files
import java.time.{LocalDate, Year, YearMonth}

import org.apache.jena.graph
import org.apache.jena.rdf.model.{Property, ResourceFactory, Statement, ModelFactory}
import org.apache.jena.riot.{RDFWriter, RDFFormat}
import utest._

import collection.mutable
import collection.JavaConverters._
import scala.xml.XML

import sys.process._

/**
  * Tests for the entire Omnicorp application.
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.NonUnitStatements" // We use non-unit statements to delete files.
  )
)
object OmnicorpTests extends TestSuite {
  def exec(args: Seq[String]): (Int, String, String) = {
    val stdout = new StringBuilder
    val stderr = new StringBuilder

    val status = args ! ProcessLogger(stdout append _, stderr append _)
    (status, stdout.toString, stderr.toString)
  }

  val tests = Tests {
    test("Make sure we can run Omnicorp and see runtime information") {
      val (status, stdout, stderr) = exec(Seq("sbt", "run"))
      assert(status == 1)
      assert(stdout contains "Omnicorp requires four arguments:")
      assert(stdout contains "Nonzero exit code: 2")
      assert(stderr contains "sbt/TrapExitSecurityException")
    }

    test("On input file examplesForTests.xml") {
      val examplesForTests = getClass.getResource("/pubmedXML/examplesForTests.xml").getPath
      val tmpFolder = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we can execute Omnicorp on the example file") {
        val cmdline = Seq(
          "sbt",
          s"""run none "$examplesForTests" "$tmpFolder" 1"""
        )
        val (status, stdout, stderr) = exec(cmdline)

        // Clean up temporary folder.
        new File(tmpFolder, "examplesForTests.xml.ttl").delete()
        tmpFolder.delete()

        // Test output and errors.
        assert(status == 0)
        assert(stdout contains "Total time:")
        assert(stdout contains "completed")

        assert(stderr contains "Begin processing")
        assert(stderr contains "Done processing")
      }
    }

    test("On incorrect input file failedExamples1.xml") {
      val failedExamples1 = getClass.getResource("/pubmedXML/failedExamples1.xml").getPath
      val tmpFolder = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we get an error message on executing Omnicorp on this example file") {
        val cmdline = Seq(
          "sbt",
          s"""run none "$failedExamples1" "$tmpFolder" 1"""
        )
        val (status, stdout, stderr) = exec(cmdline)

        // Clean up temporary folder.
        new File(tmpFolder, "failedExamples1.xml.ttl").delete()
        tmpFolder.delete()

        // Test output and errors.
        assert(status == 1)
        assert(stdout contains "Total time:")
        assert(stdout contains "completed")
        assert(stdout contains "Nonzero exit code: 1")

        assert(stderr contains "Begin processing")
        assert(stderr contains "Done processing")
      }
    }
  }
}
