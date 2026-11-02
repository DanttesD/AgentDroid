package com.termux.app.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.termux.R;

/** Keeps the MCP server alive while the app is in the background. */
public class McpService extends Service {
    public static final String ACTION_STOP = "com.agentdroid.MCP_STOP";
    public static final String ACTION_PAUSE = "com.agentdroid.MCP_PAUSE";
    private static final String EXTRA_TUNNEL = "tunnel";
    private static final String CHANNEL = "agentdroid_mcp";

    public static void start(Context c) { start(c, false); }

    public static void start(Context c, boolean withTunnel) {
        Intent i = new Intent(c, McpService.class).putExtra(EXTRA_TUNNEL, withTunnel);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) { c.startService(new Intent(c, McpService.class).setAction(ACTION_STOP)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            McpHost.stop();
            TunnelManager.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) ActionGuard.setPaused(!ActionGuard.isPaused());
        postNotification();
        try {
            McpHost.start(this);
            if (intent != null && intent.getBooleanExtra(EXTRA_TUNNEL, false) && !TunnelManager.isRunning()) {
                TunnelManager.start(this, McpHost.port(this));
            }
        } catch (Exception e) {
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    private void postNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.mcp_title), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, McpActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, McpService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean paused = ActionGuard.isPaused();
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(getString(R.string.mcp_title))
                .setContentText(getString(paused ? R.string.mcp_paused_note : R.string.mcp_notification)).setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(paused ? R.string.mcp_resume : R.string.mcp_stop_now), pause).build());
        startForeground(7002, nb.build());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() { McpHost.stop(); TunnelManager.stop(); super.onDestroy(); }
}
