package fi.fluidit.gradle.netbeans

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

class NetBeansApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create<NetBeansApplicationExtension>("netbeans", NetBeansApplicationExtension::class.java, project)

        val collectTask = project.tasks.create("collectJars", NetBeansCollectJarsTask::class.java) {
            it.netbeansDirectory = extension.netbeansDirectory
            it.collectDir = extension.collectDir
        }
        addDependsOnTaskInOtherProjects(collectTask, true, "jar", "compile")
        addDependsOnTaskInOtherProjects(collectTask, true, "jar", "runtime")

        val obfuscateTask = project.tasks.create("collectAndObfuscateJars", NetBeansCollectAndObfuscateTask::class.java) {
            it.netbeansDirectory = extension.netbeansDirectory
            it.collectDir = extension.collectDir
        }
        addDependsOnTaskInOtherProjects(obfuscateTask, true, "jar", "compile")
        addDependsOnTaskInOtherProjects(obfuscateTask, true, "jar", "runtime")

        val nbmTask = project.tasks.create("netbeansApplication", NetBeansApplicationTask::class.java) {
            it.brandingToken = extension.brandingToken
            it.etcConfFile = extension.etcConfFile
            it.binDirectory = extension.binDirectory
            it.netbeansDirectory = extension.netbeansDirectory
            it.etcClustersFile = extension.etcClustersFile
            it.brandingDirectory = extension.brandingDirectory
            it.postfix = extension.postfix
            it.collectDir = extension.collectDir
            it.defaultStartLevel = extension.defaultStartLevel
            it.startLevels = extension.startLevels
            it.autoloads = extension.autoloads
            it.eagers = extension.eagers
            it.enabled = extension.enabled
        }
        nbmTask.dependsOn(collectTask)
        project.tasks.getByName("build").dependsOn(nbmTask)

        val zipTask = project.tasks.create("standaloneZip", NetBeansZipTask::class.java) {
            it.brandingToken = extension.brandingToken
            it.postfix = extension.postfix
        }
        zipTask.dependsOn(nbmTask)

        val installerTask = project.tasks.create("installers", NetBeansInstallerTask::class.java) {
            it.brandingToken = extension.brandingToken
            it.installerTitle = extension.installerTitle
            it.antExecutable = extension.antExecutable
            it.postfix = extension.postfix
            it.netbeansDirectory = extension.netbeansDirectory
        }
        installerTask.dependsOn(zipTask)

        addRunAndDebugTasks(project, extension)
    }

    private fun addRunAndDebugTasks(project: Project, extension: NetBeansApplicationExtension) {
        addRunTask(project, extension, "run", false)
        addRunTask(project, extension, "debug", true)
    }

    private fun addRunTask(project: Project, extension: NetBeansApplicationExtension, taskName: String, debug: Boolean) {
        project.tasks.create(taskName, ExecuteTask::class.java) {
            it.dependsOn(project.tasks.getByName("netbeansApplication"))

            it.debug = debug
            it.brandingToken = extension.brandingToken
            it.debugPort = extension.debugPort
            it.userDir = extension.userDir
            it.additionalArguments = extension.additionalArguments
            it.postfix = extension.postfix
        }
    }

    /**
     * Adds a dependency on tasks with the specified name in other projects.  The other projects are determined from
     * project lib dependencies using the specified configuration name. These may be projects this project depends on or
     * projects that depend on this project based on the useDependOn argument.
     *
     * @param task Task to add dependencies to
     * @param useDependedOn if true, add tasks from projects this project depends on, otherwise use projects that depend on this one.
     * @param otherProjectTaskName name of task in other projects
     * @param configurationName name of configuration to use to find the other projects
     */
    private fun addDependsOnTaskInOtherProjects(
        task: Task, useDependedOn: Boolean, otherProjectTaskName: String,
        configurationName: String
    ) {
        val project = task.project
        val configuration = project.configurations.getByName(configurationName)
        task.dependsOn(configuration.getTaskDependencyFromProjectDependency(useDependedOn, otherProjectTaskName))
    }
}