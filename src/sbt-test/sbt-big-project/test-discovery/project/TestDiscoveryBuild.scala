import sbt._
import Keys._
import Def.Initialize

import fommil._

/**
 * A simple linear multi-module project of the form
 *
 *   A <- B <- C <- D
 *
 * where D is an Eclipse-style test project for C.
 */
object SimpleBuild extends Build {

  override lazy val settings = super.settings ++ Seq(
    scalaVersion := "2.10.6",
    version := "v1"
  )

  val root = Project("test-discovery", file(".")).settings(
    libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % "test",
    ivyLoggingLevel := UpdateLogging.Quiet,
    fork := true
  ).settings(
    BigProjectSettings.overrideProjectSettings(Compile, Test)
  )

}
