package com.agentdroid.exec

import java.io.IOException

/**
 * One way of running commands and touching files on the device:
 * root, own ADB, Termux, sandbox. All agent tools go through this interface.
 */
interface ExecBackend {
    /** Stable id, e.g. "root", "adb-local", "termux", "sandbox". */
    fun id(): String

    fun displayName(): String

    /** Cheap-enough probe; may run a tiny command. Never throws. */
    fun isAvailable(): Boolean

    @Throws(IOException::class, InterruptedException::class)
    fun exec(request: ExecRequest): ExecResult

    @Throws(IOException::class, InterruptedException::class)
    fun readFile(path: String): ByteArray = ShellFiles.read(this, path)

    @Throws(IOException::class, InterruptedException::class)
    fun writeFile(path: String, data: ByteArray) {
        ShellFiles.write(this, path, data)
    }
}
