package com.termux.app.agent;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a tunnel as a child process and reports its public address and state.
 * With an ngrok token and a (free, permanent) ngrok address configured it runs ngrok inside the Debian
 * environment (port 443, permanent address); otherwise a cloudflared quick tunnel (random address).
 */
public final class TunnelManager {
    private static final Pattern CF_URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final Pattern NGROK_URL = Pattern.compile("https://[a-zA-Z0-9.-]+\\.(ngrok-free\\.app|ngrok-free\\.dev|ngrok\\.app|ngrok\\.dev|ngrok\\.io)");
    private static final String PREFS = "agentdroid_tunnel";
    private static final String NGROK_BIN = "/opt/ngrok/ngrok";

    private static Process process;
    private static volatile String url = "";
    private static volatile boolean connected;
    private static volatile String error = "";
    private static volatile boolean ngrok;
    private static volatile boolean forceCloudflare;
    private static volatile boolean viaSu;
    private static volatile String note = "";

    private TunnelManager() {}

    public static synchronized boolean isRunning() { return process != null && process.isAlive(); }
    public static String url() { return url; }
    public static boolean isConnected() { return connected && isRunning(); }
    public static String error() { return error; }
    public static boolean usesNgrok() { return ngrok; }
    /** Non-empty when the permanent tunnel failed and the temporary one is used instead. */
    public static String note() { return note; }

    public static String ngrokDomain(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("domain", "");
    }

    public static boolean ngrokConfigured(Context c) { return !new SecretStore(c).get("ngrok_token").isEmpty(); }

    public static void saveNgrok(Context c, String token, String domain) {
        new SecretStore(c).put("ngrok_token", token.trim());
        String d = domain.trim().replace("https://", "").replace("http://", "");
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("domain", d).apply();
    }

    public static boolean ngrokInstalled() {
        return new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/rootfs" + NGROK_BIN).exists()
                || new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/debian" + NGROK_BIN).exists();
    }

    /** Terminal command that installs proot-distro, Debian, curl and ngrok. Safe to repeat. */
    public static String ngrokInstallScript() {
        String inner = "command -v curl >/dev/null 2>&1 || { apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates; }; "
                + "mkdir -p /opt/ngrok && cd /opt/ngrok && curl -fsSL -o n.tgz https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz "
                + "&& tar xzf n.tgz && rm -f n.tgz && ./ngrok version";
        return "{ command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro; } && "
                + "{ [ -d \"$PREFIX/var/lib/proot-distro/containers/debian\" ] || [ -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ] || proot-distro install debian; } && "
                + "proot-distro login debian -- bash -lc " + com.agentdroid.exec.ShellQuote.quote(inner);
    }

