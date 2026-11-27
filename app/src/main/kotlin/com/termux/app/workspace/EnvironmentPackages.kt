package com.termux.app.workspace

import android.util.Xml
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.xmlpull.v1.XmlPullParser

/** Native package managers; catalogue reads never install or refresh repositories.*/
object EnvironmentPackages {
    enum class Kind {
        APT,
        APK,
        DNF,
        ZYPPER,
        XBPS,
        PACMAN
    }

    // proot exposes Termux bin as a fallback; package commands must stay guest-native.
    const val GUEST_PATH: String = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    @JvmStatic
    fun nativeCommand(script: String): String {
        return "( PATH=" + SessionShell.quote(GUEST_PATH) + "; export PATH; " + script + "\n)"
    }

    @JvmStatic
    fun xbps(script: String): String {
        return "( unset FORCE_IPV6; FORCE_IPV4=1 " + script + " )"
    }

    @JvmStatic
    fun probe(): String {
        return "if command -v apt-get >/dev/null && command -v dpkg-query >/dev/null; then printf APT; elif command -v apk >/dev/null; then printf APK; elif command -v zypper >/dev/null; then printf ZYPPER; elif command -v dnf >/dev/null; then printf DNF; elif command -v xbps-query >/dev/null; then printf XBPS; elif command -v pacman >/dev/null; then printf PACMAN; else exit 2; fi"
    }

    @Throws(IOException::class)
    @JvmStatic
    fun kind(value: ByteArray): Kind {
        try {
            return Kind.valueOf((String(value, StandardCharsets.UTF_8) as java.lang.String).trim())
        } catch (error: IllegalArgumentException) {
            throw IOException("PACKAGE_MANAGER_NOT_FOUND")
        }
    }

