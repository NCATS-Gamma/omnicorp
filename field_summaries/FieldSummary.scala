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

   // We've been having some problems with setting memory usage in build.sbt, so I'm logging this
   // so we can figure out when we're messing this up.
   val MEGABYTE = 1024 * 1024
   logger.info(f"Free memory available: ${Runtime.getRuntime.freeMemory/MEGABYTE}%.2f MB")

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

   val NANOSECONDS = 1000000000
   val MILLISECONDS = 1000

   dataFiles.foreach { file =>
     logger.info(s"Began processing $file")
     val startTime = System.nanoTime()

     val rootElement = readXMLFromGZip(file)
     val parallelArticles = rootElement.nonEmptyChildren.par
     parallelArticles.tasksupport = new ForkJoinTaskSupport(forkJoinPool)
     val fields = parallelArticles.flatMap(describeElem(_, rootElement.label))
     val summary = fields.groupBy(_._1).mapValues(_.size)
     val outStream = new PrintStream(new GZIPOutputStream(new FileOutputStream(new File(s"$outDir/${file.getName}.fields.txt.gz"))))
     summary.toSeq.seq.sortBy(_._1).foreach(count => outStream.println(s"${count._2}\t${count._1}"))
     outStream.close()

     val timeTaken = (System.nanoTime() - startTime).toDouble/NANOSECONDS
     val numArticles = parallelArticles.size
     logger.info(f"Completed processing $numArticles articles from $file in ${timeTaken}%.2f seconds (${(timeTaken/numArticles)*MILLISECONDS}%.5f ms per article).")
   }
   forkJoinPool.shutdown()
 }
