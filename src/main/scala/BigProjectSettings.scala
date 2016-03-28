// Copyright (C) 2015 - 2016 Sam Halliday
// Licence: http://www.apache.org/licenses/LICENSE-2.0
package fommil

import java.nio.file.{Files, Paths}
import java.util.concurrent.ConcurrentHashMap
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import sbt.Scoped.DefinableTask
import sbt._
import Keys._
import IO._
import sbt.inc.Analysis
import sbt.inc.LastModified
import scala.util.Try

/**
 * Publicly exposed keys for settings and tasks that the user may wish
 * to use.
 */
object BigProjectKeys {
  /**
   * The user must tell us when a breaking change has been introduced
   * in a module. It will invalidate the caches of all dependent
   * project.
   */
  val breakingChange = TaskKey[Unit](
    "breakingChange",
    "Inform the build that a breaking change was introduced in this project."
  )

  /**
   * Detects source / resource changes in the Compile configuration
   * and treats their project as breaking changes. Ideal after merging
   * or rebasing to (hopefully) minimise your compile.
   */
  val breakOnChanges = TaskKey[Unit](
    "breakOnChanges",
    "Run breakingChange if sources / resources are more recent than their jar."
  )

  /**
   * Teams that use Eclipse often put tests in separate packages.
   *
   * WORKAROUND: https://bugs.eclipse.org/bugs/show_bug.cgi?id=224708
   */
  val eclipseTestsFor = SettingKey[ProjectReference](
    "eclipseTestsFor",
    "When defined, points to the project that this project is testing."
  )

  /**
   * A version of the last compilable jar is made available, so that
   * developer tools always have some reference to use for indexing
   * purposes. Recall that packageBin is deleted before each compile.
   *
   * Windows users may still experience stale jars as a result of
   * SI-9632 and similar bugs in tooling.
   *
   * Enabled by default, set to None to disable (e.g. to marginally
   * speed up CI compiles and save disk space).
   *
   * ENSIME use:
   *
   * {{{
   * val ensimeLastCompilableJarTask: Def.Initialize[Task[Option[File]]] =
   *   (state, (artifactPath in packageBin), BigProjectKeys.lastCompilableJar).map { (s, jar, lastOpt) =>
   *     BigProjectSettings.createOrUpdateLast(s.log, jar, lastOpt.get)
   *     lastOpt
   *   }
   * }}}
   */
  val lastCompilableJar = TaskKey[Option[File]](
    "lastCompilableJar",
    "Points to a copy of packageBin that is updated when the packageBin is recreated."
  )
}

object BigProjectSettings extends Plugin {
  import BigProjectKeys._

  /**
   * All the existing jars associated to a project, including the
   * transient dependency jars if this is an Eclipse-style test
   * project.
   */
  private def allPackageBins(structure: BuildStructure, log: Logger, proj: ProjectRef): Seq[File] =
    for {
      p <- proj +: ((eclipseTestsFor in proj) get structure.data).toSeq
      configs <- ((ivyConfigurations in p) get structure.data).toSeq
      config <- configs
      /* whisky in the */ jar <- (artifactPath in packageBin in config in p) get structure.data
      if jar.exists()
    } yield jar

  /**
   * Try our best to delete a file that may be referenced by a stale
   * scala-compiler file handle (affects Windows).
   */
  private def deleteLockedFile(log: Logger, file: File): Boolean = {
    log.debug(s"Deleting $file")
    if (file.exists() && !file.delete()) {
      log.debug(s"Failed to delete $file")
      System.gc()
      System.runFinalization()
      System.gc()
      file.delete()
    }
    if (file.exists()) {
      log.error(s"Failed to delete $file")
      false
    } else true
  }

  /**
   * TrackLevel.TrackIfMissing will not invalidate or rebuild jar
   * files if the user explicitly recompiles a project. We delete the
   * packageBin associated to a project when compiling that project so
   * that we never have stale jars.
   */
  private def deletePackageBinTask = (artifactPath in packageBin, state).map { (jar, s) =>
    deleteLockedFile(s.log, jar)
  }

  /**
   * The location of the packageBin, but under a subfolder named "last".
   */
  private def lastCompilableJarTask = (artifactPath in packageBin).map { jar =>
    Option(jar.getParentFile / "last" / jar.getName)
  }

