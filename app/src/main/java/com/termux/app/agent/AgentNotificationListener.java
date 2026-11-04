package com.termux.app.agent;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.agentdroid.agent.Triggers;

import java.util.List;

/** Feeds posted notifications to the user's rules (Settings > Notification access must be granted). */
public final class AgentNotificationListener extends NotificationListenerService {
    private static final Triggers.Cooldown COOLDOWN = new Triggers.Cooldown();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
            Notification n = sbn.getNotification();
            if (n == null) return;
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
            List<Triggers.Rule> rules = new TriggerStore(this).all();
            if (rules.isEmpty()) return;
            CharSequence title = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TEXT);
            Triggers.Event e = new Triggers.Event(sbn.getPackageName(), label(sbn.getPackageName()),
                    title == null ? "" : title.toString(), text == null ? "" : text.toString());
            long now = System.currentTimeMillis();
            for (Triggers.Rule r : rules) {
                if (Triggers.matches(r, e) && COOLDOWN.allow(r.id, now, r.cooldownSec)) TriggerRunner.fire(this, r, e);
            }
        } catch (RuntimeException ignored) { }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }
}
