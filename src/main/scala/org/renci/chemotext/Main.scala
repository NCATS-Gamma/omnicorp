package org.renci.chemotext

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.Calendar
import java.util.zip.GZIPInputStream

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Try}
import scala.xml.{Elem, Node, NodeSeq}
import org.apache.jena.rdf.model.{Property, ResourceFactory}
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
import org.apache.jena.datatypes.xsd.XSDDateTime
import org.apache.jena.graph

import scala.util.matching.Regex

/** A case class for modeling simple dates, like PubDate and ArticleDate in PubMed. */
case class Date (year: Option[Int], month: Option[Int], day: Option[Int])

/** A class for wrapping a PubMed article from an XML dump. */
class PubMedArticleWrapper(article: Node) {
  val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
  val MESHNamespace = "http://id.nlm.nih.gov/mesh"
  val DCTReferences: Property = DCTerms.references
  val DCTIssued: Property = DCTerms.issued
  val CURIE: Regex = "^([^:]*):(.*)$".r

  def pmid: String = (article \ "MedlineCitation" \ "PMID").text
  def title: String = (article \\ "ArticleTitle").map(_.text).mkString(" ")
  def abstractText: String = (article \\ "AbstractText").map(_.text).mkString(" ")

  def pubDatesAsNodes: NodeSeq = article \\ "PubDate"
  def articleDatesAsNodes: NodeSeq = article \\ "ArticleDate"
  def parseDates(dates: NodeSeq): Seq[Date] = dates.map(date => Date(
    Try((date \\ "Year").text.toInt).toOption,
    Try((date \\ "Month").text.toInt).toOption,
    Try((date \\ "Day").text.toInt).toOption
  ))
  def pubDates: Seq[Date] = parseDates(pubDatesAsNodes)
  def articleDates: Seq[Date] = parseDates(articleDatesAsNodes)

  def geneSymbols: String = (article \\ "GeneSymbol").map(_.text).mkString(" ")
  val (meshTermIDs, meshLabels) = (article \\ "MeshHeading").map { mh =>
    val (dMeshIds, dMeshLabels) = (mh \ "DescriptorName").map(mesh => ((mesh \ "@UI").text, mesh.text)).unzip
    val (qMeshIds, qMeshLabels) = (mh \ "QualifierName").map(mesh => ((mesh \ "@UI").text, mesh.text)).unzip
    (dMeshIds ++ qMeshIds, (dMeshLabels ++ qMeshLabels).mkString(" "))
  }.unzip
  val (meshSubstanceIDs, meshSubstanceLabels) = (article \\ "NameOfSubstance").map(substance => ((substance \ "@UI").text, substance.text)).unzip
  def allMeshTermIDs: Set[String] = meshTermIDs.flatten.toSet ++ meshSubstanceIDs
  def allMeshLabels: Set[String] = meshLabels.toSet ++ meshSubstanceLabels
  def asString: String = s"$title $abstractText ${allMeshLabels.mkString(" ")} $geneSymbols"
  def annotations(annotator: Annotator): List[EntityAnnotation] = {
    val configBuilder = new EntityFormatConfiguration.Builder(new StringReader(QueryParserBase.escape(asString)))
    configBuilder.longestOnly(true)
    configBuilder.minLength(3)
    annotator.processor.annotateEntities(configBuilder.get).asScala.toList
  }
  def triples(annotator: Annotator): Set[graph.Triple] = {
    val pmidIRI = ResourceFactory.createResource(s"$PMIDNamespace/$pmid")
    val meshIRIs = allMeshTermIDs.map(id => ResourceFactory.createResource(s"$MESHNamespace/$id"))
    val statements = annotations(annotator).map { annotation =>
      ResourceFactory.createStatement(pmidIRI, DCTReferences, ResourceFactory.createResource(annotation.getToken.getId))
    }
    val meshStatements = meshIRIs.map { meshIRI =>
      ResourceFactory.createStatement(pmidIRI, DCTReferences, meshIRI)
    }
    val dateStatements = pubDates.map { date =>
      val calendar = Calendar.getInstance()

      if (date.year.isDefined) calendar.set(Calendar.YEAR, date.year.get)
      if (date.month.isDefined) calendar.set(Calendar.MONTH, date.month.get)
      if (date.day.isDefined) calendar.set(Calendar.DAY_OF_MONTH, date.day.get)
      val xsdDateTime = new XSDDateTime(calendar)

      ResourceFactory.createStatement(pmidIRI, DCTIssued,
        ResourceFactory.createTypedLiteral(
          xsdDateTime.toString,
          xsdDateTime.getNarrowedDatatype
        )
      )
    }
    val allStatements = statements.toSet ++ meshStatements ++ dateStatements
    allStatements.map(_.asTriple)
  }
}

object Main extends App with LazyLogging {
  val scigraphLocation = args(0)
  val dataDir = new File(args(1))
  val outDir = args(2)
  val parallelism = args(3).toInt

  val annotator = new Annotator(scigraphLocation)

  implicit val system: ActorSystem = ActorSystem("pubmed-actors")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val dataFiles = if (dataDir.isFile) List(dataDir)
  else dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

  def readXMLFromGZip(file: File): Elem = {
    val stream = new GZIPInputStream(new FileInputStream(file))
    val elem = scala.xml.XML.load(stream)
    stream.close()
    elem
  }

  dataFiles.foreach { file =>
    val rootElement = readXMLFromGZip(file)
    val wrappedArticles = (rootElement \ "PubmedArticle").map(new PubMedArticleWrapper(_))

    logger.info(s"Begin processing $file")
    logger.info(s"Will process total articles: ${wrappedArticles.size}")
    val outStream = new FileOutputStream(new File(s"$outDir/${file.getName}.ttl"))
    val rdfStream = StreamRDFWriter.getWriterStream(outStream, Lang.TURTLE)
    rdfStream.start()
    val done = Source(wrappedArticles)
      .mapAsyncUnordered(parallelism) { article: PubMedArticleWrapper =>
        Future { article.triples(annotator) }
      }
      .runForeach { triples =>
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
      case _ =>
        rdfStream.finish()
        outStream.close()
    }
    logger.info(s"Done processing $file")
  }
  annotator.dispose()
  system.terminate()
}
