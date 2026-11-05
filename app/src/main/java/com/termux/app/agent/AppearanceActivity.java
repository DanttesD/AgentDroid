package com.termux.app.agent;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.agentdroid.agent.ThemeColors;
import com.termux.R;

/** Theme, Material You, AMOLED and accent. Changes apply at once and to every AgentDroid screen. */
public final class AppearanceActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppTheme.apply(this);
        setTitle(R.string.appearance_title);
        build();
    }

    private void build() {
        final Ui ui = new Ui(this);
        final ThemeColors t = ui.t;
        LinearLayout col = ui.column();
        col.setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24));

        col.addView(ui.title(getString(R.string.appearance_title)));
        col.addView(ui.section(getString(R.string.appearance_theme)), ui.gap(16));

        LinearLayout card = ui.card();
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {getString(R.string.appearance_system), getString(R.string.appearance_dark), getString(R.string.appearance_light)};
        for (int i = 0; i < names.length; i++) {
            final int mode = i;
            TextView c = ui.chip(names[i], AppTheme.mode(this) == i, v -> { AppTheme.setMode(this, mode); recreate(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = i < 2 ? ui.dp(6) : 0;
            modes.addView(c, p);
        }
        card.addView(modes);

        LinearLayout.LayoutParams rowGap = ui.gap(8);
        card.addView(ui.switchRow(getString(R.string.appearance_you), getString(
                android.os.Build.VERSION.SDK_INT >= 31 ? R.string.appearance_you_sub : R.string.appearance_you_old),
                AppTheme.materialYou(this), on -> { AppTheme.setMaterialYou(this, on); recreate(); }), rowGap);
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
            View dot = new View(this);
            boolean sel = AppTheme.seed(this) == i;
            dot.setBackground(ui.shape(AppTheme.SEEDS[i], 22, sel ? t.on : t.bg, sel ? 2 : 3));
            dot.setClickable(true);
            dot.setContentDescription("accent " + (i + 1));
            dot.setOnClickListener(v -> { AppTheme.setSeed(this, idx); AppTheme.setMaterialYou(this, false); recreate(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
            p.rightMargin = ui.dp(14);
            sw.addView(dot, p);
        }
        card.addView(sw, ui.gap(12));
        col.addView(card, ui.gap(4));

        col.addView(ui.section(getString(R.string.appearance_preview)), ui.gap(16));
        LinearLayout prev = ui.card();
        prev.addView(ui.text(getString(R.string.appearance_preview_title), 17, t.on, true));
        prev.addView(ui.text(getString(R.string.appearance_preview_body), 13, t.on2, false), ui.gap(2));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.rightMargin = ui.dp(8);
        btns.addView(ui.button(getString(R.string.appearance_run), Ui.Kind.FILLED, v -> { }), bp);
        btns.addView(ui.button(getString(R.string.appearance_add), Ui.Kind.TONAL, v -> { }), bp);
        btns.addView(ui.button(getString(R.string.appearance_stop), Ui.Kind.DANGER, v -> { }));
        prev.addView(btns, ui.gap(12));
        col.addView(prev, ui.gap(4));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(t.bg);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
    }
}
