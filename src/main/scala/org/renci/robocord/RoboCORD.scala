package org.renci.robocord

import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import java.time.Duration

import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.renci.robocord.annotator.Annotator
import org.renci.robocord.json.{CORDArticleWrapper, CORDJsonReader}
import org.rogach.scallop._
import org.rogach.scallop.exceptions._
import zamblauskas.csv.parser.Parser
import zamblauskas.csv.parser._

import scala.io.Source

object RoboCORD extends App with LazyLogging {
  /**
   * Command line configuration for RoboCORD.
   */
  class Conf(arguments: Seq[String], logger: Logger) extends ScallopConf(arguments) {
    override def onError(e: Throwable): Unit = e match {
      case ScallopException(message) =>
        printHelp
        logger.error(message)
        System.exit(1)
      case ex => super.onError(ex)
    }

    val version: String = getClass.getPackage.getImplementationVersion
    version("RoboCORD: part of Omnicorp v" + version)

    val data: ScallopOption[List[File]] = trailArg[List[File]](descr = "Data file(s) or directories to convert (in JSON-LD)")
    val metadata: ScallopOption[File] = opt[File](
      descr = "The CSV file containing metadata",
      default = Some(new File("robocord-data/all_sources_metadata_latest.csv"))
    )
    val output: ScallopOption[File] = opt[File](
      descr = "Directory where report should be written",
      default = Some(new File("robocord-results"))
    )
    val neo4jLocation: ScallopOption[File] = opt[File](
      descr = "Location of the Neo4J database that SciGraph should use.",
      default = Some(new File("omnicorp-scigraph"))
    )

    verify()
  }

  val timeStarted = System.nanoTime

  // Parse command line arguments.
  val conf = new Conf(args, logger)

  // Load the metadata CSV file.
  case class MetadataEntry (
    sha: String,
    source_x: String,
    title: String,
    doi: String,
    pmcid: String,
    pubmed_id: String,
    license: String,
    `abstract`: String,
    publish_time: String,
    authors: String,
    journal: String,
    `Microsoft Academic Paper ID`: String,
    `WHO #Covidence`: String,
    has_full_text: String
  )

  // Set up Annotator.
  val annotator: Annotator = new Annotator(conf.neo4jLocation())

  // Load the metadata file.
  val metadataSource = Source.fromFile(conf.metadata())
  val csvText = metadataSource.mkString
  val metadata: Seq[MetadataEntry] = Parser.parse[MetadataEntry](csvText) match {
    case Left(Parser.Failure(lineNum, line, message)) => throw new RuntimeException(s"Could not parse CSV metadata file ${conf.metadata()} on line $lineNum: $message\n\tLine: $line")
    case Right(entries: Seq[MetadataEntry]) => entries
  }
  metadataSource.close()
  val metadataMap = metadata.groupBy(_.sha)
  logger.info(s"${metadata.size} metadata entries loaded from ${conf.metadata()}.")

  // Which files do we need to process?
  val wrappedData: Stream[CORDArticleWrapper] = conf.data().flatMap(CORDJsonReader.wrapFileOrDir(_, logger)).toStream

  logger.info(s"${wrappedData.size} articles loaded from ${conf.data()}.")

  // Summarize all files into the output directory.
  // .par(conf.parallel())
  val results = wrappedData.par.map(wrappedArticle => {
    // Step 1. Find the metadata for this article.
    val metadataEntry = metadataMap.get(wrappedArticle.sha1).head.head

    // Step 2. Find the ID of this article.
    val id = if (metadataEntry.pmcid.nonEmpty) s"PMCID:${metadataEntry.pmcid}"
      else if(metadataEntry.doi.nonEmpty) s"DOI:${metadataEntry.doi}"
      else s"sha1:${wrappedArticle.sha1}"

    // Step 3. Find the annotations for this article.
    val annotations = wrappedArticle.getSciGraphAnnotations(annotator)

    // Step 4. Write them all out.
    logger.info(s"Identified ${annotations.size} annotations for article $id")
    annotations.map(annotation => s"$id\t${annotation.getToken.getId}\t${annotation.toString}")
  })

  // Write out all the results to the output file.
  logger.info(s"Writing tab-delimited output to ${conf.output()}/results.txt.")
  val pw = new PrintWriter(new FileWriter(new File(conf.output(), "results.txt")))
  results.foreach(pw.println(_))
  pw.close

  val duration = Duration.ofNanos(System.nanoTime - timeStarted)
  logger.info(s"Completed generating ${results.size} results for ${wrappedData.size} articles in ${duration.getSeconds} seconds ($duration)")
}
