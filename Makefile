# Omnicorp executable path.
OMNICORP = ./target/universal/stage/bin/omnicorp

# Maximum memory to use.
MEMORY = 16G

# Number of parallel jobs to start.
PARALLEL = 4

# The date of CORD-19 data to download.
ROBOCORD_DATE="2020-04-17"

.PHONY: all
all: output

clean:
	sbt clean
	rm -rf output SciGraph omnicorp-scigraph pubmed-annual-baseline robot robot.jar robocord-data

pubmed-annual-baseline/done:
	mkdir -p pubmed-annual-baseline &&\
	wget -N ftp://ftp.ncbi.nlm.nih.gov/pubmed/baseline/* -P pubmed-annual-baseline &&\
	touch pubmed-annual-baseline/done

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

# RoboCORD
.PHONY: robocord-download robocord-output robocord-test
robocord-download:
	# robocord-data is intended to be a symlink to robocord-datas/${ROBOCORD_DATE}, so that it is updated automatically. 
	# If robocord-data doesn't exist or is a symlink, we update it
	# automatically. Otherwise (i.e. if it's an existing directory),
	# we only update the files already in it.
	@if [ ! -e robocord-data ] || [ -L robocord-data ]; then \
		rm robocord-data; \
		mkdir -p robocord-datas/${ROBOCORD_DATE}; \
		ln -s robocord-datas/${ROBOCORD_DATE} robocord-data; \
	fi
	
	# Download CORD-19 into robocord-data.
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/comm_use_subset.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/noncomm_use_subset.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/custom_license.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/biorxiv_medrxiv.tar.gz" -P robocord-data
	wget -N "https://ai2-semanticscholar-cord-19.s3-us-west-2.amazonaws.com/${ROBOCORD_DATE}/metadata.csv" -P robocord-data

robocord-data: robocord-download
	cd robocord-data; for f in *.tar.gz; do echo Uncompressing "$$f"; tar zxvf $$f; done; cd -
	touch robocord-data

robocord-output: robocord-data SciGraph
	JAVA_OPTS="-Xmx$(MEMORY)" sbt "runMain org.renci.robocord.RoboCORD --metadata robocord-data/metadata.csv robocord-data"

robocord-test: SciGraph
	@if [ ! -e robocord-output ] || [ -L robocord-output ]; then \
		rm robocord-output; \
		mkdir -p robocord-outputs/${ROBOCORD_DATE}; \
		ln -s robocord-outputs/${ROBOCORD_DATE} robocord-output; \
	fi

	JAVA_OPTS="-Xmx$(MEMORY)" sbt "runMain org.renci.robocord.RoboCORD --metadata robocord-data/metadata.csv --current-chunk 4 --total-chunks 1000 robocord-data"
	JAVA_OPTS="-Xmx$(MEMORY)" sbt "runMain org.renci.robocord.RoboCORD --metadata robocord-data/metadata.csv --current-chunk 5 --total-chunks 1000 robocord-data"
	JAVA_OPTS="-Xmx$(MEMORY)" sbt "runMain org.renci.robocord.RoboCORD --metadata robocord-data/metadata.csv --current-chunk 6 --total-chunks 1000 robocord-data"
	JAVA_OPTS="-Xmx$(MEMORY)" sbt "runMain org.renci.robocord.RoboCORD --metadata robocord-data/metadata.csv --current-chunk 437 --total-chunks 1000 robocord-data"
