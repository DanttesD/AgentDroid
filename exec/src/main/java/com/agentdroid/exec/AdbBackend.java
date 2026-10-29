package com.agentdroid.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Commands with shell rights through the own ADB client. stderr is merged into stdout. */
public class AdbBackend implements ExecBackend {
    private final String id;
    private final String displayName;
    private final ShellTransport transport;

    /** id examples: "adb-local" (127.0.0.1 TCP port, works without Wi-Fi) and "adb-wifi". */
    public AdbBackend(String id, String displayName, ShellTransport transport) {
        this.id = id;
        this.displayName = displayName;
        this.transport = transport;
    }

    @Override public String id() { return id; }

    @Override public String displayName() { return displayName; }

    @Override public boolean isAvailable() {
        try {
            return transport.isReachable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override public ExecResult exec(ExecRequest request) throws IOException {
        String script = request.toScript();
        byte[] stdin = request.stdin();
        if (stdin != null) {
            // The ADB shell service has no separate stdin channel: pass input as base64 through a pipe.
            String b64 = new String(Base64.getEncoder().encode(stdin), StandardCharsets.US_ASCII);
            script = "printf %s " + ShellQuote.quote(b64) + " | base64 -d | (" + script + ")";
        }
        ShellTransport.Output o = transport.run(script, request.timeoutMs());
        byte[] text = o.text.getBytes(StandardCharsets.UTF_8);
        return new ExecResult(o.exitCode, text, new byte[0], false);
    }
}
