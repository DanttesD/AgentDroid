package com.termux.app.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.agentdroid.agent.Guard;
import com.agentdroid.agent.ScreenBridge;
import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ExecRequest;
import com.agentdroid.exec.ExecResult;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Emergency stop, apps the agent must not touch, and a log of what it did. */
public final class ActionGuard implements Guard {
    private static final String PREFS = "agentdroid_guard";
    private static final String DEFAULT_BLOCKED = "ru.sberbankmobile\ncom.idamob.tinkoff.android\nru.vtb24.mobilebanking.android\n"
            + "ru.alfabank.mobile.android\ncom.google.android.apps.walletnfcrel\ncom.paypal.android.p2pmobile";
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "tap", "swipe", "type_text", "key", "open_app", "click", "long_click", "set_text", "scroll", "global_action",
            "intent", "toggle", "settings", "app_install", "app_uninstall"));

    private static volatile boolean paused;

    private final Context app;
    private final ExecBackend backend;

    public ActionGuard(Context c, ExecBackend backend) {
        this.app = c.getApplicationContext();
        this.backend = backend;
    }

    public static boolean isPaused() { return paused; }

    public static void setPaused(boolean p) { paused = p; }

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** One package name per line. */
    public static String blocked(Context c) { return prefs(c).getString("blocked", DEFAULT_BLOCKED); }

    public static void setBlocked(Context c, String lines) { prefs(c).edit().putString("blocked", lines).apply(); }

    private static File log(Context c) { return new File(c.getApplicationContext().getFilesDir(), "agent-actions.log"); }

    public static String readLog(Context c) {
        try {
            File f = log(c);
            if (!f.exists()) return "";
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            int from = Math.max(0, all.length - 20_000);
            return new String(all, from, all.length - from, "UTF-8");
        } catch (Exception e) { return ""; }
    }

    public static void clearLog(Context c) { log(c).delete(); }

    @Override public void before(String tool, Map<String, Object> args, String description) {
        if (paused) throw new SecurityException("the user pressed the emergency stop; ask them to resume the agent");
        if (!ACTIONS.contains(tool)) return;
        Set<String> blocked = new HashSet<>();
        for (String l : blocked(app).split("\n")) if (!l.trim().isEmpty()) blocked.add(l.trim());
        if (blocked.isEmpty()) return;
        Object pk = args.get("package");
        if (pk instanceof String && blocked.contains(pk)) throw new SecurityException("app " + pk + " is on the user's blocked list");
        String fg = foreground();
        if (blocked.contains(fg)) throw new SecurityException("the app in the foreground (" + fg + ") is on the user's blocked list; "
                + "leave it first or ask the user to change the list");
    }

    @Override public void after(String tool, Map<String, Object> args, String result, boolean error) {
        try {
            File f = log(app);
            if (f.length() > 512 * 1024) f.delete();
            String a = String.valueOf(args);
            String r = result == null ? "" : result.replace('\n', ' ');
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + tool + " "
                    + (a.length() > 160 ? a.substring(0, 160) + "…" : a) + (error ? " ERROR " : " -> ")
                    + (r.length() > 100 ? r.substring(0, 100) + "…" : r) + "\n";
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(line.getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    private String foreground() {
        try {
            ScreenBridge b = ScreenBridge.Holder.current;
            if (b != null) { String p = b.foregroundPackage(); if (!p.isEmpty()) return p; }
            ExecResult r = backend.exec(ExecRequest.builder("dumpsys window | grep mCurrentFocus | head -1").timeoutMs(8000L).build());
            String s = r.stdout();
            int slash = s.indexOf('/');
            if (slash > 0) {
                int st = s.lastIndexOf(' ', slash) + 1;
                return s.substring(st, slash);
            }
        } catch (Exception ignored) { }
        return "";
    }
}
