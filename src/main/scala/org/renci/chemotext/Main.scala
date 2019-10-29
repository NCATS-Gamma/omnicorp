package org.renci.chemotext

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.xml.Elem

import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.rdf.model.Statement
import org.apache.jena.riot.Lang
import org.apache.jena.riot.system.StreamOps
import org.apache.jena.riot.system.StreamRDFWriter
import org.apache.jena.vocabulary.DCTerms
import org.apache.lucene.queryparser.classic.QueryParserBase

import com.typesafe.scalalogging.LazyLogging

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import io.scigraph.annotation.EntityAnnotation
import io.scigraph.annotation.EntityFormatConfiguration
import org.apache.jena.datatypes.xsd.XSDDatatype

import scala.collection.{SortedSet, immutable}

case class ArticleInfo(pmid:String,info:String,meshTermIDs:Set[String],years:Set[String])

object TextExtractor {
  def extractArticleInfos(articleSet: Elem): List[ArticleInfo] = {
    for {
      article <- (articleSet \ "PubmedArticle").toList
      pmidEl <- article \ "MedlineCitation" \ "PMID"
      pmid = pmidEl.text
      title = (article \\ "ArticleTitle").map(_.text).mkString(" ")
      dates = (article \\ "PubDate")
      years = dates.map(pub => (pub \ "Year")).filter(_.nonEmpty).map(_.text).toSet
      abstractText = (article \\ "AbstractText").map(_.text).mkString(" ")
      geneSymbols = (article \\ "GeneSymbol").map(_.text).mkString(" ")
      (meshTermIDs, meshLabels) = (article \\ "MeshHeading").map { mh =>
        val (dMeshIds, dMeshLabels) = (mh \ "DescriptorName").map(mesh => ((mesh \ "@UI").text, mesh.text)).unzip
        val (qMeshIds, qMeshLabels) = (mh \ "QualifierName").map(mesh => ((mesh \ "@UI").text, mesh.text)).unzip
        ((dMeshIds ++ qMeshIds), (dMeshLabels ++ qMeshLabels).mkString(" "))
      }.unzip
      (meshSubstanceIDs, meshSubstanceLabels) = (article \\ "NameOfSubstance").map(substance => ((substance \ "@UI").text, substance.text)).unzip
      allMeshTermIDs = meshTermIDs.flatten.toSet ++ meshSubstanceIDs
      allMeshLabels = meshLabels.toSet ++ meshSubstanceLabels
    } yield ArticleInfo(pmid, s"$title $abstractText ${allMeshLabels.mkString(" ")} $geneSymbols", allMeshTermIDs, years)
  }
}

object Main extends App with LazyLogging {

  val scigraphLocation = args(0)
  val dataDir = new File(args(1))
  val outDir = args(2)
  val parallelism = args(3).toInt

  val annotator = new Annotator(scigraphLocation)

  implicit val system = ActorSystem("pubmed-actors")
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
  val MESHNamespace = "http://id.nlm.nih.gov/mesh"
  val DCTReferences = DCTerms.references
  val DCTIssued = DCTerms.issued
  val CURIE = "^([^:]*):(.*)$".r

  val dataFiles = if (dataDir.isFile) List(dataDir)
  else dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

  def readXMLFromGZip(file: File): Elem = {
    val stream = new GZIPInputStream(new FileInputStream(file))
    val elem = scala.xml.XML.load(stream)
    stream.close()
    elem
  }

  def annotateWithSciGraph(text: String): List[EntityAnnotation] = {
    val configBuilder = new EntityFormatConfiguration.Builder(new StringReader(QueryParserBase.escape(text)))
    configBuilder.longestOnly(true)
    configBuilder.minLength(3)
    annotator.processor.annotateEntities(configBuilder.get).asScala.toList
  }

  def makeTriples(articleInfo: ArticleInfo, annotations: List[EntityAnnotation]): Set[Statement] = {
    val pmidIRI = ResourceFactory.createResource(s"$PMIDNamespace/${articleInfo.pmid}")
    val meshIRIs = articleInfo.meshTermIDs.map(id => ResourceFactory.createResource(s"$MESHNamespace/$id"))
    val statements = annotations.map { annotation =>
      ResourceFactory.createStatement(pmidIRI, DCTReferences, ResourceFactory.createResource(annotation.getToken.getId))
    }
    val meshStatements = meshIRIs.map { meshIRI =>
      ResourceFactory.createStatement(pmidIRI, DCTReferences, meshIRI)
    }
    val dateStatement = articleInfo.years.map { year =>
      ResourceFactory.createStatement(pmidIRI, DCTIssued,
        ResourceFactory.createTypedLiteral(year, XSDDatatype.XSDgYear)
      )
    }
    statements.toSet ++ meshStatements ++ dateStatement
  }

  dataFiles.foreach { file =>
    val articlesWithText = TextExtractor.extractArticleInfos(readXMLFromGZip(file))
    logger.info(s"Begin processing $file")
    logger.info(s"Will process total articles: ${articlesWithText.size}")
    val outStream = new FileOutputStream(new File(s"$outDir/${file.getName}.ttl"))
    val rdfStream = StreamRDFWriter.getWriterStream(outStream, Lang.TURTLE)
    rdfStream.start()
    val done = Source(articlesWithText).mapAsyncUnordered(parallelism) { article =>
      Future {
        makeTriples(article, annotateWithSciGraph(article.info)).map(_.asTriple)
      }
    }.runForeach { triples =>
      StreamOps.sendTriplesToStream(triples.iterator.asJava, rdfStream)
    }
    Await.ready(done, Duration.Inf).onComplete {
      case Failure(e) =>
        e.printStackTrace()
        rdfStream.finish()
        outStream.close()
        system.terminate()
        annotator.dispose()
        System.exit(1)
      case _ => {
        rdfStream.finish()
        outStream.close()
      }
    }
    logger.info(s"Done processing $file")
  }
  annotator.dispose()
  system.terminate()

}
