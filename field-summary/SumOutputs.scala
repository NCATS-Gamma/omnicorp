import java.io.{File, FileInputStream, FileOutputStream, PrintStream}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import java.util.concurrent.ForkJoinPool

import com.typesafe.scalalogging.LazyLogging

import scala.io.Source
import scala.xml.{Node, Elem, Text}
import scala.collection.parallel.ForkJoinTaskSupport

/**
  * SumOutputs sums up generated outputs to provide overall stats.
  */
object SumOutputs extends App with LazyLogging {
  val outDir = new File(args(0))

  // Which files do we need to read?
  val outFiles =
    if (outDir.isFile) List(outDir)
    else outDir.listFiles().filter(_.getName.endsWith(".fields.txt.gz")).toList

  // Read all output files.
  val lines = outFiles.flatMap(file => {
    val stream = new GZIPInputStream(new FileInputStream(file))
    Source.fromInputStream(stream, "utf8").getLines
  })

  // Read output files produced by FieldSummary.
  val countFormat = """^(\d+)\t(.*)$""".r
  val counts = lines.map {
    case countFormat(count, field) => (field, count.toInt)
    case line                      => throw new RuntimeException(s"Unable to parse line '$line'")
  }

  // Group counts by field name and add all counts for each field name.
  // Print resulting tuples.
  counts.groupBy(_._1).mapValues(_.map(_._2).sum).toSeq.sortBy(_._1).foreach(println(_))
}
