package com.agentdroid.exec;

/** Commands as uid 0 through {@code su -c}. Available only when su answers with uid=0. */
public class RootBackend extends ProcessBackend {
    public RootBackend() { this("su"); }

    public RootBackend(String suBinary) { super("root", "Root (su)", suBinary, "-c"); }

    @Override public boolean isAvailable() {
        try {
            ExecResult r = exec(ExecRequest.builder("id -u").timeoutMs(8000).build());
            return r.ok() && r.stdout().trim().equals("0");
        } catch (Exception e) {
            return false;
        }
    }
}
