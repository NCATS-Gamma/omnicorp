# Omnicorp executable path.
OMNICORP = ./target/universal/stage/bin/omnicorp

# Maximum memory to use.
MEMORY = 16G

# Number of parallel jobs to start.
PARALLEL = 4

# The date of CORD-19 data to download.
ROBOCORD_DATE="2020-03-13"

.PHONY: all
all: output

clean:
	sbt clean
	rm -rf output SciGraph omnicorp-scigraph pubmed-annual-baseline robot robot.jar robocord-data

pubmed-annual-baseline:
	mkdir -p $@ && cd $@ &&\
	curl --ftp-method singlecwd -O ftp://ftp.ncbi.nlm.nih.gov/pubmed/baseline/pubmed19n0[001-970].xml.gz

SciGraph:
	git clone https://github.com/balhoff/SciGraph.git &&\
	cd SciGraph &&\
	git checkout public-constructors &&\
	mvn -B -DskipTests -DskipITs install

robot.jar:
	curl -L -O https://github.com/ontodev/robot/releases/download/v1.4.0/robot.jar

robot: robot.jar
	curl -L -O https://raw.githubusercontent.com/ontodev/robot/master/bin/robot && chmod +x robot

ontologies-merged.ttl: robot ontologies.ofn
	ROBOT_JAVA_ARGS=-Xmx$(MEMORY) ./robot merge -i ontologies.ofn -o ontologies-merged.ttl

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


# RoboCORD
.PHONY: robocord-download
robocord-download:
	# rm -rf robocord-data
	# wget "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/comm_use_subset.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/noncomm_use_subset.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/pmc_custom_license.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/biorxiv_medrxiv.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/all_sources_metadata_${ROBOCORD_DATE}.csv" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/all_sources_metadata_${ROBOCORD_DATE}.readme" -P robocord-data

robocord-data: robocord-download
	cd robocord-data; for f in *.tar.gz; do echo Uncompressing "$$f"; tar zxvf $$f; done; cd -
	touch robocord-data

robocord-output: robocord-data SciGraph
	JAVA_OPTS="-Xmx$(MEMORY)" sbt "runMain org.renci.robocord.RoboCORD --metadata robocord-data/all_sources_metadata_${ROBOCORD_DATE}.csv robocord-data"
