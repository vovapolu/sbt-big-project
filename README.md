[![Build Status](http://fommil.com/api/badges/fommil/sbt-big-project/status.svg)](http://fommil.com/fommil/sbt-big-project)

The workflow introduced by this plugin gives *huge* time saving benefits to developers in extremely large projects (hundreds of `Project`s), where you may often make non-ABI changes in a middle layer and then run your application or tests in the top layer.

Various forms of caching are used to reduce the lag-time and overhead of basic tasks such as `compile` or `test`.

# Install

This plugin uses a source-only distribution mechanism to make it easy to install in corporate environments.

You must be using `sbt` version 0.13.10-RC1 or later.

To install, copy the `BigProjectSettings.scala` from the latest tagged release into your `project` directory and then edit your `Project`s to have the `BigProjectKeys.overrideProjectSettings` applied for each of your configurations that the `Project` supports. e.g.

```scala
val a = project settings (BigProjectSettings.overrideProjectSettings(Compile, Test))
val b = project dependsOn(a) settings (BigProjectSettings.overrideProjectSettings(Compile, Test, IntegrationTests))
val c = project dependsOn(b) settings (BigProjectSettings.overrideProjectSettings(Compile, Test))

val root = Project("root", file(".")) aggregate (a, b, c) settings (
  BigProjectSettings.overrideProjectSettings(Compile, Test)
)
```

This plugin is unforgiving of bad builds (e.g. an incorrect aggregating `rootProject`). If you need help fixing your build before using this plugin, ask in [gitter.im/sbt/sbt](https://gitter.im/sbt/sbt) or [Stack Overflow](http://stackoverflow.com/questions/tagged/sbt).

Some customisations are made available in the `BigProjectKeys` object, such as support for Eclipse-style test projects. As the site installer of `sbt-big-project`, you are expected to read the documentation in the source code, and to familiarise yourself with the accompanying tests in the `src/sbt-test` folder.

# Use

The biggest workflow change that `sbt-big-project` introduces is that it forces all projects to compile to jar, instead of just class directories, and **sbt will treat each project independently**. If a jar exists for a `Project`, sbt will treat it as a library dependency and will not perform incremental compilation on that project unless explicitly requested.

Let us consider the example project above, consisting of three `Project`s: `a`, `b` and `c`. If you make source code changes within `a`, and type `a/compile` then the next time you run your app or tests in `c`, then `b` will not be recompiled.

## `proj/breakingChange`

If you knowingly make breaking ABI changes to `a`, you should tell sbt by typing `a/breakingChange` which will flag all dependents of `a` as requiring a full recompile.

## `breakOnChanges`

There is a convenient global task, `breakOnChanges`, which will scan for changes to your source and resource files and automatically declare all the changes to be breaking.

This can be adopted as part of your workflow if you rebase your source code and wish to minimise the number of `Project`s that you recompile, whilst remaining completely safe from ABI changes that your colleagues may have introduced. (Of course, if the rebase changes core libraries, you will still incur a full recompile).
