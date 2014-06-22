name := "airando2"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache
)     

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.2.0" % "test"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.2.0" % "test"

play.Project.playScalaSettings
