// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0

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

  def simpleProject(name: String): Project = {
    BigProjectTestSupport.createSources(name)
    Project(name, file(name)).settings(
      BigProjectSettings.overrideProjectSettings(Compile, Test),
      BigProjectTestSupport.testInstrumentation(Compile, Test)
    )
  }

  val a = simpleProject("a")
  val b = simpleProject("b") dependsOn(a)
  val c = simpleProject("c") dependsOn(b)
  val d = simpleProject("d") dependsOn(c)
  val e = simpleProject("e") dependsOn(c) settings (
    BigProjectKeys.eclipseTestsFor := Some(c)
  )

  val root = Project("root", file(".")) aggregate (a, b, c, d, e) settings (
    BigProjectSettings.overrideProjectSettings(Compile, Test)
  )
}
