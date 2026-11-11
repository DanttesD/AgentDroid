package com.termux.terminal

import java.io.ByteArrayOutputStream
import java.util.function.Consumer

/** Opt-in DEC2026 framing before emulator mutation. Call only on the session's main thread. */
class SynchronizedFrameBuffer {
    private val held = ByteArrayOutputStream()
    private var prefixLength = 0
    private var frame = false
    private var since: Long = -1

    fun deadline(): Long = if (since < 0) -1 else since + TIMEOUT_MS

    fun append(bytes: ByteArray, length: Int, now: Long, sink: Consumer<ByteArray>) {
        val visible = ByteArrayOutputStream(length)
        for (i in 0 until length) {
            val value = bytes[i]
            if (prefixLength == PREFIX.size && (value == H || value == L)) {
                prefixLength = 0
                if (value == H) frame = true
                else {
                    release(visible)
                }
                continue
            }
            if (prefixLength < PREFIX.size && value == PREFIX[prefixLength]) {
                if (since < 0) since = now
                prefixLength++
                continue
            }
            if (prefixLength > 0) {
                val count = prefixLength
                prefixLength = 0
                payload(PREFIX, count, visible)
            }
            if (value == PREFIX[0]) {
                if (since < 0) since = now
                prefixLength = 1
            } else {
                put(value, visible)
            }
            if (!frame && prefixLength == 0) since = -1
        }
        if (visible.size() > 0) sink.accept(visible.toByteArray())
    }

    private fun payload(data: ByteArray, count: Int, visible: ByteArrayOutputStream) {
        for (i in 0 until count) put(data[i], visible)
    }

    private fun put(value: Byte, visible: ByteArrayOutputStream) {
        if (frame) {
            held.write(value.toInt())
            if (held.size() >= MAX_BYTES) release(visible)
        } else visible.write(value.toInt())
    }

    private fun release(visible: ByteArrayOutputStream) {
        val data = held.toByteArray()
        visible.write(data, 0, data.size)
        held.reset()
        visible.write(PREFIX, 0, prefixLength)
        prefixLength = 0
        frame = false
        since = -1
    }

    fun flush(sink: Consumer<ByteArray>) {
        val visible = ByteArrayOutputStream()
        release(visible)
        if (visible.size() > 0) sink.accept(visible.toByteArray())
    }

    fun expire(now: Long, sink: Consumer<ByteArray>) {
        if (deadline() >= 0 && now >= deadline()) flush(sink)
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        const val TIMEOUT_MS = 200L
        private val PREFIX = byteArrayOf(27, '['.code.toByte(), '?'.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '2'.code.toByte(), '6'.code.toByte())
        private const val H = 'h'.code.toByte()
        private const val L = 'l'.code.toByte()
    }
}
