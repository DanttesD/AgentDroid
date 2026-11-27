package com.termux.app.workspace

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.TreeMap

/** APT metadata only; mutations are explicitly handed to a new interactive terminal.*/
object PackageCatalog {
    class Entry {
        @JvmField val name: String
        @JvmField val version: String?
        @JvmField val downgrade: Boolean

        constructor(name: String, version: String) : this(name, version, false)

        constructor(name: String, version: String, downgrade: Boolean) {
            this.name = name
            this.version = version
            this.downgrade = downgrade
        }
    }

    const val MAX_BYTES: Int = 16 * 1024 * 1024

    @JvmStatic
    fun validName(name: String?): Boolean {
        return name != null && (name as java.lang.String).matches("[a-z0-9][a-z0-9+.-]*(?::[a-z0-9][a-z0-9-]*)?")
    }

    @JvmStatic
    fun query(tab: Int): String {
        if (tab == 0) {
            return "LC_ALL=C dpkg-query -W -f='\${db:Status-Abbrev}\\t\${binary:Package}\\t\${Version}\\n'"
        }
        if (tab != 1 && tab != 2) {
            throw IllegalArgumentException()
        }
        return "LC_ALL=C apt -o Dir::Cache::pkgcache='' -o Dir::Cache::srcpkgcache='' list" + (if (tab == 2) " --upgradable" else "")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parse(data: ByteArray, tab: Int): MutableList<Entry> {
        if (data.size > MAX_BYTES) {
            throw IOException(WorkspaceText.tr("Каталог пакетов слишком большой или повреждён"))
        }
        val result = TreeMap<String, Entry>()
        for (line in (String(data, StandardCharsets.UTF_8) as java.lang.String).split("\n")) {
            var name: String
            var version: String
            if (tab == 0) {
                val f: Array<String> = (line as java.lang.String).split("\t", -1)
                if (f.size != 3 || !f[0].startsWith("ii")) {
                    continue
                }
                name = f[1]
                version = f[2]
            } else {
                val slash: Int = line.indexOf('/')
                if (slash < 1) {
                    continue
                }
                name = line.substring(0, slash)
                val f: Array<String> = ((line.substring(slash + 1) as java.lang.String).trim() as java.lang.String).split("\\s+")
                if (f.size < 3) {
                    throw IOException(WorkspaceText.tr("Каталог пакетов слишком большой или повреждён"))
                }
                version = f[1]
            }
            if (!validName(name) || version.length > 256 || version.isEmpty()) {
                throw IOException(WorkspaceText.tr("Каталог пакетов слишком большой или повреждён"))
            }
            result.put(name, Entry(name, version))
        }
        return ArrayList<Entry>(result.values)
    }

    @JvmStatic
    fun operation(action: String, name: String?): String {
        if (action.equals("refresh")) {
            return "apt-get update"
        }
        if (!validName(name)) {
            throw IllegalArgumentException(WorkspaceText.tr("Некорректное имя пакета"))
        }
        when (action) {
            "install" -> {
                return "apt-get install -- " + SessionShell.quote(name)
            }
            "upgrade" -> {
                return "apt-get install --only-upgrade -- " + SessionShell.quote(name)
            }
            "remove" -> {
                return "apt-get remove -- " + SessionShell.quote(name)
            }
            else -> {
                throw IllegalArgumentException()
            }
        }
    }

    @JvmStatic
    fun validVersion(version: String?): Boolean {
        return version != null && version.length <= 256 && (version as java.lang.String).matches("[0-9][A-Za-z0-9.+:~\\-]*")
    }

    @JvmStatic
    fun versionOperation(entry: Entry?): String {
        if (!validName(entry!!.name) || !validVersion(entry.version)) {
            throw IllegalArgumentException(WorkspaceText.tr("Некорректная версия пакета"))
        }
        return "apt-get install -- " + SessionShell.quote(entry.name + "=" + entry.version)
    }

    @JvmStatic
    fun versionsQuery(name: String): String {
        if (!validName(name)) {
            throw IllegalArgumentException(WorkspaceText.tr("Некорректное имя пакета"))
        }
        return "set -o pipefail; metadata=\$(LC_ALL=C dpkg-query -W -f='\${db:Status-Abbrev}|\${Version}' -- " + SessionShell.quote(name) + " 2>/dev/null) || metadata=''; installed=''; case \"\$metadata\" in ii*) installed=\"\${metadata#*|}\";; esac; LC_ALL=C apt-cache -o Dir::Cache::pkgcache='' -o Dir::Cache::srcpkgcache='' madison " + SessionShell.quote(name) + " | while IFS='|' read -r package version repository; do version=\"\${version//[[:space:]]/}\"; [ -n \"\$version\" ] || continue; lower=0; if [ -n \"\$installed\" ] && dpkg --compare-versions \"\$version\" lt \"\$installed\"; then lower=1; fi; printf '%s\\t%s\\n' \"\$version\" \"\$lower\"; done"
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parseVersions(name: String, bytes: ByteArray): MutableList<Entry> {
        if (!validName(name) || bytes.size > 1024 * 1024) {
            throw IOException(WorkspaceText.tr("Некорректная версия пакета"))
        }
        val entries = LinkedHashMap<String, Entry>()
        for (line in (String(bytes, StandardCharsets.UTF_8) as java.lang.String).split("\n")) {
            if (line.isEmpty()) {
                continue
            }
            val fields: Array<String> = (line as java.lang.String).split("\t", -1)
            if (fields.size != 2 || !validVersion(fields[0]) || !(fields[1] as java.lang.String).matches("[01]")) {
                throw IOException(WorkspaceText.tr("Некорректная версия пакета"))
            }
            entries.put(fields[0], Entry(name, fields[0], fields[1].equals("1")))
            if (entries.size > 1000) {
                throw IOException(WorkspaceText.tr("Каталог пакетов слишком большой или повреждён"))
            }
        }
        return ArrayList<Entry>(entries.values)
    }

    @JvmStatic
    fun descriptionQuery(entry: Entry, installed: Boolean): String {
        if (!validName(entry.name) || !validVersion(entry.version)) {
            throw IllegalArgumentException()
        }
        if (installed) {
            return "LC_ALL=C dpkg-query -W -f='Description: \${Description}\\n' -- " + SessionShell.quote(entry.name)
        }
        return "LC_ALL=C apt-cache -o Dir::Cache::pkgcache='' -o Dir::Cache::srcpkgcache='' show --no-all-versions " + SessionShell.quote(entry.name + "=" + entry.version)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun parseDescription(bytes: ByteArray): String {
        if (bytes.size > 256 * 1024) {
            throw IOException(WorkspaceText.tr("Описание недоступно. Обновите каталог или проверьте выбранное окружение."))
        }
        val description = StringBuilder()
        var active = false
        for (line in (String(bytes, StandardCharsets.UTF_8) as java.lang.String).split("\n", -1)) {
            if (active) {
                if (line.startsWith(" ")) {
                    val value: String = line.substring(1)
                    description.append('\n').append(if (value.equals(".")) "" else value)
                    continue
                }
                break
            }
            if (line.startsWith("Description: ") || line.startsWith("Description-en: ")) {
                active = true
                description.append((line.substring(line.indexOf(':') + 1) as java.lang.String).trim())
            }
        }
        return (description.toString() as java.lang.String).trim()
    }

    @JvmStatic
    fun matchesTarget(profile: WorkspaceStore.Session?, heldHandle: String?, actualHandle: String, environment: String): Boolean {
        return heldHandle != null && !heldHandle.isEmpty() && heldHandle.equals(actualHandle) && ProjectFiles.environment(profile).equals(environment)
    }

    @JvmStatic
    fun operationProfile(source: WorkspaceStore.Session, command: String): WorkspaceStore.Session {
        val env: String = ProjectFiles.environment(source)
        if (!env.equals("termux") && !env.equals("ubuntu") && !env.startsWith("proot:")) {
            throw IllegalArgumentException()
        }
        val target = WorkspaceStore.Session()
        target.name = WorkspaceText.tr("Пакеты · ") + ProjectFiles.environmentLabel(source)
        target.environment = if (env.startsWith("proot:")) "termux" else env
        target.prootAlias = if (env.startsWith("proot:")) env.substring(6) else ""
        target.prootIsolated = source.prootIsolated
        target.directory = "~"
        target.startup = command
        if (!target.prootAlias.isEmpty()) {
            if (!WorkspaceLocalFilePicker.validAlias(target.prootAlias)) {
                throw IllegalArgumentException("INVALID_ENVIRONMENT")
            }
            target.startup = "if [ \"\${TERMUX_JOBS_ENVIRONMENT_ALIAS:-}\" = " + SessionShell.quote(target.prootAlias) + " ]; then\n" + EnvironmentPackages.nativeCommand(command) + "\nelse printf '%s\\n' 'Wrong environment. Open a new session in the selected environment.' >&2; false; fi"
        }
        return target
    }
}
