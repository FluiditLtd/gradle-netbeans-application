package fi.fluidit.gradle.netbeans

import org.codehaus.plexus.archiver.zip.ZipArchiver
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import java.io.File

open class NetBeansZipTask : DefaultTask() {
    @Input
    open var brandingToken = project.objects.property(String::class.java)

    @Input
    @Optional
    open var postfix = project.objects.property(String::class.java)

    @InputDirectory
    fun getInput() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}")

    @OutputFile
    fun getOutput() = File(project.buildDir, "${brandingToken.get()}-${postfix.getOrElse("app")}-${project.version}.zip")

    @TaskAction
    fun createZip() {
        val archiver = ZipArchiver()
        archiver.destFile = getOutput()
        archiver.addDirectory(getInput(), null, arrayOf("**/.lastModifed", "cachedir"))
        archiver.createArchive()
    }
}