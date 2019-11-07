package org.renci.chemotext

import java.time.Year

import scala.xml.XML
import utest._

object AnnotatorTest extends TestSuite {
  val tests = Tests {
    val examplesForTests = XML.loadFile(getClass.getResource("/pubmedXML/examplesForTests.xml").getPath)
    val pubmedArticles = examplesForTests \ "PubmedArticle"

    test("A basic example") {
      val wrappedArticle = new PubMedArticleWrapper(pubmedArticles(0))

      assert(wrappedArticle.pmid == "368090")
      assert(wrappedArticle.asString == "Mechanical pretreatments and etching of primary-tooth enamel.  Dental Enamel ultrastructure Dental Bonding methods Composite Resins Tooth Abrasion pathology Humans Tooth, Deciduous ultrastructure Adhesiveness Acid Etching, Dental Stress, Mechanical ")
      assert(wrappedArticle.allMeshTermIDs == Set("Q000648", "D013314", "D001840", "D003188", "D006801", "D014072", "D003743", "D014094", "Q000473", "Q000379", "D000268", "D000134"))
      assert(wrappedArticle.pubDates == Seq(Year.of(1979)))
    }

    test("An example with a MedlineDate") {
      val wrappedArticle = new PubMedArticleWrapper(pubmedArticles(1))

      assert(wrappedArticle.pmid == "10542500")
      assert(wrappedArticle.asString == "Thirty years of service.  Parkinson Disease therapy United Kingdom Humans Organizational Objectives Information Services Societies ")
      assert(wrappedArticle.allMeshTermIDs == Set("D012952", "D007255", "D006801", "D010300", "D006113", "Q000628", "D009937"))
      assert(wrappedArticle.pubDates == Seq(Year.of(1998)))
    }
  }
}
