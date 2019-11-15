package org.renci.chemotext

import java.io.File
import java.util.HashMap

import io.scigraph.annotation.{EntityProcessorImpl, EntityRecognizer}
import io.scigraph.neo4j.NodeTransformer
import io.scigraph.vocabulary.{Vocabulary, VocabularyNeo4jImpl}
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.prefixcommons.CurieUtil

class Annotator(neo4jLocation: String) {
  private val curieUtil: CurieUtil         = new CurieUtil(new HashMap())
  private val transformer: NodeTransformer = new NodeTransformer()
  private val graph: GraphDatabaseService =
    new GraphDatabaseFactory().newEmbeddedDatabase(new File(neo4jLocation))
  private val vocabulary: Vocabulary =
    new VocabularyNeo4jImpl(graph, neo4jLocation, curieUtil, transformer)
  private val recognizer = new EntityRecognizer(vocabulary, curieUtil)

  val processor = new EntityProcessorImpl(recognizer)

  def dispose(): Unit = graph.shutdown()
}
