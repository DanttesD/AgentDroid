package com.agentdroid.adb

import com.agentdroid.exec.ShellTransport
import java.io.IOException

/** Connects the own ADB client to the ExecBackend layer (`AdbBackend`). */
class AdbShellTransport(
    private val adb: AgentAdb,
    private val host: String,
    private val port: Int
) : ShellTransport {

    override fun run(script: String, timeoutMs: Long): ShellTransport.Output {
        val box = arrayOfNulls<Any>(1)
        val worker = Thread {
            box[0] = try {
                adb.shell(host, port, script)
            } catch (t: Throwable) {
                t
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            worker.interrupt()
            return ShellTransport.Output(-1, "timeout after $timeoutMs ms")
        }
        return when (val v = box[0]) {
            is AgentAdb.ShellResult -> ShellTransport.Output(v.exitCode, v.output)
            is Throwable -> throw IOException("adb shell failed: ${v.message}", v)
            else -> throw IOException("adb shell returned nothing")
        }
    }

    override fun isReachable(): Boolean = adb.isReachable(host, port)
}
