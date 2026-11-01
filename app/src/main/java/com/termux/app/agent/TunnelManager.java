package com.termux.app.agent;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the cloudflared quick tunnel as a child process and reports its public address and state. */
public final class TunnelManager {
    private static final Pattern URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static Process process;
    private static volatile String url = "";
    private static volatile boolean connected;
    private static volatile String error = "";

    private TunnelManager() {}

    public static synchronized boolean isRunning() { return process != null && process.isAlive(); }
    public static String url() { return url; }
    public static boolean isConnected() { return connected && isRunning(); }
    public static String error() { return error; }

    public static synchronized void start(int port) {
        if (isRunning()) return;
        url = ""; connected = false; error = "";
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File bin = new File(prefix + "/bin/cloudflared");
        if (!bin.exists()) { error = "cloudflared"; return; }
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getPath(), "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + port);
            pb.environment().put("PATH", prefix + "/bin");
            pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            pb.environment().put("PREFIX", prefix);
            pb.environment().put("TMPDIR", prefix + "/tmp");
            pb.environment().put("LD_LIBRARY_PATH", prefix + "/lib");
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            process = p;
            Thread t = new Thread(() -> read(p), "tunnel-reader");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            error = String.valueOf(e.getMessage());
        }
    }

    private static void read(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = URL.matcher(line);
                if (m.find() && !line.contains("api.trycloudflare")) url = m.group();
                if (line.contains("Registered tunnel connection")) connected = true;
            }
        } catch (IOException ignored) {
        }
        connected = false;
    }

    public static synchronized void stop() {
        if (process != null) process.destroy();
        process = null; connected = false; url = "";
    }
}
