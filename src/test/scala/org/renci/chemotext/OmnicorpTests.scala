package org.renci.chemotext

import java.io.File
import java.nio.file.Files

import org.apache.jena.rdf.model.{ModelFactory, Resource}
import org.apache.jena.vocabulary.RDF
import utest._

import sys.process._
import scala.util.matching.Regex
import collection.JavaConverters._

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

  // Regex for matching the final result line in STDERR.
  val finalResultRegex: Regex =
    "Took \\d+ seconds (.*) to create approx [\\d,]+ triples from [\\d,]+ articles in .*".r

  val tests: Tests = Tests {
    test("Make sure we can run Omnicorp and see runtime information") {
      val (status, stdout, _) = exec(Seq("sbt", "run"))
      assert(status == 1)
      assert(stdout contains "Omnicorp requires four arguments:")
      assert(stdout contains "Nonzero exit code returned from runner: 2")
    }

    test("On input file examplesForTests.xml") {
      val examplesForTests = getClass.getResource("/pubmedXML/examplesForTests.xml").getPath
      val tmpFolder        = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we can execute Omnicorp on the example file") {
        val (status, stdout, _) =
          exec(Seq("sbt", s"""run none "$examplesForTests" "$tmpFolder" 1"""))

        // Clean up temporary folder.
        val outputFile = new File(tmpFolder, "examplesForTests.xml.ttl")
        outputFile.delete()
        tmpFolder.delete()

        // Test output and errors.
        assert(status == 0)
        assert(stdout contains "Total time:")
        assert(stdout contains "completed")

        assert(stdout contains "Begin processing")
        assert(!finalResultRegex.findFirstIn(stdout).isEmpty)
      }
    }

    test("On incorrect input file failedExamples1.xml") {
      val failedExamples1 = getClass.getResource("/pubmedXML/failedExamples1.xml").getPath
      val tmpFolder       = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we get a warning message on executing Omnicorp on this example file") {
        val (status, stdout, _) =
          exec(Seq("sbt", s"""run none "$failedExamples1" "$tmpFolder" 1"""))

        // Clean up temporary folder.
        val outputFile = new File(tmpFolder, "failedExamples1.xml.ttl")
        outputFile.delete()
        tmpFolder.delete()

        // Test output and errors.
        assert(status == 0)
        assert(stdout contains "Total time:")
        assert(stdout contains "completed")

        assert(stdout contains "Begin processing")
        assert(stdout contains "WARN org.renci.chemotext.PubMedTripleGenerator")
        assert(
          stdout contains "Unable to parse date http://purl.org/dc/terms/issued on https://www.ncbi.nlm.nih.gov/pubmed/10542500.1: Could not parse XML node as date: <PubDate><MedlineDate>Dec-Jan</MedlineDate></PubDate>"
        )
        assert(!finalResultRegex.findFirstIn(stdout).isEmpty)
      }
    }

    test("On input file alternateVersions.xml containing alternate versions") {
      val failedExamples1 = getClass.getResource("/pubmedXML/alternateVersions.xml").getPath
      val tmpFolder       = Files.createTempDirectory("omnicorp-testing").toFile

      test("Make sure we have all four versions") {
        val (status, stdout, _) =
          exec(Seq("sbt", s"""run none "$failedExamples1" "$tmpFolder" 1"""))

        // Test output and errors.
        assert(status == 0)
        assert(stdout contains "Total time:")
        assert(stdout contains "completed")

        assert(stdout contains "Begin processing")
        assert(stdout contains "INFO org.renci.chemotext.Main$")
        assert(
          stdout matches """.*Took \d+ seconds \([\w\.]+\) to create approx \d+ triples from \d+ articles.*"""
        )

        // Load temporary file and make sure we have all four versions.
        val outputFile = new File(tmpFolder, "alternateVersions.xml.ttl")
        val model      = ModelFactory.createDefaultModel()
        model.read(outputFile.toURI.toString)

        val articleClass = model.createResource("http://purl.org/spar/fabio/Article");
        val articles: Seq[Resource] =
          model.listResourcesWithProperty(RDF.`type`, articleClass).toList.asScala
        assert(articles.size == 4)
        assert(
          articles.map(_.getURI).toSet == Set(
            "https://www.ncbi.nlm.nih.gov/pubmed/31431825.1",
            "https://www.ncbi.nlm.nih.gov/pubmed/31431825.2",
            "https://www.ncbi.nlm.nih.gov/pubmed/31431825.3",
            "https://www.ncbi.nlm.nih.gov/pubmed/31431825.4"
          )
        )

        // Clean up temporary folder.
        outputFile.delete()
        tmpFolder.delete()
      }
    }
  }
}
