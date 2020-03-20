package org.renci.robocord

import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import java.time.Duration

import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.renci.robocord.annotator.Annotator
import org.renci.robocord.json.{CORDArticleWrapper, CORDJsonReader}
import org.rogach.scallop._
import org.rogach.scallop.exceptions._
import com.github.tototoshi.csv._

import scala.collection.parallel.ParSeq
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
      default = Some(new File("robocord-output"))
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
  val csvReader = CSVReader.open(conf.metadata())
  val metadata: List[Map[String, String]] = csvReader.allWithHeaders()
  val metadataMap = metadata.groupBy(_.getOrElse("sha", ""))
  logger.info(s"${metadata.size} metadata entries for ${metadataMap.keySet.size} unique SHA ids loaded from ${conf.metadata()}, of which ${metadataMap.getOrElse("", Seq.empty).size} have missing SHAs.")

  // Which files do we need to process?
  val wrappedData: Seq[CORDArticleWrapper] = conf.data().flatMap(CORDJsonReader.wrapFileOrDir(_, logger))

  logger.info(s"${wrappedData.size} articles loaded from ${conf.data()}.")

  logger.info(s"Starting SciGraph in parallel on ${Runtime.getRuntime.availableProcessors} processors.")

  // Summarize all files into the output directory.
  // .par(conf.parallel())
  val results: ParSeq[String] = wrappedData.par.flatMap(wrappedArticle => {
    // Step 1. Find the metadata for this article.
    metadataMap.get(wrappedArticle.sha1) match {
      case None => {
        logger.error(s"Could not find metadata for ${wrappedArticle.sha1}")
        Seq()
      }
      case Some(List(metadata: Map[String, String])) => {
        // Step 2. Find the ID of this article.
        val pmcid = metadata.getOrElse("pmcid", "")
        val doi = metadata.getOrElse("doi", "")
        val id = if(pmcid != "") s"PMCID:$pmcid"
        else if(doi != "") s"DOI:$doi"
        else s"sha1:${wrappedArticle.sha1}"

        // Step 3. Find the annotations for this article.
        val annotations = wrappedArticle.getSciGraphAnnotations(annotator)

        // Step 4. Write them all out.
        logger.info(s"Identified ${annotations.size} annotations for article $id")
        annotations.map(annotation => s"$id\t${annotation.getToken.getId}\t${annotation.toString}")
      }
      case Some(list: List[Map[String, String]]) => {
        logger.error(s"Found multiple entries of metadata for ${wrappedArticle.sha1}: ${list}")
        Seq()
      }
    }
  })

  // Write out all the results to the output file.
  logger.info(s"Writing tab-delimited output to ${conf.output()}/results.txt.")
  val pw = new PrintWriter(new FileWriter(new File(conf.output(), "results.txt")))
  results.foreach(pw.println(_))
  pw.close

  val duration = Duration.ofNanos(System.nanoTime - timeStarted)
  logger.info(s"Completed generating ${results.size} results for ${wrappedData.size} articles in ${duration.getSeconds} seconds ($duration)")
}
