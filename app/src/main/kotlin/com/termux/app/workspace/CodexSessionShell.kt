package com.termux.app.workspace

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Dedicated local server with explicit permissions, no account/config-file edits.*/
object CodexSessionShell {
    @JvmStatic
    fun socketDirectory(files: File): File {
        return File(files, ".codex-control")
    }

    @JvmStatic
    fun socket(files: File, s: WorkspaceStore.Session?): File {
        return File(socketDirectory(files), s!!.codexSocketId + ".sock")
    }

    @JvmStatic
    fun sandbox(access: String): String {
        if (access.equals("full")) {
            return "danger-full-access"
        }
        return if (access.equals("read")) "read-only" else "workspace-write"
    }

    @JvmStatic
    fun approval(s: WorkspaceStore.Session?): String {
        return if (if (s!!.codexConfirmActions == null) !s.codexAccess.equals("full") else s.codexConfirmActions!!) "on-request" else "never"
    }

    @JvmStatic
    fun validThread(id: String): Boolean {
        return (id as java.lang.String).matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
    }

    @JvmStatic
    fun invocation(s: WorkspaceStore.Session?, endpoint: String): String {
        val c: StringBuilder = StringBuilder("codex --remote ").append(SessionShell.quote("unix://" + endpoint))
        if (!s!!.codexChat.equals("new")) {
            c.append(" resume")
            if (s.codexChat.equals("id") && validThread(s.codexThread)) {
                c.append(' ').append(SessionShell.quote(s.codexThread))
            } else {
                throw IllegalArgumentException("Select a specific Codex chat")
            }
        } else {
            c.append(" --sandbox ").append(sandbox(s.codexAccess))
            c.append(" --ask-for-approval ").append(approval(s))
        }
        return c.toString()
    }

    @Throws(IOException::class)
    @JvmStatic
    fun command(files: File, s: WorkspaceStore.Session?): String {
        val controls: File = socketDirectory(files)
        if (!controls.isDirectory() && !controls.mkdirs()) {
            throw IOException("Cannot create Codex control directory")
        }
        if (!controls.setReadable(false, false) || !controls.setWritable(false, false) || !controls.setExecutable(false, false) || !controls.setReadable(true, true) || !controls.setWritable(true, true) || !controls.setExecutable(true, true)) {
            throw IOException("Cannot protect control directory")
        }
        val host: String = socket(files, s).getAbsolutePath()
        if ((host as java.lang.String).getBytes(StandardCharsets.UTF_8).size > 100) {
            throw IOException("Codex socket path too long")
        }
        val endpoint: String = if (AgentEnvironments.ubuntu(s!!.codexEnvironment)) "/tmp/termux-jobs-codex/" + socket(files, s).getName() else host
        val stop: String = endpoint + ".stop"
        val stopped: String = endpoint + ".stopped"
        var client: String = invocation(s, endpoint)
        if (!s.codexChat.equals("new")) {
            client = "codex --remote " + SessionShell.quote("unix://" + endpoint) + " resume \"\$_jobs_codex_thread\""
        }
        return WorkspaceText.tr("(\ncommand -v codex >/dev/null 2>&1 || { printf '%s\\n' 'Codex не установлен в выбранной среде.' >&2; exit 127; }\n") + WorkspaceText.tr("command -v node >/dev/null 2>&1 || { printf '%s\\n' 'Для локального управления Codex нужен Node.js в выбранной среде.' >&2; exit 127; }\n") + "[ ! -e " + SessionShell.quote(endpoint) + WorkspaceText.tr(" ] || { printf '%s\\n' 'Подключение Codex уже занято.' >&2; exit 1; }\n") + "umask 077\ncodex --no-daemon -c " + SessionShell.quote("sandbox_mode=\"" + sandbox(s.codexAccess) + "\"") + " -c " + SessionShell.quote("approval_policy=\"" + approval(s) + "\"") + " app-server --listen " + SessionShell.quote("unix://" + endpoint) + " >/dev/null 2>&1 &\n_jobs_codex_pid=\$!\ntrap 'if [ -n \"\${_jobs_codex_status_pid:-}\" ]; then kill \"\$_jobs_codex_status_pid\" 2>/dev/null; wait \"\$_jobs_codex_status_pid\" 2>/dev/null; fi; if [ -n \"\${_jobs_codex_watch_pid:-}\" ]; then kill \"\$_jobs_codex_watch_pid\" 2>/dev/null; fi; kill \"\$_jobs_codex_pid\" 2>/dev/null; wait \"\$_jobs_codex_pid\" 2>/dev/null' EXIT\ntrap 'exit 130' INT TERM HUP\n_jobs_codex_wait=0\nwhile [ ! -S " + SessionShell.quote(endpoint) + " ]; do\n" + WorkspaceText.tr("kill -0 \"\$_jobs_codex_pid\" 2>/dev/null || { printf '%s\\n' 'Не удалось запустить app-server. Проверьте версию Codex.' >&2; exit 1; }\n") + WorkspaceText.tr("_jobs_codex_wait=\$((_jobs_codex_wait+1)); [ \"\$_jobs_codex_wait\" -lt 100 ] || { printf '%s\\n' 'Истекло ожидание Codex.' >&2; exit 1; }; sleep 0.1\ndone\n") + "( while kill -0 \"\$_jobs_codex_pid\" 2>/dev/null; do if [ -f " + SessionShell.quote(stop) + " ]; then kill \"\$_jobs_codex_pid\" 2>/dev/null; printf x > " + SessionShell.quote(stopped) + "; exit; fi; sleep 0.1; done ) &\n_jobs_codex_watch_pid=\$!\nnode " + SessionShell.quote(bridgePath(files, s)) + " status " + SessionShell.quote(endpoint) + " " + SessionShell.quote(if (s.codexChat.equals("id")) s.codexThread else "") + " >/dev/null 2>&1 &\n_jobs_codex_status_pid=\$!\n" + (if (CodexChatControl.enabled(files, s)) CodexChatControl.command(files, s, endpoint) else resumePreflight(files, s, endpoint) + client) + "\n)"
    }

