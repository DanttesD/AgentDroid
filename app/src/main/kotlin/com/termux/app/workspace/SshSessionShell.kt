package com.termux.app.workspace

/** SSH owns authentication; no passwords or private keys are stored in profiles.*/
object SshSessionShell {
    @JvmStatic
    fun validHost(host: String?): Boolean {
        return host != null && !host.isEmpty() && !host.startsWith("-") && (host as java.lang.String).matches("[A-Za-z0-9_.:%-]+")
    }

    @JvmStatic
    fun validUser(user: String?): Boolean {
        return user != null && (user as java.lang.String).matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")
    }

    @JvmStatic
    fun validPort(port: Int): Boolean {
        return port >= 1 && port <= 65535
    }

    @JvmStatic
    fun validIdentity(path: String?): Boolean {
        return path != null && (path.startsWith("/") || path.startsWith("~/")) && path.indexOf('\u0000') < 0 && !path.contains("\n") && !path.contains("\r")
    }

    @JvmStatic
    fun command(session: WorkspaceStore.Session?): String {
        if (!validHost(session!!.sshHost) || !validUser(session.sshUser) || !validPort(session.sshPort)) {
            throw IllegalArgumentException("Invalid SSH connection settings")
        }
        var options = ""
        if (session.sshAuth.equals("key")) {
            if (!validIdentity(session.sshIdentity)) {
                throw IllegalArgumentException("Invalid SSH identity")
            }
            val path: String = if (session.sshIdentity.startsWith("~/")) "\"\$HOME\"/" + SessionShell.quote(session.sshIdentity.substring(2)) else SessionShell.quote(session.sshIdentity)
            options = " -o IdentitiesOnly=yes -i " + path
        } else if (session.sshAuth.equals("password")) {
            options = " -o PreferredAuthentications=password -o PubkeyAuthentication=no -o KbdInteractiveAuthentication=no -o NumberOfPasswordPrompts=1 -o BatchMode=no -o StrictHostKeyChecking=ask -o ControlMaster=no -o ControlPath=none"
        }
        return "ssh -t" + options + " -p " + session.sshPort + " -l " + SessionShell.quote(session.sshUser) + " -- " + SessionShell.quote(session.sshHost) + " " + SessionShell.quote("bash -ic " + SessionShell.quote(remoteScript(session)))
    }

    @JvmStatic
    fun remoteScript(session: WorkspaceStore.Session?): String {
        val cwd: String? = session!!.directory
        val target: String = if (cwd!!.isEmpty() || cwd.equals("~")) "\"\$HOME\"" else if (cwd.startsWith("~/")) "\"\$HOME\"/" + SessionShell.quote(cwd.substring(2)) else SessionShell.quote(cwd)
        val script = StringBuilder("_termux_remote_startup() {\ncd -- " + target + WorkspaceText.tr(" || { printf '%s\\n' 'Не удалось открыть каталог на сервере.' >&2; return 1; }\n"))
        var index = 0
        for (startup in session.startupCommands()) {
            index++
            script.append("printf '%s\\n' ").append(SessionShell.quote(WorkspaceText.tr("Сервер: команда ") + index + "/" + session.startupCommands().size)).append("\n").append("eval -- ").append(SessionShell.quote(startup)).append("\nlocal _termux_remote_status=\$?\n").append("if [ \"\$_termux_remote_status\" -ne 0 ]; then printf '%s\\n' ").append(SessionShell.quote(WorkspaceText.tr("Ошибка команды ") + index + WorkspaceText.tr("; дальнейший запуск остановлен."))).append(" >&2; return \"\$_termux_remote_status\"; fi\n")
        }
        script.append("}\n_termux_remote_startup\n_termux_remote_result=\$?\nunset -f _termux_remote_startup\n")
        if (session.sshKeepOpen) {
            script.append("exec bash -i\n")
        } else {
            script.append("exit \"\$_termux_remote_result\"\n")
        }
        return script.toString()
    }
}
