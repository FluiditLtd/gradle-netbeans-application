package fi.fluidit.gradle.netbeans

import org.gradle.api.Project
import java.io.File

open class NetBeansApplicationExtension(project: Project) {
    internal var jarInfoContainer = JarInfoContainer()

    var brandingToken = project.objects.property(String::class.java)
    var installerTitle = project.objects.property(String::class.java)
    var antExecutable = project.objects.property(String::class.java)
    var netbeansDirectory = project.objects.property(File::class.java)
    var etcConfFile = project.objects.property(File::class.java)
    var etcClustersFile = project.objects.property(File::class.java)
    var binDirectory = project.objects.property(File::class.java)
    var brandingDirectory = project.objects.property(File::class.java)
    var debugPort = project.objects.property(String::class.java)
    var userDir = project.objects.property(File::class.java)
    var collectDir = project.objects.property(File::class.java)
    var additionalArguments = project.objects.property(String::class.java)
    var postfix = project.objects.property(String::class.java)
    var startLevels = project.objects.mapProperty(String::class.java, java.lang.Integer::class.java)
    var autoloads = project.objects.mapProperty(String::class.java, java.lang.Boolean::class.java)
    var eagers = project.objects.mapProperty(String::class.java, java.lang.Boolean::class.java)
    var enabled = project.objects.mapProperty(String::class.java, java.lang.Boolean::class.java)
    var defaultStartLevel = project.objects.property(java.lang.Integer::class.java)
}
