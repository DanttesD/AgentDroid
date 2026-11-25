package com.termux.app.agent

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.agentdroid.agent.ThemeColors
import com.termux.BuildConfig
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.workspace.WorkspaceSettings

/**
 * The one Settings screen of the app, opened by the gear on every screen: look, text size, language, updates,
 * proxy, data (backups, chat histories, terminal, known errors) and About. Nothing here is duplicated elsewhere.
 **/
class AppSettingsActivity : Activity() {
    private var scroll: ScrollView? = null
    private var themeSig: String = ""
    private var restoreScroll: Int = 0

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        WorkspaceSettings.prepare(this)
        setTitle(R.string.settings_title)
        restoreScroll = if (b == null) 0 else b.getInt("scroll")
        build()
    }

    protected override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (scroll != null) {
            out.putInt("scroll", scroll!!.getScrollY())
        }
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        ApprovalCenter.onResume(this)
    }

    protected override fun onPause() {
        ApprovalCenter.onPause(this)
        super.onPause()
    }

    /** Rebuilds the rows (their subtitles changed) and keeps the scroll position.*/
    private fun rebuild() {
        restoreScroll = if (scroll == null) 0 else scroll!!.getScrollY()
        build()
    }

    private fun build() {
        val ui = Ui(this)
        val t: ThemeColors = ui.t

        val root: LinearLayout = ui.column()
        root.setBackgroundColor(t.bg)

        root.addView(ui.backHeader(getString(R.string.settings_title), View.OnClickListener { v -> finish() }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val col: LinearLayout = ui.column()
        col.setPadding(ui.dp(16.0f), ui.dp(8.0f), ui.dp(16.0f), ui.dp(24.0f))

        look(ui, col)
        textSize(ui, col)

        col.addView(ui.section(getString(R.string.settings_sec_app)), ui.gap(16))
        val app: LinearLayout = ui.card()
        language(ui, app)
        app.addView(ui.divider())
        updates(ui, app)
        app.addView(ui.divider())
        proxy(ui, app)
        col.addView(app, ui.gap(4))

        col.addView(ui.section(getString(R.string.settings_sec_data)), ui.gap(16))
        val data: LinearLayout = ui.card()
        data.addView(ui.iconRow(R.drawable.workspace_save, getString(R.string.settings_backup), getString(R.string.settings_backup_sub), ui.chevron(), View.OnClickListener { v -> workspace("backup") }))
        data.addView(ui.divider())
        data.addView(ui.iconRow(R.drawable.ic_nav_chat, getString(R.string.settings_histories), getString(R.string.settings_histories_sub), ui.chevron(), View.OnClickListener { v -> workspace("histories") }))
        data.addView(ui.divider())
        data.addView(ui.iconRow(R.drawable.workspace_terminal, getString(R.string.settings_terminal), getString(R.string.settings_terminal_sub), ui.chevron(), View.OnClickListener { v -> startActivity(Intent(this, SettingsActivity::class.java)) }))
        data.addView(ui.divider())
        data.addView(ui.iconRow(R.drawable.ic_warn, getString(R.string.settings_errors), getString(R.string.settings_errors_sub), ui.chevron(), View.OnClickListener { v -> workspace("errors") }))
        col.addView(data, ui.gap(4))

        val about: LinearLayout = ui.card()
        about.addView(ui.iconRow(R.drawable.workspace_server_info, getString(R.string.settings_about), getString(R.string.settings_about_sub), ui.chevron(), View.OnClickListener { v -> about() }))
        col.addView(about, ui.gap(16))

        root.addView(col)
        scroll = ScrollView(this)
        scroll!!.setBackgroundColor(t.bg)
        scroll!!.setFillViewport(true)
        scroll!!.addView(root)
        setContentView(scroll)
        val y: Int = restoreScroll
        if (y > 0) {
            scroll!!.post(Runnable { scroll!!.scrollTo(0, y) })
        }
    }

    /** Theme chips, Material You, AMOLED and the accent colour. Changes apply at once to every screen.*/
    private fun look(ui: Ui, col: LinearLayout) {
        val t: ThemeColors = ui.t
        col.addView(ui.section(getString(R.string.settings_sec_look)))
        val card: LinearLayout = ui.card()

        val modes = LinearLayout(this)
        modes.setOrientation(LinearLayout.HORIZONTAL)
        val names: Array<String> = arrayOf<String>(getString(R.string.appearance_system), getString(R.string.appearance_dark), getString(R.string.appearance_light))
        for (i in 0 until (names.size)) {
            val mode: Int = i
            val c: TextView = ui.chip(names[i], AppTheme.mode(this) == i, View.OnClickListener { v ->
                AppTheme.setMode(this, mode)
                recreate()
            })
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.rightMargin = if (i < names.size - 1) ui.dp(6.0f) else 0
            modes.addView(c, p)
        }
        card.addView(modes)

        card.addView(ui.switchRow(getString(R.string.appearance_you), getString(if (Build.VERSION.SDK_INT >= 31) R.string.appearance_you_sub else R.string.appearance_you_old), AppTheme.materialYou(this), Ui.OnChecked { on ->
            AppTheme.setMaterialYou(this, on)
            recreate()
        }), ui.gap(8))
        card.addView(ui.switchRow(getString(R.string.appearance_amoled), getString(R.string.appearance_amoled_sub), AppTheme.amoled(this), Ui.OnChecked { on ->
            AppTheme.setAmoled(this, on)
            recreate()
        }), ui.gap(4))

        card.addView(ui.text(getString(R.string.appearance_accent), 15.0f, t.on, true), ui.gap(12))
        card.addView(ui.text(getString(if (AppTheme.wallpaperColors(this)) R.string.appearance_accent_you else R.string.appearance_accent_sub), 12.5f, t.on2, false))
        val sw = LinearLayout(this)
        sw.setOrientation(LinearLayout.HORIZONTAL)
        sw.setGravity(Gravity.CENTER_VERTICAL)
        var i = 0
        while (i < AppTheme.SEEDS.size) {
            val idx: Int = i
            val dot = View(this)
            val sel: Boolean = AppTheme.seed(this) == i && !AppTheme.wallpaperColors(this)
            dot.setBackground(ui.shape(AppTheme.SEEDS[i], 22.0f, if (sel) t.on else t.bg, (if (sel) 2 else 3).toFloat()))
            dot.setClickable(true)
            dot.setContentDescription(getString(R.string.appearance_accent) + " " + (i + 1))
            dot.setOnClickListener(View.OnClickListener { v ->
                AppTheme.setSeed(this, idx)
                AppTheme.setMaterialYou(this, false)
                recreate()
            })
            val p = LinearLayout.LayoutParams(ui.dp(44.0f), ui.dp(44.0f))
            p.rightMargin = ui.dp(14.0f)
            sw.addView(dot, p)
            i++
        }
        card.addView(sw, ui.gap(12))
        col.addView(card, ui.gap(4))
    }

    /** Four text scales on top of the system font size; applies to every screen of the app.*/
    private fun textSize(ui: Ui, col: LinearLayout) {
        col.addView(ui.section(getString(R.string.settings_sec_text)), ui.gap(16))
        val card: LinearLayout = ui.card()
        card.addView(ui.text(getString(R.string.settings_text_sub), 12.5f, ui.t.on2, false))
        val chips = LinearLayout(this)
        chips.setOrientation(LinearLayout.HORIZONTAL)
        val current: Float = AppTheme.textScale(this)
        var i = 0
        while (i < AppTheme.TEXT_SCALES.size) {
            val s: Float = AppTheme.TEXT_SCALES[i]
            val c: TextView = ui.chip(Math.round(s * 100).toString() + "%", current == s, View.OnClickListener { v ->
                AppTheme.setTextScale(this, s)
                recreate()
            })
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.rightMargin = if (i < AppTheme.TEXT_SCALES.size - 1) ui.dp(6.0f) else 0
            chips.addView(c, p)
            i++
        }
        card.addView(chips, ui.gap(10))
        col.addView(card, ui.gap(4))
    }

    private fun language(ui: Ui, card: LinearLayout) {
        val lang: String = WorkspaceSettings.language(this)
        card.addView(ui.iconRow(R.drawable.workspace_language, getString(R.string.settings_language), if (lang.isEmpty()) getString(R.string.settings_lang_system) else lang, ui.chevron(), View.OnClickListener { v -> WorkspaceSettings.pickLanguage(this, Runnable(this::recreate)) }))
    }

    private fun updates(ui: Ui, card: LinearLayout) {
        val name: String? = WorkspaceSettings.updateName(this)
        val avail: Boolean = WorkspaceSettings.updateAvailable(this) && name != null && !name.isEmpty()
        val sub: String = if (avail) getString(R.string.settings_updates_avail, name) else getString(R.string.settings_updates_sub, BuildConfig.VERSION_NAME)
        val auto = LinearLayout(this)
        auto.setGravity(Gravity.CENTER_VERTICAL)
        val label: TextView = ui.text(getString(R.string.settings_auto), 12.5f, ui.t.on2, false)
        label.setPadding(0, 0, ui.dp(8.0f), 0)
        auto.addView(label)
        auto.addView(ui.toggle(WorkspaceSettings.autoUpdates(this), Ui.OnChecked { on -> WorkspaceSettings.autoUpdates(this, on) }))
        card.addView(ui.iconRow(R.drawable.workspace_diag_download, getString(R.string.settings_updates), sub, auto, View.OnClickListener { v -> WorkspaceSettings.checkUpdates(this) }))
    }

    private fun proxy(ui: Ui, card: LinearLayout) {
        val on: String = getString(R.string.settings_on)
        val off: String = getString(R.string.settings_off)
        card.addView(ui.iconRow(R.drawable.ic_globe, getString(R.string.settings_proxy), getString(R.string.settings_proxy_sub, if (BrowserProxy.enabled(this)) on else off, if (BrowserProxy.appEnabled(this)) on else off), ui.chevron(), View.OnClickListener { v -> BrowserProxy.showDialog(this, Runnable(this::rebuild)) }))
    }

    /** Backups, chat histories and known errors live in the workspace; open the right dialog there.*/
    private fun workspace(what: String) {
        val i = Intent(this, TermuxActivity::class.java)
        i.putExtra("agentdroid_screen", "settings:" + what)
        i.putExtra("agentdroid_from", getString(R.string.settings_title))
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun about() {
        val msg: String = getString(R.string.settings_about_text, getString(R.string.application_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, getPackageName())
        ThemedDialog(this).setTitle(R.string.settings_about).setMessage(msg).setPositiveButton(R.string.settings_close, null).show()
    }
}
