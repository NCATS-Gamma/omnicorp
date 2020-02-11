package org.renci.chemotext

import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.{TemporalAccessor, ChronoField}

import scala.util.{Failure, Success, Try}
import scala.xml.{Node, NodeSeq}

/** An object containing code for working with PubMed articles. */
object PubMedArticleWrapper {
  /** Convert dates in PubMed articles into TemporalAccessors wrapping those dates. */
  def parseDate(date: Node): Try[TemporalAccessor] = {
    // Extract the Year/Month/Day fields. Note that the month requires additional
    // processing, since it may be a month name ("Apr") or a number ("4").

    // Parse the year and day-of-year, if possible.
    val maybeYear: Try[Int]       = Try((date \\ "Year").text.toInt)
    val maybeDayOfMonth: Try[Int] = Try((date \\ "Day").text.toInt)

    maybeYear map { year =>
      // We can't parse a month-only date: to parse it, we need to include the year as well.
      val monthStr = (date \\ "Month").text
      val maybeMonth: Try[Int] = Try(monthStr.toInt) orElse (Try(
        YearMonth
          .parse(s"$year-$monthStr", DateTimeFormatter.ofPattern("uuuu-MMM"))
          .getMonth
          .getValue
      ))

      maybeMonth map { month =>
        maybeDayOfMonth map { day =>
          Success(LocalDate.of(year, month, day))
        } getOrElse (Success(YearMonth.of(year, month)))
      } getOrElse ({
        // What if we have a maybeYear and a maybeDayOfMonth, but no maybeMonth?
        // That suggests that we didn't read the month correctly!
        if (maybeYear.isSuccess && maybeDayOfMonth.isSuccess && maybeMonth.isFailure)
          Failure(new RuntimeException(s"Could not extract month from node: $date"))
        else Success(Year.of(year))
      })
    } getOrElse {
      // No year? That's probably because we have a MedlineDate instead.
      // MedlineDates have different forms (e.g. "1989 Dec-1999 Jan", "2000 Spring", "2000 Dec 23-30").
      // For now, we check to see if it starts with four digits, suggesting an year.
      // See https://www.nlm.nih.gov/bsd/licensee/elements_descriptions.html#medlinedate for more details.
      val medlineDateYearMatcher = """^.*?\b(\d{4})\b.*$""".r
      val medlineDate            = (date \\ "MedlineDate").text
      medlineDate match {
        case medlineDateYearMatcher(year) => Success(Year.of(year.toInt))
        case _                            => Failure(new IllegalArgumentException(s"Could not parse XML node as date: $date"))
      }
    }
  }
}

/** A companion class for wrapping a PubMed article from an XML dump. */
class PubMedArticleWrapper(val article: Node) {
  // The following methods extract particular fields from the wrapped PubMed article.
  val pmid: String                 = (article \ "MedlineCitation" \ "PMID").text
  val title: String                = (article \\ "ArticleTitle").map(_.text).mkString(" ")
  val abstractText: String         = (article \\ "AbstractText").map(_.text).mkString(" ")
  val journalNodes: NodeSeq        = (article \\ "Article" \ "Journal")
  val journalVolume: String        = (journalNodes \ "JournalIssue" \ "Volume").map(_.text).mkString(", ")
  val journalIssue: String         = (journalNodes \ "JournalIssue" \ "Issue").map(_.text).mkString(", ")
  val journalTitle: String         = (journalNodes \ "Title").map(_.text).mkString(", ")
  val journalAbbr: String          = (journalNodes \ "ISOAbbreviation").map(_.text).mkString(", ")
  val journalISSNNodes: NodeSeq    = (journalNodes \ "ISSN")
  val pubDatesAsNodes: NodeSeq     = article \\ "PubDate"
  val articleDatesAsNodes: NodeSeq = article \\ "ArticleDate"
  val revisedDatesAsNodes: NodeSeq = article \\ "DateRevised"
  val medlinePgnNodes: NodeSeq     = article \\ "Pagination" \ "MedlinePgn"
  val medlinePagination: String    = medlinePgnNodes.map(_.text).mkString(", ")
  val pubDatesParseResults: Seq[Try[TemporalAccessor]] =
    pubDatesAsNodes map PubMedArticleWrapper.parseDate
  val articleDatesParseResults: Seq[Try[TemporalAccessor]] =
    articleDatesAsNodes map PubMedArticleWrapper.parseDate
  val revisedDatesParseResults: Seq[Try[TemporalAccessor]] =
    revisedDatesAsNodes map PubMedArticleWrapper.parseDate
  val pubDates: Seq[TemporalAccessor]     = pubDatesParseResults.map(_.toOption).flatten
  val pubDateYears: Seq[Int]              = pubDates.map(_.get(ChronoField.YEAR))
  val articleDates: Seq[TemporalAccessor] = articleDatesParseResults.map(_.toOption).flatten
  val revisedDates: Seq[TemporalAccessor] = revisedDatesParseResults.map(_.toOption).flatten

