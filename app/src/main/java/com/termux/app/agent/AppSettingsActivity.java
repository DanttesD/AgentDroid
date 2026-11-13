package com.termux.app.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentdroid.agent.ThemeColors;
import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.workspace.WorkspaceSettings;

/**
 * The one Settings screen of the app, opened by the gear on every screen: look, text size, language, updates,
 * proxy, data (backups, chat histories, terminal, known errors) and About. Nothing here is duplicated elsewhere.
 */
public final class AppSettingsActivity extends Activity {
    private ScrollView scroll;
    private String themeSig = "";
    private int restoreScroll;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppTheme.apply(this);
        themeSig = AppTheme.signature(this);
        WorkspaceSettings.prepare(this);
        setTitle(R.string.settings_title);
        restoreScroll = b == null ? 0 : b.getInt("scroll");
        build();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (scroll != null) out.putInt("scroll", scroll.getScrollY());
    }

    @Override protected void onResume() {
        super.onResume();
        if (!themeSig.equals(AppTheme.signature(this))) { recreate(); return; }
        ApprovalCenter.onResume(this);
    }

    @Override protected void onPause() { ApprovalCenter.onPause(this); super.onPause(); }

    /** Rebuilds the rows (their subtitles changed) and keeps the scroll position. */
    private void rebuild() {
        restoreScroll = scroll == null ? 0 : scroll.getScrollY();
        build();
    }

    private void build() {
        final Ui ui = new Ui(this);
        final ThemeColors t = ui.t;

        LinearLayout root = ui.column();
        root.setBackgroundColor(t.bg);

        root.addView(ui.backHeader(getString(R.string.settings_title), v -> finish()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = ui.column();
        col.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24));

        look(ui, col);
        textSize(ui, col);

        col.addView(ui.section(getString(R.string.settings_sec_app)), ui.gap(16));
        LinearLayout app = ui.card();
        language(ui, app);
        app.addView(ui.divider());
        updates(ui, app);
        app.addView(ui.divider());
        proxy(ui, app);
        col.addView(app, ui.gap(4));

        col.addView(ui.section(getString(R.string.settings_sec_data)), ui.gap(16));
        LinearLayout data = ui.card();
        data.addView(ui.iconRow(R.drawable.workspace_save, getString(R.string.settings_backup), getString(R.string.settings_backup_sub),
                ui.chevron(), v -> workspace("backup")));
        data.addView(ui.divider());
        data.addView(ui.iconRow(R.drawable.ic_nav_chat, getString(R.string.settings_histories), getString(R.string.settings_histories_sub),
                ui.chevron(), v -> workspace("histories")));
        data.addView(ui.divider());
        data.addView(ui.iconRow(R.drawable.workspace_terminal, getString(R.string.settings_terminal), getString(R.string.settings_terminal_sub),
                ui.chevron(), v -> startActivity(new Intent(this, com.termux.app.activities.SettingsActivity.class))));
        data.addView(ui.divider());
        data.addView(ui.iconRow(R.drawable.ic_warn, getString(R.string.settings_errors), getString(R.string.settings_errors_sub),
                ui.chevron(), v -> workspace("errors")));
        col.addView(data, ui.gap(4));

        LinearLayout about = ui.card();
        about.addView(ui.iconRow(R.drawable.workspace_server_info, getString(R.string.settings_about), getString(R.string.settings_about_sub),
                ui.chevron(), v -> about()));
        col.addView(about, ui.gap(16));

        root.addView(col);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(t.bg);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        final int y = restoreScroll;
        if (y > 0) scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** Theme chips, Material You, AMOLED and the accent colour. Changes apply at once to every screen. */
    private void look(final Ui ui, LinearLayout col) {
        final ThemeColors t = ui.t;
        col.addView(ui.section(getString(R.string.settings_sec_look)));
        LinearLayout card = ui.card();

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {getString(R.string.appearance_system), getString(R.string.appearance_dark), getString(R.string.appearance_light)};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            TextView c = ui.chip(names[i], AppTheme.mode(this) == i, v -> { AppTheme.setMode(this, mode); recreate(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = i < names.length - 1 ? ui.dp(6) : 0;
            modes.addView(c, p);
        }
        card.addView(modes);

        card.addView(ui.switchRow(getString(R.string.appearance_you), getString(
                android.os.Build.VERSION.SDK_INT >= 31 ? R.string.appearance_you_sub : R.string.appearance_you_old),
                AppTheme.materialYou(this), on -> { AppTheme.setMaterialYou(this, on); recreate(); }), ui.gap(8));
        card.addView(ui.switchRow(getString(R.string.appearance_amoled), getString(R.string.appearance_amoled_sub),
                AppTheme.amoled(this), on -> { AppTheme.setAmoled(this, on); recreate(); }), ui.gap(4));

        card.addView(ui.text(getString(R.string.appearance_accent), 15, t.on, true), ui.gap(12));
        card.addView(ui.text(getString(AppTheme.wallpaperColors(this) ? R.string.appearance_accent_you : R.string.appearance_accent_sub),
                12.5f, t.on2, false));
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < AppTheme.SEEDS.length; i++) {
            final int idx = i;
            android.view.View dot = new android.view.View(this);
            boolean sel = AppTheme.seed(this) == i && !AppTheme.wallpaperColors(this);
            dot.setBackground(ui.shape(AppTheme.SEEDS[i], 22, sel ? t.on : t.bg, sel ? 2 : 3));
            dot.setClickable(true);
            dot.setContentDescription(getString(R.string.appearance_accent) + " " + (i + 1));
            dot.setOnClickListener(v -> { AppTheme.setSeed(this, idx); AppTheme.setMaterialYou(this, false); recreate(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
            p.rightMargin = ui.dp(14);
            sw.addView(dot, p);
        }
        card.addView(sw, ui.gap(12));
        col.addView(card, ui.gap(4));
    }

    /** Four text scales on top of the system font size; applies to every screen of the app. */
    private void textSize(final Ui ui, LinearLayout col) {
        col.addView(ui.section(getString(R.string.settings_sec_text)), ui.gap(16));
        LinearLayout card = ui.card();
        card.addView(ui.text(getString(R.string.settings_text_sub), 12.5f, ui.t.on2, false));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        float current = AppTheme.textScale(this);
        for (int i = 0; i < AppTheme.TEXT_SCALES.length; i++) {
            final float s = AppTheme.TEXT_SCALES[i];
            TextView c = ui.chip(Math.round(s * 100) + "%", current == s, v -> { AppTheme.setTextScale(this, s); recreate(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = i < AppTheme.TEXT_SCALES.length - 1 ? ui.dp(6) : 0;
            chips.addView(c, p);
        }
        card.addView(chips, ui.gap(10));
        col.addView(card, ui.gap(4));
    }

    private void language(Ui ui, LinearLayout card) {
        String lang = WorkspaceSettings.language(this);
        card.addView(ui.iconRow(R.drawable.workspace_language, getString(R.string.settings_language),
                lang.isEmpty() ? getString(R.string.settings_lang_system) : lang, ui.chevron(),
                v -> WorkspaceSettings.pickLanguage(this, this::recreate)));
    }

    private void updates(Ui ui, LinearLayout card) {
        String name = WorkspaceSettings.updateName(this);
        boolean avail = WorkspaceSettings.updateAvailable(this) && name != null && !name.isEmpty();
        String sub = avail ? getString(R.string.settings_updates_avail, name) : getString(R.string.settings_updates_sub, BuildConfig.VERSION_NAME);
        LinearLayout auto = new LinearLayout(this);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.text(getString(R.string.settings_auto), 12.5f, ui.t.on2, false);
        label.setPadding(0, 0, ui.dp(8), 0);
        auto.addView(label);
        auto.addView(ui.toggle(WorkspaceSettings.autoUpdates(this), on -> WorkspaceSettings.autoUpdates(this, on)));
        card.addView(ui.iconRow(R.drawable.workspace_diag_download, getString(R.string.settings_updates), sub, auto,
                v -> WorkspaceSettings.checkUpdates(this)));
    }

    private void proxy(Ui ui, LinearLayout card) {
        String on = getString(R.string.settings_on), off = getString(R.string.settings_off);
        card.addView(ui.iconRow(R.drawable.ic_globe, getString(R.string.settings_proxy),
                getString(R.string.settings_proxy_sub, BrowserProxy.enabled(this) ? on : off, BrowserProxy.appEnabled(this) ? on : off),
                ui.chevron(), v -> BrowserProxy.showDialog(this, this::rebuild)));
    }

    /** Backups, chat histories and known errors live in the workspace; open the right dialog there. */
    private void workspace(String what) {
        Intent i = new Intent(this, com.termux.app.TermuxActivity.class);
        i.putExtra("agentdroid_screen", "settings:" + what);
        i.putExtra("agentdroid_from", getString(R.string.settings_title));
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    private void about() {
        String msg = getString(R.string.settings_about_text, getString(R.string.application_name),
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, getPackageName());
        new ThemedDialog(this).setTitle(R.string.settings_about).setMessage(msg)
                .setPositiveButton(R.string.settings_close, null).show();
    }
}
