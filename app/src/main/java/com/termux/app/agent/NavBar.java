package com.termux.app.agent;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;

/** Bottom navigation shared by the four top-level screens: Chat, Agents, Phone, Environment. */
public final class NavBar {
    public static final int CHAT = 0, AGENTS = 1, PHONE = 2, ENV = 3;
    private static final String[] ICONS = {"💬", "🤖", "📱", "⌨"};
    private static final int[] LABELS = {R.string.nav_chat, R.string.nav_agents, R.string.nav_phone, R.string.nav_env};
    private static final Class<?>[] TARGETS = {ChatActivity.class, AgentsActivity.class, PhoneActivity.class, EnvActivity.class};

    private NavBar() {}

    public static View build(final Activity a, int active) {
        Ui ui = new Ui(a);
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(ui.t.s1);
        bar.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(10));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean on = i == active;
            LinearLayout item = ui.column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView pill = ui.text(ICONS[i], 18, on ? ui.t.onpc : ui.t.on2, false);
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(ui.shape(on ? ui.t.pc : 0, 16, 0, 0));
            item.addView(pill, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(32)));
            TextView label = ui.text(a.getString(LABELS[i]), 12, on ? ui.t.onpc : ui.t.on2, on);
            label.setGravity(Gravity.CENTER);
            item.addView(label, ui.gap(2));
            item.setClickable(true);
            item.setContentDescription(a.getString(LABELS[i]));
            item.setOnClickListener(v -> go(a, idx, active));
            bar.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private static void go(Activity a, int idx, int active) {
        if (idx == active) return;
        Intent i = new Intent(a, TARGETS[idx]);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
    }
}
