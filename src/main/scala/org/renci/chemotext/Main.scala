package org.renci.chemotext

import java.io.{File, FileInputStream, FileOutputStream, StringReader}
import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.HashMap
import java.util.zip.GZIPInputStream

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import io.scigraph.annotation.{
  EntityAnnotation,
  EntityFormatConfiguration,
  EntityProcessorImpl,
  EntityRecognizer
}
import io.scigraph.neo4j.NodeTransformer
import io.scigraph.vocabulary.{Vocabulary, VocabularyNeo4jImpl}
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph
import org.apache.jena.rdf.model.{Property, ResourceFactory, Statement}
import org.apache.jena.riot.Lang
import org.apache.jena.riot.system.{StreamOps, StreamRDFWriter}
import org.apache.jena.vocabulary.DCTerms
import org.apache.lucene.queryparser.classic.QueryParserBase
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.prefixcommons.CurieUtil

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, NodeSeq}

/** An object containing code for working with PubMed articles. */
object PubMedArticleWrapper {
  /** Convert dates in PubMed articles into TemporalAccessors wrapping those dates. */
  def parseDate(date: Node): Try[TemporalAccessor] = {
    // Extract the Year/Month/Day fields. Note that the month requires additional
    // processing, since it may be a month name ("Apr") or a number ("4").

    def parseMedlineDate(node: Node): Try[TemporalAccessor] = {
      // No year? That's probably because we have a MedlineDate instead.
      // MedlineDates have different forms (e.g. "1989 Dec-1999 Jan", "2000 Spring", "2000 Dec 23-30").
      // For now, we check to see if it starts with four digits, suggesting an year.
      // See https://www.nlm.nih.gov/bsd/licensee/elements_descriptions.html#medlinedate for more details.
      val medlineDateYearMatcher = """^\s*(\d{4})\b.*$""".r
      val medlineDate            = (date \\ "MedlineDate").text
      medlineDate match {
        case medlineDateYearMatcher(year) => Success(Year.of(year.toInt))
        case _                            => Failure(new IllegalArgumentException(s"Could not parse XML node as date: $date"))
      }
    }

    // Parse the year and day-of-year, if possible.
    val maybeYear: Try[Int]       = Try((date \\ "Year").text.toInt)
    val maybeDayOfMonth: Try[Int] = Try((date \\ "Day").text.toInt)

    maybeYear map { year =>
      // We can't parse a month-only date: to parse it, we need to include the year as well.
      val monthStr = (date \\ "Month").text
      val maybeMonth: Try[Int] = Try(monthStr.toInt) orElse (Try(
        YearMonth
          .parse(s"$year-$monthStr", DateTimeFormatter.ofPattern("uuuu-MMM"))
          .getMonth
          .getValue
      ))

      maybeMonth map { month =>
        maybeDayOfMonth map { day =>
          Success(LocalDate.of(year, month, day))
        } getOrElse (Success(YearMonth.of(year, month)))
      } getOrElse ({
        // What if we have a maybeYear and a maybeDayOfMonth, but no maybeMonth?
        // That suggests that we didn't read the month correctly!
        if (maybeYear.isSuccess && maybeDayOfMonth.isSuccess && maybeMonth.isFailure)
          Failure(new RuntimeException(s"Could not extract month from node: $date"))
        else Success(Year.of(year))
      })
    } getOrElse (parseMedlineDate(date))
  }
}

