# This Dockerfile sets up everything you need to run Omnicorp.

# The tag here refers to: <JDK version>_<sbt version>_<Scala version>
FROM sbtscala/scala-sbt:eclipse-temurin-alpine-22_36_1.10.2_2.13.15

# Configuration options:
# - ${USERNAME} is the username to run as.
ARG USERNAME=omnicorp
# - ${ROOT} is where Omnicorp source code will be copied.
ARG ROOT=/home/${USERNAME}
# - ${CORES} is the default number of cores to use.
ARG CORES=4

# Install prerequisites.
RUN apk upgrade --no-cache

# Even though our image has scala and sbt, it doesn't have mvn.
RUN apk add --no-cache maven

# Our pipeline is based on Make.
RUN apk add --no-cache make

# Some programs to help with running jobs and copying files to Hatteras.
RUN apk add --no-cache vim
RUN apk add --no-cache screen
RUN apk add --no-cache rsync

# Create a non-root-user.
RUN adduser --home ${ROOT} --uid 1000 ${USERNAME} --disabled-password
RUN mkdir -p ${ROOT}
WORKDIR ${ROOT}
USER ${USERNAME}

# Copy over the src files.
COPY --chown=${USERNAME} Makefile ${ROOT}/Makefile
COPY --chown=${USERNAME} ./src ${ROOT}/src

# Change the path to point to the Maven location.
RUN export PATH=${PATH}:${JAVA_HOME}/bin

# It takes a while to download and install SciGraph, so we might as well let Docker handle that.
RUN make SciGraph

# We can set up the entrypoint to run `make all`, but we probably don't need that.
