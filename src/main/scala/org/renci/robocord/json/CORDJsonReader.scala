package org.renci.robocord.json

import java.io.File

import com.typesafe.scalalogging.Logger
import io.circe._
import io.circe.parser._
import io.circe.generic.auto._
import io.circe.syntax._
import io.scigraph.annotation.EntityAnnotation
import org.renci.robocord.annotator.Annotator

import scala.io.Source

case class Location(
  postCode: Option[String],
  settlement: Option[String],
  region: Option[String],
  country: Option[String]
)

case class Affiliation(
  laboratory: Option[String],
  institution: Option[String],
  location: Option[Location]
)

case class Author(
  first: String,
  middle: Seq[String],
  last: String,
  suffix: String,
  affiliation: Option[Affiliation],
  email: Option[String]
)

case class ArticleMetadata(
  title: String,
  authors: Seq[Author]
)

case class ArticleRef(
  start: Option[Int],
  end: Option[Int],
  text: Option[String],
  ref_id: Option[String]
)

case class ArticleAbstract(
  section: String,
  text: String,
  cite_spans: Seq[ArticleRef],
  ref_spans: Seq[ArticleRef]
)

case class ArticleBodyText(
  section: String,
  text: String,
  cite_spans: Seq[ArticleRef],
  ref_spans: Seq[ArticleRef],
  eq_spans: Option[Seq[ArticleRef]]
)

case class ArticleRefEntry(
  text: String,
  `type`: String
)

case class ArticleBibEntry(
  ref_id: String,
  title: String,
  authors: Seq[Author],
  year: Option[Int],
  venue: String,
  volume: String,
  issn: String,
  pages: String,
  other_ids: Map[String, Seq[String]]
)

case class Article(
  paper_id: String,
  metadata: ArticleMetadata,
  `abstract`: Seq[ArticleAbstract],
  body_text: Seq[ArticleBodyText],
  bib_entries: Map[String, ArticleBibEntry],
  ref_entries: Map[String, ArticleRefEntry],
  back_matter: Seq[ArticleBodyText]
)

class CORDArticleWrapper(article: Article) {
  val sha1 = article.paper_id
  val titleText = article.metadata.title
  val abstractText = article.`abstract`.map(_.text).mkString("\n")
  val bodyText = article.body_text.map(_.text).mkString("\n")
  val refText = article.ref_entries.values.map(_.text).mkString("\n")
  val backText = article.back_matter.map(_.text).mkString("\n")

  /** Returns the full text of this article */
  def fullText: String = s"$titleText\n$abstractText\n$bodyText\n$refText\n$backText"

  def getSciGraphAnnotations(ann: Annotator): Seq[EntityAnnotation] = ann.extractAnnotations(fullText)._2
}

object CORDJsonReader {
  def wrapFileOrDir(file: File, logger: Logger, shasToLoadLowercase: Set[String]): Seq[CORDArticleWrapper] = {
    if (file.isDirectory) file.listFiles.flatMap(wrapFileOrDir(_, logger, shasToLoadLowercase))
    else if (file.getName.toLowerCase.endsWith(".json") && shasToLoadLowercase.contains(file.getName.toLowerCase.replace(".json", ""))) {
      val source = Source.fromFile(file)
      val content = source.mkString
      source.close
      decode[Article](content) match {
        case Right(article) => {
          Seq(new CORDArticleWrapper(article))
        }
        case Left(ex) => {
          logger.error(s"COULD NOT PARSE $file: $ex")
          Seq.empty[CORDArticleWrapper]
        }
      }
    } else {
      Seq.empty[CORDArticleWrapper]
    }
  }
}
