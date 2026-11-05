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
        final AgentConfig cfg = new AgentConfig(this);
        final com.agentdroid.exec.BackendSelector sel = AgentBackends.create(this);
        LinearLayout where = ui.card();
        where.addView(ui.text(getString(R.string.env_where), 15, ui.t.on, true));
        where.addView(ui.text(getString(R.string.env_where_sub), 12.5f, ui.t.on2, false));
        LinearLayout chips = new LinearLayout(this);
        String pin = cfg.backendPin();
        final java.util.List<String> ids = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        ids.add(""); names.add(getString(R.string.agent_backend_auto_item));
        for (com.agentdroid.exec.ExecBackend b : sel.all()) { ids.add(b.id()); names.add(b.displayName()); }
        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            android.widget.TextView ch = ui.chip(names.get(i), id.equals(pin), v -> { cfg.setBackendPin(id); refresh(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = i < ids.size() - 1 ? ui.dp(6) : 0;
            chips.addView(ch, p);
        }
        where.addView(chips, ui.gap(10));
        c.addView(where);

        LinearLayout card = ui.card();
        addRows(card,
                row(getString(R.string.env_terminal), getString(R.string.env_terminal_sub), TermuxActivity.class),
                row(getString(R.string.env_workspace), getString(R.string.env_workspace_sub), TermuxActivity.class),
                row(getString(R.string.env_adb), getString(R.string.env_adb_sub), AdbConsoleActivity.class),
                row(getString(R.string.env_web), getString(R.string.env_web_sub), WebBrowserActivity.class));
        c.addView(card, ui.gap(14));
    }
}
