package com.termux.app.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Date

/** One bounded, private last-attempt report per environment. No terminal/session data.*/
object EnvironmentInstallLog {
    class Capture {
        private val text: StringBuilder = StringBuilder()
        private var truncated: Boolean = false

        fun append(chars: CharArray, count: Int) {
            text.append(chars, 0, count)
            if (text.length > LIMIT) {
                text.delete(0, text.length - LIMIT)
                truncated = true
            }
        }

        fun snapshot(): String {
            return (if (truncated) TRUNCATED else "") + text
        }

        fun recent(): String {
            return text.substring(Math.max(0, text.length - 12000))
        }
    }

    const val LIMIT: Int = 262144
    const val TRUNCATED: String = "[Earlier installer output omitted: log limit reached]\n"

    @JvmStatic
    fun clean(raw: String): String {
        var text: String = (((raw as java.lang.String).replaceAll("\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\))", "") as java.lang.String).replace('\r', '\n') as java.lang.String).replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
        text = (text as java.lang.String).replaceAll("(?im)-----BEGIN [^\\n]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^\\n]*PRIVATE KEY-----|\\z)", "[private key redacted]")
        text = (text as java.lang.String).replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s'\"<>]+", "[url]")
        text = (text as java.lang.String).replaceAll("(?im)\\b(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|passphrase|access[_-]?token|refresh[_-]?token|token|api[_-]?key|client[_-]?secret|secret)\\b\\s*[:=]\\s*[^\\n]*", "[credential redacted]")
        return (text as java.lang.String).replaceAll("\\b(?:gh[pousr]_[A-Za-z0-9_]+|github[_]pat[_][A-Za-z0-9_]+|sk-[A-Za-z0-9_-]{16,})", "[token redacted]")
    }

    @JvmStatic
    fun report(alias: String, code: Int, output: String): String {
        return "AgentDroid environment installation\nEnvironment: " + alias + "\nExit status: " + code + "\nRecorded: " + Date() + "\n\n" + clean(if (output.length > LIMIT + TRUNCATED.length) TRUNCATED + output.substring(output.length - LIMIT) else output)
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun path(files: File, alias: String): Path {
        if (!WorkspaceLocalFilePicker.validAlias(alias) || alias.equals("termux")) {
            throw IOException("INVALID_ALIAS")
        }
        val file = File(files.getCanonicalFile(), ".environment-control/logs/" + alias + ".txt")
        var part: File? = file
        while (!part!!.equals(files.getCanonicalFile())) {
            if (Files.isSymbolicLink(part.toPath())) {
                throw IOException("LOG_LINK")
            }
            part = part.getParentFile()
        }
        if (!file.getCanonicalPath().equals(file.getAbsolutePath())) {
            throw IOException("LOG_PATH")
        }
        return file.toPath()
    }

    @JvmStatic
    fun exists(files: File, alias: String): Boolean {
        try {
            return Files.isRegularFile(path(files, alias), LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return false
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun save(files: File, alias: String, text: String) {
        val dest: Path = path(files, alias)
        Files.createDirectories(dest.getParent())
        path(files, alias)
        val temp: Path = Files.createTempFile(dest.getParent(), "attempt-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.write(temp, (text as java.lang.String).getBytes(StandardCharsets.UTF_8))
            Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun read(files: File, alias: String): String {
        val file: Path = path(files, alias)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4L * LIMIT + 4096) {
            throw IOException("LOG_UNAVAILABLE")
        }
        (Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)).use { `in` ->
            (ByteArrayOutputStream()).use { out ->
                val bytes = ByteArray(8192)
                var n: Int = 0
                while (true) {
                    n = `in`.read(bytes)
                    if (n == -1) break
                    if (out.size() + n > 4L * LIMIT + 4096) {
                        throw IOException("LOG_LIMIT")
                    }
                    out.write(bytes, 0, n)
                }
                return String(out.toByteArray(), StandardCharsets.UTF_8)
            }
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun clear(files: File, alias: String) {
        Files.deleteIfExists(path(files, alias))
    }
}
