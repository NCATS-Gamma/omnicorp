PUBMED_DIR := pubmed-annual-baseline

.PHONY: all
all: create_directory download_pubmed SciGraph SciGraph-core

.PHONY: create_directory
create_directory: $(PUBMED_DIR) 

$(PUBMED_DIR)/.:
	mkdir -p $@

.PHONY: download_pubmed
download_pubmed: create_directory
	(cd pubmed-annual-baseline &&\
	curl --ftp-method singlecwd -O ftp://ftp.ncbi.nlm.nih.gov/pubmed/baseline/pubmed19n0[001-970].xml.gz)

.PHONY: SciGraph
SciGraph:
	git clone https://github.com/balhoff/SciGraph.git
	(cd SciGraph &&\
	git checkout public-constructors &&\
	mvn -DskipTests -DskipITs install)

ontologies-merged.ttl: ontologies.ofn
	robot merge -vvv -i ontologies.ofn -o ontologies-merged.ttl

.PHONY: SciGraph-core
SciGraph-core: ontologies-merged.ttl | SciGraph
	(cd SciGraph/SciGraph-core &&\
	mvn exec:java -DXmx8G -Dexec.mainClass="io.scigraph.owlapi.loader.BatchOwlLoader" -Dexec.args="-c ../../scigraph.yaml")
