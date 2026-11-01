package com.termux.app.agent;

import com.agentdroid.exec.ShellQuote;

/** External agents that can be installed in Termux and signed in with their own official login. */
public final class AgentCatalog {
    private AgentCatalog() {}

    public static final class Entry {
        public final String id;
        public final String title;
        public final String bin;
        public final String install;
        public final String login;
        public final String run;
        /** Environment variable that points the agent at a per-account data directory. */
        public final String envKey;
        public final int noteRes;

        Entry(String id, String title, String bin, String install, String login, String run, String envKey, int noteRes) {
            this.id = id; this.title = title; this.bin = bin; this.install = install;
            this.login = login; this.run = run; this.envKey = envKey; this.noteRes = noteRes;
        }

        /** Command that checks, inside the Debian environment, whether the agent is present. */
        public String checkCommand() {
            return "proot-distro login debian -- bash -lc " + ShellQuote.quote("command -v " + bin);
        }

        /** Installs proot-distro, Debian and the agent when missing; safe to run every time. */
        public String ensureInstalled() {
            String inner = "command -v " + bin + " >/dev/null 2>&1 || { apt-get update && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs npm curl ca-certificates && " + install + "; }";
            return "{ command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro; } && "
                    + "{ [ -d \"$PREFIX/var/lib/proot-distro/containers/debian\" ] || [ -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ] || proot-distro install debian; } && "
                    + "proot-distro login debian -- bash -lc " + ShellQuote.quote(inner);
        }

        /** Runs cmd in Debian with the account directory shared and used as this agent's data dir. */
        public String withAccount(String cmd, String accountDir) {
            String q = ShellQuote.quote(accountDir);
            return "proot-distro login debian --shared-tmp --bind " + q + ":" + q + " -- env "
                    + envKey + "=" + q + " bash -lc " + ShellQuote.quote(cmd);
        }
    }

    public static final Entry[] ALL = {
            new Entry("claude", "Claude Code", "claude", "npm install -g @anthropic-ai/claude-code",
                    "claude auth login || claude", "claude", "CLAUDE_CONFIG_DIR", com.termux.R.string.acc_note_claude),
            new Entry("codex", "Codex (OpenAI)", "codex", "npm install -g @openai/codex",
                    "codex login", "codex", "CODEX_HOME", com.termux.R.string.acc_note_codex),
            new Entry("gemini", "Gemini CLI", "gemini", "npm install -g @google/gemini-cli",
                    "gemini", "gemini", "HOME", com.termux.R.string.acc_note_gemini),
            new Entry("opencode", "OpenCode", "opencode", "npm install -g opencode-ai",
                    "opencode auth login", "opencode", "XDG_DATA_HOME", com.termux.R.string.acc_note_opencode),
    };

    public static Entry find(String id) {
        for (Entry e : ALL) if (e.id.equals(id)) return e;
        return null;
    }
}
