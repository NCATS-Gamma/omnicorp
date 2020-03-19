package org.renci.robocord

import java.io.File

import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.renci.robocord.json.CORDJsonReader
import org.rogach.scallop._
import org.rogach.scallop.exceptions._

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

    verify()
  }

  // Parse command line arguments.
  val conf = new Conf(args, logger)

  // Which files do we need to process?
  val dataFiles = conf.data()

  // Process!
  dataFiles.foreach(CORDJsonReader.processFile(_, logger))
}
