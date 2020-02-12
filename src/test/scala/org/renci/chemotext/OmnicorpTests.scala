package org.renci.chemotext

import scala.io.Source
import java.io.File
import java.nio.file.Files

import utest._

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

  val tests: Tests = Tests {
    test("Make sure we can run Omnicorp and see runtime information") {
      val (status, stdout, stderr) = exec(Seq("sbt", "run"))
      assert(status == 1)
      assert(stdout contains "Omnicorp requires four arguments:")
      assert(stdout contains "Nonzero exit code: 2")
      assert(stderr contains "TrapExitSecurityException")
    }

    test("On input file examplesForTests.xml") {
      val examplesForTests = getClass.getResource("/pubmedXML/examplesForTests.xml").getPath
      val tmpFolder        = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we can execute Omnicorp on the example file") {
        val (status, stdout, stderr) = exec(Seq("sbt", s"""run none "$examplesForTests" "$tmpFolder" 1"""))

        // Make sure that the output file has triples indicating completion.
        val outputFile = new File(tmpFolder, "examplesForTests.xml.ttl")
        val outputBuffer = Source.fromFile(outputFile)
        val output = outputBuffer.getLines().mkString("\n")
        assert(output contains "")
        outputBuffer.close()

        // Clean up temporary folder.
        outputFile.delete()
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
      val tmpFolder       = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we get an error message on executing Omnicorp on this example file") {
        val (status, stdout, stderr) = exec(Seq("sbt", s"""run none "$failedExamples1" "$tmpFolder" 1"""))

        // Make sure that the output file does not have triples indicating completion.
        val outputFile = new File(tmpFolder, "failedExamples1.xml.ttl")
        val outputBuffer = Source.fromFile(outputFile)
        val output = outputBuffer.getLines().mkString("\n")
        assert(output contains "")
        outputBuffer.close()

        // Clean up temporary folder.
        outputFile.delete()
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
