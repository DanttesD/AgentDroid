package com.termux.app.agent

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.InputType
import android.webkit.HttpAuthHandler
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.agentdroid.agent.NetConfig
import com.termux.R
import java.util.concurrent.Executor

/**
 * Own proxy for the built-in browser only. The site's trust score is decided by the exit IP, so a user can route
 * just the browser through a residential/mobile proxy without touching the rest of the phone.
 **/
object BrowserProxy {
    private const val PREFS: String = "agentdroid_browser_proxy"

    @JvmStatic
    private fun prefs(c: Context): SharedPreferences {
        val p: SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("migrated")) {
            // one proxy used to serve both; keep it for the browser and for AI if that box was ticked
            val e: SharedPreferences.Editor = p.edit().putBoolean("migrated", true)
            val old: String? = p.getString("addr", "")
            if (!old!!.isEmpty()) {
                e.putString("b_addr", old).putString("b_user", p.getString("user", "")).putString("b_pass", p.getString("pass", ""))
                if (p.getBoolean("ai", true)) {
                    e.putString("a_addr", old).putString("a_user", p.getString("user", "")).putString("a_pass", p.getString("pass", ""))
                }
            }
            e.apply()
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        return p
    }

    @JvmStatic
    fun supported(): Boolean {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        } catch (t: Throwable) {
            return false
        }
    }

    /** "scheme://host:port" or "", normalised from what the user typed (host:port means http).*/
    @JvmStatic
    fun normalize(raw: String?): String {
        var s: String = if (raw == null) "" else (raw as java.lang.String).trim()
        if (s.isEmpty()) {
            return ""
        }
        if (!s.contains("://")) {
            s = "http://" + s
        }
        return s
    }

    /** The browser's proxy address.*/
    @JvmStatic
    fun address(c: Context): String? {
        return prefs(c).getString("b_addr", "")
    }

    @JvmStatic
    fun enabled(c: Context): Boolean {
        return !address(c)!!.isEmpty()
    }

    @JvmStatic
    fun appEnabled(c: Context): Boolean {
        return !prefs(c).getString("a_addr", "")!!.isEmpty()
    }

    /** True when any proxy is set, for the menu tick.*/
    @JvmStatic
    fun anyEnabled(c: Context): Boolean {
        return enabled(c) || appEnabled(c)
    }

    /** Points the app's own requests (AI models) at the app proxy, or direct when none is set.*/
    @JvmStatic
    fun syncAgent(c: Context) {
        val p: SharedPreferences = prefs(c)
        NetConfig.set(p.getString("a_addr", ""), p.getString("a_user", ""), p.getString("a_pass", ""))
    }

    /** Applies the browser proxy (or clears it) for every WebView of the process. Safe to call repeatedly.*/
    @JvmStatic
    fun apply(c: Context) {
        syncAgent(c)
        if (!supported()) {
            return
        }
        try {
            val addr: String? = address(c)
            val done = Runnable {  }
            if (addr!!.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(Executor(Runnable::run), done)
            } else {
                val cfg: ProxyConfig = ProxyConfig.Builder().addProxyRule(addr).build()
                ProxyController.getInstance().setProxyOverride(cfg, Executor(Runnable::run), done)
            }
        } catch (ignored: Throwable) {
            // an invalid address must never break the browser
        }
    }

    /** Answers the proxy's own 407 challenge with the browser proxy's login. Returns true when handled.*/
    @JvmStatic
    fun authenticate(c: Context, handler: HttpAuthHandler, isProxy: Boolean): Boolean {
        if (!isProxy) {
            return false
        }
        val p: SharedPreferences = prefs(c)
        val user: String? = p.getString("b_user", "")
        if (user!!.isEmpty()) {
            return false
        }
        handler.proceed(user, p.getString("b_pass", ""))
        return true
    }

    @JvmStatic
    fun showDialog(a: Activity, onSaved: Runnable?) {
        val box = LinearLayout(a)
        box.setOrientation(LinearLayout.VERTICAL)
        val p: Int = (16 * a.getResources().getDisplayMetrics().density).toInt()
        box.setPadding(p, p, p, 0)
        val sp: SharedPreferences = prefs(a)
        val b: Array<EditText> = section(a, box, R.string.browser_proxy_sec_browser, R.string.browser_proxy_sec_browser_hint, sp, "b_")
        val ap: Array<EditText> = section(a, box, R.string.browser_proxy_sec_app, R.string.browser_proxy_sec_app_hint, sp, "a_")
        val fmt = TextView(a)
        fmt.setText(R.string.browser_proxy_hint)
        fmt.setPadding(0, p / 2, 0, 0)
        box.addView(fmt)
        if (!supported()) {
            val warn = TextView(a)
            warn.setText(R.string.browser_proxy_unsupported)
            box.addView(warn)
        }
        val sv = ScrollView(a)
        sv.addView(box)
        ThemedDialog(a).setTitle(R.string.browser_proxy_title).setView(sv).setPositiveButton(R.string.browser_proxy_save, DialogInterface.OnClickListener { d, w ->
            prefs(a).edit().putString("b_addr", normalize(b[0].getText().toString())).putString("b_user", (b[1].getText().toString() as java.lang.String).trim()).putString("b_pass", b[2].getText().toString()).putString("a_addr", normalize(ap[0].getText().toString())).putString("a_user", (ap[1].getText().toString() as java.lang.String).trim()).putString("a_pass", ap[2].getText().toString()).apply()
            apply(a)
            Toast.makeText(a, if (anyEnabled(a)) R.string.browser_proxy_on else R.string.browser_proxy_off, Toast.LENGTH_SHORT).show()
            if (onSaved != null) {
                onSaved.run()
            }
        }).setNegativeButton(android.R.string.cancel, null).show()
    }

    @JvmStatic
    private fun section(a: Activity, box: LinearLayout, title: Int, hint: Int, sp: SharedPreferences, key: String): Array<EditText> {
        val t = TextView(a)
        t.setText(title)
        t.setTextSize(16.0f)
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, (10 * a.getResources().getDisplayMetrics().density).toInt(), 0, 0)
        box.addView(t)
        val h = TextView(a)
        h.setText(hint)
        h.setTextSize(13.0f)
        box.addView(h)
        val addr: EditText = field(a, a.getString(R.string.browser_proxy_addr), sp.getString(key + "addr", ""), false)
        val user: EditText = field(a, a.getString(R.string.browser_proxy_user), sp.getString(key + "user", ""), false)
        val pass: EditText = field(a, a.getString(R.string.browser_proxy_pass), sp.getString(key + "pass", ""), true)
        box.addView(addr)
        box.addView(user)
        box.addView(pass)
        return arrayOf<EditText>(addr, user, pass)
    }

    @JvmStatic
    private fun field(a: Activity, hint: String, value: String?, secret: Boolean): EditText {
        val e = EditText(a)
        e.setHint(hint)
        e.setText(value)
        e.setSingleLine(true)
        e.setInputType(if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        return e
    }
}
