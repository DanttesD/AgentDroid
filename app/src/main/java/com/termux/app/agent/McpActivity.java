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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#121418"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, dp(30), p, p);
        sv.addView(root);

        TextView title = tv(getString(R.string.mcp_title), 18, Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(tv(getString(R.string.mcp_intro), 14, Color.parseColor("#b6bccb")));
        status = tv("", 15, Color.parseColor("#7aa2ff"));
        root.addView(status);

        Button start = btn(getString(R.string.mcp_start));
        start.setOnClickListener(v -> { McpService.start(this); v.postDelayed(this::refresh, 800); });
        Button stop = btn(getString(R.string.mcp_stop));
        stop.setOnClickListener(v -> { McpService.stop(this); v.postDelayed(this::refresh, 500); });
        final Button approval = btn("");
        approval.setOnClickListener(v -> {
            ApprovalCenter.setMode(this, (ApprovalCenter.mode(this) + 1) % 3);
            approval.setText(approvalLabel());
        });
        approval.setText(approvalLabel());
        root.addView(approval);
        root.addView(start);
        root.addView(stop);

        local = tv("", 13, Color.parseColor("#e6e8ee"));
        root.addView(local);
        Button copy = btn(getString(R.string.mcp_copy));
        copy.setOnClickListener(v -> copy(McpHost.localUrl(this)));
        root.addView(copy);
        Button renew = btn(getString(R.string.mcp_new_token));
        renew.setOnClickListener(v -> { McpHost.newToken(this); McpService.stop(this); v.postDelayed(this::refresh, 500); });
        root.addView(renew);

        TextView tt = tv(getString(R.string.mcp_tunnel_title), 16, Color.WHITE);
        tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(tt);
        tunnel = tv("", 13, Color.parseColor("#b6bccb"));
        root.addView(tunnel);
        Button term = btn(getString(R.string.mcp_open_terminal));
        term.setOnClickListener(v -> startActivity(new Intent(this, TermuxActivity.class)));
        root.addView(term);
        Button cmd = btn(getString(R.string.mcp_copy_tunnel_cmd));
        cmd.setOnClickListener(v -> copy("pkg install -y cloudflared && cloudflared tunnel --url http://127.0.0.1:" + McpHost.port(this)));
        root.addView(cmd);
        Button claude = btn(getString(R.string.mcp_open_claude));
        claude.setOnClickListener(v -> BrowserPicker.show(this, "https://claude.ai"));
        root.addView(claude);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); ApprovalCenter.onResume(this); refresh(); }

    @Override protected void onPause() { ApprovalCenter.onPause(this); super.onPause(); }

    private String approvalLabel() {
        int m = ApprovalCenter.mode(this);
        return getString(m == 0 ? R.string.mcp_approval_ask : m == 1 ? R.string.mcp_approval_hour : R.string.mcp_approval_always);
    }

    private void refresh() {
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

    private Button btn(String s) {
        Button bt = new Button(this);
        bt.setText(s); bt.setAllCaps(false); bt.setTextColor(Color.WHITE); bt.setBackgroundColor(Color.parseColor("#1c1f26"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        bt.setLayoutParams(lp);
        return bt;
    }
}
