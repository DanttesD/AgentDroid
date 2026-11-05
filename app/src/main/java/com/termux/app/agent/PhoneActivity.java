package com.termux.app.agent;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;

/** Everything the agent needs to control the phone: readiness, screen control, assistant, rules, MCP, emergency stop. */
public final class PhoneActivity extends TabActivity {
    @Override protected int tab() { return NavBar.PHONE; }
    @Override protected int titleRes() { return R.string.nav_phone; }

    private boolean notificationsOn() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    @Override protected void fill(LinearLayout c) {
        boolean screen = AgentAccessibilityService.isRunning();
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notif = notificationsOn();
        boolean[] ok = {screen, overlay, mic, notif};
        String[] names = {getString(R.string.phone_chk_screen), getString(R.string.phone_chk_overlay),
                getString(R.string.phone_chk_mic), getString(R.string.phone_chk_notif)};
        int done = 0;
        for (boolean o : ok) if (o) done++;

        LinearLayout hero = ui.column();
        hero.setBackground(ui.shape(ui.t.pc, 24, 0, 0));
        hero.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        LinearLayout top = new LinearLayout(this);
        top.addView(ui.text(getString(done == 4 ? R.string.phone_ready : R.string.phone_not_ready), 18, ui.t.onpc, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(ui.text(getString(R.string.phone_count, done, 4), 13, ui.t.onpc, true));
        hero.addView(top);
        LinearLayout bars = new LinearLayout(this);
        for (int i = 0; i < 4; i++) {
            LinearLayout col = ui.column();
            android.view.View bar = new android.view.View(this);
            bar.setBackground(ui.shape(ok[i] ? ui.t.p : ui.t.s3, 3, 0, 0));
            col.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(6)));
            col.addView(ui.text(names[i], 11.5f, ui.t.onpc, false), ui.gap(6));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.rightMargin = i < 3 ? ui.dp(6) : 0;
            bars.addView(col, p);
        }
        hero.addView(bars, ui.gap(12));
        c.addView(hero);

        final LinearLayout card = ui.card();
        boolean running = AssistantOverlayService.isRunning();
        LinearLayout assistant = ui.iconRow(R.drawable.ic_sparkle, getString(R.string.phone_assistant), getString(R.string.phone_assistant_sub),
                ui.toggle(running, on -> {
                    if (on && !overlay) { open(AssistantActivity.class); return; }
                    if (on) AssistantOverlayService.start(this); else AssistantOverlayService.stop(this);
                    card.postDelayed(this::refresh, 600);
                }), null);
        LinearLayout screenRow = ui.iconRow(R.drawable.ic_screen, getString(R.string.phone_screen),
                getString(screen ? R.string.phone_screen_on : R.string.phone_screen_off),
                ui.toggle(screen, null), v -> startActivity(new android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        screenRow.getChildAt(2).setClickable(false);
        LinearLayout rules = ui.iconRow(R.drawable.ic_bell, getString(R.string.phone_rules), getString(R.string.phone_rules_sub), ui.chevron(), v -> open(TriggersActivity.class));
        LinearLayout sec = ui.iconRow(R.drawable.ic_shield, getString(R.string.phone_security), getString(R.string.phone_security_sub), ui.chevron(), v -> open(McpActivity.class));
        addRows(card, assistant, screenRow, rules, sec);
        c.addView(card, ui.gap(14));

        boolean mcp = McpHost.isRunning();
        boolean tunnel = TunnelManager.isRunning();
        LinearLayout conn = ui.card();
        LinearLayout st = new LinearLayout(this);
        st.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.view.View dot = new android.view.View(this);
        dot.setBackground(ui.shape(mcp ? ui.t.ok : ui.t.on2, 5, 0, 0));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10));
        dl.rightMargin = ui.dp(10);
        st.addView(dot, dl);
        st.addView(ui.text(getString(mcp ? R.string.phone_mcp_working : R.string.phone_mcp_off), 15, ui.t.on, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        st.addView(ui.text(tunnel ? "ngrok" : getString(R.string.phone_mcp_local), 12.5f, ui.t.on2, false));
        conn.addView(st);
        String shown = tunnel ? TunnelManager.url() + "/mcp/\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" : "http://127.0.0.1:" + McpHost.port(this) + "/mcp/\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
        TextView url = ui.text(shown, 12, ui.t.on2, false);
        url.setTypeface(android.graphics.Typeface.MONOSPACE);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        url.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        url.setBackground(ui.shape(ui.t.s2, 12, 0, 0));
        conn.addView(url, ui.gap(10));
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp.rightMargin = ui.dp(8);
        btns.addView(ui.button(getString(R.string.mcp_copy_short), Ui.Kind.TONAL, v ->
                copy(tunnel ? TunnelManager.url() + "/mcp/" + McpHost.token(this) : McpHost.localUrl(this))), bp);
        btns.addView(ui.button(getString(R.string.mcp_new_token_short), Ui.Kind.OUTLINE, v -> { McpHost.newToken(this); McpService.stop(this); v.postDelayed(this::refresh, 600); }),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        conn.addView(btns, ui.gap(12));
        c.addView(conn, ui.gap(14));

        TextView stop = ui.button(getString(ActionGuard.isPaused() ? R.string.mcp_resume : R.string.phone_emergency), Ui.Kind.DANGER, v -> {
            if (McpHost.isRunning()) startService(new android.content.Intent(this, McpService.class).setAction(McpService.ACTION_PAUSE));
            else ActionGuard.setPaused(!ActionGuard.isPaused());
            v.postDelayed(this::refresh, 300);
        });
        stop.setMinHeight(ui.dp(56));
        c.addView(stop, ui.gap(14));
    }
}
