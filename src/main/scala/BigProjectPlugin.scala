// Copyright (C) 2015 Sam Halliday
// License: Apache-2.0
package fommil

import java.util.concurrent.ConcurrentHashMap
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import sbt._
import Keys._

object BigProjectKeys {
  /**
   * The user must tell us when a breaking change has been introduced
   * in a module. It will invalidate the caches of all dependent
   * project.
   *
   * TODO: we could potentially automatically infer this using the
   *       migration manager (if it is fast enough).
   */
  val breakingChangeTask = TaskKey[Unit](
    "breakingChange",
    "Inform the build that a breaking change was introduced in this project."
  )

  /**
   * NOT IMPLEMENTED YET
   *
   * WORKAROUND: https://bugs.eclipse.org/bugs/show_bug.cgi?id=224708
   *
   * Teams that use Eclipse often put tests in separate packages.
   */
  val eclipseTestsFor = SettingKey[Option[ProjectReference]](
    "eclipseTestsFor",
    "When defined, points to the project that this project is testing."
  )

}

/*
 * All references to `.value` in a Task mean that the task is
 * aggressively invoked as a dependency to this task. Lazily call
 * dependent tasks from Dynamic Tasks:
 *
 *   http://www.scala-sbt.org/0.13/docs/Tasks.html
 */
object BigProjectPlugin extends Plugin {
  import Workaround2348._
  import BigProjectKeys._

  /**
   * packageBin causes traversals of dependency projects.
   *
   * Caching must be evicted for a project when:
   *
   * - anything (e.g. source, config, packageBin) changes
   *
   * which we implement by deleting the packageBinFile on every
   * compile.
   *
   * However, dependent project caches must only be evicted if a
   * dependency introduced a breaking change.
   *
   * We trust the developer to inform us of breaking API changes
   * manually using the breakingChange task.
   *
   * We use the file's existence as the cache.
   */
  private def dynamicPackageBinTask: Def.Initialize[Task[File]] = Def.taskDyn {
    val jar = packageBinFile.value
    if (jar.exists()) Def.task {
      jar
    }
    else Def.task {
      val s = (streams in packageBin).value
      val c = (packageConfiguration in packageBin).value
      Package(c, s.cacheDirectory, s.log)
      jar
    }
  }

  private def deletePackageBinTask: Def.Initialize[Task[Unit]] = Def.task {
    if (packageBinFile.value.exists())
      packageBinFile.value.delete()
  }

  /**
   * transitiveUpdate causes traversals of dependency projects
   *
   * Cache must be evicted for a project and all its dependents when:
   *
   * - changes to the ivy definitions
   * - any inputs to an update phase are changed (changes to generated inputs?)
   */
  private val transitiveUpdateCache = new ConcurrentHashMap[ProjectReference, Seq[UpdateReport]]()
  private def dynamicTransitiveUpdateTask: Def.Initialize[Task[Seq[UpdateReport]]] = Def.taskDyn {
    // doesn't have a Configuration, always project-level
    val key = LocalProject(thisProject.value.id)
    val cached = transitiveUpdateCache.get(key)

    // note, must be in the dynamic task
    val make = new ScopeFilter.Make {}
    val selectDeps = ScopeFilter(make.inDependencies(ThisProject, includeRoot = false))

    if (cached != null) Def.task {
      cached
    }
    else Def.task {
      val allUpdates = update.?.all(selectDeps).value
      val calculated = allUpdates.flatten ++ globalPluginUpdate.?.value
      transitiveUpdateCache.put(key, calculated)
      calculated
    }
  }

