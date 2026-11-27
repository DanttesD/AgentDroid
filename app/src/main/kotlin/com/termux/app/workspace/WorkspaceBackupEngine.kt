package com.termux.app.workspace

import android.app.Activity
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.termux.BuildConfig
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Own Python worker only; never writes input to, or stops, a user's terminal session.*/
class WorkspaceBackupEngine {
    interface Callback {
        fun progress(event: JSONObject)

        fun done(result: JSONObject?)

        fun error(code: String)
    }

    fun interface ProcessStarter {
        @Throws(Exception::class)
        fun start(builder: ProcessBuilder): Process
    }

    private val starter: ProcessStarter?

    constructor() : this(null)

    constructor(starter: ProcessStarter?) {
        this.starter = starter
    }

    @Volatile private var process: Process? = null
    @Volatile private var cancelled: Boolean = false
    private val terminationRequested: AtomicBoolean = AtomicBoolean()

    fun cancel() {
        cancelled = true
        val owned: Process? = process
        if (owned != null) {
            terminateOnce(owned)
        }
    }

    private fun terminateOnce(owned: Process?) {
        if (terminationRequested.compareAndSet(false, true)) {
            owned!!.destroy()
        }
    }

    fun start(a: Activity, request: JSONObject, callback: Callback): Boolean {
        if (!BUSY.compareAndSet(false, true)) {
            return false
        }
        Thread(Runnable {
            var work: File? = null
            var completed: JSONObject? = null
            var terminalError: String? = null
            try {
                val python = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "python3")
                if (starter == null && !python.canExecute()) {
                    throw IOException("PYTHON_REQUIRED")
                }
                val jobs = File(a.getFilesDir(), "workspace-backup-jobs")
                if (!jobs.isDirectory() && !jobs.mkdirs()) {
                    throw IOException("PRIVATE_STORAGE")
                }
                work = File(jobs, UUID.randomUUID().toString())
                if (!work.mkdir()) {
                    throw IOException("PRIVATE_STORAGE")
                }
                val script = File(work, "backup.py")
                (a.getAssets().open("workspace-backup.py")).use { `in` ->
                    (FileOutputStream(script)).use { out ->
                        val b = ByteArray(8192)
                        var n: Int = 0
                        while (true) {
                            n = `in`.read(b)
                            if (n == -1) break
                            out.write(b, 0, n)
                        }
                    }
                }
                if (request.optString("operation").startsWith("cleanup-")) {
                    (a.getAssets().open("workspace-cleanup.py")).use { `in` ->
                        (FileOutputStream(File(work, "workspace-cleanup.py"))).use { out ->
                            val b = ByteArray(8192)
                            var n: Int = 0
                            while (true) {
                                n = `in`.read(b)
                                if (n == -1) break
                                out.write(b, 0, n)
                            }
                        }
                    }
                }
                request.put("work", work.getAbsolutePath())
                val input = File(work, "request.json")
                (FileOutputStream(input)).use { out ->
                    out.write((request.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8))
                }
                val pb = ProcessBuilder(python.getAbsolutePath(), "-I", "-S", script.getAbsolutePath(), "--request", input.getAbsolutePath())
                pb.directory(File(home()))
                pb.environment().clear()
                for (item in TermuxShellUtils.buildEnvironment(a, false, home())) {
                    val i: Int = item.indexOf('=')
                    if (i > 0) {
                        pb.environment().put(item.substring(0, i), item.substring(i + 1))
                    }
                }
                pb.redirectError(File("/dev/null"))
                if (cancelled) {
                    throw IOException("CANCELLED")
                }
                process = if (starter == null) pb.start() else starter.start(pb)
                if (cancelled) {
                    terminateOnce(process)
                }
                val result: Array<JSONObject?> = arrayOf<JSONObject?>(null)
                val failure: Array<String?> = arrayOf<String?>(null)
                (BufferedReader(InputStreamReader(process!!.getInputStream(), StandardCharsets.UTF_8))).use { reader ->
                    var line: String? = null
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        if (line.length > 32 * 1024 * 1024) {
                            throw IOException("RESULT_TOO_LARGE")
                        }
                        val event = JSONObject(line)
                        val stage: String = event.getString("stage")
                        if (stage.equals("DONE")) {
                            result[0] = event.getJSONObject("result")
                        } else if (stage.equals("ERROR")) {
                            failure[0] = event.optString("code", "BACKUP_FAILED")
                        } else {
                            a.runOnUiThread(Runnable {
                                if (!cancelled && !a.isDestroyed()) {
                                    callback.progress(event)
                                }
                            })
                        }
                    }
                }
                val status: Int = process!!.waitFor()
                if (cancelled) {
                    throw IOException("CANCELLED")
                }
                if (status != 0 || result[0] == null) {
                    throw IOException(if (failure[0] == null) "WORKER_FAILED" else failure[0])
                }
                completed = result[0]
            } catch (e: Exception) {
                var code: String? = if (cancelled) "CANCELLED" else e.message
                if (code == null || !(code as java.lang.String).matches("[A-Z_]{1,80}")) {
                    code = "BACKUP_FAILED"
                }
                terminalError = code
            } finally {
                val owned: Process? = process
                if (owned != null && running(owned)) {
                    terminateOnce(owned)
                    var interrupted = false
                    // Python must finish its owned archive/package-manager children before another storage job.
                    // An elapsed grace period is not evidence of process exit. Never release BUSY on a timer.
                    while (true) {
                        try {
                            owned.waitFor()
                            break
                        } catch (e: InterruptedException) {
                            interrupted = true
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt()
                    }
                }
                process = null
                try {
                    if (work != null) {
                        removeOwned(work)
                    }
                } finally {
                    BUSY.set(false)
                }
            }
            val result: JSONObject? = completed
            val error: String? = terminalError
            // A late tap on Cancel must not hide an already committed restore/backup result.
            a.runOnUiThread(Runnable {
                if (!a.isDestroyed()) {
                    if (error == null) {
                        callback.done(result)
                    } else {
                        callback.error(error)
                    }
                }
            })
        }, "jobs-backup-worker").start()
        return true
    }

    companion object {
        private val BUSY: AtomicBoolean = AtomicBoolean()

        @JvmStatic
        fun home(): String {
            return TermuxConstants.TERMUX_HOME_DIR_PATH
        }

        @JvmStatic
        fun prefix(): String {
            return TermuxConstants.TERMUX_PREFIX_DIR_PATH
        }

        @Throws(Exception::class)
        @JvmStatic
        fun base(a: Activity, operation: String): JSONObject {
            return JSONObject().put("operation", operation).put("prefix", prefix()).put("home", home()).put("files", a.getFilesDir().getAbsolutePath()).put("packageId", a.getPackageName()).put("appVersion", BuildConfig.VERSION_NAME)
        }

        @JvmStatic
        fun symlink(file: File): Boolean {
            try {
                return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode)
            } catch (e: ErrnoException) {
                return false
            }
        }

        @JvmStatic
        private fun running(p: Process): Boolean {
            try {
                p.exitValue()
                return false
            } catch (e: IllegalThreadStateException) {
                return true
            }
        }

        @JvmStatic
        private fun removeOwned(file: File) {
            if (symlink(file)) {
                file.delete()
                return
            }
            val children: Array<File>? = file.listFiles()
            if (children != null) {
                for (child in children) {
                    removeOwned(child)
                }
            }
            file.delete()
        }
    }
}
