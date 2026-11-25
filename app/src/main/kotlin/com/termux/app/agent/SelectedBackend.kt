package com.termux.app.agent

import com.agentdroid.exec.BackendSelector
import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ExecRequest
import com.agentdroid.exec.ExecResult
import java.io.IOException

/** Forwards every call to whatever backend the selector picks at that moment.*/
class SelectedBackend : ExecBackend {
    private val selector: BackendSelector

    constructor(selector: BackendSelector) {
        this.selector = selector
    }

    @Throws(IOException::class)
    private fun current(): ExecBackend {
        val b: ExecBackend? = selector.select()
        if (b == null) {
            throw IOException("no backend available (root, local ADB or Termux)")
        }
        return b
    }

    override fun id(): String {
        return "selected"
    }

    override fun displayName(): String {
        val b: ExecBackend? = selector.select()
        return if (b == null) "-" else b.displayName()
    }

    override fun isAvailable(): Boolean {
        return selector.select() != null
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun exec(r: ExecRequest): ExecResult {
        return current().exec(r)
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun readFile(p: String): ByteArray {
        return current().readFile(p)
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun writeFile(p: String, d: ByteArray) {
        current().writeFile(p, d)
    }
}
