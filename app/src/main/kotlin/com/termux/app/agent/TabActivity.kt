package com.termux.app.agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.R
import com.termux.app.TermuxActivity

/** A top-level screen: big title, gear (appearance), scrolling content, bottom navigation.*/
abstract class TabActivity : Activity() {
    @JvmField protected var ui: Ui? = null
    private var content: LinearLayout? = null

    protected abstract fun tab(): Int

    protected abstract fun titleRes(): Int

    /** Fills the content column. Called again by [.refresh].*/
    protected abstract fun fill(content: LinearLayout?)

    /** Optional floating action button.*/
    protected open fun fab(): TextView? {
        return null
    }

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        ui = Ui(this)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setBackgroundColor(ui!!.t.bg)

        val head = LinearLayout(this)
        head.setGravity(Gravity.CENTER_VERTICAL)
        head.setPadding(ui!!.dp(20.0f), ui!!.dp(28.0f), ui!!.dp(8.0f), ui!!.dp(4.0f))
        val title: TextView = ui!!.title(getString(titleRes()))
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui!!.iconButton(R.drawable.ic_gear, ui!!.t.on, getString(R.string.nav_settings), View.OnClickListener { v -> startActivity(Intent(this, AppSettingsActivity::class.java)) }))
        root.addView(head)

        val sv = ScrollView(this)
        content = ui!!.column()
        content!!.setPadding(ui!!.dp(16.0f), ui!!.dp(8.0f), ui!!.dp(16.0f), ui!!.dp(16.0f))
        sv.addView(content)
        val body = FrameLayout(this)
        body.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val fab: TextView? = fab()
        if (fab != null) {
            val fp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui!!.dp(56.0f), Gravity.END or Gravity.BOTTOM)
            fp.setMargins(0, 0, ui!!.dp(16.0f), ui!!.dp(16.0f))
            body.addView(fab, fp)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(NavBar.build(this, tab()))
        setContentView(root)
    }

    private var themeSig: String = ""

    protected override fun onResume() {
        super.onResume()
        ApprovalCenter.onResume(this)
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        refresh()
    }

    protected override fun onPause() {
        ApprovalCenter.onPause(this)
        super.onPause()
    }

    protected open fun refresh() {
        content!!.removeAllViews()
        fill(content)
    }

    /** A card row: title, subtitle, chevron; opens an activity.*/
    protected open fun row(name: CharSequence, sub: CharSequence?, target: Class<*>?): LinearLayout {
        val r = LinearLayout(this)
        r.setGravity(Gravity.CENTER_VERTICAL)
        r.setMinimumHeight(ui!!.dp(56.0f))
        val texts: LinearLayout = ui!!.column()
        texts.addView(ui!!.text(name, 15.0f, ui!!.t.on, true))
        if (sub != null && sub.length > 0) {
            texts.addView(ui!!.text(sub, 12.5f, ui!!.t.on2, false))
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (target != null) {
            r.addView(ui!!.icon(R.drawable.ic_next, 20, ui!!.t.on2))
            r.setClickable(true)
            r.setOnClickListener(View.OnClickListener { v -> startActivity(Intent(this, target)) })
        }
        return r
    }

    protected open fun open(target: Class<*>) {
        startActivity(Intent(this, target))
    }

    protected open fun openWorkspace(screen: String?) {
        val i = Intent(this, TermuxActivity::class.java)
        if (screen != null) {
            i.putExtra("agentdroid_screen", screen)
        }
        i.putExtra("agentdroid_from", getString(titleRes()))
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    protected open fun copy(text: String) {
        val cm: ClipboardManager? = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("agentdroid", text))
        }
        Toast.makeText(this, R.string.mcp_copied, Toast.LENGTH_SHORT).show()
    }

    protected open fun addRows(card: LinearLayout, vararg rows: LinearLayout) {
        for (i in 0 until (rows.size)) {
            if (i > 0) {
                card.addView(ui!!.divider())
            }
            card.addView(rows[i])
        }
    }
}
