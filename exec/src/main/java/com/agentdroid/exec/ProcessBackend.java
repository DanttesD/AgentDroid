package com.agentdroid.exec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the request script through a local launcher, e.g. {@code ["sh","-c"]},
 * {@code ["su","-c"]} or a Termux bash. Used by the Root, Termux and sandbox backends.
 */
public class ProcessBackend implements ExecBackend {
    private static final int MAX_OUTPUT = 8 * 1024 * 1024;

    private final String id;
    private final String displayName;
    private final List<String> launcher;

    public ProcessBackend(String id, String displayName, List<String> launcher) {
        if (launcher.isEmpty()) throw new IllegalArgumentException("launcher is empty");
        this.id = id;
        this.displayName = displayName;
        this.launcher = new ArrayList<>(launcher);
    }

    public ProcessBackend(String id, String displayName, String... launcher) {
        this(id, displayName, Arrays.asList(launcher));
    }

    @Override public String id() { return id; }

    @Override public String displayName() { return displayName; }

    @Override public boolean isAvailable() {
        try {
            ExecResult r = exec(ExecRequest.builder("echo agentdroid-ok").timeoutMs(5000).build());
            return r.ok() && r.stdout().contains("agentdroid-ok");
        } catch (Exception e) {
            return false;
        }
    }

    @Override public ExecResult exec(ExecRequest request) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(launcher);
        cmd.add(request.toScript());
        Process p = new ProcessBuilder(cmd).start();

        final byte[] stdin = request.stdin();
        Thread feeder = new Thread(() -> {
            try (OutputStream os = p.getOutputStream()) {
                if (stdin != null) os.write(stdin);
            } catch (IOException ignored) {
                // process ended before reading all input
            }
        }, "exec-stdin");
        feeder.setDaemon(true);
        feeder.start();

        final byte[][] out = new byte[2][];
        Thread outReader = reader(p, false, out, 0);
        Thread errReader = reader(p, true, out, 1);

        boolean finished = p.waitFor(request.timeoutMs(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
        outReader.join(2000);
        errReader.join(2000);
        int code = finished ? p.exitValue() : -1;
        return new ExecResult(code, out[0], out[1], !finished);
    }

    private static Thread reader(Process p, boolean err, byte[][] sink, int idx) {
        Thread t = new Thread(() -> {
            try {
                sink[idx] = ShellFiles.drain(err ? p.getErrorStream() : p.getInputStream(), MAX_OUTPUT);
            } catch (IOException e) {
                sink[idx] = new byte[0];
            }
        }, err ? "exec-stderr" : "exec-stdout");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
