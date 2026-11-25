package com.termux.app.agent

import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.agentdroid.exec.BackendSelector
import com.agentdroid.exec.ExecBackend
import com.termux.R

/**
 * Agents and their accounts in one place: the built-in agent with its model and key, the agents of the workspace,
 * and one list of accounts (web accounts of the chat and CLI accounts of the official agents).
 **/
class AgentsActivity : TabActivity() {
    protected override fun tab(): Int {
        return NavBar.AGENTS
    }

    protected override fun titleRes(): Int {
        return R.string.nav_agents
    }

    protected override fun fab(): TextView? {
        val f: TextView = ui!!.text("+  " + getString(R.string.agents_fab), 15.0f, ui!!.t.onp, true)
        f.setGravity(Gravity.CENTER)
        f.setPadding(ui!!.dp(22.0f), 0, ui!!.dp(22.0f), 0)
        f.setBackground(ui!!.shape(ui!!.t.p, 20.0f, 0, 0.0f))
        f.setClickable(true)
        f.setOnClickListener(View.OnClickListener { v -> addAccount() })
        return f
    }

    /** One entry point for every kind of account: a web account, a CLI sign-in or an API key.*/
    private fun addAccount() {
        val items: Array<String> = arrayOf<String>(getString(R.string.agents_add_web), getString(R.string.agents_add_cli), getString(R.string.agents_add_key))
        ThemedDialog(this).setTitle(R.string.agents_add_title).setItems(items, DialogInterface.OnClickListener { d, which ->
            if (which == 0) {
                WebAccountsUi.add(this, webAccounts(), WebAccountsUi.Done<WebAccounts.Account> { acc -> ChatActivity.showWebAccount(this, acc.id) })
            } else if (which == 1) {
                open(AccountsActivity::class.java)
            } else {
                ProviderDialog.show(this, AgentConfig(this), Runnable(this::refresh))
            }
        }).show()
    }

    private fun webAccounts(): WebAccounts {
        return WebAccounts(this, getString(R.string.agent_web_default))
    }

    private fun sessionCard(name: String, line: String, live: Boolean, l: View.OnClickListener, vararg chips: String): LinearLayout {
        val card: LinearLayout = ui!!.card()
        val top = LinearLayout(this)
        top.setGravity(Gravity.CENTER_VERTICAL)
        val dot = View(this)
        dot.setBackground(ui!!.shape(if (live) ui!!.t.ok else ui!!.t.on2, 5.0f, 0, 0.0f))
        val dl = LinearLayout.LayoutParams(ui!!.dp(10.0f), ui!!.dp(10.0f))
        dl.rightMargin = ui!!.dp(10.0f)
        top.addView(dot, dl)
        top.addView(ui!!.text(name, 16.0f, ui!!.t.on, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ui!!.text(getString(if (live) R.string.agents_ready else R.string.agents_setup), 12.5f, if (live) ui!!.t.ok else ui!!.t.on2, true))
        card.addView(top)
        card.addView(ui!!.text(line, 13.0f, ui!!.t.on2, false), ui!!.gap(8))
        val row = LinearLayout(this)
        for (c in chips) {
            val ch: TextView = ui!!.text(c, 12.0f, ui!!.t.on, false)
            ch.setGravity(Gravity.CENTER_VERTICAL)
            ch.setPadding(ui!!.dp(10.0f), 0, ui!!.dp(10.0f), 0)
            ch.setBackground(ui!!.shape(ui!!.t.s2, 13.0f, 0, 0.0f))
            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui!!.dp(26.0f))
            p.rightMargin = ui!!.dp(6.0f)
            row.addView(ch, p)
        }
        card.addView(row, ui!!.gap(10))
        card.setClickable(true)
        card.setOnClickListener(l)
        return card
    }

