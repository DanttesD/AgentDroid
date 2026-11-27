package com.termux.app.workspace

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Bounded, per-invocation acknowledgements; received progress is only a lower bound.*/
class ProjectFileReceipt : OutputStream {
    private val prefix: String
    private val expected: Int
    private val output: ByteArrayOutputStream = ByteArrayOutputStream()

    constructor(expected: Int) : this((UUID.randomUUID().toString() as java.lang.String).replace("-", ""), expected)

    constructor(id: String?, expected: Int) : super() {
        if (id == null || !(id as java.lang.String).matches("[a-f0-9]{32}") || expected < 1 || expected > ProjectFileTransfer.MAX_SELECTED) {
            throw IllegalArgumentException("INVALID_TRANSFER_RECEIPT")
        }
        prefix = "JOBS_TRANSFER_V1 " + id + " "
        this.expected = expected
    }

    fun progress(ordinal: Int): String {
        if (ordinal < 1 || ordinal > expected) {
            throw IllegalArgumentException("INVALID_TRANSFER_ORDINAL")
        }
        return prefix + "DONE " + ordinal
    }

    fun completion(): String {
        return prefix + "END " + expected
    }

    @Throws(IOException::class)
    override fun write(value: Int) {
        if (output.size() >= MAX_OUTPUT) {
            throw IOException("TRANSFER_OUTPUT_LIMIT")
        }
        output.write(value)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray?, offset: Int, length: Int) {
        if (bytes == null) {
            throw NullPointerException()
        }
        if (offset < 0 || length < 0 || length > bytes.size - offset) {
            throw IndexOutOfBoundsException()
        }
        if (length > MAX_OUTPUT - output.size()) {
            throw IOException("TRANSFER_OUTPUT_LIMIT")
        }
        output.write(bytes, offset, length)
    }

    private class State {
        @JvmField var acknowledged: Int = 0
        @JvmField var ended: Boolean = false
        @JvmField var invalid: Boolean = false
    }

    private fun state(): State {
        val state = State()
        val lines: Array<String> = (String(output.toByteArray(), StandardCharsets.US_ASCII) as java.lang.String).split("\n", -1)
        for (i in 0 until (lines.size - 1)) {
            val line: String = lines[i]
            if (!line.startsWith(prefix)) {
                continue
            }
            if (state.invalid) {
                continue
            }
            if (state.ended) {
                state.invalid = true
            } else if (state.acknowledged < expected && line.equals(progress(state.acknowledged + 1))) {
                state.acknowledged++
            } else if (state.acknowledged == expected && line.equals(completion())) {
                state.ended = true
            } else {
                state.invalid = true
            }
        }
        if (lines[lines.size - 1].startsWith(prefix)) {
            state.invalid = true
        }
        return state
    }

    fun acknowledged(): Int {
        return state().acknowledged
    }

    @Throws(IOException::class)
    fun requireComplete() {
        val state: State = state()
        if (state.invalid || !state.ended) {
            throw IOException("TRANSFER_NOT_CONFIRMED")
        }
    }

    companion object {
        const val MAX_OUTPUT: Int = 8192
    }
}
