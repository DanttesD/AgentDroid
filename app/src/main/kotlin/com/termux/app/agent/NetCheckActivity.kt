package com.termux.app.agent

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader
import com.agentdroid.agent.CssTheme
import com.agentdroid.agent.NetScore
import com.agentdroid.agent.ThemeColors
import com.termux.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/**
 * Network trust check: a page that is bundled in the app (assets/trustcheck) and runs inside the built-in browser,
 * so every request leaves through the same exit IP (and proxy) that AI sites see.
 **/
class NetCheckActivity : Activity() {
    private var themeSig: String = ""
    private var helper: BrowserHelper? = null
    private var web: WebView? = null
    private var th: ThemeColors? = null
    private var themedCss: String? = null
    private var scoreView: TextView? = null
    private var tierView: TextView? = null
    private var subView: TextView? = null

    private fun css(): String? {
        if (themedCss != null) {
            return themedCss
        }
        try {
            (getAssets().open("trustcheck/styles.css")).use { `in` ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int = 0
                while (true) {
                    n = `in`.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                val base: String = CssTheme.remap(out.toString("UTF-8"), AppTheme.hue(this), th!!.dark)
                val s1: String = hex(th!!.s1)
                val line: String = hex(th!!.line)
                themedCss = base + (if (th!!.dark) ":root{color-scheme:dark}" else "") + ".page-heading>div:first-child{display:none}:root,body{background:" + hex(th!!.bg) + "!important;color:" + hex(th!!.on) + "}.workspace{background:" + hex(th!!.bg) + "!important;border-color:" + line + "!important}.service-card,.panel,.history-wrap,.score-explanation,.advice-card,dialog{background:" + s1 + "!important;border-color:" + line + "!important}"
            }
        } catch (e: IOException) {
            themedCss = ""
        }
        return themedCss
    }

    /** Maps /x to assets/trustcheck/x, so the page's absolute paths keep working.*/
    private inner class Assets : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val name: String = if (path.isEmpty()) "index.html" else path
            if (name.contains("..") || name.startsWith("api/")) {
                return null
            }
            if (name.equals("styles.css")) {
                try {
                    return WebResourceResponse("text/css", "UTF-8", ByteArrayInputStream((css()!! as java.lang.String).getBytes("UTF-8")))
                } catch (e: UnsupportedEncodingException) {
                    return null
                }
            }
            try {
                val `in`: InputStream = getAssets().open("trustcheck/" + name)
                return WebResourceResponse(mime(name), "UTF-8", `in`)
            } catch (e: IOException) {
                return null
            }
        }
    }

    /** Lets the page read dnsleak.dev natively when the service does not allow cross-origin reads.*/
    inner class Bridge {
        @JavascriptInterface
        fun report(json: String) {
            try {
                val o = JSONObject(json)
                val score: Int = NetScore.total(if (o.isNull("score")) -1 else o.optInt("score", -1), o.optString("cc", ""))
                o.put("score", score)
                val sp: SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val per = JSONObject(sp.getString("per", "{}"))
                per.put(if (o.optString("service", "").isEmpty()) "any" else o.optString("service"), o)
                var worst: JSONObject? = null
                val names = JSONObject()
                val keys: MutableIterator<String> = per.keys()
                while (keys.hasNext()) {
                    val k: String = keys.next()
                    val v: JSONObject = per.getJSONObject(k)
                    if (!k.equals("any")) {
                        names.put(k, v.optInt("score", -1))
                    }
                    if (worst == null || v.optInt("score", -1) < worst.optInt("score", -1)) {
                        worst = v
                    }
                }
                val all = JSONObject(worst!!.toString())
                all.put("services", names)
                all.put("ts", System.currentTimeMillis())
                sp.edit().putString("per", per.toString()).putString("last", all.toString()).apply()
                runOnUiThread(Runnable { showResult(all) })
            } catch (ignored: Exception) {
            }
        }

        @JavascriptInterface
        fun get(url: String): String {
            try {
                if (!url.startsWith("https://dnsleak.dev/")) {
                    return ""
                }
                val c: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
                c.setConnectTimeout(6000)
                c.setReadTimeout(7000)
                c.setRequestProperty("Accept", "application/json")
                if (c.getResponseCode() != 200) {
                    return ""
                }
                (c.getInputStream()).use { `in` ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var n: Int = 0
                    while (`in`.read(buf).also { n = it } > 0 && out.size() < 262144) {
                        out.write(buf, 0, n)
                    }
                    return out.toString("UTF-8")
                }
            } catch (e: IOException) {
                return ""
            } catch (e: RuntimeException) {
                return ""
            }
        }
    }

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        th = AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        val ui = Ui(this)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(th!!.bg)
        val bar = LinearLayout(this)
        bar.setGravity(Gravity.CENTER_VERTICAL)
        val p: Int = (12 * getResources().getDisplayMetrics().density).toInt()
        bar.setPadding(p, p, p, p / 2)
        val title = TextView(this)
        title.setText(R.string.netcheck_title)
        title.setTextColor(th!!.on)
        title.setTextSize(18.0f)
        title.setSingleLine(true)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ui.button(getString(R.string.netcheck_proxy_short), Ui.Kind.TONAL, View.OnClickListener { v -> BrowserProxy.showDialog(this, Runnable(this::load)) }))
        bar.addView(ui.button(getString(R.string.netcheck_again), Ui.Kind.FILLED, View.OnClickListener { v -> load() }))
        root.addView(bar)
        val card: LinearLayout = ui.card()
        val line = LinearLayout(this)
        line.setGravity(Gravity.CENTER_VERTICAL)
        scoreView = ui.text("…", 44.0f, th!!.on2, true)
        line.addView(scoreView)
        val info: LinearLayout = ui.column()
        tierView = ui.text(getString(R.string.netcheck_checking), 16.0f, th!!.on, true)
        subView = ui.text("", 13.0f, th!!.on2, false)
        info.addView(tierView)
        info.addView(subView, ui.gap(2))
        val ip = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ip.leftMargin = ui.dp(16.0f)
        line.addView(info, ip)
        card.addView(line)
        val cp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cp.setMargins(p, 0, p, p / 2)
        root.addView(card, cp)
        val prev: JSONObject? = last(this)
        if (prev != null) {
            showResult(prev)
        }
        val host = FrameLayout(this)
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        helper = BrowserHelper(this, host, null)
        helper!!.setAssetLoader(WebViewAssetLoader.Builder().addPathHandler("/", Assets()).build())
        web = helper!!.create()
        web!!.setBackgroundColor(0xFF000000.toInt())
        web!!.addJavascriptInterface(Bridge(), "AgentNative")
        host.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        load()
    }

    private fun showResult(o: JSONObject) {
        val score: Int = o.optInt("score", -1)
        scoreView!!.setText(if (score < 0) "—" else java.lang.String.valueOf(score))
        scoreView!!.setTextColor(tierColor(th, score))
        tierView!!.setText(tierText(this, score))
        val cc: String = o.optString("cc", "")
        val country: String = if (cc.length == 2) Locale("", cc).getDisplayCountry(Locale("ru")) else ""
        val sub = StringBuilder()
        if (!country.isEmpty()) {
            sub.append(country)
        }
        val org: String = o.optString("org", "")
        if (!org.isEmpty()) {
            sub.append(if (sub.length > 0) " · " else "").append(org)
        }
        sub.append(if (sub.length > 0) " · " else "").append(getString(if (o.optBoolean("hosting")) R.string.netcheck_kind_hosting else R.string.netcheck_kind_plain))
        val sv: JSONObject? = o.optJSONObject("services")
        if (sv != null && sv.length() > 1) {
            val t = StringBuilder()
            if (sv.has("chatgpt")) {
                t.append("ChatGPT ").append(sv.optInt("chatgpt"))
            }
            if (sv.has("claude")) {
                t.append(if (t.length > 0) "  ·  " else "").append("Claude ").append(sv.optInt("claude"))
            }
            sub.append("\n").append(t)
        }
        subView!!.setText(sub)
    }

    private fun load() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("per").apply()
        scoreView!!.setText("…")
        scoreView!!.setTextColor(th!!.on2)
        tierView!!.setText(R.string.netcheck_checking)
        BrowserProxy.apply(this)
        web!!.loadUrl(START)
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (helper != null && helper!!.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onBackPressed() {
        if (helper != null && helper!!.hasPopup()) {
            helper!!.closePopup()
            return
        }
        super.onBackPressed()
    }

    protected override fun onDestroy() {
        if (web != null) {
            web!!.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val START: String = "https://appassets.androidplatform.net/index.html"

        private const val PREFS: String = "agentdroid_netcheck"

        /** Last result for other screens: {score, cc, org, hosting, ts} or null when never checked.*/
        @JvmStatic
        fun last(c: Context): JSONObject? {
            try {
                val raw: String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", "")
                return if (raw!!.isEmpty()) null else JSONObject(raw)
            } catch (e: Exception) {
                return null
            }
        }

        @JvmStatic
        fun tierText(c: Context, score: Int): String {
            val t: Int = NetScore.tier(score)
            return c.getString(if (t == 0) R.string.netcheck_tier_low else if (t == 1) R.string.netcheck_tier_mid else if (t == 2) R.string.netcheck_tier_high else R.string.netcheck_tier_unknown)
        }

        @JvmStatic
        fun tierColor(th: ThemeColors?, score: Int): Int {
            val t: Int = NetScore.tier(score)
            return if (t == 0) th!!.ok else if (t == 1) 0xFFE0A526.toInt() else if (t == 2) th!!.err else th!!.on2
        }

        @JvmStatic
        private fun hex(c: Int): String {
            return java.lang.String.format("#%06x", c and 0xFFFFFF)
        }

        @JvmStatic
        private fun mime(n: String): String {
            var n = n
            n = (n as java.lang.String).toLowerCase(Locale.ROOT)
            if (n.endsWith(".js")) {
                return "text/javascript"
            }
            if (n.endsWith(".css")) {
                return "text/css"
            }
            if (n.endsWith(".html")) {
                return "text/html"
            }
            if (n.endsWith(".svg")) {
                return "image/svg+xml"
            }
            if (n.endsWith(".woff2")) {
                return "font/woff2"
            }
            if (n.endsWith(".json")) {
                return "application/json"
            }
            return "application/octet-stream"
        }
    }
}
