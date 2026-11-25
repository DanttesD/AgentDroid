package com.termux.app.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Arrays
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Exit-code receipts in an existing session bind; no PTY parsing, output capture or polling.*/
object PackageOperationJournal {
    class Intent {
        @JvmField val action: String
        @JvmField val name: String
        @JvmField val version: String?

        constructor(action: String, name: String, version: String?) {
            this.action = action
            this.name = name
            this.version = version
        }
    }

    @JvmStatic
    private fun sessionId(value: String?): Boolean {
        return value != null && (value as java.lang.String).matches("[A-Za-z0-9][A-Za-z0-9-]{0,79}")
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun dir(files: File): Path {
        val parent: Path = files.getCanonicalFile().toPath()
        val dir: Path = parent.resolve("workspace-package-jobs")
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir)
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.toRealPath().equals(dir)) {
            throw IOException()
        }
        return dir
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun read(file: Path, max: Int): ByteArray {
        if (Files.size(file) > max) {
            throw IOException()
        }
        val out = ByteArrayOutputStream()
        (Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { `in` ->
            val buffer: ByteBuffer = ByteBuffer.allocate(1024)
            var n: Int = 0
            while (true) {
                n = `in`.read(buffer)
                if (n == -1) break
                if (out.size() + n > max) {
                    throw IOException()
                }
                out.write(buffer.array(), 0, n)
                buffer.clear()
            }
        }
        return out.toByteArray()
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun sessionDirectory(files: File, id: String, create: Boolean): File {
        val root: Path = files.getCanonicalFile().toPath().resolve("workspace-sessions")
        if (create && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(root)
        }
        if (Files.isSymbolicLink(root) || !root.toFile().getCanonicalFile().toPath().equals(root)) {
            throw IOException()
        }
        val session: Path = root.resolve(id)
        if (create && !Files.exists(session, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(session)
        }
        if (Files.isSymbolicLink(session) || !session.toFile().getCanonicalFile().toPath().equals(session)) {
            throw IOException()
        }
        return session.toFile()
    }

    @JvmStatic
    fun wrap(command: String, result: String): String {
        return "(\n" + command + "\n_jobs_package_exit=\$?\n( umask 077; set -C; printf '%s\\n' \"\$_jobs_package_exit\" > " + SessionShell.quote(result) + " ) 2>/dev/null || :\nexit \"\$_jobs_package_exit\"\n)"
    }

    @Throws(Exception::class)
    @Synchronized
    @JvmStatic
    fun prepare(files: File, historyKey: String, id: String, guest: Boolean, intent: Intent?, command: String): String {
        if (!sessionId(id) || !(historyKey as java.lang.String).matches("[a-f0-9]{64}") || intent == null || !EnvironmentPackages.validName(intent.name) || !Arrays.asList("install", "upgrade", "remove", "version").contains(intent.action)) {
            throw IOException()
        }
        val session: File = sessionDirectory(files, id, true)
        val token: String = (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
        val result: String = "package-result-" + token
        val value: JSONObject = JSONObject().put("id", token).put("key", historyKey).put("session", id).put("name", intent.name).put("operation", intent.action).put("version", intent.version).put("started", System.currentTimeMillis())
        val path: Path = dir(files).resolve(token + ".json")
        val data: ByteArray = (value.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8)
        (Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { out ->
            val buffer: ByteBuffer = ByteBuffer.wrap(data)
            while (buffer.hasRemaining()) {
                out.write(buffer)
            }
        }
        return wrap(command, if (guest) "/tmp/termux-fork-session-" + id + "/" + result else File(session, result).getAbsolutePath())
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun metadata(path: Path): JSONObject {
        val value = JSONObject(String(read(path, 16384), StandardCharsets.UTF_8))
        if (!path.getFileName().toString().equals(value.getString("id") + ".json") || !(value.getString("id") as java.lang.String).matches("[a-f0-9]{32}") || !(value.getString("key") as java.lang.String).matches("[a-f0-9]{64}") || !sessionId(value.getString("session")) || !EnvironmentPackages.validName(value.getString("name"))) {
            throw IOException()
        }
        if (!Arrays.asList("install", "upgrade", "remove", "version").contains(value.getString("operation"))) {
            throw IOException()
        }
        return value
    }

    @Throws(Exception::class)
    @Synchronized
    @JvmStatic
    fun collect(files: File, closingSession: String?) {
        (Files.newDirectoryStream(dir(files), "*.json")).use { paths ->
            var count = 0
            for (path in paths) {
                if (++count > 1000) {
                    break
                }
                try {
                    val meta: JSONObject = metadata(path)
                    val result: Path = sessionDirectory(files, meta.getString("session"), false).toPath().resolve("package-result-" + meta.getString("id"))
                    var exit: Int? = null
                    if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
                        val code: String = (String(read(result, 16), StandardCharsets.US_ASCII) as java.lang.String).trim()
                        if (code.isEmpty()) {
                            continue
                        }
                        if (!(code as java.lang.String).matches("[0-9]{1,3}") || Integer.parseInt(code) > 255) {
                            throw IOException()
                        }
                        exit = Integer.parseInt(code)
                    }
                    if (exit == null && !meta.getString("session").equals(closingSession)) {
                        continue
                    }
                    WorkspacePackageHistory.operation(files, meta.getString("key"), meta, exit, System.currentTimeMillis())
                    Files.delete(path)
                    if (exit != null) {
                        Files.deleteIfExists(result)
                    }
                } catch (ignored: Exception) {
                    /* Leave the receipt for a later retry; never read a linked result.*/
                }
            }
        }
    }

    @Throws(Exception::class)
    @Synchronized
    @JvmStatic
    fun pending(files: File, key: String): JSONArray {
        val result = JSONArray()
        (Files.newDirectoryStream(dir(files), "*.json")).use { paths ->
            for (path in paths) {
                if (result.length() >= WorkspacePackageHistory.LIMIT) {
                    break
                }
                try {
                    val meta: JSONObject = metadata(path)
                    if (!meta.getString("key").equals(key)) {
                        continue
                    }
                    result.put(JSONObject().put("name", meta.getString("name")).put("action", "operation").put("operation", meta.getString("operation")).put("phase", "pending").put("after", meta.optString("version")).put("observed", meta.getLong("started")))
                } catch (ignored: Exception) {
                }
            }
        }
        return result
    }
}
