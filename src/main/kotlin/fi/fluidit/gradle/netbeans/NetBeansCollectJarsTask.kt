package fi.fluidit.gradle.netbeans

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.tasks.*
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import java.io.File


open class NetBeansCollectJarsTask : DefaultTask() {
    @InputDirectory
    open var netbeansDirectory = project.objects.property(File::class.java)

    @Optional
    @OutputDirectory
    open var collectDir = project.objects.property(File::class.java)

    @InputFiles
    fun getArtifacts() = project.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.map { it.file }.toSet()

    @TaskAction
    fun collectJars(incrementalTaskInputs: IncrementalTaskInputs) {
        /*
         * Fetch the list of changed and removed input files from the incremental task inputs
         */
        val outOfDateFiles = HashSet<String>()
        val removedFiles = HashSet<File>()
        if (incrementalTaskInputs.isIncremental) {
            incrementalTaskInputs.outOfDate { outOfDateFiles.add(it.file.name) }
            incrementalTaskInputs.removed { removedFiles.add(it.file) }
        }
        // Go through the artifacts
        getArtifacts().forEach { it.exists() }

        // Fetch the extension object (utilize the jarInfoContainer from there)
        val extension = project.extensions.findByType(NetBeansApplicationExtension::class.java)
        val jarInfoContainer = extension?.jarInfoContainer ?: JarInfoContainer()

        // Directory for the final outputs
        val output = collectDir.getOrElse(File(project.buildDir, "cluster-jars"))
        if (!output.isDirectory)
            output.mkdirs()

        // Clear the removed files from the output to ensure no non-stale files creep in the output
        // TODO find better way - there should be a way to map the original file to the actual output file name after the fact
        //for (file in removedFiles)
        //    File(output, file.name).delete()
        if (removedFiles.isNotEmpty())
            for (file in output.listFiles())
                file.delete()

        // Platform source dir for checking if the modules are already there
        val platformDir = File(File(netbeansDirectory.get(), "platform"), "modules")

        // Process all runtime and implementation artifacts
        val artifacts = try {
                            project.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.toSet()
                        } catch (e: java.lang.IllegalStateException) {
                            emptySet<ResolvedArtifact>()
                        }
        for (artifact in artifacts) {
            val jar = artifact.file
            if (!jar.isFile)
                continue

            // Process the info from the manifest, and if the module type is right, collect it
            val jarInfo = jarInfoContainer.getOrAdd(jar)
            if (jarInfo.manifest.isOsgiBundle || jarInfo.manifest.isNetBeansModule) {
                val moduleName = jarInfo.manifest.module
                val dashedModuleName = moduleName!!.replace(".", "-")
                val target = File(output, "$dashedModuleName.jar")

                // Don't collect the NetBeans platform dependencies
                if (File(platformDir, "$dashedModuleName.jar").exists() || (!jar.name.startsWith("org-netbeans-modules-") && (jar.name.startsWith("org-openide-") || jar.name.startsWith("org-netbeans-"))))
                    continue

                // Test if obfuscating is actually needed: a) Gradle says the file is out of date,
                // b) the target file doesn't exist or c) the target is older than source file.
                else if (outOfDateFiles.contains(jar.name) || !target.exists() || target.lastModified() < jar.lastModified()) {
                    project.logger.info("Installing $artifact")

                    artifact.file.copyTo(target, true)
                    target.setLastModified(artifact.file.lastModified())
                }
            }
        }
    }
}