  // Extract journal metadata.
  val articleIdInfo: Map[String, Seq[String]] = (article \\ "ArticleIdList" \ "ArticleId")
    .groupBy(                      // Group on the basis of attribute name, by
      _.attribute("IdType")        // getting all values for attributes named "IdType";
        .getOrElse(Seq("unknown")) // if none are present, default to "unknown", but
        .mkString(" & ")           // if there are multiple, combine them with ' & '.
    )
    .mapValues(_.map(_.text))
  val dois: Seq[String] = articleIdInfo.getOrElse("doi", Seq())
  val authors: Seq[AuthorWrapper] = (article \\ "AuthorList").headOption
    .map(authorListNode => {
      val authorList = authorListNode.nonEmptyChildren.map(new AuthorWrapper(_))
      if (authorListNode.attribute("CompleteYN").map(_.text).mkString(", ") == "N")
        (authorList :+ AuthorWrapper.ET_AL)
      else authorList
    })
    .getOrElse(Seq())

  // Extract gene symbols and MeSH headings.
  val geneSymbols: String = (article \\ "GeneSymbol").map(_.text).mkString(" ")
  val (meshTermIDs, meshLabels) = (article \\ "MeshHeading").map { mh =>
    val (dMeshIds, dMeshLabels) =
      (mh \ "DescriptorName")
        .map({ mesh =>
          ((mesh \ "@UI").text, mesh.text)
        })
        .unzip
    val (qMeshIds, qMeshLabels) =
      (mh \ "QualifierName")
        .map({ mesh =>
          ((mesh \ "@UI").text, mesh.text)
        })
        .unzip
    (dMeshIds ++ qMeshIds, (dMeshLabels ++ qMeshLabels).mkString(" "))
  }.unzip
  val (meshSubstanceIDs, meshSubstanceLabels) = (article \\ "NameOfSubstance")
    .map(substance => ((substance \ "@UI").text, substance.text))
    .unzip
  val allMeshTermIDs: Set[String] = meshTermIDs.flatten.toSet ++ meshSubstanceIDs
  val allMeshLabels: Set[String]  = meshLabels.toSet ++ meshSubstanceLabels

  // Represent this PubMedArticleWrapper as a string containing all the useful information.
  val asString: String = s"$title $abstractText ${allMeshLabels.mkString(" ")} $geneSymbols"

  // Display properties of this PubMedArticleWrapper for debugging.
  override val toString: String =
    s"PMID ${pmid} (${pubDates}): ${asString} (MeSH: ${allMeshTermIDs})"

  // Generate an IRI for this PubMedArticleWrapper.
  val iriAsString: String = {
    val PMIDNamespace = "https://www.ncbi.nlm.nih.gov/pubmed"
    s"$PMIDNamespace/$pmid"
  }
}
