addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.5.1")

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

// sbt, STFU...
ivyLoggingLevel := UpdateLogging.Quiet
