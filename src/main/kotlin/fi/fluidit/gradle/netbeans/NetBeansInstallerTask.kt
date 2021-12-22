package fi.fluidit.gradle.netbeans

import org.apache.tools.ant.ProjectHelper
import org.apache.tools.ant.util.StringUtils
import org.gradle.api.AntBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import java.io.*
import java.net.JarURLConnection
import java.net.URL
import java.util.*


open class NetBeansInstallerTask : DefaultTask() {
    @Input
    open var brandingToken = project.objects.property(String::class.java)

    @Input
    open var installerTitle = project.objects.property(String::class.java)

    @Input
    open var antExecutable = project.objects.property(String::class.java)

    @InputDirectory
    open var netbeansDirectory = project.objects.property(File::class.java)

    @Input
    @Optional
    open var postfix = project.objects.property(String::class.java)

    @InputDirectory
    fun getInput() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}")

    @InputFile
    fun getZipFile() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}-${project.version}.zip")

    @OutputFile
    fun getOutput1() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}-${project.version}-linux.sh")
    @OutputFile
    fun getOutput2() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}-${project.version}-windows.exe")
    @OutputFile
    fun getOutput3() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}-${project.version}-maxosx.tgz")

    @TaskAction
    fun createInstaller() {
        val zipFile = getZipFile()

        val appIconIcnsFile: File?

        // Copy Netbeans Installer resources
        val harnessSourceDir = File(netbeansDirectory.get(), "harness")
        val harnessDir = File(project.buildDir, "harness")
        harnessSourceDir.copyRecursively(harnessDir, true)

        // Overwrite template file with modified version to accept branded images etc.
        /*if (templateFile != null) {
            val template = File(harnessDir, "nbi/stub/template.xml")
            fu.copyFile(templateFile, template)
        }*/

        appIconIcnsFile = File(harnessDir, "etc" + File.separatorChar + "applicationIcon.icns")

        val props = HashMap<String, String>()

        val baseDir = project.projectDir
        val installersFilePrefix = "${brandingToken.get()}-${postfix.getOrElse("app")}-${project.version}"

        val title = installerTitle.getOrElse(project.name + " " + project.version).replace("-SNAPSHOT", "")

        props["suite.location"] = baseDir.absolutePath.replace("\\", "/")
        props["suite.props.app.name"] = brandingToken.get()
        props["suite.dist.zip"] = zipFile.absolutePath.replace("\\", "/")
        props["suite.dist.directory"] = project.buildDir.absolutePath.replace("\\", "/")
        props["installer.build.dir"] = File(project.buildDir, "installerbuild").absolutePath.replace("\\", "/")

        props["installers.file.prefix"] = installersFilePrefix

//        props.put( "install.dir.name", installDirName );

        val appName = brandingToken.get()
        props["suite.nbi.product.uid"] = appName.toLowerCase(Locale.ENGLISH)
        props["suite.props.app.title"] = title

        var appVersion = project.version.toString().replace("-SNAPSHOT", "")
        props["suite.nbi.product.version.short"] = appVersion
        while (appVersion.split(".").dropLastWhile({ it.isEmpty() }).toTypedArray().size < 5) {
            appVersion += ".0"
        }
        props["suite.nbi.product.version"] = appVersion
        props["nbi.stub.location"] = File(harnessDir, "nbi/stub").absolutePath.replace("\\", "/")
        props["nbi.stub.common.location"] = File(harnessDir, "nbi/.common").absolutePath.replace("\\", "/")
        props["nbi.ant.tasks.jar"] =
            File(harnessDir, "modules/ext/nbi-ant-tasks.jar").absolutePath.replace("\\", "/")
        props["nbi.registries.management.jar"] =
            File(harnessDir, "modules/ext/nbi-registries-management.jar").absolutePath.replace("\\", "/")
        props["nbi.engine.jar"] = File(harnessDir, "modules/ext/nbi-engine.jar").absolutePath.replace("\\", "/")

        /*if (installerLicenseFile != null) {
            getLog().info(
                String.format(
                    "License file is at %1s, exist = %2\$s",
                    installerLicenseFile,
                    installerLicenseFile.exists()
                )
            )
            props["nbi.license.file"] = installerLicenseFile.getAbsolutePath() //mkleint: no path replacement here??
        }*/

        val platforms = ArrayList<String>()
        platforms.add("linux")
        val linuxFile = File(project.buildDir, installersFilePrefix + "-linux.sh")
        //projectHelper.attachArtifact(project, "sh", "linux", linuxFile)

        platforms.add("windows")
        val windowsFile = File(project.buildDir, installersFilePrefix + "-windows.exe")
        //projectHelper.attachArtifact(project, "exe", "windows", windowsFile)

        platforms.add("macosx")
        val macosxFile = File(project.buildDir, installersFilePrefix + "-macosx.tgz")
        //projectHelper.attachArtifact(project, "tgz", "macosx", macosxFile)

        val sb = StringBuilder()
        for (i in platforms.indices) {
            if (i != 0) {
                sb.append(" ")
            }
            sb.append(platforms[i])
        }

        props["generate.installer.for.platforms"] = sb.toString()

        var javaHome = File(System.getProperty("java.home"))
        if (File(javaHome, "lib/rt.jar").exists() && javaHome.name == "jre")
            //mkleint: does this work on mac? no rt.jar there
            javaHome = javaHome.parentFile
        props["generator-jdk-location-forward-slashes"] = javaHome.absolutePath.replace("\\", "/")

        props["pack200.enabled"] = "" + false

        if (appIconIcnsFile != null)
            props["nbi.dock.icon.file"] = appIconIcnsFile.absolutePath

        try {
            val antProject = ant.antProject
            ant.lifecycleLogLevel = AntBuilder.AntMessagePriority.INFO
            ant.properties["buildDir"] = project.buildDir

            antProject.setUserProperty(
                "ant.file",
                File(harnessDir, "nbi/stub/template.xml").absolutePath.replace("\\", "/")
            )

            val antExecutable = this.antExecutable.getOrElse("ant")
            val antHome = File(antExecutable).parent
            if (!antHome.isNullOrEmpty()) {
                antProject.setProperty("ant.home", antHome)
                antProject.setUserProperty("ant.home",  antHome)
            }
            antProject.setProperty("ant.executable", antExecutable)
            antProject.setUserProperty("ant.executable",antExecutable)


            val helper = ProjectHelper.getProjectHelper()
            antProject.addReference("ant.projectHelper", helper)
            helper.parse(antProject, File(harnessDir, "nbi/stub/template.xml"))
            for ((key, value) in props) {
                antProject.setProperty(key, value)
            }
            /*if (userSettings != null) {
                for (e in userSettings.entrySet()) {
                    antProject.setProperty(e.key, e.value)
                }
            }*/
            antProject.executeTarget("build")
        } catch (ex: Exception) {
            throw IOException("Installers creation failed: $ex", ex)
        }
    }

    private inner class FileUrlUtils {

        internal fun copyFile(toCopy: File, destFile: File): Boolean {
            return copyStream(FileInputStream(toCopy), FileOutputStream(destFile))
        }

        internal fun copyFilesRecusively(toCopy: File, destDir: File): Boolean {
            assert(destDir.isDirectory)

            if (!toCopy.isDirectory) {
                return copyFile(toCopy, File(destDir, toCopy.name))
            } else {
                val newDestDir = File(destDir, toCopy.name)
                if (!newDestDir.exists() && !newDestDir.mkdir()) {
                    return false
                }
                for (child in toCopy.listFiles()!!) {
                    if (!copyFilesRecusively(child, newDestDir)) {
                        return false
                    }
                }
            }
            return true
        }

        internal fun copyJarResourcesRecursively(destDir: File, jarConnection: JarURLConnection): Boolean {

            val jarFile = jarConnection.getJarFile()

            val e = jarFile.entries()
            while (e.hasMoreElements()) {
                val entry = e.nextElement()
                if (entry.getName().startsWith(jarConnection.getEntryName())) {
                    val filename = StringUtils.removePrefix(
                        entry.getName(), //
                        jarConnection.getEntryName()
                    )

                    val f = File(destDir, filename)
                    if (!entry.isDirectory()) {
                        val entryInputStream = jarFile.getInputStream(entry)
                        if (!copyStream(entryInputStream, f)) {
                            return false
                        }
                        entryInputStream.close()
                    } else {
                        if (!ensureDirectoryExists(f)) {
                            throw IOException("Could not create directory: " + f.absolutePath)
                        }
                    }
                }
            }
            return true
        }

        internal fun copyResourcesRecursively(originUrl: URL, destination: File): Boolean {
            try {
                val urlConnection = originUrl.openConnection()
                return  if (urlConnection is JarURLConnection) {
                            copyJarResourcesRecursively(destination, urlConnection)
                        } else {
                            copyFilesRecusively(File(originUrl.getPath()), destination)
                        }
            } catch (e: IOException) {
                throw IOException("Installers creation failed: $e", e)
            }

        }

        internal fun copyStream(inputStream: InputStream, f: File): Boolean {
            try {
                return copyStream(inputStream, FileOutputStream(f))
            } catch (e: FileNotFoundException) {
                throw IOException("Installers creation failed: $e", e)
            }

        }

        internal fun copyStream(inputStream: InputStream, os: OutputStream): Boolean {
            try {
                val buf = ByteArray(1024)

                var len = inputStream.read(buf)
                while (len > 0) {
                    os.write(buf, 0, len)
                    len = inputStream.read(buf)
                }
                inputStream.close()
                os.close()
                return true
            } catch (e: IOException) {
                throw IOException("Installers creation failed: $e", e)
            }

        }

        internal fun ensureDirectoryExists(f: File): Boolean {
            return f.exists() || f.mkdir()
        }
    }
}