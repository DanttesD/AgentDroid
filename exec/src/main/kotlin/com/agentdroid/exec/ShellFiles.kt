package com.agentdroid.exec

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/**
 * File access built on plain shell commands, so it works on every backend
 * (root, adb, termux) without a separate file channel. Content travels as base64.
 */
object ShellFiles {
    /** Raw bytes per write command; keeps each command line short enough for adb and su. */
    internal const val CHUNK: Int = 24 * 1024

    private val WHITESPACE = Regex("\\s")

    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    fun read(backend: ExecBackend, path: String): ByteArray {
        val r = backend.exec(ExecRequest.of("base64 < " + ShellQuote.quote(path)))
        if (!r.ok()) throw IOException("read failed ($r): " + r.stderr().trim { it <= ' ' })
        val b64 = r.stdout().replace(WHITESPACE, "")
        try {
            return Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw IOException("unexpected output while reading $path: ${e.message}")
        }
    }

    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    fun write(backend: ExecBackend, path: String, data: ByteArray) {
        val q = ShellQuote.quote(path)
        runChecked(backend, ": > $q")
        var off = 0
        while (off < data.size) {
            val len = minOf(CHUNK, data.size - off)
            val part = data.copyOfRange(off, off + len)
            val b64 = Base64.getEncoder().encodeToString(part)
            runChecked(backend, "printf %s " + ShellQuote.quote(b64) + " | base64 -d >> " + q)
            off += CHUNK
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun runChecked(backend: ExecBackend, script: String) {
        val r = backend.exec(ExecRequest.of(script))
        if (!r.ok()) throw IOException("write failed ($r): " + r.stderr().trim { it <= ' ' })
    }

    /** Reads at most `max` bytes from a stream. Helper for process backends. */
    @Throws(IOException::class)
    internal fun drain(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            if (out.size() < max) out.write(buf, 0, minOf(n, max - out.size()))
        }
        return out.toByteArray()
    }
}
