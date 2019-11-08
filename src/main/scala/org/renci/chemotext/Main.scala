package org.renci.chemotext

import java.io.{File, FileInputStream, FileOutputStream, StringReader}
import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.zip.GZIPInputStream

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import io.scigraph.annotation.{EntityAnnotation, EntityFormatConfiguration}
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.system.{StreamOps, StreamRDFWriter}
import org.apache.jena.vocabulary.DCTerms
import org.apache.lucene.queryparser.classic.QueryParserBase

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Try}
import scala.xml.{Elem, Node, NodeSeq}

/** An object containing code for working with PubMed articles. */
object PubMedArticleWrapper {
  /** Convert dates in PubMed articles into TemporalAccessors wrapping those dates. */
  def parseDates(dates: NodeSeq): Seq[TemporalAccessor] = dates.map(date => {
    // Extract the Year/Month/Day fields. Note that the month requires additional
    // processing, since it may be a month name ("Apr") or a number ("4").

    def parseMedlineDate(node: Node) = {
      // No year? That's probably because we have a MedlineDate instead.
      // MedlineDates have different forms (e.g. "1989 Dec-1999 Jan", "2000 Spring", "2000 Dec 23-30").
      // For now, we check to see if it starts with four digits, suggesting an year.
      // See https://www.nlm.nih.gov/bsd/licensee/elements_descriptions.html#medlinedate for more details.
      val medlineDateYearMatcher = """^\s*(\d{4})\b.*$""".r
      val medlineDate = (date \\ "MedlineDate").text
      medlineDate match {
        case medlineDateYearMatcher(year) => Year.of(year.toInt)
        case _                            => throw new RuntimeException("Date entry is missing either a 'Year' or a parsable 'MedlineDate', cannot process: " + date)
      }
    }

    // Parse the year and day-of-year, if possible.
    val maybeYear: Try[Int] = Try((date \\ "Year").text.toInt)
    val maybeDayOfMonth: Try[Int] = Try((date \\ "Day").text.toInt)

    maybeYear.map { year =>
      // We can't parse a month-only date: to parse it, we need to include the year as well.
      val monthStr = (date \\ "Month").text
      val maybeMonth: Try[Int] = Try(YearMonth.parse(s"$year-$monthStr", DateTimeFormatter.ofPattern("uuuu-MMM")).getMonth.getValue)

      maybeMonth.map { month =>
        maybeDayOfMonth.map { day =>
          LocalDate.of(year, month, day)
        }.getOrElse(YearMonth.of(year, month))
      }.getOrElse(Year.of(year))
    }.getOrElse(parseMedlineDate(date))
  })
}

/** A companion class for wrapping a PubMed article from an XML dump. */
class PubMedArticleWrapper(val article: Node) {
  // The following methods extract particular fields from the wrapped PubMed article.
  val pmid: String = (article \ "MedlineCitation" \ "PMID").text
  val title: String = (article \\ "ArticleTitle").map(_.text).mkString(" ")
  val abstractText: String = (article \\ "AbstractText").map(_.text).mkString(" ")
  val pubDatesAsNodes: NodeSeq = article \\ "PubDate"
  val articleDatesAsNodes: NodeSeq = article \\ "ArticleDate"
  val pubDates: Seq[TemporalAccessor] = PubMedArticleWrapper.parseDates(pubDatesAsNodes)
  val articleDates: Seq[TemporalAccessor] = PubMedArticleWrapper.parseDates(articleDatesAsNodes)

  // Extract gene symbols and MeSH headings.
  val geneSymbols: String = (article \\ "GeneSymbol").map(_.text).mkString(" ")
  val (meshTermIDs, meshLabels) = (article \\ "MeshHeading").map { mh =>
    val (dMeshIds, dMeshLabels) = (mh \ "DescriptorName").map(mesh => ((mesh \ "@UI").text, mesh.text)).unzip
    val (qMeshIds, qMeshLabels) = (mh \ "QualifierName").map(mesh => ((mesh \ "@UI").text, mesh.text)).unzip
    (dMeshIds ++ qMeshIds, (dMeshLabels ++ qMeshLabels).mkString(" "))
  }.unzip
  val (meshSubstanceIDs, meshSubstanceLabels) = (article \\ "NameOfSubstance").map(substance => ((substance \ "@UI").text, substance.text)).unzip
  val allMeshTermIDs: Set[String] = meshTermIDs.flatten.toSet ++ meshSubstanceIDs
  val allMeshLabels: Set[String] = meshLabels.toSet ++ meshSubstanceLabels