/** A companion class for wrapping a PubMed article from an XML dump. */
class PubMedArticleWrapper(val article: Node) {
  // The following methods extract particular fields from the wrapped PubMed article.
  val pmid: String                 = (article \ "MedlineCitation" \ "PMID").text
  val title: String                = (article \\ "ArticleTitle").map(_.text).mkString(" ")
  val abstractText: String         = (article \\ "AbstractText").map(_.text).mkString(" ")
  val pubDatesAsNodes: NodeSeq     = article \\ "PubDate"
  val articleDatesAsNodes: NodeSeq = article \\ "ArticleDate"
  val revisedDatesAsNodes: NodeSeq = article \\ "DateRevised"
  val pubDatesParseResults: Seq[Try[TemporalAccessor]] =
    pubDatesAsNodes map PubMedArticleWrapper.parseDate
  val articleDatesParseResults: Seq[Try[TemporalAccessor]] =
    articleDatesAsNodes map PubMedArticleWrapper.parseDate
  val revisedDatesParseResults: Seq[Try[TemporalAccessor]] =
    revisedDatesAsNodes map PubMedArticleWrapper.parseDate
  val pubDates: Seq[TemporalAccessor]     = pubDatesParseResults.map(_.toOption).flatten
  val articleDates: Seq[TemporalAccessor] = articleDatesParseResults.map(_.toOption).flatten
  val revisedDates: Seq[TemporalAccessor] = revisedDatesParseResults.map(_.toOption).flatten

  // Extract journal metadata.
  val articleInfo: Map[String, Seq[String]] = ???
  val doi: Seq[String] = articleInfo("doi")

  // Extract gene symbols and MeSH headings.
  val geneSymbols: String = (article \\ "GeneSymbol").map(_.text).mkString(" ")
  val (meshTermIDs, meshLabels) = (article \\ "MeshHeading").map { mh =>
    val (dMeshIds, dMeshLabels) =
      (mh \ "DescriptorName")
        .map({ mesh =>
          ((mesh \ "@UI").text, mesh.text)
        })
        .unzip
    val (qMeshIds, qMeshLabels) =
      (mh \ "QualifierName")
        .map({ mesh =>
          ((mesh \ "@UI").text, mesh.text)
        })
        .unzip
    (dMeshIds ++ qMeshIds, (dMeshLabels ++ qMeshLabels).mkString(" "))
  }.unzip
  val (meshSubstanceIDs, meshSubstanceLabels) = (article \\ "NameOfSubstance")
    .map(substance => ((substance \ "@UI").text, substance.text))
    .unzip
  val allMeshTermIDs: Set[String] = meshTermIDs.flatten.toSet ++ meshSubstanceIDs
  val allMeshLabels: Set[String]  = meshLabels.toSet ++ meshSubstanceLabels

  // Represent this PubMedArticleWrapper as a string containing all the useful information.
  val asString: String = s"$title $abstractText ${allMeshLabels.mkString(" ")} $geneSymbols"

  // Display properties of this PubMedArticleWrapper for debugging.
  override val toString: String =
    s"PMID ${pmid} (${pubDates}): ${asString} (MeSH: ${allMeshTermIDs})"

  // Generate an IRI for this PubMedArticleWrapper.
  val iriAsString: String = {
    val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
    s"$PMIDNamespace/$pmid"
  }
}

/** Methods for extracting annotations from text using SciGraph */
class Annotator(neo4jLocation: String) {
  private val curieUtil: CurieUtil         = new CurieUtil(new HashMap())
  private val transformer: NodeTransformer = new NodeTransformer()
  private val graphDB: GraphDatabaseService =
    new GraphDatabaseFactory().newEmbeddedDatabase(new File(neo4jLocation))
  private val vocabulary: Vocabulary =
    new VocabularyNeo4jImpl(graphDB, neo4jLocation, curieUtil, transformer)
  private val recognizer = new EntityRecognizer(vocabulary, curieUtil)

  val processor = new EntityProcessorImpl(recognizer)

  def dispose(): Unit = graphDB.shutdown()

  /** Extract annotations from a particular string using SciGraph. */
  def extractAnnotations(str: String): List[EntityAnnotation] = {
    val configBuilder =
      new EntityFormatConfiguration.Builder(new StringReader(QueryParserBase.escape(str)))
    configBuilder.longestOnly(true)
    configBuilder.minLength(3)
    processor.annotateEntities(configBuilder.get).asScala.toList
  }
}

