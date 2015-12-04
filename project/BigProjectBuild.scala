import sbt._
import Keys._
import com.typesafe.sbt.SbtScalariform
import scalariform.formatter.preferences._
import SbtScalariform.ScalariformKeys
import ScriptedPlugin._

object BigProjectBuild extends Build with SonatypeSupport {

  override val settings = super.settings ++ Seq(
    sbtPlugin := true,
    organization := "com.github.fommil",
    version := "1.0.0-SNAPSHOT",
    scalaVersion := "2.10.6",
    ivyLoggingLevel := UpdateLogging.Quiet,
    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8", "-target:jvm-1.6", "-feature", "-deprecation",
      "-Xfatal-warnings",
      "-language:postfixOps", "-language:implicitConversions"
    )
  ) ++ sonatype("fommil", "sbt-big-project")

  lazy val root = (project in file(".")).enablePlugins(SbtScalariform).
    settings(scriptedSettings).
    settings(
      name := "sbt-big-project",
      ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignSingleLineCaseStatements, true),
      scriptedLaunchOpts := Seq("-Dplugin.version=" + version.value),
      scriptedBufferLog := false
    )

}
