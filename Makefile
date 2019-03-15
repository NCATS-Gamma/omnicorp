PUBMED_DIR := pubmed-annual-baseline

.PHONY: all
all: create_directory download_pubmed SciGraph SciGraph-core

.PHONY: create_directory
create_directory:
	(mkdir -p $(PUBMED_DIR))


.PHONY: download_pubmed
download_pubmed: create_directory
	(cd pubmed-annual-baseline &&\
	curl --ftp-method singlecwd -O ftp://ftp.ncbi.nlm.nih.gov/pubmed/baseline/pubmed19n0[001-970].xml.gz)

.PHONY: SciGraph
SciGraph:
	rm -rf ./SciGraph
	git clone https://github.com/balhoff/SciGraph.git
	(cd SciGraph &&\
	git checkout public-constructors &&\
	mvn -DskipTests -DskipITs install)

.PHONY: robot.jar
robot.jar:
	wget https://github.com/ontodev/robot/releases/download/v1.4.0/robot.jar

ontologies-merged.ttl: robot.jar | ontologies.ofn
	java -jar ./robot.jar merge -vvv -i ontologies.ofn -o ontologies-merged.ttl

.PHONY: SciGraph-core
SciGraph-core: ontologies-merged.ttl | SciGraph
	(cd SciGraph/SciGraph-core &&\
	mvn exec:java -DXmx8G -Dexec.mainClass="io.scigraph.owlapi.loader.BatchOwlLoader" -Dexec.args="-c ../../scigraph.yaml")
