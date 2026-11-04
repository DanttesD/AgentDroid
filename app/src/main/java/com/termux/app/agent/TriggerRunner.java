package com.termux.app.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.agentdroid.agent.AgentLoop;
import com.agentdroid.agent.StreamListener;
import com.agentdroid.agent.Triggers;
import com.termux.R;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the agent for one fired rule and tells the user the outcome. One run at a time. */
public final class TriggerRunner {
    private static final String CHANNEL = "agentdroid_triggers";
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private TriggerRunner() {}

    /** Asks first (notification with a "Run" button) in confirm mode, runs at once in auto mode. */
    public static void fire(Context context, Triggers.Rule rule, Triggers.Event e) {
        Context app = context.getApplicationContext();
        if (ActionGuard.isPaused()) return;
        if (rule.mode == Triggers.AUTO) run(app, rule, e);
        else if (rule.mode == Triggers.CONFIRM) offer(app, rule, e);
    }

    private static NotificationManager nm(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.trig_title), NotificationManager.IMPORTANCE_DEFAULT));
        }
        return nm;
    }

    private static Notification.Builder builder(Context c) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
    }

    private static void offer(Context app, Triggers.Rule rule, Triggers.Event e) {
        NotificationManager nm = nm(app);
        if (nm == null) return;
        Intent run = new Intent(app, TriggerActionReceiver.class).setAction(TriggerActionReceiver.ACTION_RUN)
                .putExtra("rule", rule.id).putExtra("pkg", e.pkg).putExtra("app", e.app).putExtra("title", e.title).putExtra("text", e.text);
        int code = (int) (System.currentTimeMillis() % 1_000_000);
        PendingIntent pi = PendingIntent.getBroadcast(app, code, run, PendingIntent.FLAG_UPDATE_CURRENT);
        String body = e.app + ": " + e.title + (e.text.isEmpty() ? "" : " — " + e.text);
        Notification.Builder nb = builder(app).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.trig_offer, rule.name.isEmpty() ? rule.goal : rule.name))
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, app.getString(R.string.trig_run), pi).build());
        nm.notify(9000 + code % 500, nb.build());
    }

    public static void run(final Context app, final Triggers.Rule rule, final Triggers.Event e) {
        AgentConfig cfg = new AgentConfig(app);
        if (cfg.apiKey().isEmpty() && !cfg.provider().equals("custom")) return;
        if (!cfg.baseUrlValid()) return;
        if (!BUSY.compareAndSet(false, true)) return;
        new Thread(() -> {
            final StringBuilder answer = new StringBuilder();
            String error = null;
            try {
                AgentLoop loop = PhoneAgent.createDefault(app, PhoneAgent.approver(app));
                if (loop == null) { error = app.getString(R.string.assistant_no_backend); }
                else {
                    loop.send(Triggers.prompt(rule, e), new StreamListener() {
                        @Override public void onText(String delta) { answer.append(delta); }
                        @Override public void onToolCall(com.agentdroid.agent.ToolCall call) { answer.setLength(0); }
                    });
                }
            } catch (Exception ex) {
                error = ex.getMessage();
            } finally {
                BUSY.set(false);
            }
            NotificationManager nm = nm(app);
            if (nm == null) return;
            String text = error != null ? "⚠ " + error : answer.toString().trim();
            if (text.isEmpty()) text = "✔";
            Notification.Builder nb = builder(app).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(app.getString(R.string.trig_done, rule.name.isEmpty() ? rule.goal : rule.name))
                    .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true);
            nm.notify(9600 + (int) (System.currentTimeMillis() % 300), nb.build());
        }, "trigger-run").start();
    }
}