    @Throws(IOException::class)
    @Synchronized
    @JvmStatic
    fun prepareBridge(context: Context) {
        val directory: File = socketDirectory(context.getFilesDir())
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw IOException("Cannot create controls")
        }
        val bridge = File(directory, "bridge-v0.2.21.js")
        if (bridge.isFile()) {
            return
        }
        val temporary: File = File.createTempFile("codex-bridge-", ".tmp", directory)
        try {
            (context.getAssets().open("codex-control.js")).use { `in` ->
                (FileOutputStream(temporary)).use { out ->
                    val bytes = ByteArray(8192)
                    var count: Int = 0
                    while (true) {
                        count = `in`.read(bytes)
                        if (count == -1) break
                        out.write(bytes, 0, count)
                    }
                }
            }
            if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false) || !temporary.setReadable(true, true) || !temporary.setWritable(true, true)) {
                throw IOException("Cannot protect Codex helper")
            }
            if (!temporary.renameTo(bridge)) {
                throw IOException("Cannot publish Codex helper")
            }
        } finally {
            temporary.delete()
        }
    }

    @JvmStatic
    private fun bridgePath(files: File, s: WorkspaceStore.Session?): String {
        return if (AgentEnvironments.ubuntu(s!!.codexEnvironment)) "/tmp/termux-jobs-codex/bridge-v0.2.21.js" else File(socketDirectory(files), "bridge-v0.2.21.js").getAbsolutePath()
    }

    @JvmStatic
    fun resumePreflight(s: WorkspaceStore.Session, endpoint: String): String {
        return resumePreflight(File(TermuxConstants.TERMUX_FILES_DIR_PATH), s, endpoint)
    }

    @JvmStatic
    fun resumePreflight(files: File, s: WorkspaceStore.Session?, endpoint: String): String {
        if (s!!.codexChat.equals("new")) {
            return ""
        }
        if (!(s.codexChat.equals("id") && validThread(s.codexThread))) {
            throw IllegalArgumentException("Select a specific Codex chat")
        }
        val home: String = if (AgentEnvironments.ubuntu(s.codexEnvironment)) "/root" else TermuxConstants.TERMUX_HOME_DIR_PATH
        var cwd: String? = if (s.directory!!.isEmpty() || s.directory!!.equals("~")) home else s.directory
        if (cwd!!.startsWith("~/")) {
            cwd = home + cwd.substring(1)
        }
        return "_jobs_codex_thread=\$(node " + SessionShell.quote(bridgePath(files, s)) + " resume " + SessionShell.quote(endpoint) + " " + SessionShell.quote(s.codexAccess) + " " + SessionShell.quote(s.codexChat) + " " + SessionShell.quote(s.codexThread) + " " + SessionShell.quote(cwd) + " " + SessionShell.quote(s.codexSourceDirectory) + " " + SessionShell.quote(approval(s)) + WorkspaceText.tr(") || { printf '%s\\n' 'Не удалось продолжить чат с выбранными правами.' >&2; exit 1; }\n")
    }

    @JvmStatic
    fun proxyCommand(files: File, s: WorkspaceStore.Session): String {
        val sock: String = if (AgentEnvironments.ubuntu(s.codexEnvironment)) "/tmp/termux-jobs-codex/" + socket(files, s).getName() else socket(files, s).getAbsolutePath()
        val proxy: String = (if (AgentEnvironments.ubuntu(s.codexEnvironment)) AgentPreparation.codexPath() else "") + "command -v node >/dev/null 2>&1 || { printf '%s\\n' 'JOBS_CONTROL:NO_NODE' >&2; exit 127; }; [ -r " + SessionShell.quote(bridgePath(files, s)) + " ] || { printf '%s\\n' 'JOBS_CONTROL:NO_BRIDGE' >&2; exit 2; }; [ -S " + SessionShell.quote(sock) + " ] || { printf '%s\\n' 'JOBS_CONTROL:NO_SOCKET' >&2; exit 3; }; exec node " + SessionShell.quote(bridgePath(files, s)) + " proxy " + SessionShell.quote(sock)
        return if (AgentEnvironments.ubuntu(s.codexEnvironment)) "proot-distro login --bind " + SessionShell.quote(socketDirectory(files).getAbsolutePath() + ":/tmp/termux-jobs-codex") + " " + SessionShell.quote(AgentEnvironments.alias(s)) + " -- /bin/sh -c " + SessionShell.quote(proxy) else proxy
    }

    @JvmStatic
    fun historyCommand(s: WorkspaceStore.Session): String {
        return if (AgentEnvironments.ubuntu(s.codexEnvironment)) "proot-distro login " + SessionShell.quote(AgentEnvironments.alias(s)) + " -- /bin/sh -c " + SessionShell.quote(AgentPreparation.codexPath() + "exec codex --no-daemon app-server --stdio") else "codex --no-daemon app-server --stdio"
    }
}
