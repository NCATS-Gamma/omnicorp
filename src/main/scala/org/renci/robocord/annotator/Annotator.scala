package org.renci.robocord.annotator

import java.io.{File, StringReader}
import java.util.HashMap

import io.scigraph.annotation.{EntityAnnotation, EntityFormatConfiguration, EntityProcessorImpl, EntityRecognizer}
import io.scigraph.neo4j.NodeTransformer
import io.scigraph.vocabulary.{Vocabulary, VocabularyNeo4jImpl}
import org.apache.lucene.queryparser.classic.QueryParserBase
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.prefixcommons.CurieUtil
import scala.collection.JavaConverters._

/** Methods for extracting annotations from text using SciGraph */
class Annotator(neo4jLocation: File) {
  private val curieUtil: CurieUtil         = new CurieUtil(new HashMap())
  private val transformer: NodeTransformer = new NodeTransformer()
  private val graphDB: GraphDatabaseService = new GraphDatabaseFactory().newEmbeddedDatabase(neo4jLocation)
  private val vocabulary: Vocabulary = new VocabularyNeo4jImpl(graphDB, neo4jLocation.getAbsolutePath, curieUtil, transformer)
  private val recognizer = new EntityRecognizer(vocabulary, curieUtil)

  val processor = new EntityProcessorImpl(recognizer)

  def dispose(): Unit = graphDB.shutdown()

  /** Extract annotations from a particular string using SciGraph. */
  def extractAnnotations(str: String): List[EntityAnnotation] = {
    val configBuilder =
      new EntityFormatConfiguration.Builder(new StringReader(QueryParserBase.escape(str)))
        .longestOnly(true)
        .minLength(3)
    processor.annotateEntities(configBuilder.get).asScala.toList
  }
}
