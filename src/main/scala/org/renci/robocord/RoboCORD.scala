package org.renci.robocord

import java.io.{File, FileWriter, PrintWriter}
import java.nio.file.{CopyOption, Files, StandardCopyOption}
import java.time.Duration

import com.github.tototoshi.csv._
import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.apache.lucene.analysis.core.StopAnalyzer
import org.renci.robocord.annotator.Annotator
import org.renci.robocord.json.CORDJsonReader
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
      descr = "Prefix for the filename (we will add '_from_<start index>_until_<end index>.tsv' to the filename)",
      default = Some("robocord-output/result")
    )
    val neo4jLocation: ScallopOption[File] = opt[File](
      descr = "Location of the Neo4J database that SciGraph should use.",
      default = Some(new File("omnicorp-scigraph"))
    )
    val fromRow: ScallopOption[Int] = opt[Int](
      descr = "The row to start processing on",
      required = true
    )
    val untilRow: ScallopOption[Int] = opt[Int](
      descr = "The row to end processing before (i.e. this row will not be processed)",
      required = true
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
  val (headers: List[String], allMetadata: List[Map[String, String]]) = csvReader.allWithOrderedHeaders()
  logger.info(s"Loaded ${allMetadata.size} articles from metadata file ${conf.metadata()}.")

  // Let's make sure the loaded metadata is what we expect -- if not, fields might have changed unexpectedly!
  assert(headers == List(
    "cord_uid",
    "sha",
    "source_x",
    "title",
    "doi",
    "pmcid",
    "pubmed_id",
    "license",
    "abstract",
    "publish_time",
    "authors",
    "journal",
    "mag_id",
    "who_covidence_id",
    "arxiv_id",
    "pdf_json_files",
    "pmc_json_files",
    "url",
    "s2_id"
  ))

  // Which metadata entries do we actually need to process?
  val startIndex = conf.fromRow.toOption.get
  val endIndex = conf.untilRow.toOption.get

  // Do we already have an output file? If so, we abort.
  val inProgressFilename = conf.outputPrefix() + s"_from_${startIndex}_until_$endIndex.in-progress.txt"
  if (new File(inProgressFilename).exists()) {
    if (new File(inProgressFilename).delete())
      logger.info(s"In-progress output file '${inProgressFilename}' already exists and has been deleted.")
    else {
      logger.info(s"In-progress output file '${inProgressFilename}' already exists but could not be deleted.")
      System.exit(2)
    }
  }

  val outputFilename = conf.outputPrefix() + s"_from_${startIndex}_until_$endIndex.tsv"
  if (new File(outputFilename).length > 0) {
    logger.info(s"Output file '${outputFilename}' already exists, skipping.")
    System.exit(0)
  }

  // Divide allMetadata into chunks based on totalChunks.
  val metadata: Seq[Map[String, String]] = allMetadata.slice(startIndex, endIndex)
  val articlesTotal = metadata.size
  logger.info(s"Selected $articlesTotal articles for processing (from $startIndex until $endIndex)")

  // Prepare a list of stop words.
  val stopWords = StopAnalyzer.ENGLISH_STOP_WORDS_SET

  // Run SciGraph in parallel over the chunk we need to process.
  logger.info(s"Starting SciGraph in parallel on ${Runtime.getRuntime.availableProcessors} processors.")

  var articlesCompleted = 0

  // Summarize all files into the output directory.
  val results: ParSeq[String] = metadata.zipWithIndex.par.flatMap({ case (entry, index) =>
    // Find the ID of this article.
    // We have some duplicate CORD_UIDs (yes, really!). So we add the metadata row index to make it fully unique.
    val id = s"${entry("cord_uid")}:${startIndex + index}"
    val pmid = entry.get("pubmed_id")
    val doi = entry.get("doi")

    // It looks like there are no articules with multiple duplicate PMCIDs, but let's be paranoid
    // and use only the last one.
    val pmcids = entry.getOrElse("pmcid", "").split(';').map(_.trim).filter(_.nonEmpty)
    val pmcid = if (pmcids.length > 1) {
      val pmcidLast = pmcids.last
      logger.info(s"Found multiple PMCIDs for $id, choosing the last one ($pmcidLast) out of: $pmcids")
      pmcidLast
    } else pmcids.headOption.getOrElse("")

    // Choose an "article ID", which is one of: (1) PubMed ID, (2) DOI, (3) PMCID or (4) CORD_UID.
    val articleId = if (pmid.nonEmpty && pmid.get.nonEmpty) pmid.map("PMID:" + _).mkString("|")
      else if(doi.nonEmpty && doi.get.nonEmpty) doi.map("DOI:" + _).mkString("|")
      else if(pmcid.nonEmpty) s"PMCID:${pmcid}"
      else s"CORD_UID:$id"

    // Full-text articles are stored by path. We might have multiple PMC or PDF parses; we prioritize PMC over PDF.
    val pmcJSONFiles = entry("pmc_json_files").split(';').map(_.trim).filter(_.nonEmpty)
    val pdfJSONFiles = entry("pdf_json_files").split(';').map(_.trim).filter(_.nonEmpty)
    val fullTextFilename: String = if (pmcJSONFiles.nonEmpty) {
      if (pmcJSONFiles.length == 1) pmcJSONFiles.head
      else {
        val pmcLast = pmcJSONFiles.last
        logger.info(s"Found multiple PMC JSON files, choosing the last one ($pmcLast) out of $pmcJSONFiles")
        pmcLast
      }
    } else if (pdfJSONFiles.nonEmpty) {
      if (pdfJSONFiles.length == 1) pdfJSONFiles.head
      else {
        val pdfLast = pdfJSONFiles.last
        logger.info(s"Found multiple PDF JSON files, choosing the last one ($pdfLast) out of $pdfJSONFiles")
        pdfLast
      }
    } else ""

    val (fullText, withFullText) = if (fullTextFilename.nonEmpty) {
      val jsonReader = CORDJsonReader.wrapFile(new File(new File("robocord-data"), fullTextFilename), logger)
      (jsonReader.map(_.fullText).mkString("\n===\n"), s"with full text from $fullTextFilename")
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

      // For some reason, matchedString includes stop words. Let's see if we can't get rid of all of them.
      val matchedStringNoStops = matchedString
        // TODO: we currently see "pig\-tailed macaques \(a" -> "pig-tailed macaques \(".
        // Maybe unescape all "\x" to "x"?

        .replaceAll("^\\W+", "")                                // Remove leading non-word characters.
        .replaceAll("\\W+$", "")                                // Remove trailing non-word characters.
        .replaceAll("\\\\-", "-")                               // Unescape dashes.
        .split("\\b+")                                          // Split at word boundaries.
        .map(_.trim)                                            // Trim all strings.
        .filter(!_.isEmpty)                                     // Remove all empty strings.
        .filter(str => !stopWords.contains(str.toLowerCase))    // Filter out stop words.
        .mkString(" ")                                          // Recombine into a single string.
        .replaceAll("\\s+-\\s+", "-")                           // Remove spaces around dashes.
        .trim                                                   // Make sure we don't have any leading/trailing spaces left over.

      s"""$id\t$articleId\t${if (pmcid == "") "" else s"PMCID:$pmcid"}\t$withFullText\t"$preText"\t"$matchedString"\t"$matchedStringNoStops"\t"$postText"\t${annotation.getToken.getId}\t${annotation.toString}"""
    })
  })

  // Write out all the results to the output file.
  logger.info(s"Writing tab-delimited output to $outputFilename.")
  val pw = new PrintWriter(new FileWriter(new File(inProgressFilename)))
  results.foreach(pw.println(_))
  pw.close

  val duration = Duration.ofNanos(System.nanoTime - timeStarted)
  logger.info(s"Completed generating ${results.size} results for $articlesTotal articles in ${duration.getSeconds} seconds ($duration)")

  Files.move(
    new File(inProgressFilename).toPath,
    new File(outputFilename).toPath,
    StandardCopyOption.REPLACE_EXISTING
  )
  logger.info(s"Renamed ${inProgressFilename} to ${outputFilename}; file ready for use.")
}
