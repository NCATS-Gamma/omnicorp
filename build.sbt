enablePlugins(JavaAppPackaging)

organization  := "org.renci"

name          := "omnicorp"

version       := "0.1-SNAPSHOT"

licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

// Scalac options.

scalaVersion  := "2.12.10" //Neo4j has a 2.11 Scala dependency but it seems to be only for the cypher parser

scalacOptions ++= Seq("-unchecked", "-deprecation", "-encoding", "utf8", "-Ywarn-unused")

addCompilerPlugin(scalafixSemanticdb)
scalacOptions in Test ++= Seq("-Yrangepos")

mainClass in Compile := Some("org.renci.chemotext.Main")

// Code formatting and linting tools.

wartremoverWarnings ++= Warts.unsafe

// Running and command line options.

javaOptions += "-Xmx20G"

fork := true

testFrameworks += new TestFramework("utest.runner.Framework")

// Dependency information.

resolvers += Resolver.mavenLocal

libraryDependencies ++= {
  Seq(
    "org.backuity.clist"          %% "clist-core"             % "3.2.2",
    "org.backuity.clist"          %% "clist-macros"           % "3.2.2" % "provided",
    "com.typesafe.akka"           %% "akka-stream"            % "2.5.9",
    "org.scala-lang.modules"      %% "scala-xml"              % "1.0.6",
    "io.scigraph"                 %  "scigraph-core"          % "2.1-SNAPSHOT",
    "io.scigraph"                 %  "scigraph-entity"        % "2.1-SNAPSHOT",
    "org.codehaus.groovy"         %  "groovy-all"             % "2.4.6",
    "org.apache.jena"             %  "apache-jena-libs"       % "3.13.1",

    // Testing
    "com.lihaoyi"                 %% "utest"                  % "0.7.1" % "test",

    // Logging
    "com.typesafe.scala-logging"  %% "scala-logging"          % "3.7.1",
    "ch.qos.logback"              %  "logback-classic"        % "1.2.3",
    "com.outr"                    %% "scribe"                 % "2.7.3",

    // Command line argument parsing.
    "org.rogach"                  %% "scallop"                % "3.3.2",

    // JSON parsing.
    "io.circe"                    %% "circe-core"             % "0.13.0",
    "io.circe"                    %% "circe-generic"          % "0.13.0",
    "io.circe"                    %% "circe-parser"           % "0.13.0",

    // CSV parsing.
    "com.github.tototoshi"        %% "scala-csv"              % "1.3.6"
  )
}
