package fi.fluidit.gradle.netbeans

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.*
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.ArrayList


open class NetBeansCollectAndObfuscateTask : DefaultTask() {
    @InputDirectory
    open var netbeansDirectory = project.objects.property(File::class.java)

    @Optional
    @OutputDirectory
    open var collectDir = project.objects.property(File::class.java)

    @Optional
    @OutputDirectory
    open var obfuscationTempDir = project.objects.property(File::class.java)

    @Optional
    @Input
    open var inJarsFilter = project.objects.property(String::class.java)

    @Optional
    @Input
    open var jarFilesFilter = project.objects.property(String::class.java)

    @Optional
    @Input
    open var copyOnFail = project.objects.property(Boolean::class.java)

    private var instructions = ArrayList<List<String>>()

    @InputFiles
    fun getArtifacts() = project.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.map { it.file }

    @InputFiles
    fun getArtifacts2() = project.configurations.getByName("compileClasspath").resolvedConfiguration.resolvedArtifacts.map { it.file }

    @InputFiles
    fun getArtifacts3() = try { project.configurations.getByName("extra").resolvedConfiguration.resolvedArtifacts.map { it.file } } catch (e: java.lang.IllegalStateException) { emptyList<File>() }

    fun instruction(vararg values: String): NetBeansCollectAndObfuscateTask {
        instructions.add(values.toList())
        return this
    }

    @TaskAction
    fun obfuscateJars(incrementalTaskInputs: IncrementalTaskInputs) {
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
        getArtifacts2().forEach { it.exists() }
        getArtifacts3().forEach { it.exists() }

        // Fetch the extension object (utilize the jarInfoContainer from there)
        val extension = project.extensions.findByType(NetBeansApplicationExtension::class.java)
        val jarInfoContainer = extension?.jarInfoContainer ?: JarInfoContainer()

        // Directory for the final outputs
        val finalOutput = collectDir.getOrElse(File(project.buildDir, "cluster-jars"))
        if (!finalOutput.isDirectory)
            finalOutput.mkdirs()

        // Temporary output dir
        val output = obfuscationTempDir.getOrElse(File(project.buildDir, "obfuscation-input-jars"))
        if (!output.isDirectory)
            output.mkdirs()

        // Clear the removed files from the output to ensure no non-stale files creep in the output
        for (file in removedFiles)
            File(output, file.name).delete()

        // Platform source dir for checking if the modules are already there
        val platformDir = File(File(netbeansDirectory.get(), "platform"), "modules")

        // Collect names of non-NetBeans platform, OSGi and NetBeans module dependencies for obfuscation
        val toObfuscate = HashSet<ResolvedArtifact>()
        val artifacts = try { project.configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.toSet() } catch (e: java.lang.IllegalStateException) {emptySet<ResolvedArtifact>() }
        for (artifact in artifacts) {
            val jar = artifact.file
            if (!jar.isFile)
                continue

            // Process the info from the manifest, and if the module type is right, collect it for obfuscation
            val jarInfo = jarInfoContainer.getOrAdd(jar)
            if (jarInfo.manifest.isOsgiBundle || jarInfo.manifest.isNetBeansModule) {
                // Don't collect the NetBeans platform dependencies
                val moduleName = jarInfo.manifest.module
                val dashedModuleName = moduleName!!.replace(".", "-")
                if (File(platformDir, "$dashedModuleName.jar").exists() || (!jar.name.startsWith("org-netbeans-modules-") && (jar.name.startsWith("org-openide-") || jar.name.startsWith("org-netbeans-"))))
                    continue

                project.logger.info("Going to obfuscate $jar")
                toObfuscate.add(artifact)
            }
            else
                project.logger.info("Skipping (not OSGI or NB module): $jar")
        }

        // Obfuscate all dependencies of the project
        val processedFilterString = if (jarFilesFilter.orNull != null) processFilterString(jarFilesFilter.get()) else null
        val dependencies = try { project.configurations.getByName("compileClasspath").resolvedConfiguration.getFirstLevelModuleDependencies(Specs.SATISFIES_ALL) } catch (e: java.lang.IllegalStateException) {emptySet<ResolvedDependency>() }
        val processedDependencies = HashSet<ResolvedArtifact>()
        for (dependency in dependencies)
            obfuscateDependency(output, outOfDateFiles, dependency, toObfuscate, processedDependencies, jarInfoContainer, processedFilterString)

        // Clean the final output dir - ensure there are no left-over stale files
        for (file in finalOutput.listFiles())
            file.delete()

        // Then copy over the obfuscated artifacts
        for (artifact in artifacts) {
            // Try to load the jar from the obfuscated artifacts and if it cannot be found,
            // load it from  the artifact source.
            var jar = File(output, artifact.file.name)
            if (!jar.exists() || !jar.isFile)
                jar = artifact.file

            if (!jar.isFile)
                continue

            // Install only OSGi and NetBeans module runtime dependencies
            val jarInfo = jarInfoContainer.getOrAdd(jar)
            if (jarInfo.manifest.isOsgiBundle || jarInfo.manifest.isNetBeansModule) {
                val moduleName = jarInfo.manifest.module
                val dashedModuleName = moduleName!!.replace(".", "-")
                // Don't collect the NetBeans platform dependencies
                if (File(platformDir, "$dashedModuleName.jar").exists() || (!jar.name.startsWith("org-netbeans-modules-") && (jar.name.startsWith("org-openide-") || jar.name.startsWith("org-netbeans-"))))
                    continue

                val target = File(finalOutput, "$dashedModuleName.jar")
                project.logger.info("Installing obfuscated $artifact")
                artifact.file.copyTo(target, true)
                target.setLastModified(artifact.file.lastModified())
            }
        }
    }

