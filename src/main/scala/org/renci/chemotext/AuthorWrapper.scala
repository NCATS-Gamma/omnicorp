package org.renci.chemotext

import scala.xml.Node
import scala.collection.immutable

/** Author name management. */
class AuthorWrapper(node: Node) {
  val isSpellingCorrect      = ((node \ "ValidYN").text == "Y")
  val collectiveName: String = (node \ "CollectiveName").text
  val lastName: String       = (node \ "LastName").text
  val foreName: String       = (node \ "ForeName").text
  val suffix: String         = (node \ "Suffix").text
  val initials: String       = (node \ "Initials").text
  // TODO: add support for <AffiliationInfo>
  // TODO: add support for <EqualContrib>

  // Support for identifiers.
  val identifier: immutable.Seq[(String, String)] =
    (node \ "Identifier").map(id => (id.attribute("Source").map(_.text).mkString(", ") -> id.text))
  val orcIds: Seq[String] = identifier
    .filter(_._1 == "ORCID")
    .map(_._2)
    .map(_.replaceAll("[\\s+\\-]", ""))
    .map(_.replaceAll("^https?:\\/\\/(?:www.)?orcid.org\\/", ""))
    .map(_.replaceAll("(.{4})(?!$)", "$1-"))
    .map("https://orcid.org/" + _.trim)

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
