package fi.fluidit.gradle.netbeans

import org.gradle.api.Project
import java.io.File

open class NetBeansApplicationExtension(project: Project) {
    internal var jarInfoContainer = JarInfoContainer()

    var brandingToken = project.objects.property(String::class.java)
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
}
