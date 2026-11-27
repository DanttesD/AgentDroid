package com.termux.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agentdroid.agent.AgentLoop
import com.agentdroid.agent.StreamListener
import com.agentdroid.agent.ToolCall
import com.agentdroid.agent.Triggers
import com.termux.R
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the agent for one fired rule and tells the user the outcome. One run at a time.*/
object TriggerRunner {
    private const val CHANNEL: String = "agentdroid_triggers"
    private val BUSY: AtomicBoolean = AtomicBoolean(false)

    /** Asks first (notification with a "Run" button) in confirm mode, runs at once in auto mode.*/
    @JvmStatic
    fun fire(context: Context, rule: Triggers.Rule, e: Triggers.Event) {
        val app: Context = context.getApplicationContext()
        if (ActionGuard.isPaused()) {
            return
        }
        if (rule.mode == Triggers.AUTO) {
            run(app, rule, e)
        } else if (rule.mode == Triggers.CONFIRM) {
            offer(app, rule, e)
        }
    }

    @JvmStatic
    private fun nm(c: Context): NotificationManager? {
        val nm: NotificationManager? = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.trig_title), NotificationManager.IMPORTANCE_DEFAULT))
        }
        return nm
    }

    @JvmStatic
    private fun builder(c: Context): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
    }

    @JvmStatic
    private fun offer(app: Context, rule: Triggers.Rule, e: Triggers.Event) {
        val nm: NotificationManager? = nm(app)
        if (nm == null) {
            return
        }
        val run: Intent = Intent(app, TriggerActionReceiver::class.java).setAction(TriggerActionReceiver.ACTION_RUN).putExtra("rule", rule.id).putExtra("pkg", e.pkg).putExtra("app", e.app).putExtra("title", e.title).putExtra("text", e.text)
        val code: Int = (System.currentTimeMillis() % 1_000_000).toInt()
        val pi: PendingIntent = PendingIntent.getBroadcast(app, code, run, PendingIntent.FLAG_UPDATE_CURRENT)
        val body: String = e.app + ": " + e.title + (if (e.text.isEmpty()) "" else " — " + e.text)
        val nb: Notification.Builder = builder(app).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(app.getString(R.string.trig_offer, if (rule.name.isEmpty()) rule.goal else rule.name)).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true).addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, app.getString(R.string.trig_run), pi).build())
        nm.notify(9000 + code % 500, nb.build())
    }

    @JvmStatic
    fun run(app: Context, rule: Triggers.Rule, e: Triggers.Event) {
        val cfg = AgentConfig(app)
        if (cfg.apiKey().isEmpty() && !cfg.provider()!!.equals("custom")) {
            return
        }
        if (!cfg.baseUrlValid()) {
            return
        }
        if (!BUSY.compareAndSet(false, true)) {
            return
        }
        Thread(Runnable {
            val answer = StringBuilder()
            var error: String? = null
            try {
                val loop: AgentLoop? = PhoneAgent.createDefault(app, PhoneAgent.approver(app))
                if (loop == null) {
                    error = app.getString(R.string.assistant_no_backend)
                } else {
                    loop.send(Triggers.prompt(rule, e), object : StreamListener() {
                        override fun onText(delta: String) {
                            answer.append(delta)
                        }

                        override fun onToolCall(call: ToolCall) {
                            answer.setLength(0)
                        }
                    })
                }
            } catch (ex: Exception) {
                error = ex.message
            } finally {
                BUSY.set(false)
            }
            val nm: NotificationManager? = nm(app)
            if (nm == null) {
                return@Runnable
            }
            var text: String = if (error != null) "⚠ " + error else (answer.toString() as java.lang.String).trim()
            if (text.isEmpty()) {
                text = "✔"
            }
            val nb: Notification.Builder = builder(app).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(app.getString(R.string.trig_done, if (rule.name.isEmpty()) rule.goal else rule.name)).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            nm.notify(9600 + (System.currentTimeMillis() % 300).toInt(), nb.build())
        }, "trigger-run").start()
    }
}
