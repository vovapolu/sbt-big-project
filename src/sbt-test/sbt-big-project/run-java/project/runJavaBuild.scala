// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0

import sbt._
import Keys._
import Def.Initialize

import fommil._

object runJavaBuild extends Build {

  override lazy val settings = super.settings ++ Seq(
    scalaVersion := "2.10.6",
    version := "v1"
  )

  val root = Project("run-java", file(".")).settings(
    ivyLoggingLevel := UpdateLogging.Quiet,
    fork := true,
    javaOptions += "-Dtesting_default_key1=default_value1",
    envVars += ("testing_default_key2", "defaule_value2")
  ).settings(
    BigProjectSettings.overrideProjectSettings(Compile, Test)
  )
}