    /** An account row: letter avatar, name, what it is, and how it signs in.*/
    private fun accountRow(name: String, sub: String, how: String, current: Boolean, tap: View.OnClickListener, hold: View.OnLongClickListener?): LinearLayout {
        val row = LinearLayout(this)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setMinimumHeight(ui!!.dp(52.0f))
        val av: TextView = ui!!.text(if (name.isEmpty()) "?" else (name.substring(0, 1) as java.lang.String).toUpperCase(), 14.0f, ui!!.t.on, true)
        av.setGravity(Gravity.CENTER)
        av.setBackground(ui!!.shape(if (current) ui!!.t.pc else ui!!.t.s3, 17.0f, 0, 0.0f))
        av.setTextColor(if (current) ui!!.t.onpc else ui!!.t.on)
        val al = LinearLayout.LayoutParams(ui!!.dp(34.0f), ui!!.dp(34.0f))
        al.rightMargin = ui!!.dp(12.0f)
        row.addView(av, al)
        val t: LinearLayout = ui!!.column()
        val n: TextView = ui!!.text(name, 14.5f, ui!!.t.on, true)
        n.setSingleLine(true)
        n.setEllipsize(TextUtils.TruncateAt.END)
        t.addView(n)
        val s: TextView = ui!!.text(sub, 12.5f, ui!!.t.on2, false)
        s.setSingleLine(true)
        s.setEllipsize(TextUtils.TruncateAt.END)
        t.addView(s)
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val h: TextView = ui!!.text(how, 12.0f, ui!!.t.onpc, true)
        h.setGravity(Gravity.CENTER)
        h.setPadding(ui!!.dp(10.0f), 0, ui!!.dp(10.0f), 0)
        h.setBackground(ui!!.shape(ui!!.t.pc, 13.0f, 0, 0.0f))
        row.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui!!.dp(26.0f)))
        row.setClickable(true)
        row.setOnClickListener(tap)
        if (hold != null) {
            row.setOnLongClickListener(hold)
        }
        return row
    }

    private val webListener: WebAccountsUi.Listener = object : WebAccountsUi.Listener {
        override fun renamed(acc: WebAccounts.Account) {
            refresh()
        }

        override fun serviceChanged(acc: WebAccounts.Account) {
            refresh()
        }

        override fun deleted(acc: WebAccounts.Account) {
            val sp: SharedPreferences = getSharedPreferences("agentdroid_agent", Context.MODE_PRIVATE)
            if (sp.getInt("web_acc", -1) == acc.id) {
                sp.edit().putBoolean("web_mode", false).apply()
            }
            BrowserHelper.deleteProfile(acc.profile())
            refresh()
        }
    }

    protected override fun fill(c: LinearLayout?) {
        val cfg = AgentConfig(this)
        val sel: BackendSelector = AgentBackends.create(this)
        val pin: String? = cfg.backendPin()
        if (!pin!!.isEmpty()) {
            try {
                sel.pin(pin)
            } catch (ignored: Exception) {
            }
        }
        val b: ExecBackend? = sel.select()
        val ready: Boolean = !cfg.apiKey().isEmpty() || cfg.provider()!!.equals("custom")

        val head = LinearLayout(this)
        head.setGravity(Gravity.CENTER_VERTICAL)
        head.addView(ui!!.section(getString(R.string.agents_running)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val create: TextView = ui!!.text(getString(R.string.agents_new_session), 13.0f, ui!!.t.p, true)
        create.setPadding(ui!!.dp(8.0f), ui!!.dp(8.0f), ui!!.dp(4.0f), ui!!.dp(8.0f))
        create.setOnClickListener(View.OnClickListener { v -> openWorkspace("sessions") })
        head.addView(create)
        c!!.addView(head)
        c.addView(sessionCard("AgentDroid", ProviderDialog.summary(this, cfg), ready, View.OnClickListener { v -> open(ChatActivity::class.java) }, if (b == null) "-" else (b.displayName() as java.lang.String).replaceAll(" \\(.*", ""), getString(R.string.agents_with_confirm)), ui!!.gap(0))
        c.addView(sessionCard(getString(R.string.agents_ws), getString(R.string.agents_ws_line), true, View.OnClickListener { v -> openWorkspace("sessions") }, "OpenCode", "Claude Code", "Codex", "Gemini"), ui!!.gap(10))

        val models: LinearLayout = ui!!.card()
        models.addView(ui!!.iconRow(R.drawable.ic_sparkle, getString(R.string.agents_models), getString(R.string.agents_model_row), ui!!.chevron(), View.OnClickListener { v -> ProviderDialog.show(this, cfg, Runnable(this::refresh)) }))
        c.addView(models, ui!!.gap(10))

        c.addView(ui!!.section(getString(R.string.agents_accounts)), ui!!.gap(18))
        var any = false

        val wa: WebAccounts = webAccounts()
        val web: MutableList<WebAccounts.Account> = ArrayList<WebAccounts.Account>()
        for (a in wa.accounts()) {
            if (!a.url.isEmpty()) {
                web.add(a)
            }
        }
        if (!web.isEmpty()) {
            any = true
            val sp: SharedPreferences = getSharedPreferences("agentdroid_agent", Context.MODE_PRIVATE)
            val shown: Int = if (sp.getBoolean("web_mode", false)) sp.getInt("web_acc", -1) else -1
            val g: LinearLayout = ui!!.card()
            g.setPadding(ui!!.dp(16.0f), ui!!.dp(10.0f), ui!!.dp(16.0f), ui!!.dp(6.0f))
            g.addView(ui!!.text(getString(R.string.agents_web_group), 14.0f, ui!!.t.on, true))
            for (a in web) {
                g.addView(accountRow(a.name, WebAccountsUi.hostOf(a.url), getString(R.string.agents_how_web), a.id == shown, View.OnClickListener { v -> ChatActivity.showWebAccount(this, a.id) }, View.OnLongClickListener { v ->
                    WebAccountsUi.edit(this, wa, a, webListener)
                    return@OnLongClickListener true
                }))
            }
            c.addView(g, ui!!.gap(10))
        }

        val groups: MutableMap<String, MutableList<AccountsStore.Account>?> = LinkedHashMap<String, MutableList<AccountsStore.Account>?>()
        for (a in AccountsStore(this).all()) {
            var l: MutableList<AccountsStore.Account>? = groups.get(a.agentId)
            if (l == null) {
                l = ArrayList<AccountsStore.Account>()
                groups.put(a.agentId, l)
            }
            l.add(a)
        }
        for (e in groups.entries) {
            any = true
            val g: LinearLayout = ui!!.card()
            g.setPadding(ui!!.dp(16.0f), ui!!.dp(10.0f), ui!!.dp(16.0f), ui!!.dp(6.0f))
            val title: String = (e.key.substring(0, 1) as java.lang.String).toUpperCase() + e.key.substring(1)
            g.addView(ui!!.text(title, 14.0f, ui!!.t.on, true))
            for (a in e.value!!) {
                g.addView(accountRow(a.name, getString(R.string.agents_own_profile), getString(R.string.agents_how_browser), false, View.OnClickListener { v -> open(AccountsActivity::class.java) }, null))
            }
            c.addView(g, ui!!.gap(10))
        }
        if (!any) {
            val empty: LinearLayout = ui!!.card()
            empty.addView(ui!!.text(getString(R.string.agents_no_accounts), 13.0f, ui!!.t.on2, false))
            c.addView(empty)
        }
        val pad = View(this)
        c.addView(pad, LinearLayout.LayoutParams(1, ui!!.dp(72.0f)))
    }
}
