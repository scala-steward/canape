name := "canape"

organization := "net.rfc1149"

version := "0.0.8-SNAPSHOT"

scalaVersion := "2.11.7"

resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.1",
  "com.typesafe.akka" %% "akka-stream-experimental" % "2.0-M2",
  "com.typesafe.akka" %% "akka-http-experimental" % "2.0-M2",
  "com.typesafe.play" %% "play-json" % "2.4.3",
  "net.ceedubs" %% "ficus" % "1.1.2",
  "org.specs2" %% "specs2-core" % "3.6.4" % "test"
)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

fork in Test := true

publishTo := {
  val path = "/home/sam/rfc1149.net/data/ivy2/" + (if (version.value.trim.endsWith("SNAPSHOT")) "snapshots/" else "releases")
  Some(Resolver.ssh("rfc1149 ivy releases", "rfc1149.net", path) as "sam" withPermissions("0644"))
}