    @JvmStatic
    fun query(kind: Kind?, tab: Int): String {
        if (tab < 0 || tab > 2) {
            throw IllegalArgumentException()
        }
        when (kind!!) {
            Kind.APT -> {
                return PackageCatalog.query(tab)
            }
            Kind.APK -> {
                return "LC_ALL=C apk --no-network list " + arrayOf<String>("--installed", "", "--upgradable")[tab]
            }
            Kind.DNF -> {
                return "LC_ALL=C dnf --quiet --cacheonly repoquery " + arrayOf<String>("--installed", "--available --latest-limit=1", "--upgrades")[tab] + " --queryformat '%{name}\t%{evr}\\n'"
            }
            Kind.ZYPPER -> {
                return "LC_ALL=C zypper --xmlout --no-refresh " + (if (tab == 0) "--disable-repositories " else "") + (if (tab == 2) "list-updates --type package" else "search --details --type package" + (if (tab == 0) " --installed-only" else ""))
            }
            Kind.XBPS -> {
                return xbps("LC_ALL=C " + arrayOf<String>("xbps-query -l", "xbps-query -Rs ''", "xbps-install -un")[tab])
            }
            Kind.PACMAN -> {
                return "LC_ALL=C pacman " + arrayOf<String>("-Q", "-Sl", "-Qu")[tab]
            }
            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    @JvmStatic
    fun validName(name: String?): Boolean {
        return name != null && name.length <= 256 && (name as java.lang.String).matches("[A-Za-z0-9][A-Za-z0-9+._:@-]*")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parse(kind: Kind?, bytes: ByteArray, tab: Int): MutableList<PackageCatalog.Entry> {
        if (kind === Kind.APT) {
            return PackageCatalog.parse(bytes, tab)
        }
        if (bytes.size > PackageCatalog.MAX_BYTES) {
            throw IOException("PACKAGE_LIST_TOO_LARGE")
        }
        val rows = TreeMap<String, PackageCatalog.Entry>()
        val input = String(bytes, StandardCharsets.UTF_8)
        if (kind === Kind.ZYPPER) {
            try {
                val xml: XmlPullParser = xml(input)
                var event: Int = xml.getEventType()
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && (xml.getName().equals("solvable") || xml.getName().equals("update"))) {
                        add(rows, xml.getAttributeValue(null, "name"), xml.getAttributeValue(null, "edition"))
                    }
                    event = xml.next()
                }
            } catch (error: Exception) {
                throw IOException("PACKAGE_XML_INVALID", error)
            }
        } else {
            for (line in (input as java.lang.String).split("\n")) {
                if ((line as java.lang.String).trim().isEmpty()) {
                    continue
                }
                val f: Array<String> = ((line as java.lang.String).trim() as java.lang.String).split("\\s+")
                var name: String? = null
                var version: String? = null
                if (kind === Kind.DNF) {
                    val pair: Array<String> = (line as java.lang.String).split("\t", -1)
                    if (pair.size != 2) {
                        throw IOException("PACKAGE_METADATA_INVALID")
                    }
                    name = pair[0]
                    version = pair[1]
                }
                if (kind === Kind.PACMAN) {
                    if (tab == 0 && f.size >= 2) {
                        name = f[0]
                        version = f[1]
                    } else if (tab == 1 && f.size >= 3) {
                        name = f[1]
                        version = f[2]
                    } else if (tab == 2 && f.size >= 4 && f[2].equals("->")) {
                        name = f[0]
                        version = f[3]
                    } else {
                        throw IOException("PACKAGE_METADATA_INVALID")
                    }
                }
                if (kind === Kind.XBPS && tab == 0 && !f[0].equals("ii")) {
                    continue
                }
                if (kind === Kind.APK || kind === Kind.XBPS) {
                    val pkg: String? = if (kind === Kind.APK) f[0] else if (tab == 2) if (f.size > 1 && f[1].equals("update")) f[0] else null else if (f.size > 1) f[1] else null
                    if (pkg == null) {
                        continue
                    }
                    val m: Matcher = Pattern.compile(if (kind === Kind.XBPS) "^(.+)-([A-Za-z0-9][A-Za-z0-9._+~:]*_[0-9]+)\$" else "^(.+)-([0-9][A-Za-z0-9._+~:-]*)\$").matcher(pkg)
                    if (m.matches()) {
                        name = m.group(1)
                        version = m.group(2)
                    } else {
                        throw IOException("PACKAGE_METADATA_INVALID")
                    }
                }
                if (name != null) {
                    add(rows, name, version)
                }
            }
        }
        return ArrayList<PackageCatalog.Entry>(rows.values)
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun add(rows: MutableMap<String, PackageCatalog.Entry>, name: String, version: String?) {
        if (!validName(name) || version == null || version.isEmpty() || version.length > 256 || version.contains("\n") || version.contains("\t")) {
            throw IOException("PACKAGE_METADATA_INVALID")
        }
        rows.putIfAbsent(name, PackageCatalog.Entry(name, version))
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun xml(value: String): XmlPullParser {
        if ((value as java.lang.String).toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) {
            throw IOException("PACKAGE_XML_DOCTYPE")
        }
        val xml: XmlPullParser = Xml.newPullParser()
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        xml.setInput(StringReader(value))
        return xml
    }

    @JvmStatic
    fun description(kind: Kind?, entry: PackageCatalog.Entry, installed: Boolean): String {
        if (kind === Kind.APT) {
            return PackageCatalog.descriptionQuery(entry, installed)
        }
        if (!validName(entry.name)) {
            throw IllegalArgumentException("INVALID_PACKAGE_NAME")
        }
        val name: String = SessionShell.quote(entry.name)
        when (kind!!) {
            Kind.APK -> {
                return "LC_ALL=C apk --no-network info --description -- " + name
            }
            Kind.DNF -> {
                return "LC_ALL=C dnf --cacheonly info -- " + name
            }
            Kind.ZYPPER -> {
                return "LC_ALL=C zypper --xmlout --no-refresh info --type package -- " + name
            }
            Kind.XBPS -> {
                return xbps("LC_ALL=C xbps-query " + (if (installed) "" else "-R ") + "-p short_desc -- " + name)
            }
            Kind.PACMAN -> {
                return "LC_ALL=C pacman " + (if (installed) "-Qi" else "-Si") + " -- " + name
            }
            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parseDescription(kind: Kind?, bytes: ByteArray): String {
        if (kind === Kind.APT) {
            return PackageCatalog.parseDescription(bytes)
        }
        if (bytes.size > 256 * 1024) {
            throw IOException("DESCRIPTION_TOO_LARGE")
        }
        val value: String = (String(bytes, StandardCharsets.UTF_8) as java.lang.String).trim()
        if (kind !== Kind.ZYPPER) {
            return value
        }
        try {
            val xml: XmlPullParser = xml(value)
            var e: Int = xml.getEventType()
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && xml.getName().equals("description")) {
                    return (xml.nextText() as java.lang.String).trim()
                }
                e = xml.next()
            }
            return ""
        } catch (error: Exception) {
            throw IOException("PACKAGE_XML_INVALID", error)
        }
    }

    @JvmStatic
    fun operation(kind: Kind?, action: String, name: String?): String {
        if (kind === Kind.APT) {
            return PackageCatalog.operation(action, name)
        }
        if (action.equals("refresh")) {
            when (kind!!) {
                Kind.APK -> {
                    return "apk update"
                }
                Kind.DNF -> {
                    return "dnf makecache"
                }
                Kind.ZYPPER -> {
                    return "zypper refresh"
                }
                Kind.XBPS -> {
                    return xbps("xbps-install -S")
                }
                Kind.PACMAN -> {
                    return "pacman -Syu"
                }
                else -> {
                    throw IllegalArgumentException()
                }
            }
        }
        if (!validName(name)) {
            throw IllegalArgumentException("INVALID_PACKAGE_NAME")
        }
        val pkg: String = SessionShell.quote(name)
        val install: Boolean = action.equals("install")
        val upgrade: Boolean = action.equals("upgrade")
        val remove: Boolean = action.equals("remove")
        if (!install && !upgrade && !remove) {
            throw IllegalArgumentException()
        }
        when (kind!!) {
            Kind.APK -> {
                return "apk --interactive " + (if (remove) "del" else if (upgrade) "upgrade" else "add") + " -- " + pkg
            }
            Kind.DNF -> {
                return "dnf " + (if (remove) "remove" else if (upgrade) "upgrade" else "install") + " -- " + pkg
            }
            Kind.ZYPPER -> {
                return "zypper " + (if (remove) "remove" else if (upgrade) "update" else "install") + " -- " + pkg
            }
            Kind.XBPS -> {
                return xbps((if (remove) "xbps-remove" else if (upgrade) "xbps-install -u" else "xbps-install") + " -- " + pkg)
            }
            Kind.PACMAN -> {
                return "pacman " + (if (remove) "-R" else "-Syu") + " -- " + pkg
            }
            else -> {
                throw IllegalArgumentException()
            }
        }
    }
}
