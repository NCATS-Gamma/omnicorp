package org.renci.chemotext

import java.io.{File, FileInputStream, FileOutputStream, StringReader}
import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.{TemporalAccessor, ChronoField}
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
import org.apache.jena.rdf.model.{ModelFactory, Property, Resource, ResourceFactory, Statement}
import org.apache.jena.riot.Lang
import org.apache.jena.riot.system.{StreamRDFOps, StreamRDFWriter}
import org.apache.jena.vocabulary.{DCTerms, RDF}
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.lucene.queryparser.classic.QueryParserBase
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.prefixcommons.CurieUtil

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, NodeSeq}

/** Author name management. */
class AuthorWrapper(node: Node) {
  val isSpellingCorrect = ((node \ "ValidYN").text == "Y")
  val collectiveName    = (node \ "CollectiveName").text
  val lastName          = (node \ "LastName").text
  val foreName          = (node \ "ForeName").text
  val suffix            = (node \ "Suffix").text
  val initials          = (node \ "Initials").text
  // TODO: add support for <AffiliationInfo>
  // TODO: add support for <EqualContrib>

  // Support for identifiers.
  val identifier          = (node \ "Identifier").map(id => (id.attribute("Source") -> id.text))
  val orcIds: Seq[String] = identifier.filter(_._1 == "ORCID").map(_._2)

  // FOAF uses foaf:givenName and foaf:familyName.
  val givenName: String = foreName
  val familyName: String = {
    if (suffix.isEmpty) lastName else s"$lastName $suffix"
  }
  val name: String = if (!collectiveName.isEmpty) collectiveName else s"$givenName $familyName"
}

