// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository 
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.2.1")

resolvers += Resolver.url("IVI", url("file:///home/belka/.ivy2/local/"))(Resolver.ivyStylePatterns)

addSbtPlugin("net.koofr" % "play2-sprites" % "0.4.0-SNAPSHOT")

