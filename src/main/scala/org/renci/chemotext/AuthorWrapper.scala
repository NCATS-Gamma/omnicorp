package org.renci.chemotext

import java.io.{File, FileInputStream, FileOutputStream, StringReader}
import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.{TemporalAccessor, ChronoField}
import java.util.HashMap
import java.util.zip.GZIPInputStream

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import com.typesafe.scalalogging.LazyLogging
import io.scigraph.annotation.{
  EntityAnnotation,
  EntityFormatConfiguration,
  EntityProcessorImpl,
  EntityRecognizer
}
import io.scigraph.neo4j.NodeTransformer
import io.scigraph.vocabulary.{Vocabulary, VocabularyNeo4jImpl}
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.graph
import org.apache.jena.rdf.model.{ModelFactory, Property, Resource, ResourceFactory, Statement}
import org.apache.jena.riot.Lang
import org.apache.jena.riot.system.{StreamRDFOps, StreamRDFWriter}
import org.apache.jena.vocabulary.{DCTerms, RDF}
import org.apache.jena.sparql.vocabulary.FOAF
import org.apache.lucene.queryparser.classic.QueryParserBase
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.prefixcommons.CurieUtil

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, NodeSeq}

/** Author name management. */
class AuthorWrapper(node: Node) {
  val isSpellingCorrect = ((node \ "ValidYN").text == "Y")
  val collectiveName    = (node \ "CollectiveName").text
  val lastName          = (node \ "LastName").text
  val foreName          = (node \ "ForeName").text
  val suffix            = (node \ "Suffix").text
  val initials          = (node \ "Initials").text
  // TODO: add support for <AffiliationInfo>
  // TODO: add support for <EqualContrib>

  // Support for identifiers.
  val identifier =
    (node \ "Identifier").map(id => (id.attribute("Source").map(_.text).mkString(", ") -> id.text))
  val orcIds: Seq[String] = identifier.filter(_._1 == "ORCID").map(_._2)

  // FOAF uses foaf:givenName and foaf:familyName.
  val givenName: String = foreName
  val familyName: String = {
    if (suffix.isEmpty) lastName else s"$lastName $suffix"
  }
  val name: String = if (!collectiveName.isEmpty) collectiveName else s"$givenName $familyName"
  val shortName: String =
    if (!collectiveName.isEmpty) collectiveName
    else {
      if (suffix.isEmpty) {
        if (initials.isEmpty) lastName else s"$lastName $initials"
      } else {
        if (initials.isEmpty) s"$lastName $suffix" else s"$lastName $initials $suffix"
      }
    }
}

object AuthorWrapper {
  val ET_AL: AuthorWrapper = new AuthorWrapper(
    <Author><CollectiveName>et al</CollectiveName></Author>
  )
}
