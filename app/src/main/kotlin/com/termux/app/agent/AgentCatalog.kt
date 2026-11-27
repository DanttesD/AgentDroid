package com.termux.app.agent

import com.agentdroid.exec.ShellQuote
import com.termux.R

/** External agents that can be installed in Termux and signed in with their own official login.*/
object AgentCatalog {

    class Entry {
        @JvmField val id: String
        @JvmField val title: String
        @JvmField val bin: String
        @JvmField val install: String
        @JvmField val login: String
        @JvmField val run: String
        /** Environment variable that points the agent at a per-account data directory.*/
        @JvmField val envKey: String
        @JvmField val noteRes: Int

        constructor(id: String, title: String, bin: String, install: String, login: String, run: String, envKey: String, noteRes: Int) {
            this.id = id
            this.title = title
            this.bin = bin
            this.install = install
            this.login = login
            this.run = run
            this.envKey = envKey
            this.noteRes = noteRes
        }

        /** Command that checks, inside the Debian environment, whether the agent is present.*/
        fun checkCommand(): String {
            return "proot-distro login debian -- bash -lc " + ShellQuote.quote("command -v " + bin)
        }

        /** Installs proot-distro, Debian and the agent when missing; safe to run every time.*/
        fun ensureInstalled(): String {
            val inner: String = "command -v " + bin + " >/dev/null 2>&1 || { apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm curl ca-certificates && " + install + "; }"
            return "{ command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro; } && { [ -d \"\$PREFIX/var/lib/proot-distro/containers/debian\" ] || [ -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ] || proot-distro install debian; } && proot-distro login debian -- bash -lc " + ShellQuote.quote(inner)
        }

        /** Runs cmd in Debian with the account directory shared and used as this agent's data dir.*/
        fun withAccount(cmd: String, accountDir: String): String {
            val q: String = ShellQuote.quote(accountDir)
            return "proot-distro login debian --shared-tmp --bind " + q + ":" + q + " -- env " + envKey + "=" + q + " bash -lc " + ShellQuote.quote(cmd)
        }
    }

    @JvmField val ALL: Array<Entry> = arrayOf<Entry>(Entry("claude", "Claude Code", "claude", "npm install -g @anthropic-ai/claude-code", "claude auth login || claude", "claude", "CLAUDE_CONFIG_DIR", R.string.acc_note_claude), Entry("codex", "Codex (OpenAI)", "codex", "npm install -g @openai/codex", "codex login", "codex", "CODEX_HOME", R.string.acc_note_codex), Entry("gemini", "Gemini CLI", "gemini", "npm install -g @google/gemini-cli", "gemini", "gemini", "HOME", R.string.acc_note_gemini), Entry("opencode", "OpenCode", "opencode", "npm install -g opencode-ai", "opencode auth login", "opencode", "XDG_DATA_HOME", R.string.acc_note_opencode))

    @JvmStatic
    fun find(id: String): Entry? {
        for (e in ALL) {
            if (e.id.equals(id)) {
                return e
            }
        }
        return null
    }
}