object AuthorWrapper {
  val ET_AL: AuthorWrapper = new AuthorWrapper(<Author><LastName>et al.</LastName></Author>)
}

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
  val journalNodes: NodeSeq        = (article \\ "Article" \ "Journal")
  val journalVolume: String        = (journalNodes \ "JournalIssue" \ "Volume").map(_.text).mkString(", ")
  val journalIssue: String         = (journalNodes \ "JournalIssue" \ "Issue").map(_.text).mkString(", ")
  val journalTitle: String         = (journalNodes \ "Title").map(_.text).mkString(", ")
  val journalAbbr: String          = (journalNodes \ "ISOAbbreviation").map(_.text).mkString(", ")
  val journalISSNNodes: NodeSeq    = (journalNodes \ "ISSN")
  val pubDatesAsNodes: NodeSeq     = article \\ "PubDate"
  val articleDatesAsNodes: NodeSeq = article \\ "ArticleDate"
  val revisedDatesAsNodes: NodeSeq = article \\ "DateRevised"
  val medlinePgnNodes: NodeSeq     = article \\ "Pagination" \ "MedlinePgn"
  val medlinePagination: String    = medlinePgnNodes.map(_.text).mkString(", ")
  val pubDatesParseResults: Seq[Try[TemporalAccessor]] =
    pubDatesAsNodes map PubMedArticleWrapper.parseDate
  val articleDatesParseResults: Seq[Try[TemporalAccessor]] =
    articleDatesAsNodes map PubMedArticleWrapper.parseDate
  val revisedDatesParseResults: Seq[Try[TemporalAccessor]] =
    revisedDatesAsNodes map PubMedArticleWrapper.parseDate
  val pubDates: Seq[TemporalAccessor]     = pubDatesParseResults.map(_.toOption).flatten
  val pubDateYears: Seq[Int]              = pubDates.map(_.get(ChronoField.YEAR))
  val articleDates: Seq[TemporalAccessor] = articleDatesParseResults.map(_.toOption).flatten
  val revisedDates: Seq[TemporalAccessor] = revisedDatesParseResults.map(_.toOption).flatten

  // Extract journal metadata.
  val articleIdInfo: Map[String, Seq[String]] = (article \\ "ArticleIdList" \ "ArticleId")
    .groupBy(                      // Group on the basis of attribute name, by
      _.attribute("IdType")        // getting all values for attributes named "IdType";
        .getOrElse(Seq("unknown")) // if none are present, default to "unknown", but
        .mkString(" & ")           // if there are multiple, combine them with ' & '.
    )
    .mapValues(_.map(_.text))
  val dois: Seq[String] = articleIdInfo.getOrElse("doi", Seq())
  val authors: Seq[AuthorWrapper] = {
    val authorListNodes = (article \\ "AuthorList")
    if (authorListNodes.isEmpty) Seq() else {
      val authorListNode  = authorListNodes.head
      val authorList      = authorListNode.nonEmptyChildren.map(new AuthorWrapper(_))
      if (authorListNode.attribute("CompleteYN") == "N")
        (authorList :+ AuthorWrapper.ET_AL)
      else authorList
    }
  }

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
  // Some namespaces.
  val MESHNamespace       = "http://id.nlm.nih.gov/mesh"
  val PRISMBasicNamespace = "http://prismstandard.org/namespaces/basic/3.0"
  val FOAFNamespace       = "http://xmlns.com/foaf/0.1"
  val FaBiONamespace      = "http://purl.org/spar/fabio"
  val FRBRNamespace       = "http://purl.org/vocab/frbr/core"

  // Extract dates as RDF statements.
  def convertDatesToTriples(pmidIRI: Resource, property: Property)(
    date: Try[TemporalAccessor]
  ): Statement = {
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

  /** Generate the triples for a particular PubMed article. */
  def generateTriples(
    pubMedArticleWrapped: PubMedArticleWrapper,
    optAnnotator: Option[Annotator]
  ): Set[graph.Triple] = {
    // Generate an IRI for this PubMed article.
    val pmidIRI = ResourceFactory.createResource(pubMedArticleWrapped.iriAsString)

    // Add publication metadata.
    val publicationMetadata = Seq(
      DCTerms.title                                               -> Seq(pubMedArticleWrapped.title),
      ResourceFactory.createProperty(s"$PRISMBasicNamespace/doi") -> pubMedArticleWrapped.dois
    )
    val publicationMetadataStatements = publicationMetadata
      .filter(!_._2.isEmpty)
      .map({
        case (prop, value) =>
          ResourceFactory.createStatement(
            pmidIRI,
            prop,
            ResourceFactory.createTypedLiteral(value.mkString(", "), XSDDatatype.XSDstring)
          )
      }) ++ Seq(
      // <pmidIRI> a fabio:Article
      ResourceFactory.createStatement(
        pmidIRI,
        RDF.`type`,
        ResourceFactory.createResource(s"$FaBiONamespace/Article")
      )
    ) ++ (
      // <pmidIRI> fabio:hasPublicationYear "2019"^xsd:gYear
      pubMedArticleWrapped.pubDateYears.map(
        year =>
          ResourceFactory.createStatement(
            pmidIRI,
            ResourceFactory.createProperty(s"$FaBiONamespace/hasPublicationYear"),
            ResourceFactory.createTypedLiteral(year.toString, XSDDatatype.XSDgYear)
          )
      )
    ) ++ ({
      // <pmidIRI> prism:pageRange "(start page)-(end page), (start page)-(end page)"
      // <pmidIRI> prism:startingPage "start page"^^xsd:string
      // <pmidIRI> prism:startingPage "end page"^^xsd:string

      // If there is exactly one page range, we'll try to split it into a
      // start page and an end page.
      val pageRangeRegex = raw"(.+?)\s*-\s*(.+)".r
      val startEndPageStatements = pubMedArticleWrapped.medlinePgnNodes.map(_.text) match {
        case Seq(pageRangeRegex(startPage, endPage)) => Seq(
          ResourceFactory.createStatement(
            pmidIRI,
            ResourceFactory.createProperty(s"$PRISMBasicNamespace/startingPage"),
            ResourceFactory.createStringLiteral(startPage)
          ),
          ResourceFactory.createStatement(
            pmidIRI,
            ResourceFactory.createProperty(s"$PRISMBasicNamespace/endingPage"),
            ResourceFactory.createStringLiteral(endPage)
          )
        )
        case _ => Seq()
      }

      // Add the overall page range as well.
      startEndPageStatements :+ ResourceFactory.createStatement(
        pmidIRI,
        ResourceFactory.createProperty(s"$PRISMBasicNamespace/pageRange"),
        ResourceFactory.createStringLiteral(pubMedArticleWrapped.medlinePagination)
      )
    }) ++ ({
      // <pmidIRI> frbr:partOf {
      //    a fabio:JournalIssue
      //    prism:issueIdentifier "issue number"
      //    frbr:partOf {
      //      a fabio:JournalIssue
      //      prism:volume "volume number"
      //      frbr:partOf {
      //        a fabio:Journal
      //        fabio:hasNLMJournalTitleAbbreviation "Journal Abbreviation"
      //        dcterms:title "Journal Title"
      //        prism:issn "ISSN"
      //        prism:eIssn "Electronic ISSN"
      //      }
      //    }
      //  }
      val journalModel = ModelFactory.createDefaultModel
      val journalResource = journalModel.createResource(
        ResourceFactory.createResource(s"$FaBiONamespace/Journal")
      )
        .addProperty(DCTerms.title, pubMedArticleWrapped.journalTitle)
        .addProperty(
          ResourceFactory.createProperty(s"$FaBiONamespace/hasNLMJournalTitleAbbreviation"),
          pubMedArticleWrapped.journalAbbr
        )

      pubMedArticleWrapped.journalISSNNodes.foreach(issnNode => {
        val prismPropName = issnNode.attribute("IssnType").map(_.text) match {
          case Some("Electronic") => "eIssn"
          case Some("Print")      => "issn"
          case _                       => "issn"
        }

        journalResource.addProperty(
          ResourceFactory.createProperty(s"$PRISMBasicNamespace/$prismPropName"),
          issnNode.text
        )
      })

      val volumeResource = journalModel.createResource(
        ResourceFactory.createResource(s"$FaBiONamespace/JournalVolume")
      )
        .addProperty(
          ResourceFactory.createProperty(s"$PRISMBasicNamespace/volume"),
          pubMedArticleWrapped.journalVolume
        )
        .addProperty(
          ResourceFactory.createProperty(s"$FRBRNamespace#partOf"),
          journalResource
        )

      val issueResource = journalModel.createResource(
        ResourceFactory.createResource(s"$FaBiONamespace/JournalIssue")
      )
        .addProperty(
          ResourceFactory.createProperty(s"$PRISMBasicNamespace/issueIdentifier"),
          pubMedArticleWrapped.journalIssue
        )
        .addProperty(
          ResourceFactory.createProperty(s"$FRBRNamespace#partOf"),
          volumeResource
        )

      journalModel.add(ResourceFactory.createStatement(
        pmidIRI,
        ResourceFactory.createProperty(s"$FRBRNamespace#partOf"),
        issueResource
      ))

      // Convert the Journal Model into a Scala sequence of RDF statements.
      journalModel.listStatements.toList.asScala
    })

    val authorModel = ModelFactory.createDefaultModel
    val authorResources = pubMedArticleWrapped.authors.map({ author =>
      authorModel
        .createResource(FOAF.Agent)
        .addProperty(
          ResourceFactory.createProperty(s"$FOAFNamespace/familyName"),
          ResourceFactory.createTypedLiteral(author.familyName, XSDDatatype.XSDstring)
        )
        .addProperty(
          ResourceFactory.createProperty(s"$FOAFNamespace/givenName"),
          ResourceFactory.createTypedLiteral(author.givenName, XSDDatatype.XSDstring)
        )
    })
    val authorRDFList = authorModel.createList(authorResources.iterator.asJava)
    val authorStatements = authorModel.listStatements.toList.asScala :+ ResourceFactory
      .createStatement(pmidIRI, DCTerms.creator, authorRDFList)

    // Build a string citation from the provided information.
    val authorString = pubMedArticleWrapped.authors.map(_.name).mkString(", ")
    val titleString = pubMedArticleWrapped.title
    val journalTitle = pubMedArticleWrapped.journalTitle
    val pubYear = pubMedArticleWrapped.pubDateYears.mkString(", ")
    val journalVolume = pubMedArticleWrapped.journalVolume
    val journalIssue = pubMedArticleWrapped.journalIssue
    val journalPages = pubMedArticleWrapped.medlinePagination
    val pmid = pubMedArticleWrapped.pmid
    val citationString = s"$authorString. $titleString. $journalTitle ($pubYear);$journalVolume($journalIssue):$journalPages. PubMed PMID: $pmid"

    val metadataStatements = publicationMetadataStatements ++
      authorStatements ++
      (pubMedArticleWrapped.pubDatesParseResults map convertDatesToTriples(pmidIRI, DCTerms.issued)) ++
      (pubMedArticleWrapped.revisedDatesParseResults map convertDatesToTriples(
        pmidIRI,
        DCTerms.modified
      )) :+ ResourceFactory.createStatement(
        pmidIRI,
        DCTerms.bibliographicCitation,
        ResourceFactory.createStringLiteral(citationString)
      )

    // Extract meshIRIs as RDF statements.
    val meshIRIs = pubMedArticleWrapped.allMeshTermIDs map (
      id => ResourceFactory.createResource(s"$MESHNamespace/$id")
    )
    val meshStatements = meshIRIs.map { meshIRI =>
      ResourceFactory.createStatement(pmidIRI, DCTerms.references, meshIRI)
    }

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
    val allStatements = metadataStatements.toSet ++ annotationStatements.toSet ++ meshStatements ++ annotationStatements
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
        StreamRDFOps.sendTriplesToStream(triples.iterator.asJava, rdfStream)
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
