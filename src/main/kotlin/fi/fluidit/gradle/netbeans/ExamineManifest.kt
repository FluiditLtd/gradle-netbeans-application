/* ==========================================================================
 * Copyright 2003-2004 Mevenide Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * =========================================================================
 */
package fi.fluidit.gradle.netbeans

import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * Tag examines the manifest of a jar file and retrieves NetBeans specific information.
 * @author [Milos Kleint](mailto:mkleint@codehaus.org)
 */
class ExamineManifest {
    private var jarFile: File? = null
    private var manifestFile: File? = null
    var isNetBeansModule: Boolean = false
    var isOsgiBundle: Boolean = false
        private set

    var isLocalized: Boolean = false
        private set
    var specVersion: String? = null
        private set
    var implVersion: String? = null
        private set
    var startLevel: Int? = null
        private set
    var enabled: Boolean? = null
        private set

    /**
     * Full name of module: code name base, then optionally slash and major release version.
     * @return module full name
     */
    var moduleWithRelease: String? = null
        private set
    private var locBundle: String? = null
    var classpath: String? = null
        private set
    private var publicPackages: Boolean = false
    private var populateDependencies = false
    var dependencyTokens = emptyList<String>()
        private set
    var osgiImports = emptySet<String>()
        private set
    var osgiExports = emptySet<String>()
        private set

    private var friendPackages = false
    var friends = emptyList<String>()
        private set
    /**
     * list of package statements from OpenIDE-Module-Public-Packages.
     * All items end with .*
     * @return list of package
     */
    var packages = emptyList<String>()
        private set

    private var requires: MutableList<String> = ArrayList<String>()

    var netBeansProvidesTokens = emptyList<String>()
        private set
    //that's the default behaviour without the special manifest entry
    var isBundleAutoload = true
        private set

    /**
     * Code name base of the module only.
     * Does not include any release version.
     * @return module code name base
     */
    val module: String?
        get() = if (moduleWithRelease != null) moduleWithRelease!!.replaceFirst(
            "/\\d+$".toRegex(),
            ""
        ) else moduleWithRelease

    val netBeansRequiresTokens: List<String>
        get() = requires

    fun checkFile() {
        resetExamination()

        var mf: Manifest? = null
        if (jarFile != null) {
            var jar: JarFile? = null
            try {
                jar = JarFile(jarFile!!)
                mf = jar.manifest
            } catch (exc: Exception) {
                throw IllegalStateException("Could not open " + jarFile + ": " + exc.message, exc)
            } finally {
                if (jar != null) {
                    try {
                        jar.close()
                    } catch (io: IOException) {
                        throw IllegalStateException(io.message, io)
                    }

                }
            }
        } else if (manifestFile != null) {
            var stream: InputStream? = null
            try {
                stream = FileInputStream(manifestFile!!)
                mf = Manifest(stream)
            } catch (exc: Exception) {
                throw IllegalStateException(exc.message, exc)
            } finally {
                if (stream != null) {
                    try {
                        stream.close()
                    } catch (io: IOException) {
                        throw IllegalStateException(io.message, io)
                    }

                }
            }
        }
        if (mf != null) {
            processManifest(mf)
        } else {
            //MNBMODULE-22
            var source = manifestFile
            if (source == null) {
                source = jarFile
            }
            if (source == null) {
                //logger.debug( "No manifest to examine" );
            } else {
                //logger.debug( "Cannot find manifest entries in " + source.getAbsolutePath() );
            }
        }
    }

    private fun resetExamination() {
        isNetBeansModule = false
        this.isLocalized = false
        this.specVersion = null
        this.implVersion = null
        this.moduleWithRelease = null
        this.locBundle = null
        this.publicPackages = false
        this.startLevel = null
        this.enabled = null
        classpath = ""
    }

