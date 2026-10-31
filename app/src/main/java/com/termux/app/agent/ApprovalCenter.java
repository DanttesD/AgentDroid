package com.termux.app.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.termux.R;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks the user to approve an action. Shows a dialog on the visible AgentDroid screen; when the app
 * is in the background, posts a notification and waits for the user to open the app.
 */
public final class ApprovalCenter {
    private static final String CHANNEL = "agentdroid_approvals";
    private static volatile WeakReference<Activity> visible = new WeakReference<>(null);

    private ApprovalCenter() {}

    public static void onResume(Activity a) { visible = new WeakReference<>(a); }

    public static void onPause(Activity a) { if (visible.get() == a) visible = new WeakReference<>(null); }

    /** Blocks the calling (non-UI) thread until the user answers; false on denial or timeout. */
    public static boolean ask(Context appContext, String description) {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
        boolean notified = false;
        while (System.currentTimeMillis() < deadline) {
            final Activity a = visible.get();
            if (a != null && !a.isFinishing()) {
                cancelNotification(appContext);
                return showDialog(a, description);
            }
            if (!notified) { notify(appContext, description); notified = true; }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        cancelNotification(appContext);
        return false;
    }

    private static boolean showDialog(final Activity a, final String description) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    new AlertDialog.Builder(a).setTitle(R.string.agent_allow_title).setMessage(description).setCancelable(false)
                            .setPositiveButton(R.string.agent_allow, (d, w) -> { ok.set(true); latch.countDown(); })
                            .setNegativeButton(R.string.agent_deny, (d, w) -> latch.countDown()).show();
                } catch (RuntimeException e) {
                    latch.countDown();
                }
            }
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok.get();
    }

    private static void notify(Context c, String description) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.agent_allow_title), NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(c, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        nb.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(c.getString(R.string.agent_allow_title))
                .setContentText(description).setStyle(new Notification.BigTextStyle().bigText(description))
                .setContentIntent(pi).setAutoCancel(true);
        nm.notify(7001, nb.build());
    }

    private static void cancelNotification(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(7001);
    }
}
