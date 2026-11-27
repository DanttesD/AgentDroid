package com.termux.app.agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.agentdroid.agent.ThemeColors
import com.agentdroid.exec.BackendSelector
import com.agentdroid.exec.ExecRequest
import com.agentdroid.exec.ExecResult
import com.termux.R
import com.termux.app.TermuxActivity

/**
 * Two screens in one class, chosen by the extra "section": the connector that lets claude.ai, Claude Code, Codex, OpenCode…
 * use the phone (server, address, tunnel), and the safety settings of screen control (confirmations, accessibility, blocked apps, log).
 **/
open class McpActivity : Activity() {
    private var status: TextView? = null
    private var local: TextView? = null
    private var tunnel: TextView? = null
    private var a11yState: TextView? = null
    private var tunnelState: TextView? = null
    private var tunnelButton: TextView? = null
    private val tick: Runnable = Runnable(this::refresh)

    private var th: ThemeColors? = null
    private var themeSig: String = ""
    private var security: Boolean = false

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        th = AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        security = "security".equals(getIntent().getStringExtra("section"))
        setTitle(if (security) R.string.mcp_title_security else R.string.mcp_title_connector)

        val ui = Ui(this)
        val root: LinearLayout = ui.column()
        root.setBackgroundColor(th!!.bg)
        root.addView(ui.backHeader(getString(if (security) R.string.mcp_title_security else R.string.mcp_title_connector), View.OnClickListener { v -> finish() }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val col: LinearLayout = ui.column()
        col.setPadding(dp(16), dp(8), dp(16), dp(24))
        if (security) {
            securityCard(ui, col)
        } else {
            connectorCards(ui, col)
        }
        root.addView(col)
        val sv = ScrollView(this)
        sv.setBackgroundColor(th!!.bg)
        sv.addView(root)
        setContentView(sv)
    }

    /** Server, address with the tunnel, and the cloudflared way for claude.ai.*/
    private fun connectorCards(ui: Ui, col: LinearLayout) {
        col.addView(tv(getString(R.string.mcp_intro), 14, th!!.on2))

        val server: LinearLayout = ui.card()
        col.addView(server, ui.gap(14))
        server.addView(ui.text(getString(R.string.mcp_card_server), 16.0f, th!!.on, true))
        status = tv("", 15, th!!.p)
        server.addView(status)
        val start: TextView = btn(getString(R.string.mcp_start))
        start.setOnClickListener(View.OnClickListener { v ->
            McpService.start(this)
            v.postDelayed(Runnable(this::refresh), 800L)
        })
        val stop: TextView = btn(getString(R.string.mcp_stop))
        stop.setOnClickListener(View.OnClickListener { v ->
            McpService.stop(this)
            v.postDelayed(Runnable(this::refresh), 500L)
        })
        server.addView(start)
        server.addView(stop)

        val address: LinearLayout = ui.card()
        col.addView(address, ui.gap(14))
        address.addView(ui.text(getString(R.string.mcp_card_address), 16.0f, th!!.on, true))
        local = tv("", 13, th!!.on)
        address.addView(local)
        val copy: TextView = btn(getString(R.string.mcp_copy))
        copy.setOnClickListener(View.OnClickListener { v -> copy(McpHost.localUrl(this)) })
        address.addView(copy)
        val renew: TextView = btn(getString(R.string.mcp_new_token))
        renew.setOnClickListener(View.OnClickListener { v ->
            McpHost.newToken(this)
            McpService.stop(this)
            v.postDelayed(Runnable(this::refresh), 500L)
        })
        address.addView(renew)

        tunnelState = tv("", 14, th!!.p)
        val tunBtn: TextView = btn("")
        tunBtn.setOnClickListener(View.OnClickListener { v ->
            if (TunnelManager.isRunning()) {
                TunnelManager.stop()
            } else {
                if (!McpHost.isRunning()) {
                    McpService.start(this)
                }
                TunnelManager.start(this, McpHost.port(this))
            }
            refresh()
        })
        tunnelButton = tunBtn
        val copyPublic: TextView = btn(getString(R.string.mcp_copy_public))
        copyPublic.setOnClickListener(View.OnClickListener { v ->
            if (TunnelManager.url()!!.isEmpty()) {
                toast(R.string.mcp_no_public)
            } else {
                copy(TunnelManager.url() + "/mcp/" + McpHost.token(this))
            }
        })
        address.addView(tunBtn)
        address.addView(tunnelState)
        val ngrokSetup: TextView = btn(getString(R.string.mcp_ngrok_setup))
        ngrokSetup.setOnClickListener(View.OnClickListener { v -> askNgrok() })
        val ngrokInstall: TextView = btn(getString(R.string.mcp_ngrok_install))
        ngrokInstall.setOnClickListener(View.OnClickListener { v -> TermuxSessions.run(this, "ngrok", TunnelManager.ngrokInstallScript(), BrowserPicker.SLOTS[0]) })
        val ngrokSite: TextView = btn(getString(R.string.mcp_ngrok_site))
        ngrokSite.setOnClickListener(View.OnClickListener { v ->
            BrowserPicker.show(this, "https://dashboard.ngrok.com/get-started/your-authtoken")
        })
        address.addView(tv(getString(R.string.mcp_ngrok_hint), 13, th!!.on2))
        address.addView(ngrokSite)
        address.addView(ngrokInstall)
        address.addView(ngrokSetup)
        address.addView(copyPublic)

        val cloudflared: LinearLayout = ui.card()
        col.addView(cloudflared, ui.gap(14))
        cloudflared.addView(ui.text(getString(R.string.mcp_tunnel_title), 16.0f, th!!.on, true))
        tunnel = tv("", 13, th!!.on2)
        cloudflared.addView(tunnel)
        val term: TextView = btn(getString(R.string.mcp_open_terminal))
        term.setOnClickListener(View.OnClickListener { v ->
            startActivity(Intent(this, TermuxActivity::class.java).putExtra("agentdroid_from", getString(if (security) R.string.mcp_title_security else R.string.mcp_title_connector)).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        cloudflared.addView(term)
        val cmd: TextView = btn(getString(R.string.mcp_copy_tunnel_cmd))
        cmd.setOnClickListener(View.OnClickListener { v ->
            copy("pkg install -y cloudflared && cloudflared tunnel --url http://127.0.0.1:" + McpHost.port(this))
        })
        cloudflared.addView(cmd)
        val claude: TextView = btn(getString(R.string.mcp_open_claude))
        claude.setOnClickListener(View.OnClickListener { v ->
            BrowserPicker.show(this, "https://claude.ai")
        })
        cloudflared.addView(claude)
    }

    /** How much the agent may do on its own and what it may touch. The emergency stop lives on the Phone tab.*/
    private fun securityCard(ui: Ui, col: LinearLayout) {
        val card: LinearLayout = ui.card()
        col.addView(card)
        card.addView(ui.text(getString(R.string.mcp_security_title), 16.0f, th!!.on, true))
        a11yState = tv("", 14, th!!.p)
        card.addView(a11yState)

        val approval: TextView = btn("")
        approval.setOnClickListener(View.OnClickListener { v ->
            ApprovalCenter.setMode(this, (ApprovalCenter.mode(this) + 1) % 3)
            approval.setText(approvalLabel())
        })
        approval.setText(approvalLabel())
        card.addView(approval)
        val a11y: TextView = btn(getString(R.string.mcp_a11y_open))
        a11y.setOnClickListener(View.OnClickListener { v -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        card.addView(a11y)
        val a11yAuto: TextView = btn(getString(R.string.mcp_a11y_auto))
        a11yAuto.setOnClickListener(View.OnClickListener { v -> enableA11yViaShell() })
        card.addView(a11yAuto)
        val awake: TextView = btn(getString(R.string.mcp_keepalive))
        awake.setOnClickListener(View.OnClickListener { v -> keepAliveViaShell() })
        card.addView(awake)
        val blocked: TextView = btn(getString(R.string.mcp_blocked_apps))
        blocked.setOnClickListener(View.OnClickListener { v -> editBlocked() })
        card.addView(blocked)
        val logBtn: TextView = btn(getString(R.string.mcp_action_log))
        logBtn.setOnClickListener(View.OnClickListener { v -> showLog() })
        card.addView(logBtn)
        val boot: TextView = btn("")
        boot.setOnClickListener(View.OnClickListener { v ->
            BootReceiver.setMode(this, (BootReceiver.mode(this) + 1) % 3)
            boot.setText(bootLabel())
        })
        boot.setText(bootLabel())
        card.addView(boot)
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        ApprovalCenter.onResume(this)
        refresh()
    }

    protected override fun onPause() {
        if (tunnelState != null) {
            tunnelState!!.removeCallbacks(tick)
        }
        ApprovalCenter.onPause(this)
        super.onPause()
    }

    private fun approvalLabel(): String {
        val m: Int = ApprovalCenter.mode(this)
        return getString(if (m == 0) R.string.mcp_approval_ask else if (m == 1) R.string.mcp_approval_hour else R.string.mcp_approval_always)
    }

    private fun askNgrok() {
        val token = EditText(this)
        token.setHint(R.string.mcp_ngrok_token)
        token.setSingleLine(true)
        val domain = EditText(this)
        domain.setHint(R.string.mcp_ngrok_domain)
        domain.setSingleLine(true)
        domain.setText(TunnelManager.ngrokDomain(this))
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(40, 10, 40, 0)
        box.addView(token)
        box.addView(domain)
        ThemedDialog(this).setTitle(R.string.mcp_ngrok_setup).setView(box).setPositiveButton(R.string.agent_save, DialogInterface.OnClickListener { d, w ->
            var t: String = (token.getText().toString() as java.lang.String).trim()
            if (t.isEmpty() && TunnelManager.ngrokConfigured(this)) {
                t = SecretStore(this).get("ngrok_token")
            }
            TunnelManager.saveNgrok(this, t, domain.getText().toString())
            TunnelManager.stop()
            refresh()
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    private fun bootLabel(): String {
        val m: Int = BootReceiver.mode(this)
        return getString(if (m == 0) R.string.mcp_boot_off else if (m == 1) R.string.mcp_boot_server else R.string.mcp_boot_tunnel)
    }

    private fun enableA11yViaShell() {
        val comp: String = getPackageName() + "/" + AgentAccessibilityService::class.java.getName()
        val app: Context = getApplicationContext()
        Thread(Runnable {
            var msg: String
            try {
                val sel: BackendSelector = AgentBackends.create(app)
                val pin: String? = AgentConfig(app).backendPin()
                if (!pin!!.isEmpty()) {
                    try {
                        sel.pin(pin)
                    } catch (ignored: Exception) {
                    }
                }
                val b = SelectedBackend(sel)
                val cmd: String = "cur=\$(settings get secure enabled_accessibility_services); case \"\$cur\" in *" + comp + "*) ;; ''|null) settings put secure enabled_accessibility_services " + comp + " ;; *) settings put secure enabled_accessibility_services \"\$cur:" + comp + "\" ;; esac; settings put secure accessibility_enabled 1"
                val r: ExecResult = b.exec(ExecRequest.builder(cmd).timeoutMs(15000L).build())
                msg = if (r.ok()) getString(R.string.mcp_a11y_done) else getString(R.string.mcp_a11y_fail, (r.stderr() as java.lang.String).trim())
            } catch (e: Exception) {
                msg = getString(R.string.mcp_a11y_fail, java.lang.String.valueOf(e.message))
            }
            val m: String = msg
            runOnUiThread(Runnable {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show()
                refresh()
            })
        }, "a11y-enable").start()
    }

    /** Exempts the app from Doze and background restrictions so the shell, server and tunnel survive a switched-off screen.*/
    private fun keepAliveViaShell() {
        val pkg: String = getPackageName()
        val app: Context = getApplicationContext()
        Thread(Runnable {
            var msg: String
            try {
                val sel: BackendSelector = AgentBackends.create(app)
                val pin: String? = AgentConfig(app).backendPin()
                if (!pin!!.isEmpty()) {
                    try {
                        sel.pin(pin)
                    } catch (ignored: Exception) {
                    }
                }
                val b = SelectedBackend(sel)
                val cmd: String = "dumpsys deviceidle whitelist +" + pkg + "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; am set-standby-bucket " + pkg + " active"
                val r: ExecResult = b.exec(ExecRequest.builder(cmd).timeoutMs(15000L).build())
                msg = if (r.ok()) getString(R.string.mcp_keepalive_done) else getString(R.string.mcp_a11y_fail, (r.stderr() as java.lang.String).trim())
            } catch (e: Exception) {
                msg = getString(R.string.mcp_a11y_fail, java.lang.String.valueOf(e.message))
            }
            val m: String = msg
            runOnUiThread(Runnable { Toast.makeText(this, m, Toast.LENGTH_LONG).show() })
        }, "keepalive").start()
    }

    private fun editBlocked() {
        val et = EditText(this)
        et.setMinLines(6)
        et.setGravity(Gravity.TOP)
        et.setText(ActionGuard.blocked(this))
        ThemedDialog(this).setTitle(R.string.mcp_blocked_apps).setMessage(R.string.mcp_blocked_hint).setView(et).setPositiveButton(R.string.agent_save, DialogInterface.OnClickListener { d, w -> ActionGuard.setBlocked(this, et.getText().toString()) }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    private fun showLog() {
        val log: String = ActionGuard.readLog(this)
        ThemedDialog(this).setTitle(R.string.mcp_action_log).setMessage(if (log.isEmpty()) getString(R.string.mcp_log_empty) else log).setPositiveButton(android.R.string.ok, null).setNeutralButton(R.string.mcp_log_clear, DialogInterface.OnClickListener { d, w -> ActionGuard.clearLog(this) }).show()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        if (tunnelButton != null && tunnelState != null) {
            tunnelButton!!.setText(if (TunnelManager.isRunning()) R.string.mcp_tunnel_stop else R.string.mcp_tunnel_start)
            var st: String
            if (!TunnelManager.error().isEmpty()) {
                st = getString(R.string.mcp_tunnel_err, TunnelManager.error())
            } else if (!TunnelManager.isRunning()) {
                st = getString(R.string.mcp_tunnel_off) + (if (TunnelManager.ngrokConfigured(this)) "\n" + getString(if (TunnelManager.ngrokInstalled()) R.string.mcp_ngrok_ready else R.string.mcp_ngrok_need_install, TunnelManager.ngrokDomain(this)) else "")
            } else if (TunnelManager.isConnected()) {
                st = getString(R.string.mcp_tunnel_ok, TunnelManager.url()) + (if (TunnelManager.note().isEmpty()) "" else "\n" + getString(R.string.mcp_tunnel_fallback, TunnelManager.note()))
            } else {
                st = getString(R.string.mcp_tunnel_wait)
            }
            tunnelState!!.setText(st)
            tunnelState!!.removeCallbacks(tick)
            if (TunnelManager.isRunning()) {
                tunnelState!!.postDelayed(tick, 1500L)
            }
        }
        if (a11yState != null) {
            a11yState!!.setText(getString(if (AgentAccessibilityService.isRunning()) R.string.mcp_a11y_on else R.string.mcp_a11y_off))
        }
        if (status != null) {
            val on: Boolean = McpHost.isRunning()
            status!!.setText(if (on) getString(R.string.mcp_status_on, McpHost.port(this)) else getString(R.string.mcp_status_off))
        }
        if (local != null) {
            local!!.setText(getString(R.string.mcp_local_url, McpHost.localUrl(this)))
        }
        if (tunnel != null) {
            tunnel!!.setText(getString(R.string.mcp_tunnel_text, McpHost.port(this), "/mcp/" + McpHost.token(this)))
        }
    }

    private fun copy(s: String) {
        val cm: ClipboardManager? = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("agentdroid", s))
        }
        Toast.makeText(this, R.string.mcp_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int {
        return (v * getResources().getDisplayMetrics().density).toInt()
    }

    private fun tv(s: String, sp: Int, color: Int): TextView {
        val t = TextView(this)
        t.setText(s)
        t.setTextSize(sp.toFloat())
        t.setTextColor(color)
        t.setTextIsSelectable(true)
        t.setPadding(0, dp(8), 0, dp(4))
        return t
    }

    private fun btn(s: String): TextView {
        val bt: TextView = Ui(this).button(s, Ui.Kind.TONAL, null)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        bt.setLayoutParams(lp)
        return bt
    }
}
