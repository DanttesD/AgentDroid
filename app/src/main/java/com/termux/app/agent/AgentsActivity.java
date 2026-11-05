package com.termux.app.agent;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Agents and their accounts in one place: the built-in agent, external agents (via the workspace), web accounts, extra accounts. */
public final class AgentsActivity extends TabActivity {
    @Override protected int tab() { return NavBar.AGENTS; }
    @Override protected int titleRes() { return R.string.nav_agents; }

    @Override protected void fill(LinearLayout c) {
        AgentConfig cfg = new AgentConfig(this);
        LinearLayout built = ui.card();
        built.addView(ui.text(getString(R.string.agents_builtin), 16, ui.t.on, true));
        String model = cfg.model();
        built.addView(ui.text(model == null || model.isEmpty() ? getString(R.string.agents_not_set) : model, 13, ui.t.on2, false), ui.gap(2));
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.rightMargin = ui.dp(8);
        btns.addView(ui.button(getString(R.string.agents_open_chat), Ui.Kind.FILLED, v -> startActivity(new Intent(this, ChatActivity.class))), bp);
        btns.addView(ui.button(getString(R.string.agents_workspace), Ui.Kind.TONAL, v -> startActivity(new Intent(this, TermuxActivity.class))));
        built.addView(btns, ui.gap(12));
        c.addView(ui.section(getString(R.string.agents_running)));
        c.addView(built);

        c.addView(ui.section(getString(R.string.agents_accounts)), ui.gap(14));
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (AccountsStore.Account a : new AccountsStore(this).all()) {
            List<String> l = groups.get(a.agentId);
            if (l == null) { l = new ArrayList<>(); groups.put(a.agentId, l); }
            l.add(a.name);
        }
        if (groups.isEmpty()) {
            LinearLayout empty = ui.card();
            empty.addView(ui.text(getString(R.string.agents_no_accounts), 13, ui.t.on2, false));
            c.addView(empty);
        }
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            LinearLayout g = ui.card();
            g.addView(ui.text(e.getKey(), 14, ui.t.on, true));
            for (String n : e.getValue()) g.addView(ui.text(n, 14.5f, ui.t.on2, false), ui.gap(6));
            c.addView(g, ui.gap(10));
        }
        TextView add = ui.button(getString(R.string.agents_manage), Ui.Kind.TONAL, v -> startActivity(new Intent(this, AccountsActivity.class)));
        c.addView(add, ui.gap(14));
    }
}
