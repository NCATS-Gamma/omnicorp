import java.io.{File, FileInputStream, FileOutputStream, PrintStream}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import java.util.concurrent.ForkJoinPool

import com.typesafe.scalalogging.LazyLogging

import scala.xml.{Node, Elem, Text}
import scala.collection.parallel.ForkJoinTaskSupport

/**
 * FieldSummary generates a summary of all the fields used in an XML file.
 */
 object FieldSummary extends App with LazyLogging {
   val dataDir = new File(args(0))
   val outDir = args(1)
   val parallelism = args(2)

   // Use a fork-join pool to limit the number of parallel processes to
   // the number of specified nodes.
   // See https://docs.scala-lang.org/overviews/parallel-collections/configuration.html
   val forkJoinPool = new ForkJoinPool(parallelism.toInt)

   // Which files do we need to read?
   val dataFiles = if (dataDir.isFile) List(dataDir)
   else dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

   def readXMLFromGZip(file: File): Elem = {
     val stream = new GZIPInputStream(new FileInputStream(file))
     val elem = scala.xml.XML.load(stream)
     stream.close()
     elem
   }

   def describeElem(el: Node, parentLabel: String = ""): Seq[(String, String)] = {
     val nodeFullName = parentLabel + "." + el.label
     val descriptions = el match {
       case Text(str) => Seq((parentLabel, str))
       case _ => el.attributes.map(attr => (nodeFullName + '/' + attr.key, attr.value.map(_.text).mkString(", "))).toSeq
     }

     return descriptions ++ el.nonEmptyChildren.flatMap(describeElem(_, nodeFullName))
   }

   val NANOSECOND = 1000000000

   dataFiles.foreach { file =>
     val startTime = System.nanoTime()
     logger.info(s"Began processing $file")
     val rootElement = readXMLFromGZip(file)
     val parallelArticles = rootElement.nonEmptyChildren.par
     parallelArticles.tasksupport = new ForkJoinTaskSupport(forkJoinPool)
     val fields = parallelArticles.flatMap(describeElem(_, rootElement.label))
     val summary = fields.groupBy(_._1).mapValues(_.size)
     val outStream = new PrintStream(new GZIPOutputStream(new FileOutputStream(new File(s"$outDir/${file.getName}.fields.txt.gz"))))
     summary.toSeq.seq.sortBy(_._1).foreach(outStream.println(_))
     logger.info(f"Completed processing $file in ${(System.nanoTime() - startTime).toDouble/NANOSECOND}%.2f seconds.")
     outStream.close()
   }
   forkJoinPool.shutdown()
 }
