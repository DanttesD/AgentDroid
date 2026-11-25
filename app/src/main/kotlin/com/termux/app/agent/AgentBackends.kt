package com.termux.app.agent

import android.content.Context
import com.agentdroid.adb.AgentAdb
import com.agentdroid.exec.AdbBackend
import com.agentdroid.exec.BackendSelector
import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ProcessBackend
import com.agentdroid.exec.RootBackend
import com.agentdroid.exec.ShellTransport
import com.termux.shared.termux.TermuxConstants
import java.io.IOException

/** Builds the backends the agent can use: root, own ADB on localhost:5555, Termux.*/
object AgentBackends {
    const val LOCAL_ADB_PORT: Int = 5555
    const val TERMUX_HOME: String = TermuxConstants.TERMUX_HOME_DIR_PATH

    @JvmStatic
    fun create(context: Context): BackendSelector {
        val adb = AgentAdb(context)
        val l: MutableList<ExecBackend> = ArrayList<ExecBackend>()
        l.add(RootBackend())
        l.add(AdbBackend("adb-local", "ADB (127.0.0.1:" + LOCAL_ADB_PORT + ")", object : ShellTransport {
            @Throws(IOException::class)
            override fun run(script: String, timeoutMs: Long): ShellTransport.Output {
                try {
                    val r: AgentAdb.ShellResult = adb.shell("127.0.0.1", LOCAL_ADB_PORT, script)
                    return ShellTransport.Output(r.getExitCode(), r.getOutput())
                } catch (e: Exception) {
                    throw IOException(e.message, e)
                }
            }

            override fun isReachable(): Boolean {
                return adb.isReachable("127.0.0.1", LOCAL_ADB_PORT, 1500)
            }
        }))
        val prefix: String = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        l.add(ProcessBackend("termux", "Termux (app sandbox)", prefix + "/bin/env", "PATH=" + prefix + "/bin", "HOME=" + TERMUX_HOME, "PREFIX=" + prefix, "TERMUX__PREFIX=" + prefix, "TERMUX_APP__PACKAGE_NAME=" + TermuxConstants.TERMUX_PACKAGE_NAME, "TERMUX__HOME=" + TERMUX_HOME, "TMPDIR=" + prefix + "/tmp", prefix + "/bin/bash", "-c"))
        return BackendSelector(l)
    }
}
