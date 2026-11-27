package com.termux.app.agent

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.agentdroid.agent.Triggers

/** Feeds posted notifications to the user's rules (Settings > Notification access must be granted).*/
class AgentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null || getPackageName().equals(sbn.getPackageName())) {
                return
            }
            val n: Notification? = sbn.getNotification()
            if (n == null) {
                return
            }
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY) != 0) {
                return
            }
            val rules: MutableList<Triggers.Rule> = TriggerStore(this).all()
            if (rules.isEmpty()) {
                return
            }
            val title: CharSequence? = if (n.extras == null) null else n.extras.getCharSequence(Notification.EXTRA_TITLE)
            val text: CharSequence? = if (n.extras == null) null else n.extras.getCharSequence(Notification.EXTRA_TEXT)
            val e = Triggers.Event(sbn.getPackageName(), label(sbn.getPackageName()), if (title == null) "" else title.toString(), if (text == null) "" else text.toString())
            val now: Long = System.currentTimeMillis()
            for (r in rules) {
                if (Triggers.matches(r, e) && COOLDOWN.allow(r.id, now, r.cooldownSec)) {
                    TriggerRunner.fire(this, r, e)
                }
            }
        } catch (ignored: RuntimeException) {
        }
    }

    private fun label(pkg: String): String {
        try {
            val pm: PackageManager = getPackageManager()
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            return pkg
        }
    }

    companion object {
        private val COOLDOWN: Triggers.Cooldown = Triggers.Cooldown()
    }
}
