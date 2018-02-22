package org.renci.chemotext

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.xml.Elem

import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.rdf.model.Statement
import org.apache.jena.vocabulary.DCTerms

import com.typesafe.scalalogging.LazyLogging

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import io.scigraph.annotation.EntityAnnotation
import io.scigraph.annotation.EntityFormatConfiguration

object Main extends App with LazyLogging {

  val scigraphLocation = args(0)
  val fileLoadParallelism = args(1).toInt
  val annotationParallelism = args(2).toInt

  val annotator = new Annotator(scigraphLocation)

  implicit val system = ActorSystem("pubmed-actors")
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
  val DCTReferences = DCTerms.references
  val CURIE = "^([^:]*):(.*)$".r

  val dataDir = new File("data")
  val dataFiles = dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

  def readXMLFromGZip(file: File): Future[Elem] = Future {
    blocking {
      val stream = new GZIPInputStream(new FileInputStream(file))
      val elem = scala.xml.XML.load(stream)
      stream.close()
      elem
    }
  }

  def extractText(articleSet: Elem): List[(String, String)] = {
    for {
      article <- (articleSet \ "PubmedArticle").toList
      pmidEl <- article \\ "PMID"
      pmid = pmidEl.text
      title = (article \\ "ArticleTitle").map(_.text).mkString(" ")
      abstractText = (article \\ "AbstractText").map(_.text).mkString(" ")
      meshTerms = (article \\ "MeshHeading").map(mh =>
        (mh \ "DescriptorName").map(_.text).mkString(" ") +
          (mh \ "QualifierName").map(_.text).mkString(" ")).mkString(" ")
    } yield pmid -> s"$title $abstractText $meshTerms"
  }

  def annotateWithSciGraph(text: String): Future[List[EntityAnnotation]] = Future {
    val configBuilder = new EntityFormatConfiguration.Builder(new StringReader(text))
    configBuilder.longestOnly(true)
    configBuilder.minLength(3)
    blocking {
      annotator.processor.annotateEntities(configBuilder.get).asScala.toList
    }
  }

  def makeTriples(pmid: String, annotations: List[EntityAnnotation]): Set[Statement] = {
    val pmidIRI = ResourceFactory.createResource(s"$PMIDNamespace/$pmid")
    val statements = annotations.map { annotation =>
      ResourceFactory.createStatement(pmidIRI, DCTReferences, ResourceFactory.createResource(annotation.getToken.getId))
    }
    statements.toSet
  }

  val done = Source(dataFiles)
    .mapAsyncUnordered(fileLoadParallelism) { file =>
      readXMLFromGZip(file).map(file.getName -> _)
    }
    .map {
      case (filename, articleSet) =>
        filename -> extractText(articleSet)
    }
    .mapAsyncUnordered(fileLoadParallelism) {
      case (filename, articlesWithText) => Future {
        Source(articlesWithText).mapAsyncUnordered(annotationParallelism) {
          case (pmid, text) =>
            annotateWithSciGraph(text).map(pmid -> _)
        }.fold(Set.empty[Statement]) {
          case (statements, (pmid, json)) => statements ++ makeTriples(pmid, json)
        }.runForeach { statements =>
          blocking {
            val outStream = new FileOutputStream(new File(s"$filename.ttl"))
            val model = ModelFactory.createDefaultModel()
            model.add(statements.toSeq.asJava)
            model.write(outStream, "ttl")
            outStream.close()
          }
        }
      }
    }.runForeach { fileCompletion =>
      
    }

  Await.ready(done, Duration.Inf).onComplete {
    case Failure(e) =>
      e.printStackTrace()
      system.terminate()
      annotator.dispose()
      System.exit(1)
    case _ => {
      system.terminate()
      annotator.dispose()
    }
  }

}