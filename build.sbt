inThisBuild(
  Seq(
    sbtPlugin := true,
    organization := "com.fommil",
    scalaVersion := "2.10.6"
  )
)

sonatypeGithub := ("fommil", "sbt-big-project")
licenses := Seq(Apache2)

name := "sbt-big-project"

scriptedSettings
scriptedBufferLog := false
scriptedLaunchOpts := Seq(
  "-Xss2m", "-Xmx1g",
  "-Dplugin.version=" + version.value,
  // WORKAROUND https://github.com/sbt/sbt/issues/2568
  "-javaagent:" + (baseDirectory.value / "class-monkey-1.7.0-assembly.jar")
)

// BLOCKED on understanding how to migrate dependsOn
scalacOptions -= "-Xfatal-warnings"
