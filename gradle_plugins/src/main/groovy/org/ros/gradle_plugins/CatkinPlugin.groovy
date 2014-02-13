package org.ros.gradle_plugins;

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.StopActionException

import org.gradle.api.*;

/*
 * Provides catkin information to the gradle build, defining properties:
 *
 * - project.catkin.pkg : information about this package
 * - project.catkin.workspaces : list of Strings
 * - project.catkin.tree.generate() : create the pkgs dictionary
 * - project.catkin.tree.pkgs : dictionary of CatkinPackage objects
 *
 * The latter can be iterated over for information:
 *
 * project.catkin.tree.pkgs.each { pair ->
 *   pkg = pair.value
 *   println pkg.name
 *   println pkg.version
 *   pkg.dependencies.each { d ->
 *     println d
 *   }
 *   // filtered list of *_msg dependencies.
 *   pkg.messageDependencies().each { d ->
 *     println d
 *   }
 * }
 *
 * Use this only once in the root of a multi-project gradle build - it will
 * only generate the properties once and share them this way.
 */
class CatkinPlugin implements Plugin<Project> {
	def void apply(Project project) {
	  project.extensions.create("catkin", CatkinPluginExtension)

    def workspaces = System.getenv("ROS_PACKAGE_PATH")
    if (workspaces == null) {
      project.catkin.workspaces = []
    } else {
      project.catkin.workspaces = workspaces.tokenize(":")
    }

    project.catkin.tree = new CatkinPackages(project, project.catkin.workspaces)

    def packageXml = project.file("package.xml")
    if (!packageXml.exists()) {
      throw new StopActionException("Missing package.xml for project: ${project}")
    }
    project.catkin.pkg = new CatkinPackage(packageXml)

    project.task("catkinPackageInfo") << {
      println "Catkin Workspaces: ${project.catkin.workspaces}"
      println "Catkin Packages: ${project.catkin.tree.pkgs.collect({ it.value })}"
    }
  }
}

class CatkinPluginExtension {
  CatkinPackage pkg
  List<String> workspaces
  CatkinPackages tree
}

class CatkinPackages {
  def Map<String, CatkinPackage> pkgs
  def List<String> workspaces
  def Project project

  def CatkinPackages(Project project, List<String> workspaces) {
    this.project = project
    this.workspaces = workspaces
    pkgs = [:]
  }

  def generate() {
    if (pkgs.size() == 0) {
      workspaces.each { workspace ->
        def manifestTree = project.fileTree(dir: workspace, include: "**/package.xml")
        manifestTree.each {
          def pkg = new CatkinPackage(it)
          if (pkgs.containsKey(pkg.name)) {
            if (pkgs[pkg.name].version < pkg.version) {
              println("Replacing " + pkg.name + " version " + pkgs[pkg.name].version +
                      " with version " + pkg.version + ".")
              pkgs[pkg.name] = pkg
            }
          } else {
            pkgs.put(pkg.name, pkg)
          }
        }
      }
    }
  }
}

class CatkinPackage {
  def name
  def version
  def dependencies

  def CatkinPackage(File packageXmlFilename) {
    println "Loading " + packageXmlFilename
    def packageXml = new XmlParser().parse(packageXmlFilename)
    name = packageXml.name.text()
    version = packageXml.version.text()
    dependencies = packageXml.build_depend.collect({ it.text() })
  }

  String toString() { "${name} ${version} ${dependencies}" }

  /*
   * Find and annotate a list of package package dependencies.
   * Useful for message artifact generation).
   *
   * @return List<String> : dependencies (package name strings)
   */
  Set<String> messageDependencies(Project project, Collection<String> dependencies) {
    Set<String> result = [];
    dependencies.each {
      if (project.catkin.tree.pkgs.containsKey(it)) {
        if (it.contains("_msgs") || it.contains("_srvs")) {
          result.add(it)
        }
        result.addAll(messageDependencies(project, project.catkin.tree.pkgs[it].dependencies))
      }
    }
    return result
  }

  void generateMessageArtifact(Project project) {
    project.version = version
    project.dependencies.add("compile", "org.ros.rosjava_bootstrap:message_generation:[0.2,0.3)")
    messageDependencies(project, dependencies).each {
      project.dependencies.add("compile", project.dependencies.project(path: ":${it}"))
    }
    def generatedSourcesDir = "${project.buildDir}/generated-src"
    def generateSourcesTask = project.tasks.create("generateSources", JavaExec)
    generateSourcesTask.description = "Generate sources for ${name}"
    generateSourcesTask.outputs.dir(project.file(generatedSourcesDir))
    generateSourcesTask.args = [generatedSourcesDir, name]
    generateSourcesTask.classpath = project.configurations.compile
    generateSourcesTask.main = "org.ros.internal.message.GenerateInterfaces"
    project.tasks.compileJava.source generateSourcesTask.outputs.files
  }

  void generateUnofficialMessageArtifact(Project project) {
    /* Couple of constraints here:
       1) maven group forced to org.ros.rosjava_messages to that all message artifact
        dependencies are easily found.
       2) Open ended dependency range (takes the latest in ROS_PACKAGE_PATH) since we
        don"t know the artifact versions the user really wants.
    */
    project.version = version
    project.group = "org.ros.rosjava_messages"
    project.dependencies.add("compile", "org.ros.rosjava_bootstrap:message_generation:[0.2,0.3)")
    messageDependencies(project, dependencies).each { d ->
      project.dependencies.add("compile", "org.ros.rosjava_messages:" + d + ":[0.1,)")
    }
    def generatedSourcesDir = "${project.buildDir}/generated-src"
    def generateSourcesTask = project.tasks.create("generateSources", JavaExec)
    generateSourcesTask.description = "Generate sources for " + name
    generateSourcesTask.outputs.dir(project.file(generatedSourcesDir))
    generateSourcesTask.args = new ArrayList<String>([generatedSourcesDir, name])
    generateSourcesTask.classpath = project.configurations.runtime
    generateSourcesTask.main = "org.ros.internal.message.GenerateInterfaces"
    project.tasks.compileJava.source generateSourcesTask.outputs.files
  }

  /*
   * Hack to work around for rosjava_test_msgs - look in a subfolder for the
   * msgs and name the artifact by the subfolder name/version.
   */
  void generateMessageArtifactInSubFolder(Project project, String subfolderName, List<String> dependencies) {
    // project.version = version use the subfolder"s project version
    project.dependencies.add("compile", "org.ros.rosjava_bootstrap:message_generation:[0.2,0.3)")
    dependencies.each { d ->
      project.dependencies.add("compile", project.dependencies.project(path: ":" + d))
    }
    def generatedSourcesDir = "${project.buildDir}/generated-src"
    def generateSourcesTask = project.tasks.create("generateSources", JavaExec)
    generateSourcesTask.description = "Generate sources for " + name + "/" + subfolderName
    generateSourcesTask.outputs.dir(project.file(generatedSourcesDir))
    generateSourcesTask.args = new ArrayList<String>([generatedSourcesDir, subfolderName])
    generateSourcesTask.classpath = project.configurations.runtime
    generateSourcesTask.main = "org.ros.internal.message.GenerateInterfaces"
    project.tasks.compileJava.source generateSourcesTask.outputs.files
  }
}