  /**
   * Similar to deletePackageBinTask but works for all configurations
   * of the current project.
   */
  private def deleteAllPackageBinTask = (thisProjectRef, state).map { (proj, s) =>
    val structure = Project.extract(s).structure
    allPackageBins(structure, s.log, proj).foreach { jar =>
      deleteLockedFile(s.log, jar)
    }
  }

  // WORKAROUND https://github.com/sbt/sbt/issues/2417
  implicit class NoMacroTaskSupport[T](val t: TaskKey[T]) extends AnyVal {
    def theTask: SettingKey[Task[T]] = Scoped.scopedSetting(t.scope, t.key)
  }

  // turn T => Task[T]
  def task[T](t: T): Task[T] = Task[T](Info(), Pure(() => t, true))

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
  private def dynamicPackageBinTask: Def.Initialize[Task[File]] = (
    (artifactPath in packageBin),
    lastCompilableJar,
    (streams in packageBin),
    (packageConfiguration in packageBin).theTask
  ).flatMap { (jar, lastOpt, s, configTask) =>
      if (jar.exists()) {
        lastOpt.foreach { last => createOrUpdateLast(s.log, jar, last) }
        task(jar)
      } else configTask.map { c =>
        Package(c, s.cacheDirectory, s.log)
        lastOpt.foreach { last => createOrUpdateLast(s.log, jar, last) }
        jar
      }
    }

