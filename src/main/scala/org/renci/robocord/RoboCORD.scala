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

  // Identify full text SHAs and PMCIDs in the current chunk (`metadata`).
  val shasToLoad = metadata.flatMap(_.get("sha")).flatMap(_.split(';')).map(_.trim.toLowerCase).toSet
  val pmcIdsToLoad = metadata.flatMap(_.get("pmcid")).flatMap(_.split(';')).map(_.trim.toLowerCase).toSet
  // logger.info(s"shasToLoad: $shasToLoad")
  // logger.info(s"pmcIdsToLoad: $pmcIdsToLoad")

  // Load up full text data for those SHAs and PMCIDs, and create a map so we can identify them easily.
  val fullTextData: Seq[CORDArticleWrapper] = conf.data().flatMap(CORDJsonReader.wrapFileOrDir(_, logger, shasToLoad, pmcIdsToLoad))
  val fullTextById: Map[String, Seq[CORDArticleWrapper]] = fullTextData.groupBy(_.id)
  logger.info(s"Loaded ${fullTextData.size} full text documents corresponding to ${fullTextById.keySet.size} IDs.")

  // Run SciGraph in parallel over the chunk we need to process.
  logger.info(s"Starting SciGraph in parallel on ${Runtime.getRuntime.availableProcessors} processors.")

  var articlesCompleted = 0

  // Summarize all files into the output directory.
  val results: ParSeq[String] = metadata.par.flatMap(entry => {
    // Find the ID of this article.
    val id = entry("cord_uid")
    val pmid = entry.get("pubmed_id")
    val doi = entry.get("doi")

    // For SHA and PMCID, we do a little additional processing: if there are multiple SHAs or PMCIDs,
    // we only use the last one, but we record all IDs in the log.
    val shas = entry.getOrElse("sha", "").split(';').map(_.trim).filter(_.nonEmpty)
    val sha = if (shas.length > 1) {
      val shaLast = shas.last
      logger.info(s"Found multiple SHAs for $id, choosing the last one ($shaLast) out of: $shas")
      shaLast
    } else shas.headOption.getOrElse("")
    val pmcids = entry.getOrElse("pmcid", "").split(';').map(_.trim).filter(_.nonEmpty)
    val pmcid = if (pmcids.length > 1) {
      val pmcidLast = pmcids.last
      logger.info(s"Found multiple PMCIDs for $id, choosing the last one ($pmcidLast) out of: $pmcids")
      pmcidLast
    } else pmcids.headOption.getOrElse("")

    // Choose an "article ID", which is one of: (1) PubMed ID, (2) DOI, (3) PMCID or (4) CORD_UID.
    val articleId = if (pmid.nonEmpty && pmid.get.nonEmpty) pmid.map("PMID:" + _).mkString("|")
      else if(doi.nonEmpty && doi.get.nonEmpty) doi.map("DOI:" + _).mkString("|")
      else if(pmcid.nonEmpty) pmcid.map("PMCID:" + _)
      else s"CORD_UID:$id"

    // Determine which full text document to use. If a full text JSON document (via SHA or PMCID) is available,
    // we use that. Otherwise, we fall back to the title and abstract from the metadata file.
    val (fullText, withFullText) = if (pmcid.nonEmpty && fullTextById.contains(pmcid)) {
      // Retrieve the full text.
      (
        fullTextById.getOrElse(pmcid, Seq.empty).map(_.fullText).mkString("\n===\n"),
        s"with full text from PMCID $pmcid"
      )
    } else if (sha.nonEmpty && fullTextById.contains(sha)) {
      // Retrieve the full text.
      (
        fullTextById.getOrElse(sha, Seq.empty).map(_.fullText).mkString("\n===\n"),
        s"with full text from SHA $sha"
      )
    } else {
      // We don't have full text, so just annotate the title and abstract.
      val title: String = entry.getOrElse("title", "")
      val abstractText = entry.getOrElse("abstract", "")
      (
        s"$title\n$abstractText",
        "without full text"
      )
    }

    // Extract annotations from the full text using SciGraph.
    val (parsedFullText, annotations) = annotator.extractAnnotations(fullText.replaceAll("\\s+", " "))

    // Write them all out.
    articlesCompleted += 1
    val articlesPercentage = f"${articlesCompleted.toFloat / articlesTotal * 100}%.2f%%"
    logger.info(s"Identified ${annotations.size} annotations for article $id $withFullText (approx $articlesCompleted out of $articlesTotal, $articlesPercentage)")
    annotations.map(annotation => {
      val matchedString = parsedFullText.slice(annotation.getStart, annotation.getEnd).replaceAll("\\s+", " ")
      val preText = parsedFullText.slice(annotation.getStart - conf.context(), annotation.getStart).replaceAll("\\s+", " ")
      val postText = parsedFullText.slice(annotation.getEnd, annotation.getEnd + conf.context()).replaceAll("\\s+", " ")
      s"""$id\t$articleId\t${if (pmcid == "") "" else s"PMCID:$pmcid"}\t$withFullText\t"$preText"\t"$matchedString"\t"$postText"\t${annotation.getToken.getId}\t${annotation.toString}"""
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
