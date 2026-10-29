package com.agentdroid.exec;

import java.io.IOException;

/**
 * Minimal view of a remote shell (the own ADB client implements it in the app module),
 * so this module does not depend on Android code.
 */
public interface ShellTransport {
    /** Runs a script. Output is merged stdout+stderr; exit code is -1 if unknown. */
    Output run(String script, long timeoutMs) throws IOException;

    final class Output {
        public final int exitCode;
        public final String text;

        public Output(int exitCode, String text) {
            this.exitCode = exitCode;
            this.text = text;
        }
    }

    /** True while the endpoint answers (for example 127.0.0.1:5555 or the Wi-Fi debugging port). */
    boolean isReachable();
}
