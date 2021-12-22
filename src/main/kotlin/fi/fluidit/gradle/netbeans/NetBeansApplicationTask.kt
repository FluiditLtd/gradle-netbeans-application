package fi.fluidit.gradle.netbeans

import org.apache.tools.ant.DirectoryScanner
import org.apache.tools.ant.taskdefs.Chmod
import org.apache.tools.ant.taskdefs.Zip
import org.apache.tools.ant.types.FileSet
import org.apache.tools.ant.types.ZipFileSet
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.IllegalArgumentException
import java.nio.charset.Charset
import java.util.zip.CRC32


open class NetBeansApplicationTask : DefaultTask() {
    @Input
    open var brandingToken = project.objects.property(String::class.java)

    @InputDirectory
    @Optional
    open var brandingDirectory = project.objects.property(File::class.java)

    @InputDirectory
    open var netbeansDirectory = project.objects.property(File::class.java)

    @InputFile
    @Optional
    open var etcConfFile = project.objects.property(File::class.java)

    @InputFile
    @Optional
    open var etcClustersFile = project.objects.property(File::class.java)

    @InputDirectory
    @Optional
    open var binDirectory = project.objects.property(File::class.java)

    @Input
    @Optional
    open var postfix = project.objects.property(String::class.java)

    @Optional
    @InputDirectory
    open var collectDir = project.objects.property(File::class.java)

    @Optional
    @Input
    open var startLevels = project.objects.mapProperty(String::class.java, Integer::class.java)

    @Optional
    @Input
    open var defaultStartLevel = project.objects.property(Integer::class.java)

    @Optional
    @Input
    open var autoloads = project.objects.mapProperty(String::class.java, java.lang.Boolean::class.java)

    @Optional
    @Input
    open var eagers = project.objects.mapProperty(String::class.java, java.lang.Boolean::class.java)

    @Optional
    @Input
    open var enabled = project.objects.mapProperty(String::class.java, java.lang.Boolean::class.java)

