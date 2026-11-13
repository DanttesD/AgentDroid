package com.agentdroid.exec

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs the request script through a local launcher, e.g. `["sh","-c"]`,
 * `["su","-c"]` or a Termux bash. Used by the Root, Termux and sandbox backends.
 */
open class ProcessBackend(
    private val id: String,
    private val displayName: String,
    launcher: List<String>,
) : ExecBackend {
    private val launcherCommand: List<String>

    init {
        require(launcher.isNotEmpty()) { "launcher is empty" }
        launcherCommand = ArrayList(launcher)
    }

    constructor(id: String, displayName: String, vararg launcher: String) : this(id, displayName, launcher.asList())

    override fun id(): String = id

    override fun displayName(): String = displayName

    override fun isAvailable(): Boolean = try {
        val r = exec(ExecRequest.builder("echo agentdroid-ok").timeoutMs(5000).build())
        r.ok() && r.stdout().contains("agentdroid-ok")
    } catch (e: Exception) {
        false
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun exec(request: ExecRequest): ExecResult {
        val cmd = ArrayList(launcherCommand)
        cmd.add(request.toScript())
        val p = ProcessBuilder(cmd).start()

        val stdin = request.stdin()
        val feeder = Thread({
            try {
                p.outputStream.use { os -> if (stdin != null) os.write(stdin) }
            } catch (ignored: IOException) {
                // process ended before reading all input
            }
        }, "exec-stdin")
        feeder.isDaemon = true
        feeder.start()

        val out = arrayOfNulls<ByteArray>(2)
        val outReader = reader(p, false, out, 0)
        val errReader = reader(p, true, out, 1)

        val finished = p.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            p.destroyForcibly()
            p.waitFor(2, TimeUnit.SECONDS)
        }
        outReader.join(2000)
        errReader.join(2000)
        val code = if (finished) p.exitValue() else -1
        return ExecResult(code, out[0], out[1], !finished)
    }

    private fun reader(p: Process, err: Boolean, sink: Array<ByteArray?>, idx: Int): Thread {
        val t = Thread({
            sink[idx] = try {
                ShellFiles.drain(if (err) p.errorStream else p.inputStream, MAX_OUTPUT)
            } catch (e: IOException) {
                ByteArray(0)
            }
        }, if (err) "exec-stderr" else "exec-stdout")
        t.isDaemon = true
        t.start()
        return t
    }

    private companion object {
        const val MAX_OUTPUT = 8 * 1024 * 1024
    }
}
