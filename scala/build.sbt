name := "reflex"

version := "0.1"

scalaVersion := "2.13.1"

resolvers += "Maven Central" at "https://repo1.maven.org/maven2/"

libraryDependencies += "com.github.scopt" %% "scopt"     % "4.0.0-RC2"
libraryDependencies += "dk.brics"         %  "automaton" % "1.12-1"
libraryDependencies += "com.michaelpollmeier" %% "gremlin-scala" % "3.4.7.2"
libraryDependencies += "org.apache.tinkerpop" % "tinkergraph-gremlin" % "3.4.8"
// Kill warnings
libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.30"

val circeVersion = "0.12.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % "test"

mainClass in assembly := some("io.github.thebabush.reflex.Main")
assemblyOutputPath in assembly := file("./jreflex.jar")
