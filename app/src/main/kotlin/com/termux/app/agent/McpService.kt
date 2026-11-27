package com.termux.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.termux.R

/** Keeps the MCP server alive while the app is in the background.*/
open class McpService : Service() {
    // Keeps the CPU awake while the phone is reachable through the tunnel, so the connection survives a switched-off screen.
    private var wake: PowerManager.WakeLock? = null

    private fun syncWake() {
        val need: Boolean = TunnelManager.isRunning()
        try {
            if (need && wake == null) {
                val pm: PowerManager? = getSystemService(Context.POWER_SERVICE) as PowerManager?
                wake = pm!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentdroid:mcp")
                wake!!.setReferenceCounted(false)
                wake!!.acquire()
            } else if (!need && wake != null) {
                if (wake!!.isHeld()) {
                    wake!!.release()
                }
                wake = null
            }
        } catch (ignored: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            McpHost.stop()
            TunnelManager.stop()
            syncWake()
            stopForeground(true)
            stopSelf()
            return Service.START_NOT_STICKY
        }
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            ActionGuard.setPaused(!ActionGuard.isPaused())
        }
        postNotification()
        try {
            McpHost.start(this)
            if (intent != null && intent.getBooleanExtra(EXTRA_TUNNEL, false) && !TunnelManager.isRunning()) {
                TunnelManager.start(this, McpHost.port(this))
            }
            syncWake()
        } catch (e: Exception) {
            stopForeground(true)
            stopSelf()
        }
        return Service.START_STICKY
    }

    private fun postNotification() {
        val nm: NotificationManager? = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.mcp_title), NotificationManager.IMPORTANCE_LOW))
        }
        val open: PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, McpActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause: PendingIntent = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused: Boolean = ActionGuard.isPaused()
        val nb: Notification.Builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        nb.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(getString(R.string.mcp_title)).setContentText(getString(if (paused) R.string.mcp_paused_note else R.string.mcp_notification)).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(if (paused) R.string.mcp_resume else R.string.mcp_stop_now), pause).build())
        startForeground(7002, nb.build())
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        McpHost.stop()
        TunnelManager.stop()
        syncWake()
        super.onDestroy()
    }

    companion object {

        const val ACTION_STOP: String = "com.agentdroid.MCP_STOP"
        const val ACTION_PAUSE: String = "com.agentdroid.MCP_PAUSE"
        private const val EXTRA_TUNNEL: String = "tunnel"
        private const val CHANNEL: String = "agentdroid_mcp"

        @JvmStatic
        fun start(c: Context) {
            start(c, false)
        }

        @JvmStatic
        fun start(c: Context, withTunnel: Boolean) {
            val i: Intent = Intent(c, McpService::class.java).putExtra(EXTRA_TUNNEL, withTunnel)
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i)
            } else {
                c.startService(i)
            }
        }

        @JvmStatic
        fun stop(c: Context) {
            c.startService(Intent(c, McpService::class.java).setAction(ACTION_STOP))
        }
    }
}
