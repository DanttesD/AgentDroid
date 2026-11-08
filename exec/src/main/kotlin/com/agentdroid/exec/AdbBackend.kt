package com.agentdroid.exec

import java.io.IOException
import java.util.Base64

/**
 * Commands with shell rights through the own ADB client. stderr is merged into stdout.
 * `id` examples: "adb-local" (127.0.0.1 TCP port, works without Wi-Fi) and "adb-wifi".
 */
open class AdbBackend(
    private val id: String,
    private val displayName: String,
    private val transport: ShellTransport,
) : ExecBackend {
    override fun id(): String = id

    override fun displayName(): String = displayName

    override fun isAvailable(): Boolean = try {
        transport.isReachable()
    } catch (e: RuntimeException) {
        false
    }

    @Throws(IOException::class)
    override fun exec(request: ExecRequest): ExecResult {
        var script = request.toScript()
        val stdin = request.stdin()
        if (stdin != null) {
            // The ADB shell service has no separate stdin channel: pass input as base64 through a pipe.
            val b64 = Base64.getEncoder().encodeToString(stdin)
            script = "printf %s " + ShellQuote.quote(b64) + " | base64 -d | (" + script + ")"
        }
        val o = transport.run(script, request.timeoutMs)
        val text = o.text.toByteArray(Charsets.UTF_8)
        return ExecResult(o.exitCode, text, ByteArray(0), false)
    }
}
