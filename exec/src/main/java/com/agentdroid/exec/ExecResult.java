package com.agentdroid.exec;

import java.nio.charset.StandardCharsets;

/** Result of a command. {@code exitCode} is -1 when it could not be determined. */
public final class ExecResult {
    private final int exitCode;
    private final byte[] stdout;
    private final byte[] stderr;
    private final boolean timedOut;

    public ExecResult(int exitCode, byte[] stdout, byte[] stderr, boolean timedOut) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? new byte[0] : stdout;
        this.stderr = stderr == null ? new byte[0] : stderr;
        this.timedOut = timedOut;
    }

    public int exitCode() { return exitCode; }
    public boolean timedOut() { return timedOut; }
    public boolean ok() { return !timedOut && exitCode == 0; }
    public byte[] stdoutBytes() { return stdout.clone(); }
    public String stdout() { return new String(stdout, StandardCharsets.UTF_8); }
    public String stderr() { return new String(stderr, StandardCharsets.UTF_8); }

    @Override public String toString() {
        return "ExecResult{exit=" + exitCode + (timedOut ? ", timedOut" : "") + ", stdout=" + stdout.length + "B, stderr=" + stderr.length + "B}";
    }
}
