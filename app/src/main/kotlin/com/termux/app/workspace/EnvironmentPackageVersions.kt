package com.termux.app.workspace

import android.util.Xml
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/** Available versions from enabled, cached repositories; never refreshes or installs.*/
object EnvironmentPackageVersions {
    class Version {
        @JvmField val name: String
        @JvmField val version: String
        @JvmField val architecture: String
        @JvmField val repository: String?

        constructor(name: String, version: String, architecture: String, repository: String) {
            this.name = name
            this.version = version
            this.architecture = architecture
            this.repository = repository
        }

        fun label(): String {
            return version + (if (architecture.isEmpty()) "" else " · " + architecture) + "\n" + repository
        }
    }

    const val MAX_BYTES: Int = 1024 * 1024

    @JvmStatic
    fun supported(kind: EnvironmentPackages.Kind?): Boolean {
        return kind === EnvironmentPackages.Kind.APK || kind === EnvironmentPackages.Kind.DNF || kind === EnvironmentPackages.Kind.ZYPPER
    }

    @JvmStatic
    private fun validVersion(value: String?): Boolean {
        return value != null && value.length <= 256 && (value as java.lang.String).matches("[A-Za-z0-9][A-Za-z0-9.+_:~%\\-]*")
    }

    @JvmStatic
    private fun validRepository(value: String?): Boolean {
        return value != null && value.length <= 200 && (value as java.lang.String).matches("[A-Za-z0-9_][A-Za-z0-9_.:+\\-]*")
    }

