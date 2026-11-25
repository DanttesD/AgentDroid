package com.termux.app.workspace

import android.system.Os
import android.system.StructStat
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale
import java.util.Objects
import java.util.TreeSet
import java.util.UUID
import java.util.function.ToLongFunction
import org.json.JSONArray
import org.json.JSONObject

/** Observed installed-list changes, shared by presets using the same concrete rootfs.*/
object WorkspacePackageHistory {
    const val LIMIT: Int = 300
    const val MAX_BYTES: Int = 2 * 1024 * 1024

    @Throws(Exception::class)
    @JvmStatic
    fun key(scope: String, root: String, device: Long, inode: Long): String {
        val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(((scope + "\n" + root + "\n" + device + ":" + inode) as java.lang.String).getBytes(StandardCharsets.UTF_8))
        val value = StringBuilder()
        for (b in hash) {
            value.append(java.lang.String.format(Locale.ROOT, "%02x", b.toInt() and 255))
        }
        return value.toString()
    }

    @Throws(Exception::class)
    @JvmStatic
    fun key(profile: WorkspaceStore.Session?): String {
        val scope: String = WorkspaceStorageCleanup.scope(profile)
        if (scope.isEmpty()) {
            throw IOException()
        }
        var root: File? = if (scope.equals("termux")) File(TermuxConstants.TERMUX_PREFIX_DIR_PATH) else WorkspaceEnvironments.root(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"), scope)
        if (root == null) {
            throw IOException()
        }
        root = root.getCanonicalFile()
        val st: StructStat = Os.lstat(root!!.getPath())
        return key(scope, root.getPath(), st.st_dev, st.st_ino)
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun file(files: File, key: String): Path {
        if (!(key as java.lang.String).matches("[a-f0-9]{64}")) {
            throw IOException()
        }
        val parent: Path = files.getCanonicalFile().toPath()
        val dir: Path = parent.resolve("workspace-package-history")
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir)
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().equals(dir)) {
            throw IOException()
        }
        return dir.resolve(key + ".json")
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun load(file: Path): JSONObject {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return JSONObject().put("schema", 1).put("baseline", false).put("packages", JSONObject()).put("events", JSONArray())
        }
        if (Files.size(file) > MAX_BYTES) {
            throw IOException()
        }
        val bytes = ByteArrayOutputStream()
        (Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { `in` ->
            val buffer: ByteBuffer = ByteBuffer.allocate(8192)
            var n: Int = 0
            while (true) {
                n = `in`.read(buffer)
                if (n == -1) break
                if (bytes.size() + n > MAX_BYTES) {
                    throw IOException()
                }
                bytes.write(buffer.array(), 0, n)
                buffer.clear()
            }
        }
        val data = JSONObject(bytes.toString("UTF-8"))
        if (data.optInt("schema") != 1 || data.optJSONObject("packages") == null || data.optJSONArray("events") == null || data.getJSONArray("events").length() > LIMIT) {
            throw IOException()
        }
        return data
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun save(target: Path, data: JSONObject) {
        val bytes: ByteArray = (data.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            throw IOException()
        }
        val temp: Path = target.resolveSibling("" + UUID.randomUUID() + ".tmp")
        try {
            (Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { out ->
                val buffer: ByteBuffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    out.write(buffer)
                }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Throws(Exception::class)
    @Synchronized
    @JvmStatic
    fun observe(files: File, key: String, installed: MutableList<PackageCatalog.Entry>?, now: Long) {
        val path: Path = file(files, key)
        val old: JSONObject = load(path)
        val previous: JSONObject = old.getJSONObject("packages")
        val current = JSONObject()
        val names = TreeSet<String>()
        for (entry in installed!!) {
            if (!EnvironmentPackages.validName(entry.name) || entry.version == null || entry.version.length > 256) {
                throw IOException()
            }
            current.put(entry.name, entry.version)
            names.add(entry.name)
        }
        val it: MutableIterator<String> = previous.keys()
        while (it.hasNext()) {
            names.add(it.next())
        }
        val events: JSONArray = old.getJSONArray("events")
        if (old.optBoolean("baseline")) {
            for (name in names) {
                val before: String? = if (previous.has(name)) previous.getString(name) else null
                val after: String? = if (current.has(name)) current.getString(name) else null
                if (Objects.equals(before, after)) {
                    continue
                }
                var combined = false
                for (i in events.length() - 1 downTo 0) {
                    val event: JSONObject = events.getJSONObject(i)
                    if (name.equals(event.optString("name")) && event.optString("phase").equals("success") && event.optLong("observed") >= old.optLong("snapshotAt")) {
                        event.put("before", if (before == null) "" else before).put("after", if (after == null) "" else after).put("reconciled", true)
                        combined = true
                        break
                    }
                }
                if (!combined) {
                    events.put(JSONObject().put("name", name).put("action", if (before == null) "installed" else if (after == null) "removed" else "changed").put("before", if (before == null) "" else before).put("after", if (after == null) "" else after).put("observed", now))
                }
            }
        }
        val trimmed = JSONArray()
        var i: Int = Math.max(0, events.length() - LIMIT)
        while (i < events.length()) {
            trimmed.put(events.get(i))
            i++
        }
        save(path, JSONObject().put("schema", 1).put("baseline", true).put("snapshotAt", now).put("packages", current).put("events", trimmed))
    }

    @Throws(Exception::class)
    @Synchronized
    @JvmStatic
    fun operation(files: File, key: String, metadata: JSONObject, exit: Int?, now: Long) {
        val path: Path = file(files, key)
        val data: JSONObject = load(path)
        val events: JSONArray = data.getJSONArray("events")
        var i = 0
        while (i < events.length()) {
            if (metadata.getString("id").equals(events.getJSONObject(i).optString("operationId"))) {
                return
            }
            i++
        }
        events.put(JSONObject().put("operationId", metadata.getString("id")).put("name", metadata.getString("name")).put("action", "operation").put("operation", metadata.getString("operation")).put("after", metadata.optString("version")).put("observed", now).put("exit", if (exit == null) -1 else exit).put("phase", if (exit == null) "unknown" else if (exit == 0) "success" else if (exit == 130) "cancelled" else "failure"))
        val trimmed = JSONArray()
        run {
            var i: Int = Math.max(0, events.length() - LIMIT)
            while (i < events.length()) {
                trimmed.put(events.get(i))
                i++
            }
        }
        data.put("events", trimmed)
        save(path, data)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun merge(finished: JSONArray?, pending: JSONArray): JSONArray {
        val rows: MutableList<JSONObject> = ArrayList<JSONObject>()
        for (source in arrayOf<JSONArray?>(finished, pending)) {
            var i = 0
            while (i < source!!.length()) {
                rows.add(source.getJSONObject(i))
                i++
            }
        }
        rows.sortWith(Comparator.comparingLong(ToLongFunction<JSONObject> { v -> v.optLong("observed") }))
        val result = JSONArray()
        var i: Int = Math.max(0, rows.size - LIMIT)
        while (i < rows.size) {
            result.put(rows.get(i))
            i++
        }
        return result
    }

    @Throws(Exception::class)
    @Synchronized
    @JvmStatic
    fun read(files: File, key: String): JSONArray {
        return load(file(files, key)).getJSONArray("events")
    }
}
