name := "airando2"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  jdbc,
  anorm,
  cache
)     

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.2.0" % "test"

libraryDependencies += "org.scalatest" % "scalatest_2.10" % "2.2.0" % "test"

libraryDependencies += "joda-time" % "joda-time" % "2.0"

libraryDependencies += "org.mongodb" %% "casbah" % "2.7.3"

// testOptions in Test := Seq(Tests.Filter(s => s.startsWith("Local")))

play.Project.playScalaSettings

requireJs += "main.js"

requireJsShim += "main.js"
