package com.termux.app.agent;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agents and their accounts in one place. */
public final class AgentsActivity extends TabActivity {
    @Override protected int tab() { return NavBar.AGENTS; }
    @Override protected int titleRes() { return R.string.nav_agents; }

    @Override protected TextView fab() {
        TextView f = ui.text("+  " + getString(R.string.agents_fab), 15, ui.t.onp, true);
        f.setGravity(Gravity.CENTER);
        f.setPadding(ui.dp(22), 0, ui.dp(22), 0);
        f.setBackground(ui.shape(ui.t.p, 20, 0, 0));
        f.setClickable(true);
        f.setOnClickListener(v -> open(AccountsActivity.class));
        return f;
    }

    private LinearLayout sessionCard(String name, String line, boolean live, View.OnClickListener l, String... chips) {
        LinearLayout card = ui.card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(ui.shape(live ? ui.t.ok : ui.t.on2, 5, 0, 0));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10));
        dl.rightMargin = ui.dp(10);
        top.addView(dot, dl);
        top.addView(ui.text(name, 16, ui.t.on, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(ui.text(getString(live ? R.string.agents_ready : R.string.agents_setup), 12.5f, live ? ui.t.ok : ui.t.on2, true));
        card.addView(top);
        card.addView(ui.text(line, 13, ui.t.on2, false), ui.gap(8));
        LinearLayout row = new LinearLayout(this);
        for (String c : chips) {
            TextView ch = ui.text(c, 12, ui.t.on, false);
            ch.setGravity(Gravity.CENTER_VERTICAL);
            ch.setPadding(ui.dp(10), 0, ui.dp(10), 0);
            ch.setBackground(ui.shape(ui.t.s2, 13, 0, 0));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(26));
            p.rightMargin = ui.dp(6);
            row.addView(ch, p);
        }
        card.addView(row, ui.gap(10));
        card.setClickable(true);
        card.setOnClickListener(l);
        return card;
    }

    @Override protected void fill(LinearLayout c) {
        AgentConfig cfg = new AgentConfig(this);
        com.agentdroid.exec.BackendSelector sel = AgentBackends.create(this);
        String pin = cfg.backendPin();
        if (!pin.isEmpty()) { try { sel.pin(pin); } catch (Exception ignored) { } }
        com.agentdroid.exec.ExecBackend b = sel.select();
        String model = cfg.model();
        boolean ready = !cfg.apiKey().isEmpty() || cfg.provider().equals("custom");

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.section(getString(R.string.agents_running)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView create = ui.text(getString(R.string.agents_new_session), 13, ui.t.p, true);
        create.setPadding(ui.dp(8), ui.dp(8), ui.dp(4), ui.dp(8));
        create.setOnClickListener(v -> openWorkspace("sessions"));
        head.addView(create);
        c.addView(head);
        c.addView(sessionCard("AgentDroid", (model == null || model.isEmpty() ? getString(R.string.agents_not_set) : model) + " · " + cfg.provider(), ready,
                v -> open(ChatActivity.class), b == null ? "-" : b.displayName().replaceAll(" \\(.*", ""), getString(R.string.agents_with_confirm)), ui.gap(0));
        c.addView(sessionCard(getString(R.string.agents_ws), getString(R.string.agents_ws_line), true,
                v -> openWorkspace("sessions"), "OpenCode", "Claude Code", "Codex", "Gemini"), ui.gap(10));

        c.addView(ui.section(getString(R.string.agents_accounts)), ui.gap(18));
        Map<String, List<AccountsStore.Account>> groups = new LinkedHashMap<>();
        for (AccountsStore.Account a : new AccountsStore(this).all()) {
            List<AccountsStore.Account> l = groups.get(a.agentId);
            if (l == null) { l = new ArrayList<>(); groups.put(a.agentId, l); }
            l.add(a);
        }
        if (groups.isEmpty()) {
            LinearLayout empty = ui.card();
            empty.addView(ui.text(getString(R.string.agents_no_accounts), 13, ui.t.on2, false));
            c.addView(empty);
        }
        for (Map.Entry<String, List<AccountsStore.Account>> e : groups.entrySet()) {
            LinearLayout g = ui.card();
            g.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(6));
            String title = e.getKey().substring(0, 1).toUpperCase() + e.getKey().substring(1);
            g.addView(ui.text(title, 14, ui.t.on, true));
            for (AccountsStore.Account a : e.getValue()) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(ui.dp(52));
                TextView av = ui.text(a.name.substring(0, 1).toUpperCase(), 14, ui.t.on, true);
                av.setGravity(Gravity.CENTER);
                av.setBackground(ui.shape(ui.t.s3, 17, 0, 0));
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34));
                al.rightMargin = ui.dp(12);
                row.addView(av, al);
                LinearLayout t = ui.column();
                t.addView(ui.text(a.name, 14.5f, ui.t.on, true));
                t.addView(ui.text(getString(R.string.agents_own_profile), 12.5f, ui.t.on2, false));
                row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView how = ui.text(getString(R.string.agents_how_browser), 12, ui.t.onpc, true);
                how.setGravity(Gravity.CENTER);
                how.setPadding(ui.dp(10), 0, ui.dp(10), 0);
                how.setBackground(ui.shape(ui.t.pc, 13, 0, 0));
                row.addView(how, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ui.dp(26)));
                g.addView(row);
            }
            c.addView(g, ui.gap(10));
        }
        View pad = new View(this);
        c.addView(pad, new LinearLayout.LayoutParams(1, ui.dp(72)));
    }
}
