package com.termux.app.workspace

import android.content.Context
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Arrays
import java.util.concurrent.TimeUnit

/** Independent native Claude CLI; no access to another agent's control plane.*/
object ClaudeCode {
    const val GUEST: String = "/tmp/termux-jobs-claude"

    @JvmStatic
    fun directory(files: File): File {
        return File(files, ".claude-control")
    }

    @JvmStatic
    fun validThread(id: String?): Boolean {
        return id != null && (id as java.lang.String).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    @JvmStatic
    fun valid(s: WorkspaceStore.Session?): Boolean {
        return s!!.claudeAgent && s.environment.equals("ubuntu") && AgentEnvironments.ubuntu(s.codexEnvironment) && s.aiAgent.isEmpty() && Arrays.asList("", "sonnet", "opus", "haiku").contains(s.claudeModel) && Arrays.asList("manual", "acceptEdits", "plan").contains(s.claudeMode) && Arrays.asList("default", "low", "medium", "high", "xhigh", "max").contains(s.claudeEffort) && Arrays.asList("new", "choose", "id").contains(s.claudeChat) && validThread(s.claudeThread) && validThread(s.claudeSocketId)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(context: Context) {
        val dir: File = directory(context.getFilesDir())
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create Claude controls")
        }
        protect(dir)
        for (asset in arrayOf<String>("claude-bootstrap.sh", "claude-control.js", "claude-control-v2.js", "claude-chat.cjs", "claude-native-hook.cjs", "agent-receipts.cjs", "agent-attachments.cjs")) {
            val temporary: File = File.createTempFile("claude-", ".tmp", dir)
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
                if (!temporary.renameTo(File(dir, asset))) {
                    throw IOException("Cannot replace Claude controls")
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
            throw IOException("Cannot protect Claude controls")
        }
    }

    @JvmStatic
    fun command(files: File, s: WorkspaceStore.Session?): String {
        if (!valid(s)) {
            throw IllegalArgumentException("Invalid Claude profile")
        }
        return "( set -e; export JOBS_CLAUDE_LANGUAGE=" + (if (WorkspaceText.tr("Агент: ")!!.startsWith("Агент")) "ru" else "en") + "; export JOBS_CLAUDE_EFFORT=" + SessionShell.quote(s!!.claudeEffort) + "; source " + SessionShell.quote(GUEST + "/claude-bootstrap.sh") + "; exec \"\$JOBS_CLAUDE_NODE\" " + SessionShell.quote(GUEST + "/claude-control-v2.js") + " run " + SessionShell.quote(GUEST + "/" + s.claudeSocketId + ".sock") + " " + SessionShell.quote(s.claudeModel) + " " + SessionShell.quote(s.claudeMode) + " " + SessionShell.quote(s.claudeChat) + " " + SessionShell.quote(s.claudeThread) + " )"
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
            throw IllegalArgumentException("Invalid Claude run")
        }
        val guest: String = "node=\$(cat \"\$HOME/.local/share/termux-jobs/claude-code/controller-node\") && exec \"\$node\" " + SessionShell.quote(GUEST + "/claude-control-v2.js") + " stop " + SessionShell.quote(GUEST + "/" + runId + ".sock")
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
                throw IOException("Claude stop timeout")
            }
        } finally {
            p.destroy()
        }
    }
}
