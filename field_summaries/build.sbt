licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))

scalaVersion  := "2.12.8" //Neo4j has a 2.11 Scala dependency but it seems to be only for the cypher parser

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

scalacOptions in Test ++= Seq("-Yrangepos")

mainClass in Compile := Some("FieldSummary")

javaOptions += "-Xmx20G"

fork in Test := true

resolvers += Resolver.mavenLocal

libraryDependencies ++= {
  Seq(
    "org.scala-lang.modules"      %% "scala-xml"              % "1.0.6",
    "com.typesafe.scala-logging"  %% "scala-logging"          % "3.7.1",
    "ch.qos.logback"              %  "logback-classic"        % "1.2.3"
  )
}
