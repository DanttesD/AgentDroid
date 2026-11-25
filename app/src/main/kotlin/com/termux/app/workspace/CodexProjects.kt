package com.termux.app.workspace

import android.text.TextUtils
import com.termux.shared.termux.TermuxConstants
import java.util.ArrayDeque
import java.util.Deque

/** Project association is a directory plus an environment, not a copied transcript.*/
object CodexProjects {
    @JvmStatic
    fun directory(environment: String, path: String?): String {
        var path = path
        val home: String = if (AgentEnvironments.ubuntu(environment)) "/root" else TermuxConstants.TERMUX_HOME_DIR_PATH
        if (path == null || path.isEmpty() || path.equals("~")) {
            path = home
        } else if (path.startsWith("~/")) {
            path = home + path.substring(1)
        }
        if (!path.startsWith("/") || path.indexOf('\u0000') >= 0 || path.contains("\n") || path.contains("\r")) {
            return ""
        }
        val parts: Deque<String> = ArrayDeque<String>()
        for (part in (path as java.lang.String).split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast()
                }
            } else {
                parts.addLast(part)
            }
        }
        return "/" + TextUtils.join("/", parts)
    }

    @JvmStatic
    fun matches(environment: String, expected: String?, actual: String?): Boolean {
        val a: String = directory(environment, expected)
        return !a.isEmpty() && a.equals(directory(environment, actual))
    }

    @JvmStatic
    fun bind(profile: WorkspaceStore.Session?, status: CodexStatus): Boolean {
        if (profile!!.codexSocketId.equals(profile.codexBindingBlockedRun) || java.lang.Boolean.FALSE.equals(status.materialized) || !profile.environment.equals("codex") || !profile.codexChat.equals("new") || !status.error!!.isEmpty() || !CodexSessionShell.validThread(status.thread) || status.cwd.isEmpty() || !matches(profile.codexEnvironment, profile.directory, status.cwd)) {
            return false
        }
        profile.codexSourceDirectory = status.cwd
        profile.codexChat = "id"
        profile.codexThread = status.thread
        profile.codexThreadName = if (status.title.isEmpty()) profile.name else status.title
        return true
    }
}
