import fommil.BigProjectSettings

scalaVersion in ThisBuild := "2.11.8"

val a = project.settings(
  BigProjectSettings.overrideProjectSettings(Compile, Test)
)

val b = project.dependsOn(a).settings(
  BigProjectSettings.overrideProjectSettings(Compile, Test)
)
