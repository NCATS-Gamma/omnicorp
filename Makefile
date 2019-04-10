OMNICORP = ./target/universal/stage/bin/omnicorp

.PHONY: all
all: omnicorp-scigraph

pubmed-annual-baseline:
	mkdir -p $@ && cd $@ &&\
	curl --ftp-method singlecwd -O ftp://ftp.ncbi.nlm.nih.gov/pubmed/baseline/pubmed19n0[001-970].xml.gz

SciGraph:
	git clone https://github.com/balhoff/SciGraph.git &&\
	cd SciGraph &&\
	git checkout public-constructors &&\
	mvn -DskipTests -DskipITs install

robot.jar:
	curl -L -O https://github.com/ontodev/robot/releases/download/v1.4.0/robot.jar

robot: robot.jar
	curl -L -O https://raw.githubusercontent.com/ontodev/robot/master/bin/robot && chmod +x robot

ontologies-merged.ttl: robot ontologies.ofn
	ROBOT_JAVA_ARGS=-Xmx8G ./robot merge -i ontologies.ofn -o ontologies-merged.ttl

omnicorp-scigraph: ontologies-merged.ttl SciGraph
	cd SciGraph/SciGraph-core &&\
	mvn exec:java -DXmx8G -Dexec.mainClass="io.scigraph.owlapi.loader.BatchOwlLoader" -Dexec.args="-c ../../scigraph.yaml"

$(OMNICORP):
	sbt stage

output: $(OMNICORP) pubmed-annual-baseline omnicorp-scigraph
	mkdir -p $@ &&\
	JAVA_OPTS=-Xmx80G $(OMNICORP) omnicorp-scigraph pubmed-annual-baseline $@ 20
