package org.renci.robocord

import java.io.{File, FileWriter, PrintWriter}
import java.time.Duration

import com.github.tototoshi.csv._
import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.renci.robocord.annotator.Annotator
import org.renci.robocord.json.{CORDArticleWrapper, CORDJsonReader}
import org.rogach.scallop._
import org.rogach.scallop.exceptions._

import scala.collection.parallel.ParSeq

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
    val outputPrefix: ScallopOption[String] = opt[String](
      descr = "Prefix for the filename (we will add '_from_<start index>_until_<end index>.txt' to the filename)",
      default = Some("robocord-output/result")
    )
    val neo4jLocation: ScallopOption[File] = opt[File](
      descr = "Location of the Neo4J database that SciGraph should use.",
      default = Some(new File("omnicorp-scigraph"))
    )
    val currentChunk: ScallopOption[Int] = opt[Int](
      descr = "The current chunks (from 0 to totalChunks-1)",
      default = Some(0)
    )
    val totalChunks: ScallopOption[Int] = opt[Int](
      descr = "The total number of chunks to process",
      default = Some(-1)
    )
    val context: ScallopOption[Int] = opt[Int](
      descr = "How many characters before and after the matched term should be displayed.",
      default = Some(50)
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
  val allMetadata: List[Map[String, String]] = csvReader.allWithHeaders()
  logger.info(s"Loaded ${allMetadata.size} articles from metadata file ${conf.metadata()}.")

  // Which metadata entries do we actually need to process?
  val currentChunk: Int = conf.currentChunk()
  val totalChunks: Int = if (conf.totalChunks() == -1) allMetadata.size else conf.totalChunks()
  val chunkLength: Int = allMetadata.size/totalChunks
  val startIndex: Int = currentChunk * chunkLength
  val endIndex: Int = startIndex + chunkLength

  // Divide allMetadata into chunks based on totalChunks.
  val metadata: Seq[Map[String, String]] = allMetadata.slice(startIndex, endIndex)
  val articlesTotal = metadata.size
  logger.info(s"Selected $articlesTotal articles for processing (from $startIndex until $endIndex, chunk $currentChunk out of $totalChunks)")

  // We primarily pull the metadata from allMetadata, which includes abstracts. In some cases, however,
  // we also have full text for those files.
  val shasToLoad = metadata.map(_.get("sha")).flatten.flatMap(_.split(';')).map(_.trim.toLowerCase).toSet
  val fullTextData: Seq[CORDArticleWrapper] = conf.data().flatMap(CORDJsonReader.wrapFileOrDir(_, logger, shasToLoad))
  val fullTextBySHA: Map[String, Seq[CORDArticleWrapper]] = fullTextData.groupBy(_.sha1)
  logger.info(s"Loaded ${fullTextData.size} full text documents corresponding to ${fullTextBySHA.keySet.size} SHA hashes.")

  logger.info(s"Starting SciGraph in parallel on ${Runtime.getRuntime.availableProcessors} processors.")

  var articlesCompleted = 0

  // Summarize all files into the output directory.
  // .par(conf.parallel())
  val results: ParSeq[String] = metadata.par.flatMap(entry => {
    // Get ID.
    val pmid = entry.getOrElse("pubmed_id", "")
    val pmcid = entry.getOrElse("pmcid", "")
    val doi = entry.getOrElse("doi", "")
    val title = entry.getOrElse("title", "")
    val id = if (pmid != "") s"PMID:$pmid"
    else if (pmcid != "") s"PMCID:$pmcid"
    else if (doi != "") s"DOI:$doi"
    else if (title != "") s"TITLE:${title.replace("\\s", "_")}"
    else s"UNKNOWN_ID"

    // Step 1. Find the ID of this article.
    val shas = entry.getOrElse("sha", "").split(';').map(_.trim).filter(_.nonEmpty)
    val sha = if (shas.length > 1) {
      val shaLast = shas.last
      logger.info(s"Found multiple SHAs for $id, choosing the last one ($shaLast) out of: $shas")
      shaLast
    } else shas.headOption.getOrElse("")

    val abstractText = entry.getOrElse("abstract", "")

    // Is there a full text article for this entry?
    val fullText: String = if (sha.nonEmpty && fullTextBySHA.contains(sha)) {
      // Retrieve the full text.
      fullTextBySHA.getOrElse(sha, Seq()).map(_.fullText).mkString("\n===\n")
    } else {
      // We don't have full text, so just annotate the title and abstract.
      s"$title\n$abstractText"
    }
    val withFullText = if (sha.nonEmpty && fullTextBySHA.contains(sha)) s"with full text from $sha" else "without full text"
    val (parsedFullText, annotations) = annotator.extractAnnotations(fullText.replaceAll("\\s+", " "))

    // Step 4. Write them all out.
    articlesCompleted += 1
    val articlesPercentage = f"${articlesCompleted.toFloat / articlesTotal * 100}%.2f%%"
    logger.info(s"Identified ${annotations.size} annotations for article $id $withFullText (approx $articlesCompleted out of $articlesTotal, $articlesPercentage)")
    annotations.map(annotation => {
      val matchedString = parsedFullText.slice(annotation.getStart, annotation.getEnd).replaceAll("\\s+", " ")
      val preText = parsedFullText.slice(annotation.getStart - conf.context(), annotation.getStart).replaceAll("\\s+", " ")
      val postText = parsedFullText.slice(annotation.getEnd, annotation.getEnd + conf.context()).replaceAll("\\s+", " ")
      s"""$id\t${if (pmcid == "") "" else s"PMCID:$pmcid"}\t$withFullText\t"$preText"\t"$matchedString"\t"$postText"\t${annotation.getToken.getId}\t${annotation.toString}"""
    })
  })

  // Write out all the results to the output file.
  val outputFilename = conf.outputPrefix() + s"_from_${startIndex}_until_$endIndex.txt"
  logger.info(s"Writing tab-delimited output to $outputFilename.")
  val pw = new PrintWriter(new FileWriter(new File(outputFilename)))
  results.foreach(pw.println(_))
  pw.close

  val duration = Duration.ofNanos(System.nanoTime - timeStarted)
  logger.info(s"Completed generating ${results.size} results for $articlesTotal articles in ${duration.getSeconds} seconds ($duration)")
}
