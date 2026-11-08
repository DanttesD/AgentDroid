package com.agentdroid.exec

import java.util.LinkedHashMap

/** A command to run through an [ExecBackend]. Immutable. */
class ExecRequest private constructor(
    @get:JvmName("command") val command: String,
    @get:JvmName("workingDir") val workingDir: String?,
    env: Map<String, String>,
    stdin: ByteArray?,
    @get:JvmName("timeoutMs") val timeoutMs: Long,
) {
    private val envCopy: Map<String, String> = LinkedHashMap(env)
    private val stdinCopy: ByteArray? = stdin?.clone()

    fun env(): Map<String, String> = LinkedHashMap(envCopy)

    fun stdin(): ByteArray? = stdinCopy?.clone()

    /**
     * The command as one shell script: working directory and environment are part of
     * the text, so backends that only accept a command string (su, adb) behave the same.
     */
    fun toScript(): String {
        val sb = StringBuilder()
        if (workingDir != null) {
            sb.append("cd ").append(ShellQuote.quote(workingDir)).append(" || exit 1\n")
        }
        for ((name, value) in envCopy) {
            sb.append("export ").append(name).append('=').append(ShellQuote.quote(value)).append('\n')
        }
        sb.append(command)
        return sb.toString()
    }

    class Builder internal constructor(command: String) {
        private val command: String
        private var workingDir: String? = null
        private val env = LinkedHashMap<String, String>()
        private var stdin: ByteArray? = null
        private var timeoutMs: Long = DEFAULT_TIMEOUT_MS

        init {
            require(command.isNotEmpty()) { "command is empty" }
            this.command = command
        }

        fun workingDir(dir: String?): Builder {
            workingDir = dir
            return this
        }

        fun env(name: String, value: String): Builder {
            require(ShellQuote.isValidEnvName(name)) { "bad env name: $name" }
            env[name] = value
            return this
        }

        fun stdin(data: ByteArray?): Builder {
            stdin = data
            return this
        }

        fun timeoutMs(ms: Long): Builder {
            require(ms > 0) { "timeout must be positive" }
            timeoutMs = ms
            return this
        }

        fun build(): ExecRequest = ExecRequest(command, workingDir, env, stdin, timeoutMs)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L

        @JvmStatic
        fun builder(command: String): Builder = Builder(command)

        @JvmStatic
        fun of(command: String): ExecRequest = builder(command).build()
    }
}
