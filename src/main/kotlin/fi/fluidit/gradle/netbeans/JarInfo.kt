package fi.fluidit.gradle.netbeans

import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class JarInfo(val fileName: String, val manifest: ExamineManifest)

class JarInfoContainer : ConcurrentHashMap<String, JarInfo>() {
    @Synchronized
    fun addJar(fileName: File): JarInfo {
        val manifest = ExamineManifest()
        manifest.setJarFile(fileName)
        manifest.checkFile()

        val jarInfo = JarInfo(fileName.name, manifest)
        put(fileName.name, jarInfo)
        return jarInfo
    }

    @Synchronized
    fun getOrAdd(fileName: File): JarInfo {
        var jarInfo = get(fileName.name)
        if (jarInfo != null)
            return jarInfo

        val manifest = ExamineManifest()
        manifest.setJarFile(fileName)
        manifest.checkFile()

        jarInfo = JarInfo(fileName.name, manifest)
        put(fileName.name, jarInfo)
        return jarInfo
    }
}