    @OutputDirectory
    fun getOutput() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}")

    @TaskAction
    fun clusterApp(incrementalTaskInputs: IncrementalTaskInputs) {
        val outOfDateFiles = HashSet<String>()
        val removedFiles = HashSet<File>()
        if (incrementalTaskInputs.isIncremental) {
            incrementalTaskInputs.outOfDate { outOfDateFiles.add(it.file.name) }
            incrementalTaskInputs.removed { removedFiles.add(it.file) }
        }

        val targetDir = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}")
        if (!targetDir.isDirectory())
            targetDir.mkdirs()

        // Clear the removed files from the output to ensure no non-stale files creep in the output
        // TODO find better way - there should be a way to map the original file to the actual output file name after the fact
        //for (file in removedFiles)
        //    File(output, file.name).delete()
        if (removedFiles.isNotEmpty()) {

        }

        val timestamp = File(targetDir, ".lastModified")
        if (timestamp.exists())
            timestamp.setLastModified(System.currentTimeMillis())
        else
            timestamp.createNewFile()

        copyPlatform(outOfDateFiles, targetDir)
        copyCluster(outOfDateFiles, targetDir)
        addBranding(outOfDateFiles, targetDir)
        createEtcAndBin(outOfDateFiles, targetDir)
    }

    private fun copyPlatform(outOfDateFiles: Set<String>, targetDir: File) {
        val platformDir = File(targetDir, "platform")
        if (!platformDir.isDirectory())
            platformDir.mkdirs()

        val timestamp = File(platformDir, ".lastModified")
        if (timestamp.exists())
            timestamp.setLastModified(System.currentTimeMillis())
        else
            timestamp.createNewFile()

        project.copy {
            it.from(File(netbeansDirectory.get(), "platform"))
            it.into(platformDir)
        }
    }

    private fun copyCluster(outOfDateFiles: Set<String>, targetDir: File) {
        val extension = project.extensions.findByType(NetBeansApplicationExtension::class.java)
        val jarInfoContainer = extension?.jarInfoContainer ?: JarInfoContainer()

        val clusterDir = File(targetDir, brandingToken.get())
        if (!clusterDir.isDirectory)
            clusterDir.mkdirs()

        val timestamp = File(clusterDir, ".lastModified")
        if (timestamp.exists())
            timestamp.setLastModified(System.currentTimeMillis())
        else
            timestamp.createNewFile()

        val collectedJarsDir = collectDir.getOrElse(File(project.buildDir, "cluster-jars"))
        for (artifact in collectedJarsDir.listFiles()) {
            project.logger.info("Processing $artifact")
            val jarInfo = jarInfoContainer.getOrAdd(artifact)
            val (location, newer) = processCluster(outOfDateFiles, brandingToken.get(), targetDir, artifact)
            if (newer) {
                //getLog().info("Copying " + art.getId() + " to cluster " + clstr)
                val modules = File(location, "modules")
                modules.mkdirs()
                val config = File(location, "config")
                val confModules = File(config, "Modules")
                confModules.mkdirs()
                val updateTracking = File(location, "update_tracking")
                updateTracking.mkdirs()
                val moduleName = jarInfo.manifest.module
                val dashedModuleName = moduleName!!.replace(".", "-")
                val targetName = File(modules, "$dashedModuleName.jar") //do we need the file in some canotical name pattern?
                val specVer = jarInfo.manifest.specVersion
                try {
                    artifact.copyTo(targetName, true)
                    targetName.setLastModified(artifact.lastModified())

                    val autoload = autoloads.get().get(dashedModuleName)?.booleanValue() ?: jarInfo.manifest.isBundleAutoload

                    var startLevel: Int?
                    if (jarInfo.manifest.isOsgiBundle) {
                        startLevel = jarInfo.manifest.startLevel
                        if (startLevel == null) {
                            if (startLevels.get() != null)
                                startLevel = startLevels.get().get(dashedModuleName)?.toInt()
                            if (startLevel == null)
                                startLevel = defaultStartLevel.getOrNull()?.toInt() ?: 10
                        }
                    }
                    else
                        startLevel = null

                    var enabled: Boolean?
                    if (jarInfo.manifest.isOsgiBundle) {
                        enabled = jarInfo.manifest.enabled
                        if (enabled == null) {
                            if (this.enabled.get() != null)
                                enabled = this.enabled.get().get(dashedModuleName) as? Boolean
                        }
                        if (enabled == null && !autoload)
                            enabled = true
                    }
                    else
                        enabled = null

                    val moduleConf = File(confModules, "$dashedModuleName.xml")
                    val eager = eagers.get().get(dashedModuleName)?.booleanValue() ?: false
                    moduleConf.writeText(createBundleConfigFile(moduleName, autoload, eager, enabled, startLevel), Charset.forName("UTF-8"))
                    val updateTrackingFile = File(updateTracking, "$dashedModuleName.xml")
                    updateTrackingFile.writeText(createBundleUpdateTracking(moduleName, targetName, moduleConf, specVer!!), Charset.forName("UTF-8"))
                } catch (exc: IOException) {
                    //getLog().error(exc)
                }
            }
        }
    }

    private fun processCluster(outOfDateFiles: Set<String>, cluster: String, targetDir: File, art: File): Pair<File, Boolean> {
        val clusterFile = File(targetDir, cluster)
        var newer = false
        if (!clusterFile.exists()) {
            clusterFile.mkdir()
            newer = true
        }
        else {
            val stamp = File(clusterFile, ".lastModified")
            if (stamp.lastModified() < art.lastModified() || !File(File(clusterFile, "modules"), art.name).exists() || outOfDateFiles.contains(art.name))
                newer = true
        }
        return clusterFile to newer
    }

    private fun createBundleConfigFile(cnb: String, autoload: Boolean, eager: Boolean, enabled: Boolean?, startLevel: Int?): String {
        if (autoload && eager)
            throw IllegalArgumentException("Bundle $cnb cannot be both eager and autoload.")

        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE module PUBLIC \"-//NetBeans//DTD Module Status 1.0//EN\"\n" +
                "                        \"http://www.netbeans.org/dtds/module-status-1_0.dtd\">\n" +
                "<module name=\"" + cnb + "\">\n" +
                "    <param name=\"autoload\">$autoload</param>\n"
        if (startLevel != null)
            xml += "    <param name=\"startlevel\">" + startLevel + "</param>\n"
        if (enabled != null)
            xml += "    <param name=\"enabled\">$enabled</param>\n"

        xml +=  "    <param name=\"eager\">$eager</param>\n" +
                "    <param name=\"jar\">modules/" + cnb.replace(".", "-") + ".jar</param>\n" +
                "    <param name=\"reloadable\">false</param>\n" +
                "</module>\n"
        return xml
    }

    @Throws(FileNotFoundException::class, IOException::class)
    fun createBundleUpdateTracking(cnb: String, moduleArt: File, moduleConf: File, specVersion: String): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<module codename=\"" + cnb + "\">\n" +
                "    <module_version install_time=\"" + System.currentTimeMillis() + "\" last=\"true\" origin=\"installer\" specification_version=\"" + specVersion + "\">\n" +
                "        <file crc=\"" + crcForFile(moduleConf).value + "\" name=\"config/Modules/" + cnb.replace(".", "-") + ".xml\"/>\n" +
                "        <file crc=\"" + crcForFile(moduleArt).value + "\" name=\"modules/" + cnb.replace(".","-") + ".jar\"/>\n" +
                "    </module_version>\n" +
                "</module>"
    }

    @Throws(FileNotFoundException::class, IOException::class)
    private fun crcForFile(inFile: File): CRC32 {
        val crc = CRC32()
        FileInputStream(inFile).use { inFileStream ->
            val array = ByteArray(inFile.length().toInt())
            val len = inFileStream.read(array)
            if (len != array.size)
                throw IOException("Cannot fully read $inFile")
            crc.update(array)
        }
        return crc
    }

    private fun createEtcAndBin(outOfDateFiles: Set<String>, targetDir: File) {
        val etcDir = File(targetDir, "etc")
        if (!etcDir.isDirectory())
            etcDir.mkdirs()

        val clusterConf = File(etcDir, "${brandingToken.get()}.clusters")
        val clustersString: String
        if (etcClustersFile.orNull != null)
            clustersString = etcClustersFile.get().readText(Charset.forName("UTF-8"))
        else {
            clusterConf.createNewFile()
            val buffer = StringBuilder()
            val clusters = targetDir.listFiles { pathname -> File(pathname, ".lastModified").exists() }
            for (cluster in clusters!!) {
                buffer.append(cluster.name)
                buffer.append("\n")
            }
            clustersString = buffer.toString()
        }
        clusterConf.writeText(clustersString, Charset.forName("UTF-8"))

        var confFile: File? = etcConfFile.orNull
        var str: String
        if (confFile == null) {
            val harnessDir = File(netbeansDirectory.get(), "harness")
            // app.conf contains default options and other settings
            confFile = File(harnessDir.getAbsolutePath(), "etc" + File.separator + "app.conf")
        }
        str = confFile.readText(Charset.forName("UTF-8"))

        val confDestFile = File(etcDir, "${brandingToken.get()}.conf")

        str = str.replace("\${branding.token}", brandingToken.get())
        confDestFile.writeText(str, Charset.forName("UTF-8"))

        val destBinDir = File(targetDir, "bin")
        destBinDir.mkdir()

        val binDir: File
        val destExe64 = File(destBinDir, "${brandingToken.get()}64.exe")
        val destSh = File(destBinDir, brandingToken.get())

        if (binDirectory.orNull != null) {
            //we have custom launchers.
            binDir = binDirectory.get()
            val fls = binDir.listFiles { file -> file.name.startsWith("app") } ?: throw IllegalStateException("Parameter 'binDirectory' has to point to an existing folder.")
            for (fl in fls) {
                val name = fl.name
                var dest: File? = null
                if (name.endsWith("64.exe"))
                    dest = destExe64
                else if (!name.contains(".") || name.endsWith(".sh"))
                    dest = destSh

                if (dest != null && fl.exists())
                    fl.copyTo(dest, true)
            }
        }

        else {
            val harnessDir = File(netbeansDirectory.get(), "harness")
            //we have org-netbeans-modules-apisupport-harness in target area, just use it's own launchers.
            binDir = File(harnessDir, "launchers")
            if (binDir.exists()) {
                val exe64 = File(binDir, "app64.exe")
                if (exe64.isFile)
                    exe64.copyTo(destExe64, true)
                val sh = File(binDir, "app.sh")
                if (sh.isFile)
                    sh.copyTo(destSh, true)
            }
        }

        val antProject = ant.antProject
        val chmod = antProject.createTask("chmod") as Chmod
        val fs = FileSet()
        fs.dir = destBinDir
        fs.setIncludes("*")
        chmod.addFileset(fs)
        chmod.setPerm("755")
        chmod.execute()
    }

    private fun addBranding(outOfDateFiles: Set<String>, targetDir: File) {
        val brandingDir = brandingDirectory.orNull
        if (brandingDir == null)
            return

        val branding = brandingToken.get()
        val clusterDir = File(targetDir, branding)

        val scanner = DirectoryScanner()
        scanner.setIncludes(arrayOf("**/*.*"))
        scanner.addDefaultExcludes()
        scanner.setBasedir(brandingDir)
        scanner.scan()

        val clusterPathPart = "netbeans" + File.separator + brandingToken
        val outputDir = File(project.buildDir, "branding_and_locales")
        outputDir.mkdirs()

         // copy all files and see to it that they get the correct names
        for (brandingFilePath in scanner.includedFiles) {
            val brandingFile = File(brandingDir, brandingFilePath)
            val locale = getLocale(brandingFile.name)
            val token = if (locale[1].isNullOrEmpty()) branding else "${branding}_" + locale[1]
            val root = File(outputDir, token)
            root.mkdirs()

            val destinationName = locale[0] + "_" + token + locale[2]
            val brandingDestination = File(root, brandingFilePath.replace(brandingFile.name, destinationName))
            if (!brandingDestination.parentFile.exists())
                brandingDestination.parentFile.mkdirs()

            brandingFile.copyTo(brandingDestination, true)
        }

        for (rootDir in outputDir.listFiles()) {
            if (!rootDir.isDirectory())
                continue

            val effectiveBranding = rootDir.name
             // create jar-files from each toplevel .jar directory
            scanner.setIncludes(arrayOf("**/*.jar"))
            scanner.basedir = rootDir
            scanner.scan()

            for (jarDirectoryPath in scanner.includedDirectories)  {
                 // move nnn.jar directory to nnn.jar.tmp
                val jarDirectory = File(rootDir, jarDirectoryPath)
                val destinationLocation = File(clusterDir, jarDirectoryPath).parentFile
                destinationLocation.mkdirs()

                 // jars should be placed in locales/ under the same directory the jar-directories are
                val destinationJar = File(destinationLocation, "locale"
                    + File.separator + destinationFileName(jarDirectory.name, effectiveBranding))

                val antProject = project.ant.project
                val zipTask =  Zip()
                zipTask.project = antProject
                zipTask.destFile = destinationJar

                val files = ZipFileSet()
                files.project = antProject
                files.dir = jarDirectory
                files.setIncludes("**/*")
                zipTask.addFileset(files)

                zipTask.execute()
            }
        }
    }

    private fun destinationFileName(brandingFilePath: String, branding: String): String {
        // use first underscore in filename
        val lastSeparator = brandingFilePath.lastIndexOf(File.separator)
        val infix = "_$branding"

        // no underscores, use dot
        val lastDot = brandingFilePath.lastIndexOf(".")
        return if (lastDot == -1 || lastDot < lastSeparator) {
            brandingFilePath + infix
        } else brandingFilePath.substring(0, lastDot) + infix + brandingFilePath.substring(lastDot)
    }

    //[0] prefix
    //[1] locale
    //[2] suffix
    private fun getLocale(name: String): Array<String?> {
        var name = name
        var suffix = ""
        val dot = name.indexOf(".")
        if (dot > -1) { //remove file extension
            suffix = name.substring(dot)
            name = name.substring(0, dot)
        }
        var locale: String? = null
        var count = 1
        //iterate from back of the string, max 3 times and see if the pattern patches local pattern
        while (count <= 3) {
            val underscore = name.lastIndexOf('_')
            if (underscore > -1) {
                val loc1 = name.substring(underscore + 1)
                if (loc1.length != 2) {
                    break
                }
                locale = loc1 + if (locale == null) "" else "_$locale"
                name = name.substring(0, underscore)
            } else {
                break
            }
            count = count + 1
        }
        return arrayOf(name, locale, suffix)
    }
}