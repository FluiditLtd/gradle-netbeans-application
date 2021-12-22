package fi.fluidit.gradle.netbeans

import org.apache.tools.ant.taskdefs.Zip
import org.apache.tools.ant.types.ZipFileSet
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
        val zipTask =  Zip()
        val antProject = project.ant.project
        zipTask.project = antProject
        zipTask.destFile = getOutput()

        val files = ZipFileSet()
        files.project = antProject
        files.dir = getInput()
        files.appendExcludes(arrayOf("bin/**", "**/.lastModifed", "cachedir"))
        files.prefix = "${brandingToken.get()}/"
        zipTask.addFileset(files)

        val binFileSet = ZipFileSet()
        binFileSet.project = antProject
        binFileSet.setFileMode("755")
        binFileSet.dir = File(getInput(), "bin")
        binFileSet.prefix = "${brandingToken.get()}/bin/"
        zipTask.addFileset(binFileSet)

        zipTask.execute()
    }
}