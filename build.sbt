enablePlugins(JavaAppPackaging)

organization  := "org.renci"

name          := "omnicorp"

version       := "0.1-SNAPSHOT"

licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

scalaVersion  := "2.11.11" //Neo4j has a 2.11 Scala dependency

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

scalacOptions in Test ++= Seq("-Yrangepos")

mainClass in Compile := Some("org.renci.chemotext.Main")

javaOptions += "-Xmx20G"

fork in Test := true

resolvers += Resolver.mavenLocal

libraryDependencies ++= {
  Seq(
    "org.backuity.clist"          %% "clist-core"             % "3.2.2",
    "org.backuity.clist"          %% "clist-macros"           % "3.2.2" % "provided",
    "com.typesafe.akka"           %% "akka-stream"            % "2.5.9",
    "org.scala-lang.modules"      %% "scala-xml"              % "1.0.6",
    "io.scigraph"                 %  "scigraph-core"          % "2.1-SNAPSHOT",
    "io.scigraph"                 %  "scigraph-entity"        % "2.1-SNAPSHOT",
    "com.typesafe.scala-logging"  %% "scala-logging"          % "3.7.1",
    "ch.qos.logback"              %  "logback-classic"        % "1.2.3",
    "org.codehaus.groovy"         %  "groovy-all"             % "2.4.6",
    "org.apache.jena"             %  "apache-jena-libs"       % "3.2.0" //pomOnly()
  )
}