    @JvmStatic
    private fun validZypperRepository(value: String?): Boolean {
        return value != null && value.length <= 200 && (value as java.lang.String).matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.:+() \\-]*")
    }

    @JvmStatic
    private fun validArch(value: String?): Boolean {
        return value != null && value.length <= 64 && (value as java.lang.String).matches("[A-Za-z0-9][A-Za-z0-9_\\-]*") && !value.equals("src") && !value.equals("nosrc")
    }

    @JvmStatic
    fun query(kind: EnvironmentPackages.Kind?, name: String): String {
        if (!supported(kind) || !EnvironmentPackages.validName(name)) {
            throw IllegalArgumentException("INVALID_PACKAGE_NAME")
        }
        val pkg: String = SessionShell.quote(name)
        when (kind!!) {
            EnvironmentPackages.Kind.APK -> {
                return "LC_ALL=C apk --no-network policy -- " + pkg
            }
            EnvironmentPackages.Kind.DNF -> {
                return "LC_ALL=C dnf --quiet --cacheonly repoquery --available --queryformat '%{name}\t%{evr}\t%{arch}\t%{repoid}\\n' -- " + pkg
            }
            EnvironmentPackages.Kind.ZYPPER -> {
                return "LC_ALL=C zypper --xmlout --no-refresh search --details --type package --match-exact -- " + pkg
            }
            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parse(kind: EnvironmentPackages.Kind?, name: String, bytes: ByteArray): MutableList<Version> {
        if (!supported(kind) || !EnvironmentPackages.validName(name) || bytes.size > MAX_BYTES) {
            throw invalid()
        }
        val rows = LinkedHashMap<String, Version>()
        val text = String(bytes, StandardCharsets.UTF_8)
        if (kind === EnvironmentPackages.Kind.ZYPPER) {
            if ((text as java.lang.String).toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
                throw invalid()
            }
            try {
                val xml: XmlPullParser = Xml.newPullParser()
                xml.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                xml.setInput(StringReader(text))
                var event: Int = xml.getEventType()
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event != XmlPullParser.START_TAG || !xml.getName().equals("solvable")) {
                        event = xml.next()
                        continue
                    }
                    val actual: String = xml.getAttributeValue(null, "name")
                    if (!name.equals(actual)) {
                        event = xml.next()
                        continue
                    }
                    val repo: String = xml.getAttributeValue(null, "repository")
                    val arch: String = xml.getAttributeValue(null, "arch")
                    if ("(System Packages)".equals(repo) || "@System".equals(repo) || "src".equals(arch) || "nosrc".equals(arch)) {
                        event = xml.next()
                        continue
                    }
                    add(rows, kind, Version(actual, xml.getAttributeValue(null, "edition"), arch, repo))
                    event = xml.next()
                }
            } catch (error: Exception) {
                throw IOException("PACKAGE_VERSIONS_INVALID", error)
            }
        } else if (kind === EnvironmentPackages.Kind.DNF) {
            for (line in (text as java.lang.String).split("\n")) {
                if (line.isEmpty()) {
                    continue
                }
                val f: Array<String> = (line as java.lang.String).split("\t", -1)
                if (f.size != 4) {
                    throw invalid()
                }
                if (!name.equals(f[0]) || f[2].equals("src") || f[2].equals("nosrc")) {
                    continue
                }
                add(rows, kind, Version(f[0], f[1], f[2], f[3]))
            }
        } else {
            var version: String? = null
            var target = false
            for (line in (text as java.lang.String).split("\n")) {
                if ((line as java.lang.String).trim().isEmpty()) {
                    continue
                }
                if (!line.startsWith(" ")) {
                    target = line.equals(name + " policy:")
                    version = null
                    continue
                }
                if (!target) {
                    continue
                }
                if (line.startsWith("  ") && !line.startsWith("    ") && line.endsWith(":")) {
                    version = (line as java.lang.String).trim()
                    version = version!!.substring(0, version.length - 1)
                    if (!validVersion(version)) {
                        throw invalid()
                    }
                } else if (line.startsWith("    ")) {
                    val repo: String = (line as java.lang.String).trim()
                    // An installed-only version cannot be downloaded from a repository.
                    if (repo.equals("lib/apk/db/installed") || repo.equals("/lib/apk/db/installed") || repo.equals("usr/lib/apk/db/installed") || repo.equals("/usr/lib/apk/db/installed")) {
                        continue
                    }
                    if (version == null) {
                        throw invalid()
                    }
                    add(rows, kind, Version(name, version, "", safeRepositoryUrl(repo)))
                } else {
                    throw invalid()
                }
            }
        }
        return ArrayList<Version>(rows.values)
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun safeRepositoryUrl(value: String): String {
        try {
            val uri = URI(value)
            if (value.length > 2048 || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getRawPath().contains("\n")) {
                throw invalid()
            }
            // Credentials and query strings must never appear in the version picker.
            return URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toASCIIString()
        } catch (error: URISyntaxException) {
            throw invalid()
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun add(rows: MutableMap<String, Version>, kind: EnvironmentPackages.Kind?, value: Version) {
        if (!EnvironmentPackages.validName(value.name) || !validVersion(value.version)) {
            throw invalid()
        }
        if (kind === EnvironmentPackages.Kind.APK) {
            if (value.repository == null || !value.architecture.isEmpty()) {
                throw invalid()
            }
        } else if (!validArch(value.architecture) || !(if (kind === EnvironmentPackages.Kind.ZYPPER) validZypperRepository(value.repository) else validRepository(value.repository))) {
            throw invalid()
        }
        rows.putIfAbsent(value.version + "\t" + value.architecture + "\t" + value.repository, value)
        if (rows.size > 1000) {
            throw invalid()
        }
    }

    @JvmStatic
    fun operation(kind: EnvironmentPackages.Kind?, value: Version): String {
        try {
            if (!supported(kind)) {
                throw invalid()
            }
            add(HashMap<String, Version>(), kind, value)
        } catch (error: IOException) {
            throw IllegalArgumentException("INVALID_PACKAGE_VERSION", error)
        }
        when (kind!!) {
            EnvironmentPackages.Kind.APK -> {
                return "apk --interactive add -- " + SessionShell.quote(value.name + "=" + value.version)
            }
            EnvironmentPackages.Kind.DNF -> {
                return "dnf install --from-repo=" + SessionShell.quote(value.repository) + " -- " + SessionShell.quote(value.name + "-" + value.version + "." + value.architecture)
            }
            EnvironmentPackages.Kind.ZYPPER -> {
                return "zypper install --oldpackage --from " + SessionShell.quote(value.repository) + " -- " + SessionShell.quote(value.name + "." + value.architecture + "=" + value.version)
            }
            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    @JvmStatic
    private fun invalid(): IOException {
        return IOException("PACKAGE_VERSIONS_INVALID")
    }
}
