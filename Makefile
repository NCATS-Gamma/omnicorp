# Omnicorp executable path.
OMNICORP = ./target/universal/stage/bin/omnicorp

# Maximum memory to use.
MEMORY = 200G

# Number of parallel jobs to start.
PARALLEL = 4

#.PHONY: all
all: output

clean:
	sbt clean
	rm -rf output SciGraph omnicorp-scigraph pubmed-annual-baseline robot robot.jar robocord-data

pubmed-annual-baseline/done:
	mkdir -p pubmed-annual-baseline &&\
	wget -N ftp://ftp.ncbi.nlm.nih.gov/pubmed/baseline/* -P pubmed-annual-baseline &&\
	touch pubmed-annual-baseline/done

SciGraph:
	git clone https://github.com/gaurav/SciGraph.git &&\
	cd SciGraph &&\
	git checkout public-constructors-and-methods &&\
	mvn -B -DskipTests -DskipITs install

robot.jar:
	curl -L -O https://github.com/ontodev/robot/releases/download/v1.4.0/robot.jar

robot: robot.jar
	curl -L -O https://raw.githubusercontent.com/ontodev/robot/master/bin/robot && chmod +x robot

ontologies-merged.ttl: robot ontologies.ofn
	ROBOT_JAVA_ARGS=-Xmx$(MEMORY) ./robot merge -i ontologies.ofn -i manually_added.ttl -o ontologies-merged.ttl

omnicorp-scigraph: ontologies-merged.ttl SciGraph
	rm -rf $@ && cd SciGraph/SciGraph-core &&\
	MAVEN_OPTS="-Xmx$(MEMORY)" mvn exec:java -Dexec.mainClass="io.scigraph.owlapi.loader.BatchOwlLoader" -Dexec.args="-c ../../scigraph.yaml"

$(OMNICORP): SciGraph
	sbt stage

output: $(OMNICORP) pubmed-annual-baseline omnicorp-scigraph
	rm -rf $@ && mkdir -p $@ &&\
	JAVA_OPTS="-Xmx$(MEMORY)" $(OMNICORP) omnicorp-scigraph pubmed-annual-baseline $@ $(PARALLEL)

coursier:
	# These are in the Linux installation instructions as per https://get-coursier.io/docs/cli-overview.html#linux
	# Please create ./coursier as needed on your operating system.
	curl -Lo coursier https://git.io/coursier-cli-linux &&
	chmod +x coursier &&
	./coursier --help

test: coursier output
	JAVA_OPTS="-Xmx$(MEMORY)" ./coursier launch com.ggvaidya:shacli_2.12:0.1-SNAPSHOT -- validate shacl/omnicorp-shapes.ttl output/*.ttl
