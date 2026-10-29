package com.agentdroid.exec;

import java.util.LinkedHashMap;
import java.util.Map;

/** A command to run through an {@link ExecBackend}. Immutable. */
public final class ExecRequest {
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private final String command;
    private final String workingDir;
    private final Map<String, String> env;
    private final byte[] stdin;
    private final long timeoutMs;

    private ExecRequest(Builder b) {
        this.command = b.command;
        this.workingDir = b.workingDir;
        this.env = new LinkedHashMap<>(b.env);
        this.stdin = b.stdin == null ? null : b.stdin.clone();
        this.timeoutMs = b.timeoutMs;
    }

    public static Builder builder(String command) { return new Builder(command); }

    public static ExecRequest of(String command) { return builder(command).build(); }

    public String command() { return command; }
    public String workingDir() { return workingDir; }
    public Map<String, String> env() { return new LinkedHashMap<>(env); }
    public byte[] stdin() { return stdin == null ? null : stdin.clone(); }
    public long timeoutMs() { return timeoutMs; }

    /**
     * The command as one shell script: working directory and environment are part of
     * the text, so backends that only accept a command string (su, adb) behave the same.
     */
    public String toScript() {
        StringBuilder sb = new StringBuilder();
        if (workingDir != null) {
            sb.append("cd ").append(ShellQuote.quote(workingDir)).append(" || exit 1\n");
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            sb.append("export ").append(e.getKey()).append('=').append(ShellQuote.quote(e.getValue())).append('\n');
        }
        sb.append(command);
        return sb.toString();
    }

    public static final class Builder {
        private final String command;
        private String workingDir;
        private final Map<String, String> env = new LinkedHashMap<>();
        private byte[] stdin;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;

        private Builder(String command) {
            if (command == null || command.isEmpty()) throw new IllegalArgumentException("command is empty");
            this.command = command;
        }

        public Builder workingDir(String dir) { this.workingDir = dir; return this; }

        public Builder env(String name, String value) {
            if (!ShellQuote.isValidEnvName(name)) throw new IllegalArgumentException("bad env name: " + name);
            env.put(name, value);
            return this;
        }

        public Builder stdin(byte[] data) { this.stdin = data; return this; }

        public Builder timeoutMs(long ms) {
            if (ms <= 0) throw new IllegalArgumentException("timeout must be positive");
            this.timeoutMs = ms;
            return this;
        }

        public ExecRequest build() { return new ExecRequest(this); }
    }
}
