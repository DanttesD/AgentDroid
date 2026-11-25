package com.termux.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.termux.R
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks the user to approve an action. Shows a dialog on the visible AgentDroid screen; when the app
 * is in the background, posts a notification and waits for the user to open the app.
 **/
object ApprovalCenter {

    /** Asks inside the overlay assistant when no AgentDroid screen is open (the user is in another app).*/
    fun interface Presenter {
        fun ask(description: String, force: Boolean): Boolean
    }

    /**
     * A screen that can show the question inside its own content (the chat feed). Returns TRUE or FALSE once the user answered,
     * or null when it cannot (web mode, or the user left the screen meanwhile).
     **/
    fun interface Inline {
        fun askInline(description: String, force: Boolean): Boolean?
    }

    private const val CHANNEL: String = "agentdroid_approvals"
    @Volatile private var visible: WeakReference<Activity?> = WeakReference<Activity?>(null)

    private const val PREFS: String = "agentdroid_approvals"
    @JvmField val HOUR_MS: Long = TimeUnit.HOURS.toMillis(1L)

    @Volatile private var overlay: Presenter? = null

    @JvmStatic
    fun setOverlay(p: Presenter?) {
        overlay = p
    }

    @JvmStatic
    private fun prefs(c: Context): SharedPreferences {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 0 = ask every time, 1 = allow for one hour, 2 = always allow.*/
    @JvmStatic
    fun mode(c: Context): Int {
        val p: SharedPreferences = prefs(c)
        if (p.getBoolean("always", false)) {
            return 2
        }
        return if (System.currentTimeMillis() < p.getLong("until", 0L)) 1 else 0
    }

    @JvmStatic
    fun setMode(c: Context, mode: Int) {
        prefs(c).edit().putBoolean("always", mode == 2).putLong("until", if (mode == 1) System.currentTimeMillis() + HOUR_MS else 0L).apply()
    }

    @JvmStatic
    fun onResume(a: Activity) {
        visible = WeakReference<Activity?>(a)
    }

    @JvmStatic
    fun onPause(a: Activity) {
        if (visible.get() === a) {
            visible = WeakReference<Activity?>(null)
        }
    }

    /** Blocks the calling (non-UI) thread until the user answers; false on denial or timeout.*/
    @JvmStatic
    fun ask(appContext: Context, description: String): Boolean {
        return ask(appContext, description, false)
    }

    /** `force`: ask even when the user allowed everything (sending, paying, deleting).*/
    @JvmStatic
    fun ask(appContext: Context, description: String, force: Boolean): Boolean {
        if (!force && mode(appContext) != 0) {
            return true
        }
        val deadline: Long = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3L)
        var notified = false
        while (System.currentTimeMillis() < deadline) {
            val a: Activity? = visible.get()
            if (a != null && !a.isFinishing()) {
                cancelNotification(appContext)
                if (a is Inline) {
                    val answer: Boolean? = (a as Inline).askInline(description, force)
                    if (answer != null) {
                        return answer
                    }
                    if (visible.get() !== a) {
                        continue
                    } // the user left that screen: ask again where he is now
                }
                return showDialog(a, description, force)
            }
            val p: Presenter? = overlay
            if (p != null) {
                cancelNotification(appContext)
                return p.ask(description, force)
            }
            if (!notified) {
                notify(appContext, description)
                notified = true
            }
            try {
                Thread.sleep(500L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        cancelNotification(appContext)
        return false
    }

    @JvmStatic
    private fun showDialog(a: Activity, description: String, force: Boolean): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        a.runOnUiThread(object : Runnable {
            override fun run() {
                try {
                    val db: AlertDialog.Builder = ThemedDialog(a).setTitle(R.string.agent_allow_title).setMessage(description).setCancelable(false).setPositiveButton(R.string.agent_allow, DialogInterface.OnClickListener { d, w ->
                        ok.set(true)
                        latch.countDown()
                    }).setNegativeButton(R.string.agent_deny, DialogInterface.OnClickListener { d, w -> latch.countDown() })
                    if (!force) {
                        db.setNeutralButton(R.string.agent_allow_hour, DialogInterface.OnClickListener { d, w ->
                            setMode(a, 1)
                            ok.set(true)
                            latch.countDown()
                        })
                    }
                    db.show()
                } catch (e: RuntimeException) {
                    latch.countDown()
                }
            }
        })
        try {
            latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return ok.get()
    }

    @JvmStatic
    private fun notify(c: Context, description: String) {
        val nm: NotificationManager? = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (nm == null) {
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.agent_allow_title), NotificationManager.IMPORTANCE_HIGH))
        }
        val open: Intent = Intent(c, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi: PendingIntent = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_UPDATE_CURRENT)
        val nb: Notification.Builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
        nb.setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(c.getString(R.string.agent_allow_title)).setContentText(description).setStyle(Notification.BigTextStyle().bigText(description)).setContentIntent(pi).setAutoCancel(true)
        nm.notify(7001, nb.build())
    }

    @JvmStatic
    private fun cancelNotification(c: Context) {
        val nm: NotificationManager? = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (nm != null) {
            nm.cancel(7001)
        }
    }
}
