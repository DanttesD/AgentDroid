package com.termux.app.agent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;

/** Controls the MCP server that lets claude.ai, Claude Code, Codex, OpenCode… use the phone. */
public class McpActivity extends Activity {
    private TextView status;
    private TextView local;
    private TextView tunnel;

    private com.agentdroid.agent.ThemeColors th;

    private String themeSig = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        th = AppTheme.apply(this);
        themeSig = AppTheme.signature(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(th.bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, dp(30), p, p);
        sv.addView(root);

        final Ui ui = new Ui(this);
        LinearLayout cur = root;
        TextView title = tv(getString(R.string.mcp_title), 26, th.on);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(tv(getString(R.string.mcp_intro), 14, th.on2));
        cur = ui.card();
        root.addView(cur, ui.gap(14));
        cur.addView(ui.text(getString(R.string.mcp_card_server), 16, th.on, true));
        status = tv("", 15, th.p);
        cur.addView(status);

        TextView start = btn(getString(R.string.mcp_start));
        start.setOnClickListener(v -> { McpService.start(this); v.postDelayed(this::refresh, 800); });
        TextView stop = btn(getString(R.string.mcp_stop));
        stop.setOnClickListener(v -> { McpService.stop(this); v.postDelayed(this::refresh, 500); });
        final TextView approval = btn("");
        approval.setOnClickListener(v -> {
            ApprovalCenter.setMode(this, (ApprovalCenter.mode(this) + 1) % 3);
            approval.setText(approvalLabel());
        });
        approval.setText(approvalLabel());
        cur.addView(approval);
        cur.addView(start);
        cur.addView(stop);

        final TextView stopAgent = btn("");
        stopAgent.setOnClickListener(v -> {
            if (McpHost.isRunning()) startService(new Intent(this, McpService.class).setAction(McpService.ACTION_PAUSE));
            else ActionGuard.setPaused(!ActionGuard.isPaused());
            v.postDelayed(() -> stopAgent.setText(stopLabel()), 300);
        });
        stopAgent.setText(stopLabel());
        cur.addView(stopAgent);

        cur = ui.card();
        root.addView(cur, ui.gap(14));
        cur.addView(ui.text(getString(R.string.mcp_security_title), 16, th.on, true));
        a11yState = tv("", 14, th.p);
        cur.addView(a11yState);
        TextView a11y = btn(getString(R.string.mcp_a11y_open));
        a11y.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        cur.addView(a11y);
        TextView a11yAuto = btn(getString(R.string.mcp_a11y_auto));
        a11yAuto.setOnClickListener(v -> enableA11yViaShell());
        cur.addView(a11yAuto);
        TextView awake = btn(getString(R.string.mcp_keepalive));
        awake.setOnClickListener(v -> keepAliveViaShell());
        cur.addView(awake);
        TextView blocked = btn(getString(R.string.mcp_blocked_apps));
        blocked.setOnClickListener(v -> editBlocked());
        cur.addView(blocked);
        TextView logBtn = btn(getString(R.string.mcp_action_log));
        logBtn.setOnClickListener(v -> showLog());
        cur.addView(logBtn);
        final TextView boot = btn("");
        boot.setOnClickListener(v -> { BootReceiver.setMode(this, (BootReceiver.mode(this) + 1) % 3); boot.setText(bootLabel()); });
        boot.setText(bootLabel());
        cur.addView(boot);

        cur = ui.card();
        root.addView(cur, ui.gap(14));
        cur.addView(ui.text(getString(R.string.mcp_card_address), 16, th.on, true));
        local = tv("", 13, th.on);
        cur.addView(local);
        TextView copy = btn(getString(R.string.mcp_copy));
        copy.setOnClickListener(v -> copy(McpHost.localUrl(this)));
        cur.addView(copy);
        TextView renew = btn(getString(R.string.mcp_new_token));
        renew.setOnClickListener(v -> { McpHost.newToken(this); McpService.stop(this); v.postDelayed(this::refresh, 500); });
        cur.addView(renew);

        tunnelState = tv("", 14, th.p);
        final TextView tunBtn = btn("");
        tunBtn.setOnClickListener(v -> {
            if (TunnelManager.isRunning()) TunnelManager.stop();
            else { if (!McpHost.isRunning()) McpService.start(this); TunnelManager.start(this, McpHost.port(this)); }
            refresh();
        });
        tunnelButton = tunBtn;
        TextView copyPublic = btn(getString(R.string.mcp_copy_public));
        copyPublic.setOnClickListener(v -> {
            if (TunnelManager.url().isEmpty()) toast(R.string.mcp_no_public);
            else copy(TunnelManager.url() + "/mcp/" + McpHost.token(this));
        });
        cur.addView(tunBtn);
        cur.addView(tunnelState);
        TextView ngrokSetup = btn(getString(R.string.mcp_ngrok_setup));
        ngrokSetup.setOnClickListener(v -> askNgrok());
        TextView ngrokInstall = btn(getString(R.string.mcp_ngrok_install));
        ngrokInstall.setOnClickListener(v -> TermuxSessions.run(this, "ngrok", TunnelManager.ngrokInstallScript(), BrowserPicker.SLOTS[0]));
        TextView ngrokSite = btn(getString(R.string.mcp_ngrok_site));
        ngrokSite.setOnClickListener(v -> BrowserPicker.show(this, "https://dashboard.ngrok.com/get-started/your-authtoken"));
        cur.addView(tv(getString(R.string.mcp_ngrok_hint), 13, th.on2));
        cur.addView(ngrokSite);
        cur.addView(ngrokInstall);
        cur.addView(ngrokSetup);
        cur.addView(copyPublic);

        cur = ui.card();
        root.addView(cur, ui.gap(14));
        cur.addView(ui.text(getString(R.string.mcp_tunnel_title), 16, th.on, true));
        tunnel = tv("", 13, th.on2);
        cur.addView(tunnel);
        TextView term = btn(getString(R.string.mcp_open_terminal));
        term.setOnClickListener(v -> startActivity(new Intent(this, TermuxActivity.class)));
        cur.addView(term);
        TextView cmd = btn(getString(R.string.mcp_copy_tunnel_cmd));
        cmd.setOnClickListener(v -> copy("pkg install -y cloudflared && cloudflared tunnel --url http://127.0.0.1:" + McpHost.port(this)));
        cur.addView(cmd);
        TextView claude = btn(getString(R.string.mcp_open_claude));
        claude.setOnClickListener(v -> BrowserPicker.show(this, "https://claude.ai"));
        cur.addView(claude);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); if (!themeSig.equals(AppTheme.signature(this))) { recreate(); return; } ApprovalCenter.onResume(this); refresh(); }

    @Override protected void onPause() { if (tunnelState != null) tunnelState.removeCallbacks(tick); ApprovalCenter.onPause(this); super.onPause(); }

    private String approvalLabel() {
        int m = ApprovalCenter.mode(this);
        return getString(m == 0 ? R.string.mcp_approval_ask : m == 1 ? R.string.mcp_approval_hour : R.string.mcp_approval_always);
    }

    private void askNgrok() {
        final android.widget.EditText token = new android.widget.EditText(this);
        token.setHint(R.string.mcp_ngrok_token);
        token.setSingleLine(true);
        final android.widget.EditText domain = new android.widget.EditText(this);
        domain.setHint(R.string.mcp_ngrok_domain);
        domain.setSingleLine(true);
        domain.setText(TunnelManager.ngrokDomain(this));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 10, 40, 0);
        box.addView(token);
        box.addView(domain);
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.mcp_ngrok_setup).setView(box)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    String t = token.getText().toString().trim();
                    if (t.isEmpty() && TunnelManager.ngrokConfigured(this)) t = new SecretStore(this).get("ngrok_token");
                    TunnelManager.saveNgrok(this, t, domain.getText().toString());
                    TunnelManager.stop();
                    refresh();
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    private TextView a11yState;

    private String stopLabel() { return getString(ActionGuard.isPaused() ? R.string.mcp_resume : R.string.mcp_stop_now); }

    private String bootLabel() {
        int m = BootReceiver.mode(this);
        return getString(m == 0 ? R.string.mcp_boot_off : m == 1 ? R.string.mcp_boot_server : R.string.mcp_boot_tunnel);
    }

    private void enableA11yViaShell() {
        final String comp = getPackageName() + "/" + AgentAccessibilityService.class.getName();
        final Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                com.agentdroid.exec.BackendSelector sel = AgentBackends.create(app);
                String pin = new AgentConfig(app).backendPin();
                if (!pin.isEmpty()) { try { sel.pin(pin); } catch (Exception ignored) { } }
                SelectedBackend b = new SelectedBackend(sel);
                String cmd = "cur=$(settings get secure enabled_accessibility_services); "
                        + "case \"$cur\" in *" + comp + "*) ;; ''|null) settings put secure enabled_accessibility_services " + comp + " ;; "
                        + "*) settings put secure enabled_accessibility_services \"$cur:" + comp + "\" ;; esac; "
                        + "settings put secure accessibility_enabled 1";
                com.agentdroid.exec.ExecResult r = b.exec(com.agentdroid.exec.ExecRequest.builder(cmd).timeoutMs(15000L).build());
                msg = r.ok() ? getString(R.string.mcp_a11y_done) : getString(R.string.mcp_a11y_fail, r.stderr().trim());
            } catch (Exception e) {
                msg = getString(R.string.mcp_a11y_fail, String.valueOf(e.getMessage()));
            }
            final String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); refresh(); });
        }, "a11y-enable").start();
    }

    /** Exempts the app from Doze and background restrictions so the shell, server and tunnel survive a switched-off screen. */
    private void keepAliveViaShell() {
        final String pkg = getPackageName();
        final Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                com.agentdroid.exec.BackendSelector sel = AgentBackends.create(app);
                String pin = new AgentConfig(app).backendPin();
                if (!pin.isEmpty()) { try { sel.pin(pin); } catch (Exception ignored) { } }
                SelectedBackend b = new SelectedBackend(sel);
                String cmd = "dumpsys deviceidle whitelist +" + pkg + "; cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                        + "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; am set-standby-bucket " + pkg + " active";
                com.agentdroid.exec.ExecResult r = b.exec(com.agentdroid.exec.ExecRequest.builder(cmd).timeoutMs(15000L).build());
                msg = r.ok() ? getString(R.string.mcp_keepalive_done) : getString(R.string.mcp_a11y_fail, r.stderr().trim());
            } catch (Exception e) {
                msg = getString(R.string.mcp_a11y_fail, String.valueOf(e.getMessage()));
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }, "keepalive").start();
    }

    private void editBlocked() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setMinLines(6);
        et.setGravity(android.view.Gravity.TOP);
        et.setText(ActionGuard.blocked(this));
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.mcp_blocked_apps).setMessage(R.string.mcp_blocked_hint).setView(et)
                .setPositiveButton(R.string.agent_save, (d, w) -> ActionGuard.setBlocked(this, et.getText().toString()))
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    private void showLog() {
        String log = ActionGuard.readLog(this);
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.mcp_action_log)
                .setMessage(log.isEmpty() ? getString(R.string.mcp_log_empty) : log)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.mcp_log_clear, (d, w) -> ActionGuard.clearLog(this)).show();
    }

    private TextView tunnelState;
    private TextView tunnelButton;
    private final Runnable tick = this::refresh;

    private void toast(int res) { android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show(); }

    private void refresh() {
        tunnelButton.setText(TunnelManager.isRunning() ? R.string.mcp_tunnel_stop : R.string.mcp_tunnel_start);
        String st;
        if (!TunnelManager.error().isEmpty()) st = getString(R.string.mcp_tunnel_err, TunnelManager.error());
        else if (!TunnelManager.isRunning()) st = getString(R.string.mcp_tunnel_off) + (TunnelManager.ngrokConfigured(this)
                ? "\n" + getString(TunnelManager.ngrokInstalled() ? R.string.mcp_ngrok_ready : R.string.mcp_ngrok_need_install, TunnelManager.ngrokDomain(this))
                : "");
        else if (TunnelManager.isConnected()) st = getString(R.string.mcp_tunnel_ok, TunnelManager.url())
                + (TunnelManager.note().isEmpty() ? "" : "\n" + getString(R.string.mcp_tunnel_fallback, TunnelManager.note()));
        else st = getString(R.string.mcp_tunnel_wait);
        tunnelState.setText(st);
        tunnelState.removeCallbacks(tick);
        if (TunnelManager.isRunning()) tunnelState.postDelayed(tick, 1500);
        if (a11yState != null) a11yState.setText(getString(AgentAccessibilityService.isRunning() ? R.string.mcp_a11y_on : R.string.mcp_a11y_off));
        boolean on = McpHost.isRunning();
        status.setText(on ? getString(R.string.mcp_status_on, McpHost.port(this)) : getString(R.string.mcp_status_off));
        local.setText(getString(R.string.mcp_local_url, McpHost.localUrl(this)));
        tunnel.setText(getString(R.string.mcp_tunnel_text, McpHost.port(this), "/mcp/" + McpHost.token(this)));
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("agentdroid", s));
        Toast.makeText(this, R.string.mcp_copied, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTextIsSelectable(true);
        t.setPadding(0, dp(8), 0, dp(4));
        return t;
    }

    private TextView btn(String s) {
        TextView bt = new Ui(this).button(s, Ui.Kind.TONAL, null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        bt.setLayoutParams(lp);
        return bt;
    }
}
