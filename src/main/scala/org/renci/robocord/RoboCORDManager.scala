package org.renci.robocord

import scala.sys.process._
import org.rogach.scallop.{ScallopConf, ScallopOption}
import org.rogach.scallop.exceptions.ScallopException
import java.io.File
import java.util.concurrent.TimeUnit

import com.github.tototoshi.csv.CSVReader

/**
 * RoboCORD Manager manages runs of the RoboCORD Worker. Since we run RoboCORD on a SLURM-based cluster,
 * we have a manager that starts workers using `srun` to fill in gaps that need executing.
 */
object RoboCORDManager extends App {
  /**
   * Command line configuration for RoboCORDManager.
   */
  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    override def onError(e: Throwable): Unit = e match {
      case ScallopException(message) =>
        printHelp
        scribe.error(message)
        System.exit(1)
      case ex => super.onError(ex)
    }

    val version: String = getClass.getPackage.getImplementationVersion
    version("RoboCORD: part of Omnicorp v" + version)

    val metadata: ScallopOption[File] = opt[File](
      descr = "The CSV file containing metadata",
      default = Some(new File("robocord-data/metadata.csv"))
    )
    val outputDir: ScallopOption[File] = opt[File](
      descr = "Directory containing output files. Our output files are in the format '$outputDir/result_from_{index}_until_{index}.txt'",
      default = Some(new File("robocord-output"))
    )
    val neo4jLocation: ScallopOption[File] = opt[File](
      descr = "Location of the Neo4J database that SciGraph should use.",
      default = Some(new File("omnicorp-scigraph"))
    )
    val context: ScallopOption[Int] = opt[Int](
      descr = "How many characters before and after the matched term should be displayed.",
      default = Some(50)
    )
    val jobSize: ScallopOption[Int] = opt[Int](
      descr = "The maximum number of rows in each job.",
      default = Some(500)
    )
    val dryRun: ScallopOption[Boolean] = opt[Boolean](
      descr = "If true, perform a dry run: display the srun commands to be executed, but don't actually execute them.",
      default = Some(false)
    )
    val tryOnce: ScallopOption[Boolean] = opt[Boolean](
      descr = "If true, try executing a single job.",
      default = Some(false)
    )
    val cmdDelay: ScallopOption[Int] = opt[Int](
      descr = "The number of seconds to wait before running the subsequent job.",
      default = Some(2)
    )

    verify()
  }

  // Parse command line arguments.
  val conf = new Conf(args)

  // Load the metadata file.
  val csvReader = CSVReader.open(conf.metadata())
  val (headers: List[String], metadata: List[Map[String, String]]) = csvReader.allWithOrderedHeaders()
  scribe.info(s"Loaded ${metadata.size} articles from metadata file ${conf.metadata()}.")

  // Get all existing output files.
  val filenameRegex = """results_from_(\d+)_until_(\d+).txt""".r
  val existingRangesSorted: Seq[Range] = conf.outputDir
    .getOrElse(new File("robocord-output"))
    .listFiles()
    .toSeq
    .filter(file => file.getName.startsWith("results_") && file.getName.endsWith(".txt"))
    .map(file => file.getName match {
      case filenameRegex(from, until) => new Range(from.toInt, until.toInt, 1)
      case _ => throw new RuntimeException(s"Unexpected result filename found: ${file.getName}.")
    })
    .sortBy(_.start)

  scribe.info(s"Ranges completed: ${existingRangesSorted}")

  // We can now generate a set of ranges that work around those that have already been generated.
  val rangesToProcess: Seq[Range] = if (existingRangesSorted.isEmpty) Seq(metadata.indices) else {
    val rangesToExclude = (
      new Range(metadata.indices.head, metadata.indices.head, 1)
      +: existingRangesSorted :+
      new Range(metadata.size, metadata.size, 1)
    )
    scribe.info(s"Ranges to exclude: ${rangesToExclude}")
    scribe.info(s"Sliding ranges to exclude: ${rangesToExclude.sliding(2).toSeq}")
    rangesToExclude
      .sliding(2) // Group them up in pairs, i.e. indexes (0, 1), (1, 2), (2, 3), ...
      .map({
        // For each pair, choose the range from the end of the first range to the start of the next range
        case Seq(prev, next) => Range(prev.end, next.start)
      })
      .filter(_.nonEmpty) // Some of these ranges may be empty (e.g. 640 to 640); we can exclude those.
      .toSeq
  }
  scribe.info(s"Ranges to process: ${rangesToProcess.mkString(", ")}")

  // Okay, now we know the ranges that need to be processed. We divide them up into jobs recursively.
  var jobCount = 0
  def processJob(range: Range): Unit = {
    if (range.size > conf.jobSize.getOrElse(200)) {
      val halfway: Int = range.start + range.size/2
      processJob(new Range(range.start, halfway, 1))
      processJob(new Range(halfway, range.end, 1))
    } else {
      jobCount += 1
      val logger: ProcessLogger = ProcessLogger.apply(scribe.error(_))
      if (conf.dryRun.getOrElse(true)) {
        scribe.info(s" - Would execute job ${jobCount}: ${range} (size: ${range.size})")
      } else {
        val cmd = Seq(
	  "/bin/bash",
          "robocord-sbatch.sh",
          "--metadata", "robocord-data/metadata.csv",
          "--from-row", range.start.toString,
          "--until-row", range.end.toString,
          "--output-prefix", "robocord-output/results",
          "robocord-data"
        )
        val process = cmd.run(logger)
        if (process.exitValue == 0)
          scribe.info(s" - Executed job ${jobCount}: ${range} (size: ${range.size}): ${cmd}")
        else
          scribe.error(s" - Could not execute job ${jobCount}: ${range} (size: ${range.size})")
        if(conf.tryOnce.getOrElse(false)) System.exit(0)
        TimeUnit.SECONDS.sleep(conf.cmdDelay.getOrElse(2))
      }
    }
  }

  rangesToProcess.foreach(processJob(_))
}
