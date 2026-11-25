package com.termux.app.workspace

import android.content.Context
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.TimeUnit

/** Independent Gemini CLI; no access to another agent's control plane.*/
object GeminiCli {
    const val GUEST: String = "/tmp/termux-jobs-gemini"

    @JvmStatic
    fun directory(files: File): File {
        return File(files, ".gemini-control")
    }

    @JvmStatic
    fun validThread(id: String?): Boolean {
        return id != null && (id as java.lang.String).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    @JvmStatic
    fun validCloudProject(value: String?): Boolean {
        return value != null && (value.isEmpty() || (value as java.lang.String).matches("[a-z][a-z0-9-]{4,28}[a-z0-9]|[0-9]{6,20}"))
    }

    @JvmStatic
    fun validCloudLocation(value: String?): Boolean {
        return value != null && (value.isEmpty() || value.length <= 63 && (value as java.lang.String).matches("global|[a-z]+(?:-[a-z0-9]+){1,4}"))
    }

    @JvmStatic
    fun valid(s: WorkspaceStore.Session?): Boolean {
        return s!!.geminiAgent && s.environment.equals("ubuntu") && AgentEnvironments.ubuntu(s.codexEnvironment) && s.aiAgent.isEmpty() && !s.claudeAgent && Arrays.asList("auto", "google", "api", "vertex").contains(s.geminiAuth) && validCloudProject(s.geminiCloudProject) && validCloudLocation(s.geminiCloudLocation) && Arrays.asList("", "auto", "flash", "pro").contains(s.geminiModel) && Arrays.asList("default", "auto_edit", "plan").contains(s.geminiMode) && Arrays.asList("default", "minimal", "low", "medium", "high").contains(s.geminiThinking) && Arrays.asList("new", "choose", "id").contains(s.geminiChat) && validThread(s.id) && validThread(s.geminiThread) && validThread(s.geminiSocketId)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(context: Context) {
        val dir: File = directory(context.getFilesDir())
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create Gemini controls")
        }
        protect(dir)
        for (asset in arrayOf<String>("gemini-bootstrap.sh", "gemini-control.js", "gemini-live.cjs", "gemini-thinking.cjs", "gemini-live-loader.mjs", "gemini-chat.cjs", "agent-receipts.cjs", "agent-attachments.cjs")) {
            val temporary: File = File.createTempFile("gemini-", ".tmp", dir)
            try {
                (context.getAssets().open(asset)).use { `in` ->
                    (FileOutputStream(temporary)).use { out ->
                        val b = ByteArray(8192)
                        var n: Int = 0
                        while (true) {
                            n = `in`.read(b)
                            if (n == -1) break
                            out.write(b, 0, n)
                        }
                    }
                }
                protect(temporary)
                if (!temporary.renameTo(File(dir, if (asset.equals("gemini-control.js")) "gemini-control-v2.js" else asset))) {
                    throw IOException("Cannot replace Gemini controls")
                }
            } finally {
                temporary.delete()
            }
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun protect(f: File) {
        if (!f.setReadable(false, false) || !f.setWritable(false, false) || !f.setExecutable(false, false) || !f.setReadable(true, true) || !f.setWritable(true, true) || f.isDirectory() && !f.setExecutable(true, true)) {
            throw IOException("Cannot protect Gemini controls")
        }
    }

    @JvmStatic
    fun command(files: File, s: WorkspaceStore.Session?): String {
        if (!valid(s)) {
            throw IllegalArgumentException("Invalid Gemini profile")
        }
        val keyFile: String = GUEST + "/keys/" + s!!.id + ".key"
        return "( set -e; trap " + SessionShell.quote("rm -f -- " + SessionShell.quote(keyFile)) + " EXIT; " + (if (s.geminiAuth.equals("api") && !s.geminiEncryptedKey.isEmpty()) "export JOBS_GEMINI_KEY_FILE=" + SessionShell.quote(keyFile) + "; " else "") + "export JOBS_GEMINI_LANGUAGE=" + (if (WorkspaceText.tr("Агент: ")!!.startsWith("Агент")) "ru" else "en") + (if (s.geminiAuth.equals("vertex") && !s.geminiCloudProject!!.isEmpty()) "; export JOBS_GEMINI_VERTEX_PROJECT=" + SessionShell.quote(s.geminiCloudProject) + "; export JOBS_GEMINI_VERTEX_LOCATION=" + SessionShell.quote(s.geminiCloudLocation) else "") + "; export JOBS_GEMINI_THINKING=" + SessionShell.quote(s.geminiThinking) + "; export JOBS_GEMINI_DEBUG=" + (if (s.extendedDebug) "1" else "0") + "; source " + SessionShell.quote(GUEST + "/gemini-bootstrap.sh") + "; exec \"\$JOBS_GEMINI_NODE\" " + SessionShell.quote(GUEST + "/gemini-control-v2.js") + " run " + SessionShell.quote(GUEST + "/" + s.geminiSocketId + ".sock") + " " + SessionShell.quote(s.geminiModel) + " " + SessionShell.quote(s.geminiMode) + " " + SessionShell.quote(s.geminiChat) + " " + SessionShell.quote(s.geminiThread) + " " + SessionShell.quote(s.geminiAuth) + " )"
    }

    @JvmStatic
    private fun profile(environment: String): WorkspaceStore.Session {
        val p = WorkspaceStore.Session()
        p.codexEnvironment = environment
        return p
    }

    @Throws(Exception::class)
    @JvmStatic
    fun stop(context: Context, runId: String) {
        stop(context, runId, "ubuntu")
    }

    /** Caller captures the unique run ID before scheduling; a later run cannot be stopped.*/
    @Throws(Exception::class)
    @JvmStatic
    fun stop(context: Context, runId: String, environment: String) {
        if (!validThread(runId)) {
            throw IllegalArgumentException("Invalid Gemini run")
        }
        val guest: String = "node=\$(cat \"\$HOME/.local/share/termux-jobs/gemini-cli/controller-node\") && exec \"\$node\" " + SessionShell.quote(GUEST + "/gemini-control-v2.js") + " stop " + SessionShell.quote(GUEST + "/" + runId + ".sock")
        val script: String = "exec proot-distro login --bind " + SessionShell.quote(directory(context.getFilesDir()).getAbsolutePath() + ":" + GUEST) + " " + SessionShell.quote(AgentEnvironments.alias(profile(environment))) + " -- /bin/bash --noprofile --norc -c " + SessionShell.quote(guest)
        val pb = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", script)
        pb.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        pb.environment().clear()
        for (entry in TermuxShellUtils.buildEnvironment(context, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val i: Int = entry.indexOf('=')
            if (i > 0) {
                pb.environment().put(entry.substring(0, i), entry.substring(i + 1))
            }
        }
        pb.redirectOutput(File("/dev/null"))
        pb.redirectError(File("/dev/null"))
        val p: Process = pb.start()
        try {
            if (!p.waitFor(7L, TimeUnit.SECONDS)) {
                throw IOException("Gemini stop timeout")
            }
        } finally {
            p.destroy()
        }
    }
}
