package com.termux.app.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R
import org.json.JSONObject

/** Everything the agent needs to control the phone: readiness, screen control, assistant, rules, MCP, emergency stop.*/
class PhoneActivity : TabActivity() {
    protected override fun tab(): Int {
        return NavBar.PHONE
    }

    protected override fun titleRes(): Int {
        return R.string.nav_phone
    }

    private fun notificationsOn(): Boolean {
        val flat: String? = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners")
        return flat != null && flat.contains(getPackageName())
    }

    /** The MCP screen has two parts: the connector for outside agents and the safety settings of screen control.*/
    private fun openMcp(section: String) {
        startActivity(Intent(this, McpActivity::class.java).putExtra("section", section))
    }

    protected override fun fill(c: LinearLayout?) {
        val screen: Boolean = AgentAccessibilityService.isRunning()
        val overlay: Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val mic: Boolean = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif: Boolean = notificationsOn()
        val ok: BooleanArray = booleanArrayOf(screen, overlay, mic, notif)
        val names: Array<String> = arrayOf<String>(getString(R.string.phone_chk_screen), getString(R.string.phone_chk_overlay), getString(R.string.phone_chk_mic), getString(R.string.phone_chk_notif))
        var done = 0
        for (o in ok) {
            if (o) {
                done++
            }
        }

        val hero: LinearLayout = ui!!.column()
        hero.setBackground(ui!!.shape(ui!!.t.pc, 24.0f, 0, 0.0f))
        hero.setPadding(ui!!.dp(16.0f), ui!!.dp(16.0f), ui!!.dp(16.0f), ui!!.dp(16.0f))
        val top = LinearLayout(this)
        top.addView(ui!!.text(getString(if (done == 4) R.string.phone_ready else R.string.phone_not_ready), 18.0f, ui!!.t.onpc, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ui!!.text(getString(R.string.phone_count, done, 4), 13.0f, ui!!.t.onpc, true))
        hero.addView(top)
        // Every bar is a button: it opens the place where that item is switched on.
        val fix: Array<Runnable> = arrayOf<Runnable>(Runnable { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Runnable {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))
        }, Runnable {
            if (!mic) {
                requestPermissions(arrayOf<String>(Manifest.permission.RECORD_AUDIO), 7201)
            }
        }, Runnable { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) })
        val bars = LinearLayout(this)
        for (i in 0 until (4)) {
            val which: Int = i
            val col: LinearLayout = ui!!.column()
            col.setPadding(0, ui!!.dp(6.0f), 0, ui!!.dp(4.0f))
            col.setClickable(true)
            col.setContentDescription(names[i])
            col.setOnClickListener(View.OnClickListener { v -> fix[which].run() })
            val bar = View(this)
            bar.setBackground(ui!!.shape(if (ok[i]) ui!!.t.p else ui!!.t.s3, 3.0f, 0, 0.0f))
            col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui!!.dp(6.0f)))
            col.addView(ui!!.text(names[i], 11.5f, ui!!.t.onpc, false), ui!!.gap(6))
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.rightMargin = if (i < 3) ui!!.dp(6.0f) else 0
            bars.addView(col, p)
        }
        hero.addView(bars, ui!!.gap(12))
        c!!.addView(hero)

        val card: LinearLayout = ui!!.card()
        val running: Boolean = AssistantOverlayService.isRunning()
        val assistant: LinearLayout = ui!!.iconRow(R.drawable.ic_sparkle, getString(R.string.phone_assistant), getString(R.string.phone_assistant_sub), ui!!.toggle(running, Ui.OnChecked { on ->
            if (on && !overlay) {
                open(AssistantActivity::class.java)
                return@OnChecked
            }
            if (on) {
                AssistantOverlayService.start(this)
            } else {
                AssistantOverlayService.stop(this)
            }
            card.postDelayed(Runnable(this::refresh), 600L)
        }), null)
        val screenRow: LinearLayout = ui!!.iconRow(R.drawable.ic_screen, getString(R.string.phone_screen), getString(if (screen) R.string.phone_screen_on else R.string.phone_screen_off), ui!!.statusChip(getString(if (screen) R.string.settings_on else R.string.settings_off), screen), View.OnClickListener { v -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        val rules: LinearLayout = ui!!.iconRow(R.drawable.ic_bell, getString(R.string.phone_rules), getString(R.string.phone_rules_sub), ui!!.chevron(), View.OnClickListener { v -> open(TriggersActivity::class.java) })
        val sec: LinearLayout = ui!!.iconRow(R.drawable.ic_shield, getString(R.string.phone_security), getString(R.string.phone_security_sub), ui!!.chevron(), View.OnClickListener { v -> openMcp("security") })
        val net: JSONObject? = NetCheckActivity.last(this)
        val netScore: Int = if (net == null) -1 else net.optInt("score", -1)
        val netChip: TextView = ui!!.statusChip(if (netScore < 0) "—" else java.lang.String.valueOf(netScore), netScore >= 75)
        val netRow: LinearLayout = ui!!.iconRow(R.drawable.ic_globe, getString(R.string.phone_net), if (netScore < 0) getString(R.string.phone_net_never) else getString(R.string.phone_net_sub, netScore, NetCheckActivity.tierText(this, netScore)), netChip, View.OnClickListener { v -> open(NetCheckActivity::class.java) })
        addRows(card, assistant, screenRow, rules, sec, netRow)
        c.addView(card, ui!!.gap(14))

        val mcp: Boolean = McpHost.isRunning()
        val tunnel: Boolean = TunnelManager.isRunning()
        val conn: LinearLayout = ui!!.card()
        val st = LinearLayout(this)
        st.setGravity(Gravity.CENTER_VERTICAL)
        val dot = View(this)
        dot.setBackground(ui!!.shape(if (mcp) ui!!.t.ok else ui!!.t.on2, 5.0f, 0, 0.0f))
        val dl = LinearLayout.LayoutParams(ui!!.dp(10.0f), ui!!.dp(10.0f))
        dl.rightMargin = ui!!.dp(10.0f)
        st.addView(dot, dl)
        st.addView(ui!!.text(getString(if (mcp) R.string.phone_mcp_working else R.string.phone_mcp_off), 15.0f, ui!!.t.on, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        st.addView(ui!!.text(if (tunnel) "ngrok" else getString(R.string.phone_mcp_local), 12.5f, ui!!.t.on2, false))
        conn.addView(st)
        val shown: String = if (tunnel) TunnelManager.url() + "/mcp/••••••••" else "http://127.0.0.1:" + McpHost.port(this) + "/mcp/••••••••"
        val url: TextView = ui!!.text(shown, 12.0f, ui!!.t.on2, false)
        url.setTypeface(Typeface.MONOSPACE)
        url.setSingleLine(true)
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE)
        url.setPadding(ui!!.dp(12.0f), ui!!.dp(10.0f), ui!!.dp(12.0f), ui!!.dp(10.0f))
        url.setBackground(ui!!.shape(ui!!.t.s2, 12.0f, 0, 0.0f))
        conn.addView(url, ui!!.gap(10))
        val btns = LinearLayout(this)
        val bp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bp.rightMargin = ui!!.dp(8.0f)
        btns.addView(ui!!.button(getString(R.string.mcp_copy_short), Ui.Kind.TONAL, View.OnClickListener { v -> copy(if (tunnel) TunnelManager.url() + "/mcp/" + McpHost.token(this) else McpHost.localUrl(this)) }), bp)
        btns.addView(ui!!.button(getString(R.string.mcp_new_token_short), Ui.Kind.OUTLINE, View.OnClickListener { v ->
            McpHost.newToken(this)
            McpService.stop(this)
            v.postDelayed(Runnable(this::refresh), 600L)
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        conn.addView(btns, ui!!.gap(12))
        conn.setClickable(true)
        conn.setOnClickListener(View.OnClickListener { v -> openMcp("connector") })
        c.addView(conn, ui!!.gap(14))

        val stop: TextView = ui!!.button(getString(if (ActionGuard.isPaused()) R.string.mcp_resume else R.string.phone_emergency), Ui.Kind.DANGER, View.OnClickListener { v ->
            if (McpHost.isRunning()) {
                startService(Intent(this, McpService::class.java).setAction(McpService.ACTION_PAUSE))
            } else {
                ActionGuard.setPaused(!ActionGuard.isPaused())
            }
            v.postDelayed(Runnable(this::refresh), 300L)
        })
        stop.setMinHeight(ui!!.dp(56.0f))
        c.addView(stop, ui!!.gap(14))
    }
}