  // Represent this PubMedArticleWrapper as a string containing all the useful information.
  val asString: String = s"$title $abstractText ${allMeshLabels.mkString(" ")} $geneSymbols"

  // Display properties of this PubMedArticleWrapper for debugging.
  override val toString: String = s"PMID ${pmid} (${pubDates}): ${asString} (MeSH: ${allMeshTermIDs})"

  // Generate an IRI for this PubMedArticleWrapper.
  val iriAsString: String = {
    val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
    s"$PMIDNamespace/$pmid"
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

  /** Read a GZipped XML file and returns the root element. */
  def readXMLFromGZip(file: File): Elem = {
    val stream = new GZIPInputStream(new FileInputStream(file))
    val elem = scala.xml.XML.load(stream)
    stream.close()
    elem
  }

  /** Extract annotations from a particular string using a particular Annotator. */
  def extractAnnotations(str: String): List[EntityAnnotation] = {
    val configBuilder = new EntityFormatConfiguration.Builder(new StringReader(QueryParserBase.escape(str)))
    configBuilder.longestOnly(true)
    configBuilder.minLength(3)
    annotator.processor.annotateEntities(configBuilder.get).asScala.toList
  }

  /** Generate the triples for a particular PubMed article. */
  def generateTriples(pubMedArticleWrapped: PubMedArticleWrapper): Set[graph.Triple] = {
    // Generate an IRI for this PubMed article.
    val pmidIRI = ResourceFactory.createResource(pubMedArticleWrapped.iriAsString)

    // Extract meshIRIs as RDF statements.
    val MESHNamespace = "http://id.nlm.nih.gov/mesh"
    val meshIRIs = pubMedArticleWrapped.allMeshTermIDs.map(id => ResourceFactory.createResource(s"$MESHNamespace/$id"))
    val meshStatements = meshIRIs.map { meshIRI =>
      ResourceFactory.createStatement(pmidIRI, DCTerms.references, meshIRI)
    }

    // Extract dates as RDF statements.
    val dateStatements = pubMedArticleWrapped.pubDates.map { date: TemporalAccessor =>
      ResourceFactory.createStatement(pmidIRI, DCTerms.issued,
        date match {
          case localDate: LocalDate => ResourceFactory.createTypedLiteral(localDate.toString, XSDDatatype.XSDdate)
          case yearMonth: YearMonth => ResourceFactory.createTypedLiteral(yearMonth.toString, XSDDatatype.XSDgYearMonth)
          case year: Year           => ResourceFactory.createTypedLiteral(year.toString, XSDDatatype.XSDgYear)
        }
      )
    }

    // Extract annotations using SciGraph and convert into RDF statements.
    val annotationStatements = extractAnnotations(pubMedArticleWrapped.asString).map { annotation =>
      ResourceFactory.createStatement(pmidIRI, DCTerms.references, ResourceFactory.createResource(annotation.getToken.getId))
    }

    // Combine all statements into a single set and export as triples.
    val allStatements = annotationStatements.toSet ++ meshStatements ++ dateStatements
    allStatements.map(_.asTriple)
  }

  dataFiles.foreach { file =>
    // Load all articles and wrap them with PubMedArticleWrappers.
    val rootElement = readXMLFromGZip(file)
    val wrappedArticles = (rootElement \ "PubmedArticle").map(new PubMedArticleWrapper(_))

    logger.info(s"Begin processing $file")
    logger.info(s"Will process total articles: ${wrappedArticles.size}")

    // Prepare to write out triples in RDF/Turtle.
    val outStream = new FileOutputStream(new File(s"$outDir/${file.getName}.ttl"))
    val rdfStream = StreamRDFWriter.getWriterStream(outStream, Lang.TURTLE)
    rdfStream.start()

    // Generate triples for all wrapped PubMed articles.
    val done = Source(wrappedArticles)
      .mapAsyncUnordered(parallelism) { article: PubMedArticleWrapper =>
        Future {
          generateTriples(article)
        }
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
      case _          =>
        rdfStream.finish()
        outStream.close()
    }
    logger.info(s"Done processing $file")
  }
  annotator.dispose()
  system.terminate()
}
