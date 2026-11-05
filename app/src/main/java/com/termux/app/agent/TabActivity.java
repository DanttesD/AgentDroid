package com.termux.app.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;

/** A top-level screen: big title, gear (appearance), scrolling content, bottom navigation. */
public abstract class TabActivity extends Activity {
    protected Ui ui;
    private LinearLayout content;

    protected abstract int tab();
    protected abstract int titleRes();
    /** Fills the content column. Called again by {@link #refresh()}. */
    protected abstract void fill(LinearLayout content);
    /** Optional floating action button. */
    protected TextView fab() { return null; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppTheme.apply(this);
        themeSig = AppTheme.signature(this);
        ui = new Ui(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.t.bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(20), ui.dp(28), ui.dp(8), ui.dp(4));
        TextView title = ui.title(getString(titleRes()));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(ui.iconButton(R.drawable.ic_gear, ui.t.on, getString(R.string.nav_settings),
                v -> startActivity(new Intent(this, AppearanceActivity.class))));
        root.addView(head);

        ScrollView sv = new ScrollView(this);
        content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16));
        sv.addView(content);
        android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        body.addView(sv, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView fab = fab();
        if (fab != null) {
            android.widget.FrameLayout.LayoutParams fp = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(56), Gravity.END | Gravity.BOTTOM);
            fp.setMargins(0, 0, ui.dp(16), ui.dp(16));
            body.addView(fab, fp);
        }
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(NavBar.build(this, tab()));
        setContentView(root);
    }

    private String themeSig = "";

    @Override protected void onResume() {
        super.onResume();
        if (!themeSig.equals(AppTheme.signature(this))) { recreate(); return; }
        refresh();
    }

    protected void refresh() {
        content.removeAllViews();
        fill(content);
    }

    /** A card row: title, subtitle, chevron; opens an activity. */
    protected LinearLayout row(CharSequence name, CharSequence sub, Class<?> target) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.dp(56));
        LinearLayout texts = ui.column();
        texts.addView(ui.text(name, 15, ui.t.on, true));
        if (sub != null && sub.length() > 0) texts.addView(ui.text(sub, 12.5f, ui.t.on2, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (target != null) {
            r.addView(ui.icon(R.drawable.ic_next, 20, ui.t.on2));
            r.setClickable(true);
            r.setOnClickListener(v -> startActivity(new Intent(this, target)));
        }
        return r;
    }

    protected void open(Class<?> target) { startActivity(new Intent(this, target)); }

    protected void openWorkspace(String screen) {
        Intent i = new Intent(this, com.termux.app.TermuxActivity.class);
        if (screen != null) i.putExtra("agentdroid_screen", screen);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    protected void copy(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("agentdroid", text));
        android.widget.Toast.makeText(this, R.string.mcp_copied, android.widget.Toast.LENGTH_SHORT).show();
    }

    protected void addRows(LinearLayout card, LinearLayout... rows) {
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) card.addView(ui.divider());
            card.addView(rows[i]);
        }
    }
}
