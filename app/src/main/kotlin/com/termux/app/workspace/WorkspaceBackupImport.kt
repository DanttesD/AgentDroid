package com.termux.app.workspace

import android.app.Activity
import android.content.SharedPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Objects
import java.util.UUID
import java.util.function.Predicate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Validates complete library before mutation; imports only chosen data, never live handles.*/
object WorkspaceBackupImport {
    class Choice {
        @JvmField var commands: Boolean = false
        @JvmField var notes: Boolean = false
        @JvmField var presets: Boolean = false
        @JvmField var history: Boolean = false
        @JvmField var settings: Boolean = false
        @JvmField var replace: Boolean = false
        @JvmField var candidates: MutableSet<Int> = HashSet<Int>()
    }

    class Prepared {
        @JvmField val commands: MutableList<WorkspaceStore.Command> = ArrayList<WorkspaceStore.Command>()
        @JvmField val notes: MutableList<WorkspaceStore.Note> = ArrayList<WorkspaceStore.Note>()
        @JvmField val presets: MutableList<WorkspaceStore.Session?> = ArrayList<WorkspaceStore.Session?>()
    }

    @Throws(Exception::class)
    @JvmStatic
    fun prepare(a: Activity, report: JSONObject?, restored: JSONObject?, choice: Choice): Prepared {
        val out = Prepared()
        val library: JSONObject? = report!!.optJSONObject("workspace")
        if (library != null) {
            val incoming = WorkspaceStore(a, WorkspaceStore.ErrorListener { e ->  })
            try {
                incoming.decode(library.toString())
                if (choice.commands) {
                    for (c in incoming.commands) {
                        c.id = UUID.randomUUID().toString()
                        out.commands.add(c)
                    }
                }
                if (choice.notes) {
                    for (n in incoming.notes) {
                        n.id = UUID.randomUUID().toString()
                        out.notes.add(n)
                    }
                }
                if (choice.presets) {
                    for (s in incoming.sessions) {
                        if (WorkspaceStore.isBase(s) || !s!!.saved) {
                            continue
                        }
                        val oldId: String = s.id
                        s.id = UUID.randomUUID().toString()
                        s.restoreReview = true
                        s.handle = ""
                        s.sshEncryptedPassword = ""
                        s.geminiEncryptedKey = ""
                        s.codexSocketId = UUID.randomUUID().toString()
                        s.claudeSocketId = UUID.randomUUID().toString()
                        s.geminiSocketId = UUID.randomUUID().toString()
                        s.aiConnection = UUID.randomUUID().toString()
                        s.aiSocketId = ""
                        s.saved = true
                        rebind(s, restored)
                        if (choice.history) {
                            history(a, report, restored, oldId, s.id)
                        }
                        out.presets.add(s)
                    }
                }
            } finally {
                incoming.close()
            }
        }
        val candidates: JSONArray? = report.optJSONArray("candidates")
        val paths: JSONObject? = restored!!.optJSONObject("paths")
        if (candidates != null) {
            for (index in choice.candidates) {
                val c: JSONObject = candidates.getJSONObject(index)
                val command = WorkspaceStore.Command()
                command.name = c.getString("name")
                command.category = "Imported"
                if (c.getString("kind").equals("alias")) {
                    command.command = c.getString("command")
                } else {
                    val component: String = Integer.toString(c.getInt("component"))
                    val target: String? = if (paths == null) null else paths.optString(component, null)
                    if (target == null) {
                        throw IllegalArgumentException("SCRIPT_COMPONENT_NOT_RESTORED")
                    }
                    val root: String = c.optString("root")
                    val source: String = c.getString("source")
                    val relative: String = if (root.isEmpty()) source else source.substring(root.length + 1)
                    if (target.startsWith("proot-distro login ")) {
                        command.command = target + " -- /bin/bash " + SessionShell.quote("/" + relative)
                    } else {
                        val file = File(target, relative)
                        if (!file.getCanonicalPath().startsWith(File(target).getCanonicalPath() + "/") || WorkspaceBackupEngine.symlink(file) || !file.isFile()) {
                            throw IllegalArgumentException("SCRIPT_COMPONENT_NOT_RESTORED")
                        }
                        command.command = "bash " + SessionShell.quote(file.getAbsolutePath())
                        command.directory = target
                    }
                }
                out.commands.add(command)
            }
        }
        val envs: JSONArray? = restored.optJSONArray("environments")
        if (envs != null) {
            var i = 0
            while (i < envs.length()) {
                val alias: String = envs.getString(i)
                if (!(alias as java.lang.String).matches("jobs-[a-f0-9]{12}")) {
                    throw IllegalArgumentException("Invalid environment")
                }
                val s = WorkspaceStore.Session()
                s.name = "Linux · " + alias
                s.environment = "termux"
                s.prootAlias = alias
                s.prootIsolated = true
                s.directory = "/"
                s.startup = ""
                s.saved = true
                s.restoreReview = true
                out.presets.add(s)
                i++
            }
        }
        return out
    }

