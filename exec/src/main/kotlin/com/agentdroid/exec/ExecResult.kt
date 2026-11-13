package com.agentdroid.exec

/** Result of a command. `exitCode` is -1 when it could not be determined. */
class ExecResult(
    @get:JvmName("exitCode") val exitCode: Int,
    stdout: ByteArray?,
    stderr: ByteArray?,
    @get:JvmName("timedOut") val timedOut: Boolean,
) {
    private val out: ByteArray = stdout ?: ByteArray(0)
    private val err: ByteArray = stderr ?: ByteArray(0)

    fun ok(): Boolean = !timedOut && exitCode == 0

    fun stdoutBytes(): ByteArray = out.clone()

    fun stdout(): String = String(out, Charsets.UTF_8)

    fun stderr(): String = String(err, Charsets.UTF_8)

    override fun toString(): String =
        "ExecResult{exit=" + exitCode + (if (timedOut) ", timedOut" else "") + ", stdout=" + out.size + "B, stderr=" + err.size + "B}"
}
