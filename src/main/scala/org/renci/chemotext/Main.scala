package org.renci.chemotext

import java.io.{File, FileInputStream, FileOutputStream, StringReader}
import java.time._
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
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.Elem

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
        .longestOnly(true)
        .minLength(3)
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
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
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
        case Seq(pageRangeRegex(startPage, endPage)) =>
          Seq(
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
      val journalResource = journalModel
        .createResource(ResourceFactory.createResource(s"$FaBiONamespace/Journal"))
        .addProperty(DCTerms.title, pubMedArticleWrapped.journalTitle)
        .addProperty(
          ResourceFactory.createProperty(s"$FaBiONamespace/hasNLMJournalTitleAbbreviation"),
          pubMedArticleWrapped.journalAbbr
        )

      pubMedArticleWrapped.journalISSNNodes.foreach(issnNode => {
        val prismPropName = issnNode.attribute("IssnType").map(_.text) match {
          case Some("Electronic") => "eIssn"
          case Some("Print")      => "issn"
          case _                  => "issn"
        }

        journalResource.addProperty(
          ResourceFactory.createProperty(s"$PRISMBasicNamespace/$prismPropName"),
          issnNode.text
        )
      })

      val volumeResource = journalModel
        .createResource(ResourceFactory.createResource(s"$FaBiONamespace/JournalVolume"))
        .addProperty(
          ResourceFactory.createProperty(s"$PRISMBasicNamespace/volume"),
          pubMedArticleWrapped.journalVolume
        )
        .addProperty(ResourceFactory.createProperty(s"$FRBRNamespace#partOf"), journalResource)

      val issueResource =
        if (pubMedArticleWrapped.journalIssue.isEmpty) volumeResource
        else
          journalModel
            .createResource(ResourceFactory.createResource(s"$FaBiONamespace/JournalIssue"))
            .addProperty(
              ResourceFactory.createProperty(s"$PRISMBasicNamespace/issueIdentifier"),
              pubMedArticleWrapped.journalIssue
            )
            .addProperty(ResourceFactory.createProperty(s"$FRBRNamespace#partOf"), volumeResource)

      // Convert the Journal Model into a Scala sequence of RDF statements and add the journal issue.
      journalModel.listStatements.toList.asScala :+ ResourceFactory.createStatement(
        pmidIRI,
        ResourceFactory.createProperty(s"$FRBRNamespace#partOf"),
        issueResource
      )
    })

    val authorModel = ModelFactory.createDefaultModel
    val authorResources = pubMedArticleWrapped.authors.map({ author =>
      // If we have an ORCID for this author, use that to create a URL for this author. Otherwise, leave it as a blank node.
      val authorResource =
        if (author.orcIds.isEmpty) authorModel.createResource(FOAF.Agent)
        else
          authorModel.createResource(
            s"http://orcid.org/${author.orcIds.headOption.getOrElse("")}#person",
            FOAF.Agent
          )

      if (author.collectiveName.isEmpty) {
        authorResource
          .addProperty(
            ResourceFactory.createProperty(s"$FOAFNamespace/name"),
            ResourceFactory.createTypedLiteral(author.name, XSDDatatype.XSDstring)
          )
          .addProperty(
            ResourceFactory.createProperty(s"$FOAFNamespace/familyName"),
            ResourceFactory.createTypedLiteral(author.familyName, XSDDatatype.XSDstring)
          )
          .addProperty(
            ResourceFactory.createProperty(s"$FOAFNamespace/givenName"),
            ResourceFactory.createTypedLiteral(author.givenName, XSDDatatype.XSDstring)
          )
      } else {
        authorResource.addProperty(
          ResourceFactory.createProperty(s"$FOAFNamespace/name"),
          ResourceFactory.createTypedLiteral(author.collectiveName, XSDDatatype.XSDstring)
        )
      }
    })
    val authorRDFList = authorModel.createList(authorResources.iterator.asJava)
    val authorStatements = authorModel.listStatements.toList.asScala :+ ResourceFactory
      .createStatement(pmidIRI, DCTerms.creator, authorRDFList)

    // Build a string citation from the provided information.
    val authorString = pubMedArticleWrapped.authors.map(_.shortName).mkString(", ")
    val titleString = pubMedArticleWrapped.title + (if (pubMedArticleWrapped.title.endsWith(".")) ""
                                                    else ".")
    val journalTitle  = pubMedArticleWrapped.journalTitle
    val pubYear       = pubMedArticleWrapped.pubDateYears.mkString(", ")
    val journalVolume = pubMedArticleWrapped.journalVolume
    val journalIssue =
      if (pubMedArticleWrapped.journalIssue.isEmpty) ""
      else s"(${pubMedArticleWrapped.journalIssue})"
    val journalPages = pubMedArticleWrapped.medlinePagination
    val pmid         = pubMedArticleWrapped.pmid
    val citationString =
      s"$authorString. $titleString $journalTitle ($pubYear);$journalVolume$journalIssue:$journalPages. PubMed PMID: $pmid"

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
  if (args.length != 4) {
    println("Omnicorp requires four arguments:")
    println("\t - 1. The path to a Scigraph installation")
    println(
      "\t - 2. The path to either a single PubMed XML export or to a directory containing PubMed XML export files"
    )
    println("\t - 3. The output directory where resulting files should be written")
    println("\t - 4. The number of parallel processes to run")
    System.exit(2) // According to https://stackoverflow.com/a/40484670/27310, this is the correct error code for a command line usage error.
  }

  val scigraphLocation: String = args(0)
  val dataDir                  = new File(args(1))
  val outDir: String           = args(2)
  val parallelism: Int         = args(3).toInt

  val optAnnotator: Option[Annotator] =
    if (scigraphLocation == "none") None else Some(new Annotator(scigraphLocation))

  implicit val system: ActorSystem                  = ActorSystem("pubmed-actors")
  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: ActorMaterializer      = ActorMaterializer()

  val dataFiles: List[File] =
    if (dataDir.isFile) List(dataDir)
    else dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

  /** Read a GZipped XML file and returns the root element. */
  def readXMLFromGZip(file: File): Elem = {
    val stream =
      if (file.getName.endsWith(".gz")) new GZIPInputStream(new FileInputStream(file))
      else new FileInputStream(file)
    val elem = scala.xml.XML.load(stream)
    stream.close()
    elem
  }

  // Helper method for terminating Akka.
  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def terminateAkka(): Unit = {
    system.terminate()
    optAnnotator.foreach(_.dispose)
  }

  dataFiles.foreach { file =>
    // Start counting time.
    val startTime = System.nanoTime

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

    Await.ready(done, scala.concurrent.duration.Duration.Inf).onComplete {
      case Failure(e) =>
        e.printStackTrace()
        rdfStream.finish()
        outStream.close()
        terminateAkka()
        System.exit(1)
      case _ =>
        // Write out the number of articles processed.
        rdfStream.triple(
          graph.Triple.create(
            graph.NodeFactory.createURI(file.toURI.toString),
            graph.NodeFactory.createURI("http://example.org/pubMedArticleCount"),
            graph.NodeFactory.createLiteral(wrappedArticles.size.toString, XSDDatatype.XSDinteger)
          )
        )
        // Write out the time taken for processing.
        val duration = Duration.ofNanos(System.nanoTime - startTime)
        rdfStream.triple(
          graph.Triple.create(
            graph.NodeFactory.createURI(file.toURI.toString),
            graph.NodeFactory.createURI("http://example.org/timeTakenToProcess"),
            graph.NodeFactory.createLiteral(duration.toString, XSDDatatype.XSDduration)
          )
        )

        rdfStream.finish()
        outStream.close()
    }

    // Write out the time taken for processing.
    val duration = Duration.ofNanos(System.nanoTime - startTime)
    logger.info(s"Done processing $file in $duration")
  }
  terminateAkka()
}
