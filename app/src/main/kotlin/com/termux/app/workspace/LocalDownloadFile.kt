package com.termux.app.workspace

import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Exclusive destination stream in the selected local environment; publishes only after SSH succeeds.*/
class LocalDownloadFile : AutoCloseable {
    @JvmField val path: String
    @JvmField val temporary: String
    private val files: ProjectFiles
    private val location: WorkspaceStore.Session
    private var child: Process? = null
    private var owned: Boolean = false
    private var committed: Boolean = false
    @JvmField val stream: OutputStream

    @Throws(Exception::class)
    constructor(activity: TermuxActivity, source: WorkspaceStore.Session, directory: String, name: String) {
        if (!ServerSftp.validName(name) || !directory.startsWith("/")) {
            throw IOException("INVALID_DESTINATION")
        }
        location = source.copy()
        files = ProjectFiles(activity, location)
        path = (if (directory.equals("/")) "/" else directory + "/") + name
        temporary = (if (directory.equals("/")) "/" else directory + "/") + ".termux-jobs-download-" + UUID.randomUUID() + ".part"
        val builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", "exec " + ProjectFiles.command(location, writeScript(directory, File(temporary).getName())))
        builder.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        builder.environment().clear()
        for (item in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val eq: Int = item.indexOf('=')
            if (eq > 0) {
                builder.environment().put(item.substring(0, eq), item.substring(eq + 1))
            }
        }
        builder.redirectError(File("/dev/null"))
        try {
            child = builder.start()
            val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
            val timeout: ScheduledFuture<*> = watchdog.schedule(Runnable(child!!::destroy), 15L, TimeUnit.SECONDS)
            try {
                val ready: InputStream = child!!.getInputStream()
                val marker: ByteArray = byteArrayOf('R'.code.toByte(), 'E'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte(), 'Y'.code.toByte(), '\n'.code.toByte())
                for (expected in marker) {
                    if (ready.read() != expected.toInt()) {
                        throw IOException("DESTINATION_UNAVAILABLE")
                    }
                }
                owned = true
                stream = child!!.getOutputStream()
            } finally {
                timeout.cancel(false)
                watchdog.shutdown()
            }
        } catch (error: Exception) {
            if (child != null) {
                child!!.destroy()
            }
            files.close()
            throw error
        }
    }

    @Throws(Exception::class)
    fun finish() {
        stream.close()
        if (child!!.waitFor() != 0) {
            throw IOException("DESTINATION_WRITE_FAILED")
        }
        files.localOperation(publishScript(temporary, path))
        committed = true
    }

    fun cancel() {
        if (child != null) {
            child!!.destroy()
        }
    }

    @Throws(Exception::class)
    override fun close() {
        cancel()
        try {
            try {
                stream.close()
            } catch (ignored: IOException) {
            }
            if (owned && !committed) {
                files.localOperation("rm -f -- " + ProjectFiles.target(temporary))
            }
        } finally {
            files.close()
        }
    }

    companion object {
        @JvmStatic
        fun writeScript(directory: String, name: String): String {
            return "umask 077; cd -- " + ProjectFiles.target(directory) + " || exit 2; set -C; exec 3> " + SessionShell.quote(name) + " || exit 3; printf 'READY\\n'; cat >&3"
        }

        @JvmStatic
        fun publishScript(temporary: String, target: String): String {
            return "test -f " + ProjectFiles.target(temporary) + " && ! test -e " + ProjectFiles.target(target) + " && ! test -L " + ProjectFiles.target(target) + " && mv -T -n -- " + ProjectFiles.target(temporary) + " " + ProjectFiles.target(target) + " && ! test -e " + ProjectFiles.target(temporary)
        }
    }
}
