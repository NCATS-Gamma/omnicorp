package org.renci.chemotext

import java.io.{BufferedWriter, File, FileWriter, IOException, PrintWriter}
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.time.Duration

import com.typesafe.scalalogging.LazyLogging
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.vocabulary.{DCTerms, RDF}
import zio._
import zio.stream._

/**
 * GenerateTSV generates tab-delimited files summarizing the results of the
 * Omnicorp processing.
 */
object GenerateTSV extends App with LazyLogging {
  // Some constants to use.
  val articleClass = "http://purl.org/spar/fabio/Article"

  // Make a list of all the files we need to process.
  def getFilesInDir(files: Stream[Nothing, File]): Stream[Nothing, File] = {
    files.map(file => {
      logger.info(s"Examining file $file")
      file
    }).flatMap(file => {
      if (file.isDirectory) {
        logger.info(s"Recursing into directory $file")
        getFilesInDir(Stream.fromIterable(file.listFiles))
      } else Stream.fromIterable(Seq(file))
    })
  }

  def run(args: List[String]): ZIO[ZEnv, Nothing, Int]  = {
    // TODO replace with command line argument.
    val inputFiles: Seq[File] = Seq(new File("/"), new File("."))
    val outputDir: File = new File("tsv-output")

    val files = getFilesInDir(Stream.fromIterable(inputFiles))
      .filter(_.getName.toLowerCase.endsWith(".ttl"))
      .foldM(
        ex => console.putStrLn(ex.toString).
        res => console.putStrLn(res).const(0)
      )

    IO.succeed(0)
  }

  /**
   * Extract PubMed articles and results from the input file.
   */
  def writeTSVToDir(inputFile: File, outputDir: File): Unit = {
    if (inputFile.isDirectory) {
      logger.info(s"Recursing into directory $inputFile")
      inputFile.listFiles.filter(file =>
        file.getName.toLowerCase.endsWith(".gz.ttl") ||
        file.getName.toLowerCase.endsWith(".gz.completed.ttl")
      ).par.foreach(writeTSVToDir(_, outputDir))
    } else {
      logger.info(s"Processing file $inputFile")

      val startTime = System.nanoTime

      // TODO replace with https://jena.apache.org/documentation/io/streaming-io.html
      val dataModel = RDFDataMgr.loadModel(inputFile.toURI.toString)
      val infModel = ModelFactory.createRDFSModel(dataModel)
      val articles = infModel.listResourcesWithProperty(
        RDF.`type`,
        infModel.createResource(articleClass)
      )

      // Check to see if we've already completed processing this file.
      val completedFilename = new File(outputDir, inputFile.getName + ".tsv")
      if (completedFilename.exists) {
        logger.info(s"Skipping, since $completedFilename already exists.")
      } else {
        // Start creating an in-progress file.
        val outputFilename = new File(outputDir, inputFile.getName + ".in-progress.tsv")
        val outputStream = new PrintWriter(new BufferedWriter(new FileWriter(outputFilename)))

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
        logger.info(f"Took ${duration.getSeconds} seconds ($duration) to process $articleCount articles ($articlesPerSecond%.2f articles/sec) from $inputFile to $outputFilename")

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

  // Process files.
  // files.foreach(writeTSVToDir(_, outputDir))
}