/** Methods for generating an RDF description of a PubMedArticleWrapper. */
object PubMedTripleGenerator {
  /** Generate the triples for a particular PubMed article. */
  def generateTriples(
    pubMedArticleWrapped: PubMedArticleWrapper,
    optAnnotator: Option[Annotator]
  ): Set[graph.Triple] = {
    // Generate an IRI for this PubMed article.
    val pmidIRI = ResourceFactory.createResource(pubMedArticleWrapped.iriAsString)

    // Extract meshIRIs as RDF statements.
    val MESHNamespace = "http://id.nlm.nih.gov/mesh"
    val meshIRIs = pubMedArticleWrapped.allMeshTermIDs map (
      id => ResourceFactory.createResource(s"$MESHNamespace/$id")
    )
    val meshStatements = meshIRIs.map { meshIRI =>
      ResourceFactory.createStatement(pmidIRI, DCTerms.references, meshIRI)
    }

    // Extract dates as RDF statements.
    def convertDatesToTriples(property: Property)(date: Try[TemporalAccessor]): Statement = {
      ResourceFactory.createStatement(
        pmidIRI,
        property,
        date match {
          case Success(localDate: LocalDate) =>
            ResourceFactory.createTypedLiteral(localDate.toString, XSDDatatype.XSDdate)
          case Success(yearMonth: YearMonth) =>
            ResourceFactory.createTypedLiteral(yearMonth.toString, XSDDatatype.XSDgYearMonth)
          case Success(year: Year) =>
            ResourceFactory.createTypedLiteral(year.toString, XSDDatatype.XSDgYear)
          case Success(ta: TemporalAccessor) =>
            throw new RuntimeException(s"Unexpected temporal accessor found by parsing date: $ta")
          case Failure(error: Throwable) => throw error
        }
      )
    }

    val issuedDateStatements =
      pubMedArticleWrapped.pubDatesParseResults map convertDatesToTriples(DCTerms.issued)
    val modifiedDateStatements =
      pubMedArticleWrapped.revisedDatesParseResults map convertDatesToTriples(DCTerms.modified)

    // Extract annotations using SciGraph and convert into RDF statements.
    val annotationStatements = optAnnotator
      .map(_.extractAnnotations(pubMedArticleWrapped.asString) map { annotation =>
        ResourceFactory.createStatement(
          pmidIRI,
          DCTerms.references,
          ResourceFactory.createResource(annotation.getToken.getId)
        )
      })
      .getOrElse(Seq())

    // Combine all statements into a single set and export as triples.
    val allStatements = annotationStatements.toSet ++ meshStatements ++ issuedDateStatements ++ modifiedDateStatements
    allStatements.map(_.asTriple)
  }
}

object Main extends App with LazyLogging {
  val scigraphLocation = args(0)
  val dataDir          = new File(args(1))
  val outDir           = args(2)
  val parallelism      = args(3).toInt

  val optAnnotator: Option[Annotator] =
    if (scigraphLocation == "none") None else Some(new Annotator(scigraphLocation))

  implicit val system: ActorSystem                  = ActorSystem("pubmed-actors")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: ActorMaterializer      = ActorMaterializer()

  val dataFiles =
    if (dataDir.isFile) List(dataDir)
    else dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

  /** Read a GZipped XML file and returns the root element. */
  def readXMLFromGZip(file: File): Elem = {
    val stream = new GZIPInputStream(new FileInputStream(file))
    val elem   = scala.xml.XML.load(stream)
    stream.close()
    elem
  }

  dataFiles.foreach { file =>
    // Load all articles and wrap them with PubMedArticleWrappers.
    val rootElement     = readXMLFromGZip(file)
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
          PubMedTripleGenerator.generateTriples(article, optAnnotator)
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
        optAnnotator.foreach(_.dispose)
        System.exit(1)
      case _ =>
        rdfStream.finish()
        outStream.close()
    }
    logger.info(s"Done processing $file")
  }
  optAnnotator.foreach(_.dispose)
  system.terminate()
}
