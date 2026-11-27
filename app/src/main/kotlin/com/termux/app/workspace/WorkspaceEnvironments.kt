package com.termux.app.workspace

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Arrays
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Concrete proot aliases, independent of session name and AI provider.*/
object WorkspaceEnvironments {
    class Choice {
        @JvmField val environment: String
        @JvmField val alias: String
        @JvmField val label: String
        @JvmField val home: String
        @JvmField val installed: Boolean

        constructor(e: String, a: String, l: String, h: String, i: Boolean) {
            environment = e
            alias = a
            label = l
            home = h
            installed = i
        }
    }

    const val OLD_READER_GUARD: String = "printf '%s\\n' 'This environment preset requires a newer Termux Jobs version. Its commands were not run.' >&2; false"

    @JvmStatic
    fun alias(s: WorkspaceStore.Session?): String {
        if (!s!!.prootAlias.isEmpty()) {
            return s.prootAlias
        }
        if (s.environment.startsWith("proot:")) {
            return s.environment.substring(6)
        }
        return if (s.environment.equals("ubuntu")) "ubuntu" else ""
    }

    @JvmStatic
    fun label(alias: String): String {
        when (alias) {
            "jobs-x11-ubuntu" -> {
                return "Ubuntu X11 Test"
            }
            "ubuntu-ai" -> {
                return "Ubuntu AI"
            }
            "ubuntu" -> {
                return "Ubuntu"
            }
            "debian" -> {
                return "Debian"
            }
            "alpine" -> {
                return "Alpine"
            }
            "archlinux" -> {
                return "Arch Linux"
            }
            "fedora" -> {
                return "Fedora"
            }
            "opensuse" -> {
                return "openSUSE"
            }
            "void" -> {
                return "Void Linux"
            }
            else -> {
                return alias
            }
        }
    }

    @JvmStatic
    fun root(proot: File, alias: String): File? {
        if (!WorkspaceLocalFilePicker.validAlias(alias)) {
            return null
        }
        for (layout in arrayOf<String>("containers", "installed-rootfs")) {
            val parent = File(proot, layout)
            val container = File(parent, alias)
            val fs: File = if (layout.equals("containers")) File(container, "rootfs") else container
            try {
                if (fs.isDirectory() && container.getCanonicalFile().equals(File(parent.getCanonicalFile(), alias)) && fs.getCanonicalFile().equals(File(fs.getParentFile()!!.getCanonicalFile(), fs.getName()))) {
                    return fs
                }
            } catch (ignored: IOException) {
            }
        }
        return null
    }

    /** OCI run bypasses an invalid passwd Bash only when no Entrypoint can change our command.*/
    @JvmStatic
    fun verb(proot: File, alias: String): String {
        val fs: File? = root(proot, alias)
        if (fs == null || File(fs, "bin/bash").exists() || !File(fs, "bin/sh").exists()) {
            return "login"
        }
        val manifest = File(proot, "containers/" + alias + "/manifest.json")
        val passwd = File(fs, "etc/passwd")
        try {
            if (!manifest.isFile() || manifest.length() > 64 * 1024 || Files.isSymbolicLink(manifest.toPath()) || !passwd.isFile() || passwd.length() > 256 * 1024 || Files.isSymbolicLink(passwd.toPath())) {
                return "login"
            }
            if (!passwd.getCanonicalPath().startsWith(fs.getCanonicalPath() + "/")) {
                return "login"
            }
            var badRoot = false
            for (line in Files.readAllLines(passwd.toPath(), StandardCharsets.UTF_8)) {
                val fields: Array<String> = (line as java.lang.String).split(":", -1)
                if (fields.size >= 7 && fields[0].equals("root")) {
                    badRoot = fields[6].equals("/bin/bash")
                }
            }
            if (!badRoot) {
                return "login"
            }
            val data = JSONObject(String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8))
            val image: JSONObject? = data.optJSONObject("image_config")
            val config: JSONObject? = if (image == null) null else image.optJSONObject("config")
            if (config == null) {
                return "login"
            }
            for (key in arrayOf<String>("Entrypoint", "Cmd")) {
                if (config.has(key) && !config.isNull(key)) {
                    val value: JSONArray? = config.optJSONArray(key)
                    if (value == null) {
                        return "login"
                    }
                    if (key.equals("Entrypoint") && value.length() != 0) {
                        return "login"
                    }
                    var k = 0
                    while (k < value.length()) {
                        if (!(value.get(k) is String)) {
                            return "login"
                        }
                        k++
                    }
                }
            }
            return "run"
        } catch (error: IOException) {
            return "login"
        } catch (error: JSONException) {
            return "login"
        }
    }

    @JvmStatic
    fun verb(alias: String): String {
        return verb(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"), alias)
    }

    @JvmStatic
    fun entryCommand(alias: String, isolated: Boolean): String {
        val verb: String = verb(alias)
        return "proot-distro " + verb + " " + (if (isolated) "--isolated " else "") + (if (verb.equals("run")) "--work-dir /root " else "") + SessionShell.quote(alias) + (if (verb.equals("run")) " -- /bin/sh -i" else "")
    }

    @JvmStatic
    fun choices(home: File, proot: File): MutableList<Choice> {
        val rows: MutableList<Choice> = ArrayList<Choice>()
        rows.add(Choice("termux", "", "Termux", home.getPath(), true))
        val names: MutableSet<String> = LinkedHashSet<String>(Arrays.asList("ubuntu", "debian", "alpine", "archlinux", "fedora", "opensuse", "void"))
        for (location in WorkspaceLocalFilePicker.locations(home, proot, "")) {
            if (location.environment.startsWith("proot:")) {
                names.add(location.environment.substring(6))
            }
        }
        for (alias in names) {
            val fs: File? = root(proot, alias)
            rows.add(Choice(if (alias.equals("ubuntu")) "ubuntu" else "proot:" + alias, alias, label(alias), if (fs != null && File(fs, "root").isDirectory()) "/root" else "/", fs != null))
        }
        return rows
    }

    @JvmStatic
    fun choices(): MutableList<Choice> {
        return choices(File(TermuxConstants.TERMUX_HOME_DIR_PATH), File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"))
    }
}