    /** Kills ngrok processes left over from an earlier app process; they would keep the permanent address busy. */
    private static void killStale() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        boolean killed = false;
        for (File f : procs) {
            String n = f.getName();
            if (!n.matches("\\d+")) continue;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(new File(f, "cmdline")), StandardCharsets.UTF_8))) {
                String cmd = r.readLine();
                if (cmd != null && cmd.contains(NGROK_BIN) && cmd.contains("http")) {
                    android.os.Process.killProcess(Integer.parseInt(n));
                    killed = true;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (killed) {
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public static synchronized void start(Context c, int port) {
        if (isRunning()) return;
        url = ""; connected = false; error = "";
        if (!forceCloudflare) note = "";
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String token = new SecretStore(c).get("ngrok_token");
        ngrok = !forceCloudflare && !token.isEmpty() && ngrokInstalled();
        List<String> cmd = new ArrayList<>();
        if (ngrok) {
            killStale();
            String domain = ngrokDomain(c);
            List<String> inner = new ArrayList<>();
            inner.add(prefix + "/bin/proot-distro"); inner.add("login"); inner.add("debian"); inner.add("--shared-tmp"); inner.add("--");
            inner.add("env"); inner.add("NGROK_AUTHTOKEN=" + token); inner.add(NGROK_BIN);
            inner.add("http"); inner.add("127.0.0.1:" + port); inner.add("--log=stdout"); inner.add("--log-format=logfmt");
            if (!domain.isEmpty()) inner.add("--url=" + domain);
            if (viaSu) {
                // Android hides network card addresses from normal apps; the same app user started through su sees them.
                StringBuilder sb = new StringBuilder("env -i PATH=" + prefix + "/bin HOME=" + home + " PREFIX=" + prefix
                        + " TERMUX__PREFIX=" + prefix + " TERMUX__HOME=" + home + " TMPDIR=" + prefix + "/tmp LD_LIBRARY_PATH=" + prefix + "/lib");
                for (String a : inner) sb.append(' ').append(com.agentdroid.exec.ShellQuote.quote(a));
                cmd.add("su"); cmd.add(String.valueOf(android.os.Process.myUid())); cmd.add("-c"); cmd.add(sb.toString());
            } else {
                cmd.addAll(inner);
            }
        } else {
            File bin = new File(prefix + "/bin/cloudflared");
            if (!bin.exists()) { error = "cloudflared"; return; }
            cmd.add(bin.getPath()); cmd.add("tunnel"); cmd.add("--no-autoupdate"); cmd.add("--url"); cmd.add("http://127.0.0.1:" + port);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PATH", prefix + "/bin");
            pb.environment().put("HOME", home);
            pb.environment().put("PREFIX", prefix);
            pb.environment().put("TERMUX__PREFIX", prefix);
            pb.environment().put("TERMUX__HOME", home);
            pb.environment().put("TMPDIR", prefix + "/tmp");
            pb.environment().put("LD_LIBRARY_PATH", prefix + "/lib");
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            process = p;
            final Context app = c.getApplicationContext();
            final boolean wasNgrok = ngrok;
            final boolean wasSu = viaSu;
            final int fport = port;
            Thread t = new Thread(() -> { read(p, wasNgrok); afterExit(app, fport, wasNgrok, wasSu); }, "tunnel-reader");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            error = String.valueOf(e.getMessage());
            if (ngrok) { // could not even start (for example no su): use the temporary tunnel
                note = error;
                forceCloudflare = true;
                try { start(c, port); } finally { forceCloudflare = false; }
            }
        }
    }

    private static void read(Process p, boolean isNgrok) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (isNgrok) {
                    Matcher m = NGROK_URL.matcher(line);
                    if (line.contains("started tunnel") && m.find()) { url = m.group(); connected = true; }
                    else if (line.contains("lvl=eror") || line.contains("lvl=crit")) {
                        int i = line.indexOf("err=");
                        error = i >= 0 ? line.substring(i + 4) : line;
                    }
                } else {
                    Matcher m = CF_URL.matcher(line);
                    if (m.find() && !line.contains("api.trycloudflare")) url = m.group();
                    if (line.contains("Registered tunnel connection")) connected = true;
                }
            }
        } catch (IOException ignored) {
        }
        connected = false;
    }

    /** ngrok ended without connecting: retry through su when the network card address was hidden, then fall back to Cloudflare. */
    private static void afterExit(Context c, int port, boolean wasNgrok, boolean wasSu) {
        if (!wasNgrok || forceCloudflare) return;
        boolean retrySu;
        synchronized (TunnelManager.class) {
            if (process == null) return; // stopped by the user
            retrySu = !wasSu && error.contains("hardware address");
            note = error;
            process = null;
            if (retrySu) { viaSu = true; } else { forceCloudflare = true; }
        }
        try {
            start(c, port);
        } finally {
            forceCloudflare = false;
            viaSu = false;
        }
    }

    public static synchronized void stop() {
        if (process != null) process.destroy();
        if (ngrok) killStale();
        process = null; connected = false; url = "";
    }
}
