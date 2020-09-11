package org.renci.robocord.annotator

import java.io.{File, StringReader}
import java.util.HashMap

import com.typesafe.scalalogging.LazyLogging
import io.scigraph.annotation.{
  EntityAnnotation,
  EntityFormatConfiguration,
  EntityProcessorImpl,
  EntityRecognizer
}
import io.scigraph.neo4j.NodeTransformer
import io.scigraph.vocabulary.{Vocabulary, VocabularyNeo4jImpl}
import org.apache.lucene.analysis.core.StopAnalyzer
import org.apache.lucene.queryparser.classic.QueryParserBase
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.prefixcommons.CurieUtil

import scala.collection.JavaConverters._

/** Methods for extracting annotations from text using SciGraph */
class Annotator(neo4jLocation: File) extends LazyLogging {
  private val curieUtil: CurieUtil         = new CurieUtil(new HashMap())
  private val transformer: NodeTransformer = new NodeTransformer()
  private val graphDB: GraphDatabaseService =
    new GraphDatabaseFactory().newEmbeddedDatabase(neo4jLocation)
  private val vocabulary: Vocabulary =
    new VocabularyNeo4jImpl(graphDB, neo4jLocation.getAbsolutePath, curieUtil, transformer)
  private val recognizer = new EntityRecognizer(vocabulary, curieUtil)

  val processor = new EntityProcessorImpl(recognizer)

  def dispose(): Unit = graphDB.shutdown()

  /** Extract annotations from a particular string using SciGraph. */
  def extractAnnotations(str: String): (String, List[EntityAnnotation]) = {
    val parsedString = QueryParserBase.escape(str)
    val configBuilder = new EntityFormatConfiguration.Builder(new StringReader(""))
        .longestOnly(true)
        .minLength(3)
    (parsedString, processor.getAnnotations(parsedString, configBuilder.get).asScala.toList)
  }
}

object Annotator {
  /** Remove stop characters from matched string. */
  def removeStopCharacters(matchedString: String): String = {
    matchedString
      // TODO: we currently see "pig\-tailed macaques \(a" -> "pig-tailed macaques \(".
      // Maybe unescape all "\x" to "x"?
      .split("\\b+")                                   // Split at word boundaries.
      .map(_.trim)                                            // Trim all strings.
      .filter(!_.isEmpty)                                     // Remove all empty strings.
      .filter(str => !StopAnalyzer.ENGLISH_STOP_WORDS_SET.contains(str.toLowerCase))    // Filter out stop words.
      .mkString(" ")                                          // Recombine into a single string.
      .replaceAll("^\\W+", "")              // Remove leading non-word characters.
      .replaceAll("\\W+$", "")              // Remove trailing non-word characters.
      .replaceAll("\\\\-", "-")             // Unescape dashes.
      .replaceAll("\\s+-\\s+", "-")         // Remove spaces around dashes.
      .trim                                                   // Make sure we don't have any leading/trailing spaces left over.
  }
}
