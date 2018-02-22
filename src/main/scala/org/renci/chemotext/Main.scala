package org.renci.chemotext

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.marshalling.ToEntityMarshaller
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.RequestEntity
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.Unmarshaller
import java.nio.file.Paths
import scala.concurrent.Future
import java.io.File
import scala.collection.JavaConverters._
import scala.xml.Node
import java.util.zip.GZIPInputStream
import java.io.FileInputStream
import scala.xml.Elem
import scala.concurrent.blocking
import io.circe.Json
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.FormData
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.apache.jena.rdf.model.Statement
import com.typesafe.scalalogging.LazyLogging
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import java.io.FileOutputStream
import scala.util.Failure

object Main extends App with FailFastCirceSupport with LazyLogging {

  implicit val system = ActorSystem("pubmed-actors")
  implicit val dispatcher = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val SciGraphEndpoint = "http://localhost:9000/scigraph/annotations/entities" //FIXME
  val CurieMapEndpoint = "http://localhost:9000/scigraph/cypher/curies" //FIXME
  val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
  val DCTReferences = DCTerms.references
  val CURIE = "^([^:]*):(.*)$".r

  val dataDir = new File("data")
  val dataFiles = dataDir.listFiles().filter(_.getName.endsWith(".xml.gz")).toList

  final case class Token(id: String)
  final case class EntityAnnotation(token: Token)

  val curieMap: Map[String, String] = {
    val mapFut = for {
      response <- Http().singleRequest(HttpRequest(
        method = HttpMethods.GET,
        headers = List(headers.Accept(`application/json`)),
        uri = CurieMapEndpoint))
      json <- Unmarshal(response.entity).to[Json]
    } yield json.asObject
      .map(_.toMap
        .mapValues(_.asString.getOrElse("")))
      .getOrElse(Map.empty)
    Await.result(mapFut, Duration.Inf)
  }

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

  def annotateWithSciGraph(text: String): Future[Json] = {
    val formData: FormData = FormData(
      "content" -> text,
      "minLength" -> "3",
      "longestOnly" -> "true")
    implicit val marshaller = Marshaller.FormDataMarshaller
    for {
      requestEntity <- Marshal(formData).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(
        method = HttpMethods.POST,
        headers = List(headers.Accept(`application/json`)),
        uri = SciGraphEndpoint,
        entity = requestEntity))
      result <- Unmarshal(response.entity).to[Json]
    } yield result
  }

  def makeTriples(pmid: String, annotations: Json): Set[Statement] = {
    val pmidIRI = ResourceFactory.createResource(s"$PMIDNamespace/$pmid")
    val result = annotations.as[List[EntityAnnotation]].map(_.map { annotation =>
      val iri = annotation.token.id match {
        case CURIE(prefix, local) => curieMap.get(prefix) match {
          case Some(namespace) => namespace + local
          case None => local
        }
        case nonCurie => nonCurie
      }
      ResourceFactory.createStatement(pmidIRI, DCTReferences, ResourceFactory.createResource(iri))
    })
    result match {
      case Right(statements) => statements.toSet
      case Left(error) => {
        logger.error(s"Failed decoding JSON response for PMID: $pmid")
        Set.empty
      }
    }

  }

  val done = Source(dataFiles)
    .mapAsyncUnordered(1) { file =>
      readXMLFromGZip(file).map(file.getName -> _)
    }
    .map {
      case (filename, articleSet) =>
        filename -> extractText(articleSet)
    }
    .flatMapConcat {
      case (filename, articlesWithText) =>
        Source(articlesWithText).mapAsyncUnordered(40) {
          case (pmid, text) =>
	    logger.info(pmid)
            annotateWithSciGraph(text).map(pmid -> _)
        }.fold(Set.empty[Statement]) {
          case (statements, (pmid, json)) => statements ++ makeTriples(pmid, json)
        }.map(filename -> _)
    }.runForeach {
      case (filename, statements) =>
        blocking {
          val outStream = new FileOutputStream(new File(s"$filename.ttl"))
          val model = ModelFactory.createDefaultModel()
          model.add(statements.toSeq.asJava)
          model.write(outStream, "ttl")
          outStream.close()
        }
    }

  Await.ready(done, Duration.Inf).onComplete {
    case Failure(e) =>
      e.printStackTrace()
      system.terminate()
      System.exit(1)
    case _ => system.terminate()
  }

}