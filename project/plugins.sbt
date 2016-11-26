scalacOptions ++= Seq("-unchecked", "-deprecation")
ivyLoggingLevel := UpdateLogging.Quiet
addSbtPlugin("com.fommil" % "sbt-sensible" % "1.0.7")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "1.5.1")
libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value
