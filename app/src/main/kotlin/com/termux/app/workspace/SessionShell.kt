package com.termux.app.workspace

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Explicit Bash sessions with per-profile history, never terminal keystroke logging.*/
object SessionShell {
    @JvmStatic
    fun quote(value: String?): String {
        return "'" + (value!! as java.lang.String).replace("'", "'\\''") + "'"
    }

    @JvmStatic
    fun directory(files: File, id: String): File {
        return File(File(files, "workspace-sessions"), id)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(files: File, session: WorkspaceStore.Session): File {
        return prepare(files, session, null, null)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(files: File, session: WorkspaceStore.Session?, askpass: String?, socket: String?): File {
        if (!session!!.prootAlias.isEmpty() && (!session.environment.equals("termux") || session.geminiAgent || session.claudeAgent || !session.aiAgent.isEmpty() || !WorkspaceLocalFilePicker.validAlias(session.prootAlias))) {
            throw IOException("Invalid environment binding")
        }
        val dir: File = directory(files, session.id)
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create session directory")
        }
        val history = File(dir, "history")
        if (!history.exists()) {
            write(history, ByteArray(0))
        }
        val rc = File(dir, "bashrc")
        val cancel = File(dir, "cancel-startup")
        if (cancel.exists() && !cancel.delete()) {
            throw IOException("Cannot reset startup cancellation")
        }
        var script: String
        var startup: MutableList<String> = session.startupCommands()
        if (!session.packageRuntimeStartup.isEmpty()) {
            startup = Collections.singletonList(session.packageRuntimeStartup)
        }
        val otherGuest: Boolean = !WorkspaceEnvironments.alias(session).isEmpty() && !session.environment.equals("ubuntu")
        val guest: Boolean = otherGuest || session.environment.equals("ubuntu") || session.environment.equals("codex") && AgentEnvironments.ubuntu(session.codexEnvironment)
        if (session.geminiAgent) {
            startup = Collections.singletonList(GeminiCli.command(files, session))
        }
        if (session.claudeAgent) {
            startup = Collections.singletonList(ClaudeCode.command(files, session))
        }
        if (session.aiAgent.equals("opencode")) {
            startup = Collections.singletonList(AiAgents.command(files, session))
        }
        if (session.environment.equals("codex")) {
            startup = Collections.singletonList(CodexSessionShell.command(files, session))
        }
        if (AgentPreparation.supports(session) && !startup.isEmpty()) {
            startup = Collections.singletonList(AgentPreparation.wrap(session, startup.get(0)))
        }
        if (AgentPreparation.supports(session) && !startup.isEmpty()) {
            startup = Collections.singletonList(AgentConnection.wrap(session, startup.get(0)))
        }
        if (guest) {
            val mount: String = "/tmp/termux-fork-session-" + session.id
            val alias: String = if (otherGuest) WorkspaceEnvironments.alias(session) else if (!AgentPreparation.agent(session).isEmpty()) AgentEnvironments.alias(session) else "ubuntu"
            if (!WorkspaceLocalFilePicker.validAlias(alias)) {
                throw IOException("Invalid environment alias")
            }
            val guestRc = File(dir, "ubuntu-bashrc")
            write(guestRc, (((if (otherGuest) "export TERMUX_JOBS_ENVIRONMENT_ALIAS=" + quote(alias) + "\n" else "") + buildReadyRc("\$HOME", mount + "/history", mount + "/cancel-startup", startup, true)) as java.lang.String).getBytes(StandardCharsets.UTF_8))
            if (otherGuest) {
                write(File(dir, "proot-shrc"), (("export TERMUX_JOBS_ENVIRONMENT_ALIAS=" + quote(alias) + "\n" + (if (WorkspaceAliases.ENVIRONMENTS.contains(alias)) WorkspaceAliases.hook() else "") + portableRc(mount + "/history", mount + "/cancel-startup", startup)) as java.lang.String).getBytes(StandardCharsets.UTF_8))
            }
            var cwd: String? = if (session.directory!!.isEmpty() || session.directory!!.equals("~")) "/root" else session.directory
            if (cwd!!.startsWith("~/")) {
                cwd = "/root/" + cwd.substring(2)
            }
            val apiHandoff: Boolean = session.geminiAgent && session.geminiAuth.equals("api") && !session.geminiEncryptedKey.isEmpty()
            val handoff: String = if (apiHandoff) GeminiApiKeyVault.runtimePath(files, session).getAbsolutePath() else ""
            script = buildRc(TermuxConstants.TERMUX_HOME_DIR_PATH, history.getAbsolutePath(), "") + (if (apiHandoff) "( trap " + quote("rm -f -- " + quote(handoff)) + " EXIT\n" else "") + AgentPreparation.host(files, session) + (if (otherGuest) "printf '%s\\n' " + quote(WorkspaceText.tr("Ожидание оболочки ") + WorkspaceEnvironments.label(alias) + "…") + "\n" else WorkspaceText.tr("printf '%s\\n' 'Ожидание оболочки Ubuntu…'\n")) + "proot-distro " + (if (otherGuest) WorkspaceEnvironments.verb(alias) else "login") + " " + (if (session.prootIsolated) "--isolated " else "") + "--bind " + quote(dir.getAbsolutePath() + ":" + mount) + (if (AgentPreparation.supports(session)) " --bind " + quote(AgentPreparation.directory(files).getAbsolutePath() + ":" + AgentPreparation.GUEST) else "") + (if (session.geminiAgent) " --bind " + quote(GeminiCli.directory(files).getAbsolutePath() + ":" + GeminiCli.GUEST) else "") + (if (session.claudeAgent) " --bind " + quote(ClaudeCode.directory(files).getAbsolutePath() + ":" + ClaudeCode.GUEST) else "") + (if (session.aiAgent.equals("opencode")) " --bind " + quote(AiAgents.directory(files).getAbsolutePath() + ":/tmp/termux-jobs-ai") else "") + (if (session.environment.equals("codex")) " --bind " + quote(CodexSessionShell.socketDirectory(files).getAbsolutePath() + ":/tmp/termux-jobs-codex") else "") + " --work-dir " + quote(cwd) + " " + (if (alias.equals("ubuntu")) "ubuntu" else quote(alias)) + " -- " + (if (otherGuest) "/bin/sh -c " + quote("if command -v bash >/dev/null 2>&1; then exec bash --rcfile " + quote(mount + "/ubuntu-bashrc") + " -i; else SHELL=/bin/sh; export SHELL; ENV=" + quote(mount + "/proot-shrc") + "; export ENV; exec /bin/sh -i; fi") + "\n" else "/bin/bash --rcfile " + quote(mount + "/ubuntu-bashrc") + " -i\n")
            if (apiHandoff) {
                script += ")\n"
            }
        } else if (session.environment.equals("ssh")) {
            script = buildReadyRc(TermuxConstants.TERMUX_HOME_DIR_PATH, history.getAbsolutePath(), cancel.getAbsolutePath(), Collections.singletonList((if (session.sshAuth.equals("password") && askpass != null && socket != null) "LC_ALL=C SSH_ASKPASS=" + quote(askpass) + " SSH_ASKPASS_REQUIRE=force TERMUX_JOBS_SSH_SOCKET=" + quote(socket) + " " else "") + SshSessionShell.command(session)), false)
        } else if (session.environment.equals("termux") || session.environment.equals("codex")) {
            script = buildReadyRc(TermuxConstants.TERMUX_HOME_DIR_PATH, history.getAbsolutePath(), cancel.getAbsolutePath(), startup, false)
        } else {
            script = buildRc(TermuxConstants.TERMUX_HOME_DIR_PATH, history.getAbsolutePath(), session.startupCommands())
        }
        write(rc, (script as java.lang.String).getBytes(StandardCharsets.UTF_8))
        return rc
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepareAskpass(files: File, session: WorkspaceStore.Session?, apk: String): File {
        val dir: File = directory(files, session!!.id)
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create session directory")
        }
        val helper = File(dir, "ssh-askpass")
        write(helper, (("#!/system/bin/sh\nexport CLASSPATH=" + quote(apk) + "\nexec /system/bin/app_process /system/bin com.termux.app.workspace.SshAskpassMain \"\$@\"\n") as java.lang.String).getBytes(StandardCharsets.UTF_8))
        if (!helper.setReadable(false, false) || !helper.setWritable(false, false) || !helper.setExecutable(false, false) || !helper.setReadable(true, true) || !helper.setWritable(true, true) || !helper.setExecutable(true, true)) {
            throw IOException("Cannot protect SSH helper")
        }
        return helper
    }

    @JvmStatic
    fun buildRc(home: String, history: String, startup: String): String {
        return "if [ -f " + quote(home + "/.bashrc") + " ]; then . " + quote(home + "/.bashrc") + "; fi\nHISTFILE=" + quote(history) + "\nHISTSIZE=2000\nHISTFILESIZE=2000\nshopt -s histappend\nhistory -c\nhistory -r \"\$HISTFILE\"\nPROMPT_COMMAND+=('history -a')\n" + (if ((startup as java.lang.String).trim().isEmpty()) "" else "history -s -- " + quote(startup) + "\nhistory -a\n" + startup + "\n")
    }

    @JvmStatic
    fun buildRc(home: String, history: String, commands: MutableList<String>): String {
        if (commands.size <= 1) {
            return buildRc(home, history, if (commands.isEmpty()) "" else commands.get(0))
        }
        val script = StringBuilder(buildRc(home, history, ""))
        script.append("_termux_workspace_startup() {\n")
        var i = 0
        while (i < commands.size) {
            val command: String = commands.get(i)
            script.append("history -s -- ").append(quote(command)).append("\nhistory -a\n")
            script.append("eval -- ").append(quote(command)).append("\n")
            script.append("local _termux_workspace_status=\$?\n")
            script.append("if [ \"\$_termux_workspace_status\" -ne 0 ]; then printf '%s\\n' ").append(quote(WorkspaceText.tr("Команда ") + (i + 1) + WorkspaceText.tr(" завершилась с ошибкой; запуск остановлен."))).append(" >&2; return \"\$_termux_workspace_status\"; fi\n")
            i++
        }
        script.append("}\n_termux_workspace_startup\nunset -f _termux_workspace_startup\n")
        return script.toString()
    }

    /** Runs only at Bash's first prompt, after rc initialization. No prompt-text heuristics or timed input.*/
    @JvmStatic
    fun buildReadyRc(home: String, history: String, cancel: String, commands: MutableList<String>, guest: Boolean): String {
        var base: String = buildRc(home, history, "")
        if (guest) {
            base = (base as java.lang.String).replace(quote(home + "/.bashrc"), "\"\$HOME/.bashrc\"")
        }
        if (commands.isEmpty()) {
            return base
        }
        val script = StringBuilder(base)
        script.append("_termux_workspace_ready() {\n").append("[ \"\${_termux_workspace_done:-0}\" = 1 ] && return 0\n_termux_workspace_done=1\n")
        var i = 0
        while (i < commands.size) {
            script.append("if [ -f ").append(quote(cancel)).append(WorkspaceText.tr(" ]; then printf '%s\\n' 'Запуск команд отменён.'; return 0; fi\n"))
            script.append("printf '%s\\n' ").append(quote(WorkspaceText.tr("Оболочка готова. Запуск команды ") + (i + 1) + "/" + commands.size)).append("\n")
            script.append("history -s -- ").append(quote(commands.get(i))).append("\nhistory -a\n")
            script.append("eval -- ").append(quote(commands.get(i))).append("\nlocal _termux_workspace_status=\$?\n").append("if [ \"\$_termux_workspace_status\" -ne 0 ]; then printf '%s\\n' ").append(quote(WorkspaceText.tr("Ошибка команды ") + (i + 1) + WorkspaceText.tr("; дальнейший запуск остановлен."))).append(" >&2; return 0; fi\n")
            i++
        }
        script.append("return 0\n}\nPROMPT_COMMAND+=('_termux_workspace_ready')\n")
        return script.toString()
    }

    @JvmStatic
    fun portableRc(history: String, cancel: String, commands: MutableList<String>): String {
        val rc = StringBuilder("HISTFILE=" + quote(history) + "\nHISTSIZE=2000\nexport HISTFILE HISTSIZE\n")
        if (commands.isEmpty()) {
            return rc.toString()
        }
        rc.append("_jobs_environment_startup() {\n[ \"\${_jobs_environment_started:-0}\" = 1 ] && return 0\n_jobs_environment_started=1\n")
        for (command in commands) {
            rc.append("[ ! -f ").append(quote(cancel)).append(" ] || return 0\neval ").append(quote(command)).append("\n_jobs_status=\$?\n[ \"\$_jobs_status\" -eq 0 ] || { printf '%s\\n' 'Startup stopped after a failed command.' >&2; return 0; }\n")
        }
        return rc.append("}\n_jobs_environment_startup\n").toString()
    }

    @JvmStatic
    fun hasCancellableStartup(s: WorkspaceStore.Session?): Boolean {
        return s != null && (s.environment.equals("termux") || s.environment.equals("ubuntu")) && !s.geminiAgent && !s.claudeAgent && s.aiAgent.isEmpty() && !s.startupCommands().isEmpty()
    }

    @Throws(IOException::class)
    @JvmStatic
    fun cancel(files: File, id: String) {
        write(File(directory(files, id), "cancel-startup"), ByteArray(0))
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun write(file: File, data: ByteArray) {
        (FileOutputStream(file)).use { out ->
            out.write(data)
        }
    }
}
