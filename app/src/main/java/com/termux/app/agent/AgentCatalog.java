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

        /** Installs Node.js and the agent when they are missing; safe to run every time. */
        public String ensureInstalled() {
            return "command -v " + bin + " >/dev/null 2>&1 || { command -v npm >/dev/null 2>&1 || pkg install -y nodejs; "
                    + install + "; }";
        }

        /** Command with the account directory applied. */
        public String withAccount(String cmd, String accountDir) {
            return "env " + envKey + "=" + ShellQuote.quote(accountDir) + " " + cmd;
        }
    }

    public static final Entry[] ALL = {
            new Entry("claude", "Claude Code", "claude", "npm install -g @anthropic-ai/claude-code",
                    "claude auth login", "claude", "CLAUDE_CONFIG_DIR", com.termux.R.string.acc_note_claude),
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
