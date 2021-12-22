package fi.fluidit.gradle.netbeans

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import java.io.File
import java.util.regex.Pattern

open class ExecuteTask : Exec() {
    var brandingToken = project.objects.property(String::class.java)

    var debug: Boolean = false

    @Optional
    var debugPort = project.objects.property(String::class.java)
    @Optional
    var additionalArguments = project.objects.property(String::class.java)
    @Optional
    var userDir = project.objects.property(File::class.java)

    @Input
    @Optional
    open var postfix = project.objects.property(String::class.java)

    override fun exec() {
        val branding = brandingToken.get()

        workingDir = File(project.buildDir, "$branding-${postfix.getOrElse("app")}" + File.separator + "bin")

        var userDir = userDir.orNull
        if (userDir == null)
            userDir = File(project.buildDir, "userdir")
        userDir.mkdirs()

        val executableName = if (Os.isFamily(Os.FAMILY_WINDOWS)) "${branding}64.exe" else "$branding"
        val executable = File(workingDir, executableName)

        val args = ArrayList<String?>()
        args.addAll(listOf(executable.toString(), "--userdir", userDir.toString()))

        var debuggerPort: String? = null
        if (project.hasProperty("debuggerJpdaPort"))
            debuggerPort = project.properties["debuggerJpdaPort"] as? String

        if (debuggerPort != null) {
            args.add("-J-Xdebug")
            args.add("-J-Xrunjdwp:transport=dt_socket,server=n,address=${debuggerPort}")
        }

        else if (debug) {
            val nbmDebugPort = debugPort.orNull ?: "5005"
            args.add("-J-agentlib:jdwp=transport=dt_socket,server=y,address=${nbmDebugPort}")
        }

        val extraArgs = additionalArguments.orNull
        if (!extraArgs.isNullOrBlank()) {
            // Split the string by whitespace, but not the whitespace in quotes. Copied from:
            // https://stackoverflow.com/questions/7804335/split-string-on-spaces-in-java-except-if-between-quotes-i-e-treat-hello-wor
            val m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(extraArgs)
            while (m.find())
                args.add(m.group(1))
        }

        commandLine = args

        super.exec()
    }
}
