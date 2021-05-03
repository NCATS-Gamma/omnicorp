package org.renci.chemotext

import java.io.{BufferedWriter, File, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.time.Duration

import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.vocabulary.{DCTerms, RDF}
import org.rogach.scallop.{ScallopConf, ScallopOption}
import org.rogach.scallop.exceptions.ScallopException

/**
  * GenerateTSV generates tab-delimited files summarizing the results of the
  * Omnicorp processing.
  */
object GenerateTSV extends App with LazyLogging {
  /**
    * Command line configuration for GenerateTSV.
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
    version("GenerateTSV: part of Omnicorp v" + version)

    val files: ScallopOption[List[File]] =
      trailArg[List[File]](descr = "Data file(s) or directories to convert from RDF to TSV")
    val outputDir: ScallopOption[File] =
      opt[File](descr = "Directory for output files", default = Some(new File("tsv-output")))

    verify()
  }

  val conf = new Conf(args, logger)

  val files: Seq[File] = conf.files()
  val outputDir: File  = conf.outputDir()

  /**
    * Extract PubMed articles and results from the input file or directory.
    */
  def writeTSVToDir(inputFile: File, outputDir: File): Unit = {
    if (inputFile.isDirectory) {
      logger.info(s"Recursing into directory $inputFile")
      inputFile.listFiles
        .filter(
          file =>
            file.getName.toLowerCase.endsWith(".gz.ttl") ||
              file.getName.toLowerCase.endsWith(".gz.completed.ttl")
        )
        .par
        .foreach(writeTSVToDir(_, outputDir))
    } else {
      logger.info(s"Processing file $inputFile")

      val startTime = System.nanoTime

      val dataModel = RDFDataMgr.loadModel(inputFile.toURI.toString)
      val infModel  = ModelFactory.createRDFSModel(dataModel)
      val articles = infModel.listResourcesWithProperty(
        RDF.`type`,
        infModel.createResource("http://purl.org/spar/fabio/Article")
      )

      // Check to see if we've already completed processing this file.
      val completedFilename = new File(outputDir, inputFile.getName + ".completed.tsv")
      if (completedFilename.exists) {
        logger.info(s"Skipping, since $completedFilename already exists.")
      } else {
        // Start creating an in-progress file.
        val outputFilename = new File(outputDir, inputFile.getName + ".in-progress.tsv")
        val outputStream   = new PrintWriter(new BufferedWriter(new FileWriter(outputFilename)))

        var articleCount = 0
        articles.forEachRemaining(article => {
          articleCount += 1
          val refs = infModel.listObjectsOfProperty(article, DCTerms.references)
          refs.forEachRemaining(ref => {
            outputStream.println(article.getURI + "\t" + ref.asResource.getURI)
          })
        })
        outputStream.close()

        val duration = Duration.ofNanos(System.nanoTime - startTime)

        val articlesPerSecond = (articleCount.toFloat / duration.getSeconds)
        logger.info(
          f"Took ${duration.getSeconds} seconds ($duration) to process $articleCount articles ($articlesPerSecond%.2f articles/sec) from $inputFile to $outputFilename"
        )

        // Rename file to completed.
        Files.move(
          Paths.get(outputFilename.toURI),
          Paths.get(completedFilename.toURI),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
      }
    }
  }

  // Process all input files or directories.
  files.foreach(writeTSVToDir(_, outputDir))
}
