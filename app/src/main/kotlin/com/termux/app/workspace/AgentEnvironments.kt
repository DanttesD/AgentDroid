package com.termux.app.workspace

import com.termux.shared.termux.TermuxConstants
import java.io.File

/** Ubuntu runtime selection is shared by all four agents; old profiles stay on Ubuntu.*/
object AgentEnvironments {
    @JvmStatic
    fun editorUbuntu(existing: WorkspaceStore.Session?): String {
        return if (existing == null) "ubuntu-ai" else existing.codexEnvironment
    }

    @JvmStatic
    fun ubuntu(name: String): Boolean {
        return "ubuntu".equals(name) || "ubuntu-ai".equals(name)
    }

    @JvmStatic
    fun defaultUbuntu(): String {
        return defaultUbuntu(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"))
    }

    @JvmStatic
    fun defaultUbuntu(proot: File): String {
        return if (WorkspaceEnvironments.root(proot, "ubuntu-ai") != null || WorkspaceEnvironments.root(proot, "ubuntu") == null) "ubuntu-ai" else "ubuntu"
    }

    @JvmStatic
    fun alias(p: WorkspaceStore.Session?): String {
        if (!ubuntu(p!!.codexEnvironment)) {
            throw IllegalArgumentException("Invalid agent Ubuntu")
        }
        return p.codexEnvironment
    }

    @JvmStatic
    fun scope(p: WorkspaceStore.Session?): String {
        return if (!AgentPreparation.agent(p).isEmpty() && ubuntu(p!!.codexEnvironment)) p.codexEnvironment else WorkspaceEnvironments.alias(p)
    }

    @JvmStatic
    fun label(name: String): String {
        return if ("ubuntu-ai".equals(name)) "Ubuntu AI" else if (name.equals("ubuntu")) "Ubuntu" else "Termux"
    }
}
