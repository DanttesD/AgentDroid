package com.termux.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/** Starts the MCP server (and the tunnel) after the phone boots or the app is updated, if the user asked for it.*/
open class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val a: String? = if (intent == null) null else intent.getAction()
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return
        }
        if (!autoServer(context)) {
            return
        }
        try {
            McpService.start(context, autoTunnel(context))
        } catch (ignored: RuntimeException) {
        }
    }

    companion object {
        private const val PREFS: String = "agentdroid_boot"

        @JvmStatic
        private fun prefs(c: Context): SharedPreferences {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        @JvmStatic
        fun autoServer(c: Context): Boolean {
            return prefs(c).getBoolean("server", false)
        }

        @JvmStatic
        fun autoTunnel(c: Context): Boolean {
            return prefs(c).getBoolean("tunnel", false)
        }

        /** 0 = off, 1 = server, 2 = server + tunnel.*/
        @JvmStatic
        fun mode(c: Context): Int {
            return if (autoTunnel(c)) 2 else if (autoServer(c)) 1 else 0
        }

        @JvmStatic
        fun setMode(c: Context, m: Int) {
            prefs(c).edit().putBoolean("server", m >= 1).putBoolean("tunnel", m >= 2).apply()
        }
    }
}