    private fun processManifest(mf: Manifest) {
        val attrs = mf.mainAttributes
        this.moduleWithRelease = attrs.getValue("OpenIDE-Module")
        isNetBeansModule = module != null
        if (isNetBeansModule) {
            this.locBundle = attrs.getValue("OpenIDE-Module-Localizing-Bundle")
            this.isLocalized = locBundle != null
            this.specVersion = attrs.getValue("OpenIDE-Module-Specification-Version")
            this.implVersion = attrs.getValue("OpenIDE-Module-Implementation-Version")

            val ebl = attrs.getValue("OpenIDE-Module-Enabled")
            if (ebl != null)
                enabled = ebl.toBoolean()

            val cp = attrs.getValue(Attributes.Name.CLASS_PATH)
            classpath = cp ?: ""
            val value = attrs.getValue("OpenIDE-Module-Public-Packages")
            val frList = attrs.getValue("OpenIDE-Module-Friends")
            if (value == null || value.trim { it <= ' ' } == "-") {
                this.publicPackages = false
            } else {
                if (frList != null) {
                    this.publicPackages = false
                    val friendList = StringUtils.stripAll(StringUtils.split(frList, ","), null)
                    friendPackages = true
                    friends = Arrays.asList(*friendList)
                } else {
                    this.publicPackages = true
                }
                val packageList = StringUtils.stripAll(StringUtils.split(value, ","), null)
                packages = Arrays.asList(*packageList)
            }
            if (populateDependencies) {
                val deps = attrs.getValue("OpenIDE-Module-Module-Dependencies")
                if (deps != null) {
                    val tokens = StringTokenizer(deps, ",")
                    val depList = ArrayList<String>()
                    while (tokens.hasMoreTokens()) {
                        var tok = tokens.nextToken()
                        //we are just interested in specification and loose dependencies.
                        val spec = tok.indexOf('>')
                        val impl = tok.indexOf('=')
                        if (spec > 0) {
                            tok = tok.substring(0, spec)
                        } else if (impl > 0) {
                            tok = tok.substring(0, impl)
                        }
                        val slash = tok.indexOf('/')
                        if (slash > 0) {
                            tok = tok.substring(0, slash)
                        }
                        depList.add(tok.trim { it <= ' ' }.intern())
                    }
                    this.dependencyTokens = depList
                }
                val req = attrs.getValue("OpenIDE-Module-Requires")
                val prov = attrs.getValue("OpenIDE-Module-Provides")
                val needs = attrs.getValue("OpenIDE-Module-Needs")
                if (prov != null) {
                    netBeansProvidesTokens = StringUtils.stripAll(StringUtils.split(prov, ","), null).toMutableList()
                }
                if (req != null || needs != null) {
                    requires = ArrayList()
                    if (req != null) {
                        requires.addAll(StringUtils.stripAll(StringUtils.split(req, ","), null))
                    }
                    if (needs != null) {
                        requires.addAll(StringUtils.stripAll(StringUtils.split(needs, ","), null))
                    }
                }
            }

        } else {

            //check osgi headers first, let nb stuff override it, making nb default
            val bndName = attrs.getValue("Bundle-SymbolicName")
            if (bndName != null) {
                this.isOsgiBundle = true
                this.moduleWithRelease = bndName./* MNBMODULE-125 */replaceFirst(" *;.+".toRegex(), "")
                    ./* MNBMODULE-96 */replace('-', '_')
                this.specVersion = attrs.getValue("Bundle-Version")
                val exp = attrs.getValue("Export-Package")
                val autoload = attrs.getValue("Nbm-Maven-Plugin-Autoload")
                if (autoload != null) {
                    isBundleAutoload = java.lang.Boolean.parseBoolean(autoload)
                }
                val sl = attrs.getValue("OpenIDE-Module-StartLevel")
                if (sl != null)
                    startLevel = Integer.valueOf(sl)

                val ebl = attrs.getValue("OpenIDE-Module-Enabled")
                if (ebl != null)
                    enabled = ebl.toBoolean()

                this.publicPackages = exp != null
                if (populateDependencies) {
                    //well, this doesn't appear to cover the major way of declation dependencies in osgi - Import-Package
                    val deps = attrs.getValue("Require-Bundle")
                    if (deps != null) {
                        val depList = ArrayList<String>()
                        // http://stackoverflow.com/questions/1757065/java-splitting-a-comma-separated-string-but-ignoring-commas-in-quotes
                        for (piece in deps.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                            depList.add(piece.replaceFirst(";.+".toRegex(), "").trim { it <= ' ' }.intern())
                        }
                        this.dependencyTokens = depList
                    }
                    val imps = attrs.getValue("Import-Package")
                    if (imps != null) {
                        val depList = HashSet<String>()
                        // http://stackoverflow.com/questions/1757065/java-splitting-a-comma-separated-string-but-ignoring-commas-in-quotes
                        for (piece in imps.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                            depList.add(piece.replaceFirst(";.+".toRegex(), "").trim { it <= ' ' }.intern())
                        }
                        this.osgiImports = depList
                    }
                    val exps = attrs.getValue("Export-Package")
                    if (exps != null) {
                        val depList = HashSet<String>()
                        // http://stackoverflow.com/questions/1757065/java-splitting-a-comma-separated-string-but-ignoring-commas-in-quotes
                        for (piece in exps.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                            depList.add(piece.replaceFirst(";.+".toRegex(), "").trim { it <= ' ' }.intern())
                        }
                        this.osgiExports = depList
                    }

                }
            } else {
                // for non-netbeans, non-osgi jars.
                this.specVersion = attrs.getValue("Specification-Version")
                this.implVersion = attrs.getValue("Implementation-Version")
                this.moduleWithRelease = attrs.getValue("Package")
                this.publicPackages = false
                classpath = ""
                /*    if ( module != null )
                {
                // now we have the package to make it a module definition, add the version there..
                module = module + "/1";
                }
                 */
                if (module == null) {
                    // do we want to do that?
                    this.moduleWithRelease = attrs.getValue("Extension-Name")
                }
            }
        }

    }

    /**
     * The jar file to examine. It is exclusive with manifestFile.
     * @param jarFileLoc jar file
     */
    fun setJarFile(jarFileLoc: File) {
        jarFile = jarFileLoc
    }

    /**
     * Manifest file to be examined. It is exclusive with jarFile.
     * @param manifestFileLoc manifedt file
     */
    fun setManifestFile(manifestFileLoc: File) {
        manifestFile = manifestFileLoc
    }

    /**
     * Either call [.setJarFile] or [.setManifestFile] as appropriate.
     * @param artifactFileLoc a JAR or folder
     */
    fun setArtifactFile(artifactFileLoc: File) {
        if (artifactFileLoc.isFile) {
            setJarFile(artifactFileLoc)
        } else if (artifactFileLoc.isDirectory) {
            val mani = File(artifactFileLoc, "META-INF/MANIFEST.MF")
            if (mani.isFile) {
                setManifestFile(mani)
            } // else e.g. jarprj/target/classes has no manifest, so nothing to examine
        } else {
            throw IllegalArgumentException(artifactFileLoc.absolutePath)
        }
    }

    /**
     * returns true if there are defined public packages and there is no friend
     * declaration.
     * @return true if has public package
     */
    fun hasPublicPackages(): Boolean {
        return publicPackages
    }

    fun setPopulateDependencies(populateDependencies: Boolean) {
        this.populateDependencies = populateDependencies
    }

    /**
     * returns true if both public packages and friend list are declared.
     * @return true if has friend package
     */
    fun hasFriendPackages(): Boolean {
        return friendPackages
    }
}