    @Throws(JSONException::class)
    @JvmStatic
    private fun rebind(s: WorkspaceStore.Session?, restored: JSONObject?) {
        // Restored agents keep their existing, explicit review path; do not migrate the shared Ubuntu.
        if (s!!.environment.equals("codex") || s.geminiAgent || s.claudeAgent || !s.aiAgent.isEmpty()) {
            return
        }
        val source: String = WorkspaceEnvironments.alias(s)
        val bindings: JSONObject? = restored!!.optJSONObject("environmentBindings")
        if (source.isEmpty() || bindings == null || bindings.isNull(source) || !bindings.has(source)) {
            return
        }
        val target: String = bindings.getString(source)
        if (!(target as java.lang.String).matches("jobs-[a-f0-9]{12}")) {
            throw JSONException("Invalid restored environment binding")
        }
        s.environment = "termux"
        s.prootAlias = target
        s.prootIsolated = true
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun history(a: Activity, report: JSONObject?, restored: JSONObject?, oldId: String, newId: String) {
        val components: JSONArray = report!!.getJSONArray("components")
        val paths: JSONObject? = restored!!.optJSONObject("paths")
        if (paths == null) {
            throw IllegalArgumentException("APP_DATA_REQUIRED")
        }
        var i = 0
        while (i < components.length()) {
            if (components.getJSONObject(i).optString("kind").equals("app-data") && paths.has(Integer.toString(i))) {
                val root = File(paths.getString(Integer.toString(i)))
                val file = File(root, "workspace-sessions/" + oldId + "/history")
                if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + "/") || WorkspaceBackupEngine.symlink(file)) {
                    throw IllegalArgumentException("UNSAFE_HISTORY")
                }
                if (file.isFile()) {
                    if (file.length() > 64 * 1024 * 1024) {
                        throw IllegalArgumentException("HISTORY_TOO_LARGE")
                    }
                    val dir: File = SessionShell.directory(a.getFilesDir(), newId)
                    if (!dir.mkdirs()) {
                        throw IllegalArgumentException("HISTORY_DIRECTORY")
                    }
                    (FileInputStream(file)).use { `in` ->
                        (FileOutputStream(File(dir, "history"))).use { out ->
                            val bytes = ByteArray(8192)
                            var n: Int = 0
                            while (true) {
                                n = `in`.read(bytes)
                                if (n == -1) break
                                out.write(bytes, 0, n)
                            }
                        }
                    }
                    return
                }
                return
            }
            i++
        }
        throw IllegalArgumentException("APP_DATA_REQUIRED")
    }

    @Throws(Exception::class)
    @JvmStatic
    fun apply(activity: Activity, store: WorkspaceStore, data: Prepared, choice: Choice) {
        if (!store.isWritable()) {
            throw IllegalStateException("LIBRARY_READ_ONLY")
        }
        val recovery = File(activity.getFilesDir(), "workspace-import-recovery")
        if (!recovery.isDirectory() && !recovery.mkdirs()) {
            throw IOException("Recovery directory")
        }
        val snapshot = File(recovery, "before-" + UUID.randomUUID() + ".json")
        (FileOutputStream(snapshot)).use { out ->
            out.write((store.encode().toString() as java.lang.String).getBytes(StandardCharsets.UTF_8))
        }
        if (choice.replace) {
            if (choice.commands) {
                store.commands.clear()
            }
            if (choice.notes) {
                store.notes.clear()
            }
            if (choice.presets) {
                store.sessions.removeIf(Predicate<WorkspaceStore.Session?> { s -> s!!.saved && s.handle.isEmpty() && !WorkspaceStore.isBase(s) })
            }
        }
        for (c in data.commands) {
            val existing: WorkspaceStore.Command? = store.commands.stream().filter(Predicate<WorkspaceStore.Command> { x -> x.name.equals(c.name) && x.command!!.equals(c.command) && x.directory.equals(c.directory) }).findFirst().orElse(null)
            if (existing == null) {
                store.commands.add(c)
            } else {
                c.id = existing.id
            }
        }
        for (n in data.notes) {
            val existing: WorkspaceStore.Note? = store.notes.stream().filter(Predicate<WorkspaceStore.Note> { x -> x.name.equals(n.name) && x.text.equals(n.text) }).findFirst().orElse(null)
            if (existing == null) {
                store.notes.add(n)
            } else {
                n.id = existing.id
            }
        }
        // Each restored preset retains its freshly allocated ID and history directory.
        // Names are not identities: even otherwise identical copies can own different histories.
        for (s in data.presets) {
            store.sessions.add(s)
        }
        store.save()
    }

    @JvmStatic
    fun persisted(saved: JSONObject, data: Prepared): Boolean {
        for (kind in arrayOf<String>("commands", "notes", "sessions")) {
            val ids: MutableSet<String> = HashSet<String>()
            val array: JSONArray? = saved.optJSONArray(kind)
            if (array == null) {
                return false
            }
            var i = 0
            while (i < array.length()) {
                ids.add(array.optJSONObject(i).optString("id"))
                i++
            }
            if (kind.equals("commands")) {
                for (c in data.commands) {
                    if (!ids.contains(c.id)) {
                        return false
                    }
                }
            }
            if (kind.equals("notes")) {
                for (n in data.notes) {
                    if (!ids.contains(n.id)) {
                        return false
                    }
                }
            }
            if (kind.equals("sessions")) {
                for (s in data.presets) {
                    var actual: JSONObject? = null
                    var i = 0
                    while (i < array.length()) {
                        val item: JSONObject? = array.optJSONObject(i)
                        if (item != null && s!!.id.equals(item.optString("id"))) {
                            actual = item
                            break
                        }
                        i++
                    }
                    try {
                        if (actual == null || !sameJson(actual, WorkspaceStore.sessionJson(s))) {
                            return false
                        }
                    } catch (error: JSONException) {
                        return false
                    }
                }
            }
        }
        return true
    }

    @Throws(JSONException::class)
    @JvmStatic
    private fun sameJson(left: Any, right: Any): Boolean {
        if (left is JSONObject && right is JSONObject) {
            val a: JSONObject = left as JSONObject
            val b: JSONObject = right as JSONObject
            if (a.length() != b.length()) {
                return false
            }
            val keys: MutableIterator<String> = a.keys()
            while (keys.hasNext()) {
                val key: String = keys.next()
                if (!b.has(key) || !sameJson(a.get(key), b.get(key))) {
                    return false
                }
            }
            return true
        }
        if (left is JSONArray && right is JSONArray) {
            val a: JSONArray = left as JSONArray
            val b: JSONArray = right as JSONArray
            if (a.length() != b.length()) {
                return false
            }
            var i = 0
            while (i < a.length()) {
                if (!sameJson(a.get(i), b.get(i))) {
                    return false
                }
                i++
            }
            return true
        }
        if (left is Number && right is Number) {
            return BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
        }
        return Objects.equals(left, right)
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun settings(a: Activity): JSONObject {
        val out = JSONObject()
        for (name in arrayOf<String>("workspace-language", "workspace-input", "com.termux_preferences")) {
            val values = JSONObject()
            for (entry in a.getSharedPreferences(name, 0).getAll().entries) {
                val v: Any? = entry.value
                if (v is String || v is Boolean || v is Int || v is Long || v is Float) {
                    values.put(entry.key, v)
                }
            }
            out.put(name, values)
        }
        return out
    }

    @Throws(JSONException::class)
    @JvmStatic
    fun settings(a: Activity, value: JSONObject?) {
        if (value == null) {
            return
        }
        for (name in arrayOf<String>("workspace-language", "workspace-input", "com.termux_preferences")) {
            val data: JSONObject? = value.optJSONObject(name)
            if (data == null) {
                continue
            }
            val edit: SharedPreferences.Editor = a.getSharedPreferences(name, 0).edit()
            val keys: MutableIterator<String> = data.keys()
            while (keys.hasNext()) {
                val key: String = keys.next()
                if (key.equals("current_session") || key.equals("jobs_log_off_default_v1")) {
                    continue
                }
                val v: Any = data.get(key)
                if (v is Boolean) {
                    edit.putBoolean(key, v as Boolean)
                } else if (v is Int) {
                    edit.putInt(key, v as Int)
                } else if (v is Long) {
                    edit.putLong(key, v as Long)
                } else if (v is Number) {
                    edit.putFloat(key, (v as Number).toFloat())
                } else if (v is String && (v as String).length < 64 * 1024) {
                    edit.putString(key, v as String)
                }
            }
            edit.apply()
        }
    }
}
