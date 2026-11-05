package com.termux.app.agent;

import android.content.Intent;
import android.widget.LinearLayout;

import com.termux.R;
import com.termux.app.AdbConsoleActivity;
import com.termux.app.TermuxActivity;

/** Where commands run and the tools around it: terminal, ADB, workspace (files, desktop, servers, packages). */
public final class EnvActivity extends TabActivity {
    @Override protected int tab() { return NavBar.ENV; }
    @Override protected int titleRes() { return R.string.nav_env; }

    @Override protected void fill(LinearLayout c) {
        LinearLayout card = ui.card();
        addRows(card,
                row(getString(R.string.env_terminal), getString(R.string.env_terminal_sub), TermuxActivity.class),
                row(getString(R.string.env_workspace), getString(R.string.env_workspace_sub), TermuxActivity.class),
                row(getString(R.string.env_adb), getString(R.string.env_adb_sub), AdbConsoleActivity.class),
                row(getString(R.string.env_web), getString(R.string.env_web_sub), WebBrowserActivity.class));
        c.addView(card);
        LinearLayout info = ui.card();
        info.addView(ui.text(getString(R.string.env_where), 15, ui.t.on, true));
        info.addView(ui.text(getString(R.string.env_where_sub), 12.5f, ui.t.on2, false), ui.gap(2));
        c.addView(info, ui.gap(14));
    }
}
