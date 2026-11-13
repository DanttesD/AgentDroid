package com.termux.app.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Starts the MCP server (and the tunnel) after the phone boots or the app is updated, if the user asked for it. */
public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "agentdroid_boot";

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static boolean autoServer(Context c) { return prefs(c).getBoolean("server", false); }

    public static boolean autoTunnel(Context c) { return prefs(c).getBoolean("tunnel", false); }

    /** 0 = off, 1 = server, 2 = server + tunnel. */
    public static int mode(Context c) { return autoTunnel(c) ? 2 : autoServer(c) ? 1 : 0; }

    public static void setMode(Context c, int m) { prefs(c).edit().putBoolean("server", m >= 1).putBoolean("tunnel", m >= 2).apply(); }

    @Override public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!autoServer(context)) return;
        try { McpService.start(context, autoTunnel(context)); } catch (RuntimeException ignored) { }
    }
}
