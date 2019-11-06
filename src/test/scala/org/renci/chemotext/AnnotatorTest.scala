package org.renci.chemotext

import utest._

object AnnotatorTest extends TestSuite {
  val tests = Tests {
    test("An example entry from pubmed19n0013.xml") {
      val exampleEntry = <PubmedArticleSet>
        <PubmedArticle>
          <MedlineCitation Status="MEDLINE" Owner="NLM">
            <PMID Version="1">368090</PMID>
            <DateCompleted>
              <Year>1979</Year>
              <Month>04</Month>
              <Day>28</Day>
            </DateCompleted>
            <DateRevised>
              <Year>2009</Year>
              <Month>11</Month>
              <Day>11</Day>
            </DateRevised>
            <Article PubModel="Print">
              <Journal>
                <ISSN IssnType="Print">1945-1954</ISSN>
                <JournalIssue CitedMedium="Print">
                  <Volume>46</Volume>
                  <Issue>1</Issue>
                  <PubDate>
                    <MedlineDate>1979 Jan-Feb</MedlineDate>
                  </PubDate>
                </JournalIssue>
                <Title>ASDC journal of dentistry for children</Title>
                <ISOAbbreviation>ASDC J Dent Child</ISOAbbreviation>
              </Journal>
              <ArticleTitle>Mechanical pretreatments and etching of primary-tooth enamel.</ArticleTitle>
              <Pagination>
                <MedlinePgn>43-9</MedlinePgn>
              </Pagination>
              <AuthorList CompleteYN="Y">
                <Author ValidYN="Y">
                  <LastName>Bozalis</LastName>
                  <ForeName>W G</ForeName>
                  <Initials>WG</Initials>
                </Author>
                <Author ValidYN="Y">
                  <LastName>Marshall</LastName>
                  <ForeName>G W</ForeName>
                  <Initials>GW</Initials>
                  <Suffix>Jr</Suffix>
                </Author>
                <Author ValidYN="Y">
                  <LastName>Cooley</LastName>
                  <ForeName>R O</ForeName>
                  <Initials>RO</Initials>
                </Author>
              </AuthorList>
              <Language>eng</Language>
              <PublicationTypeList>
                <PublicationType UI="D003160">Comparative Study</PublicationType>
                <PublicationType UI="D016428">Journal Article</PublicationType>
              </PublicationTypeList>
            </Article>
            <MedlineJournalInfo>
              <Country>United States</Country>
              <MedlineTA>ASDC J Dent Child</MedlineTA>
              <NlmUniqueID>0146172</NlmUniqueID>
              <ISSNLinking>1945-1954</ISSNLinking>
            </MedlineJournalInfo>
            <ChemicalList>
              <Chemical>
                <RegistryNumber>0</RegistryNumber>
                <NameOfSubstance UI="D003188">Composite Resins</NameOfSubstance>
              </Chemical>
            </ChemicalList>
            <CitationSubset>D</CitationSubset>
            <CitationSubset>IM</CitationSubset>
            <MeshHeadingList>
              <MeshHeading>
                <DescriptorName UI="D000134" MajorTopicYN="Y">Acid Etching, Dental</DescriptorName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D000268" MajorTopicYN="N">Adhesiveness</DescriptorName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D003188" MajorTopicYN="N">Composite Resins</DescriptorName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D001840" MajorTopicYN="Y">Dental Bonding</DescriptorName>
                <QualifierName UI="Q000379" MajorTopicYN="Y">methods</QualifierName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D003743" MajorTopicYN="N">Dental Enamel</DescriptorName>
                <QualifierName UI="Q000648" MajorTopicYN="Y">ultrastructure</QualifierName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D006801" MajorTopicYN="N">Humans</DescriptorName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D013314" MajorTopicYN="N">Stress, Mechanical</DescriptorName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D014072" MajorTopicYN="N">Tooth Abrasion</DescriptorName>
                <QualifierName UI="Q000473" MajorTopicYN="N">pathology</QualifierName>
              </MeshHeading>
              <MeshHeading>
                <DescriptorName UI="D014094" MajorTopicYN="N">Tooth, Deciduous</DescriptorName>
                <QualifierName UI="Q000648" MajorTopicYN="Y">ultrastructure</QualifierName>
              </MeshHeading>
            </MeshHeadingList>
          </MedlineCitation>
          <PubmedData>
            <History>
              <PubMedPubDate PubStatus="pubmed">
                <Year>1979</Year>
                <Month>1</Month>
                <Day>1</Day>
              </PubMedPubDate>
              <PubMedPubDate PubStatus="medline">
                <Year>2001</Year>
                <Month>3</Month>
                <Day>28</Day>
                <Hour>10</Hour>
                <Minute>1</Minute>
              </PubMedPubDate>
              <PubMedPubDate PubStatus="entrez">
                <Year>1979</Year>
                <Month>1</Month>
                <Day>1</Day>
                <Hour>0</Hour>
                <Minute>0</Minute>
              </PubMedPubDate>
            </History>
            <PublicationStatus>ppublish</PublicationStatus>
            <ArticleIdList>
              <ArticleId IdType="pubmed">368090</ArticleId>
            </ArticleIdList>
          </PubmedData>
        </PubmedArticle>
      </PubmedArticleSet>

      val articleInfos = TextExtractor.extractArticleInfos(exampleEntry)

      // We only have one article.
      assert(articleInfos.size == 1)

      // Check whether all fields in the first article are as expected.
      val firstArticle = articleInfos.head
      assert(firstArticle.pmid == "368090")
      assert(firstArticle.info == "Mechanical pretreatments and etching of primary-tooth enamel.  Dental Enamel ultrastructure Dental Bonding methods Composite Resins Tooth Abrasion pathology Humans Tooth, Deciduous ultrastructure Adhesiveness Acid Etching, Dental Stress, Mechanical ")
      assert(firstArticle.meshTermIDs == Set("Q000648", "D013314", "D001840", "D003188", "D006801", "D014072", "D003743", "D014094", "Q000473", "Q000379", "D000268", "D000134"))
      assert(firstArticle.years == Set()) // We don't support MedlineDate entries yet.
    }
  }
}
