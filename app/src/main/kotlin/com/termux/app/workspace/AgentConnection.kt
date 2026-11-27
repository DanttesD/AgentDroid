package com.termux.app.workspace

/** Optional user-owned executable, scoped to this agent's startup process only.*/
object AgentConnection {
    @JvmStatic
    fun valid(value: String?): Boolean {
        if (value == null || value.length > 4096) {
            return false
        }
        if (value.isEmpty()) {
            return true
        }
        if (!value.startsWith("/")) {
            return false
        }
        for (i in 0 until (value.length)) {
            if (Character.isISOControl(value[i])) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun wrap(profile: WorkspaceStore.Session?, command: String): String {
        if (!AgentPreparation.supports(profile) || profile!!.agentConnectionCommand.isEmpty()) {
            return command
        }
        val path: String = profile.agentConnectionCommand
        if (!valid(path)) {
            throw IllegalArgumentException("Invalid agent connection executable")
        }
        val q: String = SessionShell.quote(path)
        return "( [ -f " + q + " ] && [ -x " + q + " ] || { printf '%s\\n' " + SessionShell.quote(if (WorkspaceText.tr("Агент: ")!!.startsWith("Агент")) "Команда подключения недоступна или не исполняется. Обычный запуск не выполнен." else "Connection executable is missing or not executable. Direct startup was not used.") + " >&2; exit 126; }; exec " + q + " /bin/bash --noprofile --norc -c " + SessionShell.quote(command) + " )"
    }
}