    /**
     * Perform the actual obfuscation.
     */
    private fun obfuscateDependency(targetDir: File, outOfDateFiles: Set<String>, dependency: ResolvedDependency, toObfuscate: Set<ResolvedArtifact>, processedDependencies: MutableSet<ResolvedArtifact>, jarInfoContainer: JarInfoContainer, processedFilterString: List<Pair<Boolean, Array<Regex>>>?) {
        // First make sure all dependencies are obfuscated
        for (transient in dependency.children)
            obfuscateDependency(targetDir, outOfDateFiles, transient, toObfuscate, processedDependencies, jarInfoContainer, processedFilterString)

        // Process all the artifacts published by the dependency. Typically there is only one.
        val artifacts = dependency.moduleArtifacts
        for (artifact in artifacts) {
            val jar = artifact.file
            val target = File(targetDir, artifact.file.name)

            // Skip the artifact if it is already processed or if it is not to be obfuscated
            if (processedDependencies.contains(artifact))
                continue
            else if (!toObfuscate.contains(artifact)) {
                processedDependencies.add(artifact)
                continue
            }
            // Skip (but copy to the targetDir) the files, that don't match the given group/artifact name/version filter string.
            else if (processedFilterString != null && !includeDependency(dependency, processedFilterString)) {
                logger.info("Skipping $artifact as the dependency does not match the filter.")

                processedDependencies.add(artifact)
                if (outOfDateFiles.contains(jar.name) || !target.exists() || target.lastModified() < jar.lastModified()) {
                    artifact.file.copyTo(target, true)
                    target.setLastModified(jar.lastModified())
                }
                continue
            }

            /*
             * Proceed with the actual obfuscation
             */
            processedDependencies.add(artifact)

            // Test if obfuscating is actually needed: a) Gradle says the file is out of date,
            // b) the target file doesn't exist or c) the target is older than source file.
            if (outOfDateFiles.contains(jar.name) || !target.exists() || target.lastModified() < jar.lastModified()) {
                project.logger.info("Obfuscating $artifact")

                // Create the ProGuard configuration file
                val proguardFile = File(project.buildDir, "proguard.pro")
                proguardFile.bufferedWriter().use {
                    it.write("-basedirectory ${project.buildDir}\n")

                    // Add the library artifacts
                    val libraries = allArtifacts(dependency)
                    for (libraryArtifact in libraries) {
                        project.logger.info("Library $libraryArtifact")

                        // For some reason, the artifact it self is included as it's own dependency...
                        if (libraryArtifact != artifact) {
                            // Use the obfuscated library, if available, otherwise use the original file
                            val dependencyInLibrary = File(targetDir, libraryArtifact.file.name)
                            if (dependencyInLibrary.exists())
                                it.write("-libraryjars $dependencyInLibrary\n")
                            else
                                it.write("-libraryjars ${libraryArtifact.file}\n")
                        }
                    }

                    // Add any (missing) extra libraries
                    val extraLibraries = try { project.configurations.getByName("extra").resolvedConfiguration.resolvedArtifacts } catch (e: java.lang.IllegalStateException) {emptySet<ResolvedArtifact>() }
                    for (libraryArtifact in extraLibraries) {
                        if (libraryArtifact != artifact && !libraries.contains(libraryArtifact)) {
                            project.logger.info("Extra library $libraryArtifact")
                            // Use the obfuscated library, if available, otherwise use the original file
                            val dependencyInLibrary = File(targetDir, libraryArtifact.file.name)
                            if (dependencyInLibrary.exists())
                                it.write("-libraryjars $dependencyInLibrary\n")
                            else
                                it.write("-libraryjars ${libraryArtifact.file}\n")
                        }
                    }


                    // Supply the jar file name + the possible file filter
                    val inFilter = inJarsFilter.orNull
                    if (inFilter != null)
                        it.write("-injars $jar($inFilter)\n")
                    else
                        it.write("-injars $jar\n")

                    // Write the target file name
                    it.write("-outjars ${File(targetDir, jar.name)}\n")

                    // Write any instructions supplied by the user
                    for (values in instructions)
                        it.write("-${values.joinToString(" ")}\n")
                }

                // Execute ProGuard storing the output to a byte array output stream.
                // The output will be shown, if there are errors.
                val proguardOutput = ByteArrayOutputStream()
                try {
                    project.javaexec {
                        it.workingDir = project.buildDir
                        it.classpath = project.files(project.buildscript.configurations.getByName("classpath").resolvedConfiguration.resolvedArtifacts.map { it.file })
                        it.main = "proguard.ProGuard"
                        it.args = listOf("@proguard.pro")
                        it.errorOutput = proguardOutput
                        it.standardOutput = proguardOutput
                    }
                }
                catch (e:  org.gradle.process.internal.ExecException) {
                    // The obfucation didn't succeed
                    logger.warn(String(proguardOutput.toByteArray(), Charset.defaultCharset()))

                    // If copy on fail is allowed, copy the file as is ...
                    if (copyOnFail.getOrElse(false)) {
                        logger.warn("Could not obfuscate file ${jar.name}. Using the unobfuscated file.")
                        artifact.file.copyTo(target, true)
                        target.setLastModified(jar.lastModified())
                    }
                    // ... otherwise re-throw the exception
                    else {
                        logger.warn("Could not obfuscate file ${jar.name}.")
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Create a set of all dependencies recursively.
     */
    private fun allArtifacts(description: ResolvedDependency): Set<ResolvedArtifact> {
        val ret = HashSet<ResolvedArtifact>()
        ret.addAll(description.moduleArtifacts)
        for (child in description.children)
            ret.addAll(allArtifacts(child))
        return ret
    }

    /**
     * Create a set of all dependencies recursively.
     */
    private fun allDependencies(description: ResolvedDependency): Set<ResolvedDependency> {
        val ret = HashSet<ResolvedDependency>()
        for (child in description.children) {
            ret.add(child)
            ret.addAll(allDependencies(child))
        }
        return ret
    }

    /**
     * Test if the given dependency should be included according to the filterString processed via [processedFilterString].
     * The supported filter strings are of type <group>[:<artifact>[:<version>]], where each of the parts can start with
     * ! for negation and contain any number of * or ?. Only the existing parts are matched.
     */
    private fun includeDependency(dependency: ResolvedDependency, processedFilterString: List<Pair<Boolean, Array<Regex>>>): Boolean {
        var allNegates = true
        for ((negate, parts) in processedFilterString) {
            allNegates = allNegates && negate

            var match = true
            if (parts.size > 1)
                match = match && parts[0].matches(dependency.moduleGroup)
            if (parts.size > 2)
                match = match && parts[1].matches(dependency.moduleName)
            if (parts.size > 3)
                match = match && parts[2].matches(dependency.moduleVersion)

            if (negate && match)
                return false
            else if (!negate && match)
                return true
        }

        return allNegates
    }

    /**
     * Process the given [filterString] into a list of pairs of boolean indicating negation and array of part regular
     * expressions that contain the globs converted into regexps.
     * The supported filter strings are of type <group>[:<artifact>[:<version>]], where each of the parts can start with
     * ! for negation and contain any number of * or ?. Only the existing parts are matched.
     */
    private fun processFilterString(filterString: String): List<Pair<Boolean, Array<Regex>>> {
        val pairs = ArrayList<Pair<Boolean, Array<Regex>>>()
        val filters = filterString.split(",").map { it.trim() }
        for (filter in filters) {
            val negate = filter.isNotEmpty() && filter[0] == '!'
            val actualFilter = if (negate) filter.substring(1) else filter
            val parts = actualFilter.split(':').map { it.trim() }
            val regexps = Array(parts.size, { Regex(convertGlobToRegEx(parts[it])) })
            pairs.add(negate to regexps)
        }
        return pairs
    }

    /**
     * Convert glob (i.e. * and ? containing) filter into regular regular expression.
     * Copied (and modified) from: https://stackoverflow.com/questions/1247772/is-there-an-equivalent-of-java-util-regex-for-glob-type-patterns
     */
    private fun convertGlobToRegEx(line: String): String {
        var line = line
        line = line.trim { it <= ' ' }
        val sb = StringBuilder(line.length)

        var escaping = false
        var inCurlies = 0
        for (currentChar in line.toCharArray()) {
            when (currentChar) {
                '*' -> {
                    if (escaping)
                        sb.append("\\*")
                    else
                        sb.append(".*")
                    escaping = false
                }
                '?' -> {
                    if (escaping)
                        sb.append("\\?")
                    else
                        sb.append('.')
                    escaping = false
                }
                '.', '(', ')', '+', '|', '^', '$', '@', '%' -> {
                    sb.append('\\')
                    sb.append(currentChar)
                    escaping = false
                }
                '\\' -> if (escaping) {
                    sb.append("\\\\")
                    escaping = false
                } else
                    escaping = true
                '{' -> {
                    if (escaping) {
                        sb.append("\\{")
                    } else {
                        sb.append('(')
                        inCurlies++
                    }
                    escaping = false
                }
                '}' -> {
                    if (inCurlies > 0 && !escaping) {
                        sb.append(')')
                        inCurlies--
                    } else if (escaping)
                        sb.append("\\}")
                    else
                        sb.append("}")
                    escaping = false
                }
                ',' -> if (inCurlies > 0 && !escaping) {
                    sb.append('|')
                } else if (escaping)
                    sb.append("\\,")
                else
                    sb.append(",")
                else -> {
                    escaping = false
                    sb.append(currentChar)
                }
            }
        }
        return sb.toString()
    }
}