  def createOrUpdateLast(log: Logger, jar: File, last: File): Unit =
    if (jar.exists() && jar.lastModified != last.lastModified) {
      log.info(s"backing up $jar to $last")
      deleteLockedFile(log, last)
      IO.copyFile(jar, last, preserveLastModified = true)
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
  private def dynamicTransitiveUpdateTask: Def.Initialize[Task[Seq[UpdateReport]]] =
    (thisProject, transitiveUpdate.theTask).flatMap {
      (proj, transitiveUpdateTask) =>
        val key = LocalProject(proj.id)
        val cached = transitiveUpdateCache.get(key)
        if (cached != null) task(cached)
        else (transitiveUpdateTask).map { calculated =>
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
  private def dynamicDependencyClasspathTask: Def.Initialize[Task[Classpath]] =
    (thisProject, configuration, dependencyClasspath.theTask).flatMap {
      (proj, config, dependencyClasspathTask) =>
        val key = (LocalProject(proj.id), config)
        val cached = dependencyClasspathCache.get(key)
        if (cached != null && cached.forall(_.data.exists())) task(cached)
        else (dependencyClasspathTask).map { calculated =>
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
  def dynamicExportedProductsTask: Def.Initialize[Task[Classpath]] =
    (thisProject, configuration, artifactPath in packageBin, exportedProducts.theTask).flatMap {
      (proj, config, jar, exportedProductsTask) =>
        val key = (LocalProject(proj.id), config)
        val cached = exportedProductsCache.get(key)
        if (jar.exists() && cached != null) task(cached)
        else exportedProductsTask.map { calculated =>
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
  private def dynamicProjectDescriptorsTask: Def.Initialize[Task[Map[ModuleRevisionId, ModuleDescriptor]]] =
    (thisProject, projectDescriptors.theTask).flatMap { (proj, projectDescriptorsTask) =>
      val key = LocalProject(proj.id)
      val cached = projectDescriptorsCache.get(key)
      if (cached != null) task(cached)
      else (projectDescriptorsTask).map { calculated =>
        projectDescriptorsCache.put(key, calculated)
        calculated
      }
    }

  /**
   * Returns the exhaustive set of projects that depend on the given one
   * (not including itself).
   */
  private[fommil] def dependents(structure: BuildStructure, thisProj: ProjectRef): Seq[ProjectRef] = {
    val dependents = {
      for {
        proj <- structure.allProjects
        dep <- proj.dependencies
        resolved <- Project.getProject(dep.project, structure)
      } yield (resolved, proj)
    }.groupBy {
      case (child, parent) => child
    }.map {
      case (child, grouped) => (child, grouped.map(_._2).toSet)
    }

    def deeper(p: ResolvedProject): Set[ResolvedProject] = {
      val deps = dependents.getOrElse(p, Set.empty)
      deps ++ deps.flatMap(deeper)
    }

    // optimised projectRef lookup
    val refs: Map[String, ProjectRef] = structure.allProjectRefs.map { ref =>
      (ref.project, ref)
    }.toMap
    val proj = Project.getProject(thisProj, structure).get
    deeper(proj).map { resolved => refs(resolved.id) }(collection.breakOut)
  }

  private def downstreamAndSelfJars(structure: BuildStructure, log: Logger, proj: ProjectRef): Seq[File] = {
    val downstream = dependents(structure, proj).toSeq
    for {
      p <- (downstream :+ proj)
      jar <- allPackageBins(structure, log, p)
    } yield jar
  }

  /**
   * Deletes all the packageBins of dependent projects.
   */
  def breakingChangeTask: Def.Initialize[Task[Unit]] =
    (state, thisProjectRef).map { (s, proj) =>
      val structure = Project.extract(s).structure
      downstreamAndSelfJars(structure, s.log, proj).foreach { jar =>
        deleteLockedFile(s.log, jar)
      }
    }

  /**
   * Deletes all dependent jars if any inputs are more recent than the
   * oldest output.
   */
  def breakOnChangesTask: Def.Initialize[Task[Unit]] =
    (state, thisProjectRef, sourceDirectories in Compile, resourceDirectories in Compile).map { (s, proj, srcs, ress) =>
      // note, we do not use `sources' or `resources' because they can
      // have transient dependencies on compile.
      val structure = Project.extract(s).structure

      // wasteful that we do this many times when aggregating
      val jars = downstreamAndSelfJars(structure, s.log, proj)

      // this is the expensive bit, we do it exactly as much as we need
      if (jars.nonEmpty) {
        val oldest = jars.map(_.lastModified()).min
        val inputs = for {
          dir <- (srcs ++ ress)
          input <- (dir ** "*").filter(_.isFile).get
        } yield input

        inputs.find(_.lastModified() > oldest).foreach { _ =>
          for {
            jar <- jars
          } deleteLockedFile(s.log, jar)
        }
      }
    }

  /**
   * We want to be sure that this is the last collection of Settings
   * that runs on each project, so we require that the user manually
   * apply these overrides.
   */
  def overrideProjectSettings(configs: Configuration*): Seq[Setting[_]] = Seq(
    exportJars := true,
    forcegc in Global := true, // WORKAROUND https://github.com/sbt/sbt/issues/1223 (MOSTLY USELESS)
    trackInternalDependencies := TrackLevel.TrackIfMissing,
    transitiveUpdate <<= dynamicTransitiveUpdateTask,
    projectDescriptors <<= dynamicProjectDescriptorsTask,
    breakingChange <<= breakingChangeTask,
    breakOnChanges <<= breakOnChangesTask
  ) ++ configs.flatMap { config =>
      inConfig(config)(
        Seq(
          lastCompilableJar <<= lastCompilableJarTask,
          packageBin <<= dynamicPackageBinTask,
          dependencyClasspath <<= dynamicDependencyClasspathTask,
          exportedProducts <<= dynamicExportedProductsTask,
          compile <<= compile dependsOn deletePackageBinTask,
          runMain <<= runMain dependsOn deletePackageBinTask
        ) ++ {
            if (config == Test || config.extendsConfigs.contains(Test)) Seq(
              test <<= test dependsOn deleteAllPackageBinTask,
              definedTests <<= (definedTests, testLoader, state).map { (orig, loader, s) =>
                ClassLoaderHack.release(loader, s.log)
                orig
              },
              executeTests <<= (executeTests, testLoader, state).map { (orig, loader, s) =>
                ClassLoaderHack.release(loader, s.log)
                orig
              }
            )
            else Nil
          }
      )
    }

}

/**
 * WORKAROUND https://github.com/sbt/sbt/issues/2496
 *
 * Could also be used to workaround:
 *
 * - https://issues.scala-lang.org/browse/SI-9632
 * - https://issues.scala-lang.org/browse/SI-9682
 * - https://issues.scala-lang.org/browse/SI-9683
 *
 * The basic problem is that classloaders will retain `JarFile`
 * references and not close them until finalisation runs as part of
 * GC. A basic (and inefficient) workaround is to run GC regularly and
 * in problematic code as per https://github.com/sbt/sbt/issues/1223
 *
 * However, the problem gets *much* worse when running test discovery
 * (and tests) because, for some inexplicable reason, the JVM loses
 * the references to the `JarFile`s and can never run their
 * finalisers, effectively creating immutable jar files on the disk
 * and turning all subsequent compiles into no-ops (on Windows) and
 * merely leaking file handlers (on Linux and OS X), eventually
 * resulting in a kernel error.
 *
 * This workaround touches internal JVM implementation details of Java
 * 7 and may break in Java 8+ AND internal implementation details of
 * sbt's classloaders. If you're on Java 6, you're basically doomed.
 */
object ClassLoaderHack {

  /**
   * Aggressively release resources of the classloader.
   */
  def release(cl: ClassLoader, log: Logger): Unit = {
    cl match {
      case null =>
      case u: java.net.URLClassLoader =>
        // simply calling `URLClassLoader.close()` will leave the
        // classloader in an unusable state and we cannot guarantee
        // that others are not accessing it concurrently.
        //
        // It is not completely clear if these closeable objects are
        // actually causing a problem. The main problem is the
        // URLClassPath.JarLoader, which we address next.
        val closeablesField = classOf[java.net.URLClassLoader].getDeclaredField("closeables")
        closeablesField.setAccessible(true)
        val closeables = closeablesField.get(u).asInstanceOf[java.util.WeakHashMap[java.io.Closeable, Void]]
        closeables synchronized {
          val it = closeables.keySet().iterator()
          while (it.hasNext()) {
            val closeable = it.next()
            closeable.close()
            it.remove()
          }
        }

        // We need to close the URLClassPath.Loaders (specifically the
        // JarLoaders), yet trick the URLClassPath into being
        // re-usable. Several ways to do this:
        //
        //   1. manually close the Loaders (a private static class)
        //   2. close the loaders and then reset the classpath's fields
        //   3. close the classpath and put a new one in the classloader
        //   4. close the classloader and give it a new classpath
        //      (temporarily breaks the classloader)
        //
        // Some of these options are less thread-safe than others.
        //
        // option #3 it is...
        val urlClassPathField = classOf[java.net.URLClassLoader].getDeclaredField("ucp")
        urlClassPathField.setAccessible(true)
        val urlClassPath = urlClassPathField.get(u).asInstanceOf[sun.misc.URLClassPath]
        val replacementUrlClassPath = new sun.misc.URLClassPath(u.getURLs)
        urlClassPathField.set(u, replacementUrlClassPath)
        // fresh, volatile / barrier, calls to the URLClassLoader
        // should not see the old URLClassPath instance, so close it.
        // This has the possibility of closing a live classloader, in
        // which case the only remedy is to re-run the sbt task that
        // failed (or restart sbt).
        urlClassPath.closeLoaders()

        val parentField = classOf[java.lang.ClassLoader].getDeclaredField("parent")
        parentField.setAccessible(true)
        val parent = parentField.get(u).asInstanceOf[ClassLoader]
        release(parent, log)

      case f: sbt.classpath.ClasspathFilter =>
        val parentField = classOf[sbt.classpath.ClasspathFilter].getDeclaredField("parent")
        parentField.setAccessible(true)
        val parent = parentField.get(f).asInstanceOf[ClassLoader]

        val rootField = classOf[sbt.classpath.ClasspathFilter].getDeclaredField("root")
        rootField.setAccessible(true)
        val root = rootField.get(f).asInstanceOf[ClassLoader]

        release(parent, log)
        release(root, log)

      case f: sbt.classpath.DualLoader =>
        val parentAField = classOf[sbt.classpath.DualLoader].getDeclaredField("parentA")
        parentAField.setAccessible(true)
        val parentA = parentAField.get(f).asInstanceOf[ClassLoader]

        val parentBField = classOf[sbt.classpath.DualLoader].getDeclaredField("parentB")
        parentBField.setAccessible(true)
        val parentB = parentBField.get(f).asInstanceOf[ClassLoader]

        release(parentA, log)
        release(parentB, log)

      // not accessible
      // case f: xsbt.boot.BootFilteredLoader =>

      case o =>
        log.debug(s"ClassLoader isn't supported: ${o.getClass}")
    }
  }
}
