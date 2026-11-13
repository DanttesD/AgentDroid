package com.agentdroid.exec

import java.io.IOException

/**
 * Minimal view of a remote shell (the own ADB client implements it in the app module),
 * so this module does not depend on Android code.
 */
interface ShellTransport {
    /** Runs a script. Output is merged stdout+stderr; exit code is -1 if unknown. */
    @Throws(IOException::class)
    fun run(script: String, timeoutMs: Long): Output

    class Output(@JvmField val exitCode: Int, @JvmField val text: String)

    /** True while the endpoint answers (for example 127.0.0.1:5555 or the Wi-Fi debugging port). */
    fun isReachable(): Boolean
}
