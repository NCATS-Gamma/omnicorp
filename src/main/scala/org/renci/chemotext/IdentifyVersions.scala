package org.renci.chemotext

import java.io.{File, FileInputStream}
import java.util.zip.GZIPInputStream

import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable

/**
 * IdentifyVersions identifies PubMed identifiers in the PubMed XML files
 * that refer to multiple versions. This can be useful in
 */
object IdentifyVersions extends App with LazyLogging {
  // TODO: Replace with command line option.
  val xmlDir:File = new File("pubmed-annual-baseline")

  /** Identify PMIDs with multiple versions in the input file or directory. */
  def identifyPMIDs(inputFile: File): Unit = {
    if(inputFile.isDirectory) {
      logger.info(s"Recursing into directory $inputFile")
      inputFile.listFiles.filter(file =>
        file.getName.toLowerCase.endsWith(".xml.gz") ||
        file.getName.toLowerCase.endsWith(".xml")
      ).foreach(identifyPMIDs(_))
    } else {
      logger.info(s"Processing input file $inputFile")

      // Is it a .gz file? If so, we need to apply an GzipInputStream.
      val stream =
        if (inputFile.getName.endsWith(".gz")) new GZIPInputStream(new FileInputStream(inputFile))
        else new FileInputStream(inputFile)
      val root = scala.xml.XML.load(stream)
      stream.close()

      // Find all PMIDs.
      val pmidElements = root \\ "PMID"
      val results = pmidElements
        .filter(node => (node \@ "Version") != "1")
        .map(pmidElement => (pmidElement.text, pmidElement \@ "Version"))

      results.map(result => println(result._1 + "\t" + result._2))

      val uniqPMIDs = results.map(_._1).toSet
      logger.info(s"Checked $inputFile, found ${uniqPMIDs.size} PMIDs with two or more versions.")
    }
  }

  // Start the run.
  identifyPMIDs(xmlDir)
}