  /**
   * dependencyClasspath causes traversals of dependency projects.
   *
   * Cache must be evicted for a project and all its dependents when:
   *
   * - anything (e.g. source, config) changes and the packageBin is not recreated
   *
   * we implement invalidation by checking that all files in the
   * cached classpath exist, if any are missing, we do the work.
   */
  private val dependencyClasspathCache = new ConcurrentHashMap[(ProjectReference, Configuration), Classpath]()
  private def dynamicDependencyClasspathTask: Def.Initialize[Task[Classpath]] = Def.taskDyn {
    val key = (LocalProject(thisProject.value.id), configuration.value)
    val cached = dependencyClasspathCache.get(key)

    if (cached != null && cached.forall(_.data.exists())) Def.task {
      cached
    }
    else Def.task {
      val calculated = internalDependencyClasspath.value ++ externalDependencyClasspath.value
      dependencyClasspathCache.put(key, calculated)
      calculated
    }
  }

  /**
   * Gets invoked when the dependencyClasspath cache misses. We use
   * this to avoid invoking compile:compile unless the jar file is
   * missing for ThisProject.
   */
  val exportedProductsCache = new ConcurrentHashMap[(ProjectReference, Configuration), Classpath]()
  def dynamicExportedProductsTask: Def.Initialize[Task[Classpath]] = Def.taskDyn {
    val key = (LocalProject(thisProject.value.id), configuration.value)
    val jar = packageBinFile.value
    val cached = exportedProductsCache.get(key)

    if (jar.exists() && cached != null) Def.task {
      cached
    }
    else Def.task {
      val calculated = (Classpaths.exportProductsTask).value
      exportedProductsCache.put(key, calculated)
      calculated
    }
  }

  /**
   * projectDescriptors causes traversals of dependency projects.
   *
   * Cache must be evicted for a project and all its dependents when:
   *
   * - any project changes, all dependent project's caches must be cleared
   */
  private val projectDescriptorsCache = new ConcurrentHashMap[ProjectReference, Map[ModuleRevisionId, ModuleDescriptor]]()
  private def dynamicProjectDescriptorsTask: Def.Initialize[Task[Map[ModuleRevisionId, ModuleDescriptor]]] = Def.taskDyn {
    // doesn't have a Configuration, always project-level
    val key = LocalProject(thisProject.value.id)
    val cached = projectDescriptorsCache.get(key)

    if (cached != null) Def.task {
      cached
    }
    else Def.task {
      val calculated = Classpaths.depMap.value
      projectDescriptorsCache.put(key, calculated)
      calculated
    }
  }

  /**
   * We want to be sure that this is the last collection of Settings
   * that runs on each project, so we require that the user manually
   * apply these overrides.
   */
  def overrideProjectSettings(configs: Configuration*): Seq[Setting[_]] = Seq(
    // TODO: if the resolution cache is used, can we do away with some of these?
    // TODO: how much of the caching pattern can be abstracted?
    // TODO: can we set original impls aside and call them in the dynamic?
    //       (instead of re-implementing, which is fragile)
    exportJars := true,
    transitiveUpdate := dynamicTransitiveUpdateTask.value,
    projectDescriptors := dynamicProjectDescriptorsTask.value
  ) ++ configs.flatMap { config =>
      inConfig(config)(
        Seq(
          packageBinFile := packageBinFileSetting.value,
          packageBin := dynamicPackageBinTask.value,
          dependencyClasspath := dynamicDependencyClasspathTask.value,
          exportedProducts := dynamicExportedProductsTask.value,
          compile <<= compile dependsOn deletePackageBinTask
        )
      )
    }

}

/**
 * WORKAROUND: https://github.com/sbt/sbt/issues/2348
 *
 * Re-computes the name of the packageBin without invoking compilation.
 */
object Workaround2348 {
  val packageBinFile = SettingKey[File](
    "packageBinFile",
    "Cheap way to obtain the location of the packageBin file."
  )

  def packageBinFileSetting = Def.setting {
    val append = configuration.value match {
      case Compile => ""
      case Test    => "-tests"
      case _       => "-" + configuration.value.name
    }
    crossTarget.value / s"${projectID.value.name}_${scalaBinaryVersion.value}-${projectID.value.revision}$append.jar"
  }
}
