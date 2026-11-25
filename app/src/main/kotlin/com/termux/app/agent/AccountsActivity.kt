package com.termux.app.agent

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.agentdroid.agent.ThemeColors
import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ExecRequest
import com.agentdroid.exec.ExecResult
import com.agentdroid.exec.ShellQuote
import com.termux.R

/** Accounts and sign-in: install the official agents, add accounts, sign in through the built-in browser.*/
open class AccountsActivity : Activity() {
    private var BG: Int = 0
    private var PANEL: Int = 0
    private var FG: Int = 0
    private var DIM: Int = 0
    private var ACCENT: Int = 0
    private var BTN: Int = 0
    private var ONBTN: Int = 0

    private var store: AccountsStore? = null
    private var list: LinearLayout? = null
    private var termux: ExecBackend? = null

    private var themeSig: String = ""

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val th: ThemeColors = AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        BG = th.bg
        PANEL = th.s2
        FG = th.on
        DIM = th.on2
        ACCENT = th.p
        BTN = th.pc
        ONBTN = th.onpc
        store = AccountsStore(this)
        termux = AgentBackends.create(this).find("termux")
        val sv = ScrollView(this)
        sv.setBackgroundColor(BG)
        list = LinearLayout(this)
        list!!.setOrientation(LinearLayout.VERTICAL)
        val p: Int = dp(14)
        list!!.setPadding(p, dp(30), p, p)
        sv.addView(list)
        setContentView(sv)
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        ApprovalCenter.onResume(this)
        render()
    }

    protected override fun onPause() {
        ApprovalCenter.onPause(this)
        super.onPause()
    }

    private fun render() {
        list!!.removeAllViews()
        val t: TextView = tv(getString(R.string.acc_title), 20, FG)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        list!!.addView(t)
        list!!.addView(tv(getString(R.string.acc_intro), 14, DIM))
        for (e in AgentCatalog.ALL) {
            list!!.addView(agentCard(e))
        }
    }

    private fun agentCard(e: AgentCatalog.Entry): LinearLayout {
        val card = LinearLayout(this)
        card.setOrientation(LinearLayout.VERTICAL)
        card.setBackground(Ui(this).shape(PANEL, 22.0f, Ui(this).t.line, 1.0f))
        val p: Int = dp(16)
        card.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        card.setLayoutParams(lp)

        val title: TextView = tv(e.title, 17, FG)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        card.addView(title)
        card.addView(tv(getString(e.noteRes), 13, DIM))
        val status: TextView = tv(getString(R.string.acc_checking), 14, ACCENT)
        card.addView(status)
        checkInstalled(e, status)

        val install: TextView = btn(getString(R.string.acc_install))
        install.setOnClickListener(View.OnClickListener { v ->
            Toast.makeText(this, R.string.acc_install_note, Toast.LENGTH_LONG).show()
            TermuxSessions.run(this, e.title, e.ensureInstalled(), BrowserPicker.SLOTS[0])
        })
        card.addView(install)
        val add: TextView = btn(getString(R.string.acc_add))
        add.setOnClickListener(View.OnClickListener { v -> askName(e) })
        card.addView(add)

        for (a in store!!.of(e.id)) {
            card.addView(accountRow(e, a))
        }
        return card
    }

    private fun accountRow(e: AgentCatalog.Entry, a: AccountsStore.Account): LinearLayout {
        val row = LinearLayout(this)
        row.setOrientation(LinearLayout.VERTICAL)
        row.setPadding(0, dp(10), 0, 0)
        row.addView(tv(getString(R.string.acc_account_line, a.name, BrowserPicker.name(this, a.browserSlot)), 14, FG))
        val btns = LinearLayout(this)
        btns.setOrientation(LinearLayout.HORIZONTAL)
        val run: TextView = small(getString(R.string.acc_run))
        run.setOnClickListener(View.OnClickListener { v ->
            TermuxSessions.run(this, e.title + " " + a.name, e.ensureInstalled() + "; mkdir -p " + ShellQuote.quote(a.dir()) + "; " + e.withAccount(e.run, a.dir()), BrowserPicker.SLOTS[a.browserSlot])
        })
        val relogin: TextView = small(getString(R.string.acc_relogin))
        relogin.setOnClickListener(View.OnClickListener { v -> login(e, a) })
        val del: TextView = small(getString(R.string.acc_delete))
        del.setOnClickListener(View.OnClickListener { v -> confirmDelete(e, a) })
        btns.addView(run)
        btns.addView(relogin)
        btns.addView(del)
        row.addView(btns)
        return row
    }

    private fun login(e: AgentCatalog.Entry, a: AccountsStore.Account) {
        TermuxSessions.run(this, e.title + " " + a.name, e.ensureInstalled() + "; mkdir -p " + ShellQuote.quote(a.dir()) + "; " + e.withAccount(e.login, a.dir()), BrowserPicker.SLOTS[a.browserSlot])
    }

    private fun askName(e: AgentCatalog.Entry) {
        val input = EditText(this)
        input.setSingleLine(true)
        ThemedDialog(this).setTitle(R.string.acc_name_title).setView(input).setPositiveButton(R.string.agent_save, DialogInterface.OnClickListener { d, w ->
            val name: String = (input.getText().toString() as java.lang.String).trim()
            if (!AccountsStore.validName(name)) {
                toast(R.string.acc_name_bad)
                return@OnClickListener
            }
            if (store!!.of(e.id).size >= AccountsStore.MAX_PER_AGENT) {
                toast(R.string.acc_limit)
                return@OnClickListener
            }
            val acc: AccountsStore.Account? = store!!.add(e.id, name)
            if (acc == null) {
                toast(R.string.acc_name_exists)
                return@OnClickListener
            }
            render()
            login(e, acc)
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    private fun confirmDelete(e: AgentCatalog.Entry, a: AccountsStore.Account) {
        ThemedDialog(this).setMessage(getString(R.string.acc_delete_confirm, a.name)).setPositiveButton(R.string.acc_delete, DialogInterface.OnClickListener { d, w ->
            store!!.remove(a)
            Thread(Runnable {
                try {
                    termux!!.exec(ExecRequest.builder("rm -rf " + ShellQuote.quote(a.dir())).build())
                } catch (ignored: Exception) {
                }
            }, "acc-delete").start()
            render()
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }

    private fun checkInstalled(e: AgentCatalog.Entry, status: TextView) {
        Thread(Runnable {
            var ok = false
            try {
                if (termux != null) {
                    val r: ExecResult = termux!!.exec(ExecRequest.builder(e.checkCommand()).timeoutMs(60_000.toLong()).build())
                    ok = r.ok() && !(r.stdout() as java.lang.String).trim().isEmpty()
                }
            } catch (ignored: Exception) {
            }
            val installed: Boolean = ok
            runOnUiThread(Runnable { status.setText(if (installed) R.string.acc_installed else R.string.acc_not_installed) })
        }, "acc-check").start()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int): Int {
        return (v * getResources().getDisplayMetrics().density).toInt()
    }

    private fun tv(s: String, sp: Int, color: Int): TextView {
        val t = TextView(this)
        t.setText(s)
        t.setTextSize(sp.toFloat())
        t.setTextColor(color)
        t.setPadding(0, dp(6), 0, dp(2))
        return t
    }

    private fun btn(s: String): TextView {
        val bt: TextView = Ui(this).button(s, Ui.Kind.TONAL, null)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        bt.setLayoutParams(lp)
        return bt
    }

    private fun small(s: String): TextView {
        val bt: TextView = Ui(this).button(s, Ui.Kind.OUTLINE, null)
        bt.setTextSize(12.5f)
        bt.setMinHeight(dp(40))
        bt.setPadding(dp(8), 0, dp(8), 0)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = dp(6)
        bt.setLayoutParams(lp)
        return bt
    }
}
