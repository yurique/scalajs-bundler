package scalajsbundler

import org.scalajs.core.tools.io.{FileVirtualJSFile, VirtualJSFile}
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import sbt.Keys._
import sbt._

object ScalaJSBundlerPlugin extends AutoPlugin {

  override lazy val requires = ScalaJSPlugin

  // Exported keys
  object autoImport {

    val npmUpdate = taskKey[Unit]("Fetch NPM dependencies")

    val npmDependencies = settingKey[Seq[(String, String)]]("NPM dependencies (libraries that your program uses)")

    val npmDevDependencies = settingKey[Seq[(String, String)]]("NPM dev dependencies (libraries that the build uses)")

    val webpack = taskKey[Seq[File]]("Bundle the output of a Scala.js stage using webpack")

    val webpackConfigFile = settingKey[Option[File]]("Configuration file to use with webpack")

    val webpackEntries = taskKey[Seq[(String, File)]]("Webpack entry bundles")

    val webpackEmitSourceMaps = settingKey[Boolean]("Whether webpack should emit source maps at all")

    val enableReloadWorkflow = settingKey[Boolean]("Whether to enable the reload workflow for fastOptJS")

  }

  val scalaJSBundlerLauncher = taskKey[Launcher]("Launcher generated by scalajs-bundler")

  val scalaJSBundlerConfigFiles = taskKey[ConfigFiles]("Writes the config files")

  val scalaJSBundlerManifest = taskKey[File]("Writes the NPM_DEPENDENCIES file")

  import autoImport._

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(

    scalaJSModuleKind := ModuleKind.CommonJSModule,

    version in webpack := "1.13",

    webpackConfigFile := None,

    (products in Compile) := (products in Compile).dependsOn(scalaJSBundlerManifest).value,

    scalaJSBundlerManifest :=
      NpmDependencies.writeManifest(
        NpmDependencies(
          (npmDependencies in Compile).value.to[List],
          (npmDependencies in Test).value.to[List],
          (npmDevDependencies in Compile).value.to[List],
          (npmDevDependencies in Test).value.to[List]
        ),
        (classDirectory in Compile).value
      ),

    enableReloadWorkflow := true

  ) ++
    inConfig(Compile)(perConfigSettings) ++
    inConfig(Test)(perConfigSettings ++ testSettings)

  private lazy val perConfigSettings: Seq[Def.Setting[_]] =
    Seq(
      loadedJSEnv := loadedJSEnv.dependsOn(npmUpdate in fastOptJS).value,
      npmDependencies := Seq.empty,
      npmDevDependencies := Seq.empty
    ) ++
    perScalaJSStageSettings(fastOptJS) ++
    perScalaJSStageSettings(fullOptJS) ++
    Seq(
      webpack in fullOptJS := webpackTask(fullOptJS).value,
      webpack in fastOptJS := Def.taskDyn {
        if (enableReloadWorkflow.value) ReloadWorkflowTasks.webpackTask(fastOptJS)
        else webpackTask(fastOptJS)
      }.value
    )

  private lazy val testSettings: Seq[Setting[_]] =
    Seq(
      npmDependencies ++= (npmDependencies in Compile).value,
      npmDevDependencies ++= (npmDevDependencies in Compile).value
    )

  private def perScalaJSStageSettings(stage: TaskKey[Attributed[File]]): Seq[Def.Setting[_]] = Seq(

    npmUpdate in stage := Def.task {
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value
      val jsResources = scalaJSNativeLibraries.value.data

      val cachedActionFunction =
        FileFunction.cached(
          streams.value.cacheDirectory / "npm-update",
          inStyle = FilesInfo.hash
        ) { _ =>
          Commands.npmUpdate(targetDir, log)
          jsResources.foreach { resource =>
            IO.write(targetDir / resource.relativePath, resource.content)
          }
          Set.empty
        }

      cachedActionFunction(Set(targetDir /  "package.json"))
      ()
    }.dependsOn(scalaJSBundlerConfigFiles in stage).value,

    webpackEntries in stage := {
      val launcherFile = (scalaJSBundlerLauncher in stage).value.file
      val stageFile = stage.value.data
      val name = stageFile.name.stripSuffix(".js")
      Seq(name -> launcherFile)
    },

    scalaJSLauncher in stage := {
      val launcher = (scalaJSBundlerLauncher in stage).value
      Attributed[VirtualJSFile](FileVirtualJSFile(launcher.file))(
        AttributeMap.empty.put(name.key, launcher.mainClass)
      )
    },

    scalaJSBundlerLauncher in stage :=
      Launcher.write(
        (crossTarget in stage).value,
        stage.value.data,
        (mainClass in (scalaJSLauncher in stage)).value.getOrElse(sys.error("No main class detected"))
      ),

    scalaJSBundlerConfigFiles in stage :=
      ConfigFiles.writeConfigFiles(
        streams.value.log,
        (crossTarget in stage).value,
        (version in webpack).value,
        (webpackConfigFile in stage).value,
        (webpackEntries in stage).value,
        (webpackEmitSourceMaps in stage).value,
        fullClasspath.value,
        npmDependencies.value,
        npmDevDependencies.value,
        configuration.value
      ),

    webpackEmitSourceMaps in stage := (emitSourceMaps in stage).value,

    relativeSourceMaps in stage := (webpackEmitSourceMaps in stage).value

  )

  def webpackTask(stage: TaskKey[Attributed[File]]): Def.Initialize[Task[Seq[File]]] =
    Def.task {
      val log = streams.value.log
      val targetDir = (crossTarget in stage).value
      val configFiles = (scalaJSBundlerConfigFiles in stage).value
      val entries = (webpackEntries in stage).value

      val cachedActionFunction =
        FileFunction.cached(
          streams.value.cacheDirectory / s"${stage.key.label}-webpack",
          inStyle = FilesInfo.hash
        ) { in =>
          Commands.bundle(targetDir, log)
          configFiles.output.to[Set] // TODO Support custom webpack config file (the output may be overridden by users)
        }
      cachedActionFunction(Set(
        configFiles.webpackConfig,
        configFiles.packageJson
      ) ++
        (webpackConfigFile in stage).value.map(Set(_)).getOrElse(Set.empty) ++
        entries.map(_._2).to[Set] + stage.value.data).to[Seq] // Note: the entries should be enough, excepted that they currently are launchers, which do not change even if the scalajs stage output changes
    }.dependsOn(npmUpdate in stage)

}
