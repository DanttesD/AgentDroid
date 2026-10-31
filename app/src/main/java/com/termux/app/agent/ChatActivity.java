package com.termux.app.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;

import com.agentdroid.agent.AgentLoop;
import com.agentdroid.agent.Approver;
import com.agentdroid.agent.StreamListener;
import com.agentdroid.agent.ToolCall;
import com.agentdroid.agent.Tools;
import com.agentdroid.exec.BackendSelector;
import com.agentdroid.exec.ExecBackend;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Chat with the agent. Programmatic UI, no layout resources. */
public class ChatActivity extends Activity {
    private static final int BG = Color.parseColor("#121418");
    private static final int PANEL = Color.parseColor("#1c1f26");
    private static final int FG = Color.parseColor("#e6e8ee");
    private static final int DIM = Color.parseColor("#8b93a7");
    private static final int ACCENT = Color.parseColor("#7aa2ff");

    private AgentConfig config;
    private BackendSelector selector;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private Button sendBtn;
    private TextView backendLabel;
    private volatile AgentLoop loop;
    private volatile boolean busy;
    private TextView currentAssistant;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        config = new AgentConfig(this);
        selector = AgentBackends.create(this);
        String pin = config.backendPin();
        if (!pin.isEmpty()) { try { selector.pin(pin); } catch (Exception ignored) { } }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(10);
        root.setPadding(pad, dp(28), pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("AgentDroid", 18, FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button backendBtn = button("Backend");
        backendBtn.setOnClickListener(v -> pickBackend());
        bar.addView(backendBtn);
        Button settingsBtn = button("Settings");
        settingsBtn.setOnClickListener(v -> showSettings());
        bar.addView(settingsBtn);
        root.addView(bar);

        backendLabel = text("", 12, DIM);
        root.addView(backendLabel);

        scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(DIM);
        input.setHint("Message the agent…");
        input.setBackgroundColor(PANEL);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5);
        input.setPadding(pad, pad, pad, pad);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = button("Send");
        sendBtn.setOnClickListener(v -> onSend());
        row.addView(sendBtn);
        root.addView(row);
        setContentView(root);

        refreshBackendLabel();
        addNote("Pick a provider and API key in Settings, then write a task. Shell and file writes ask for your approval.");
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String s) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setTextColor(FG);
        bt.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        bt.setLayoutParams(lp);
        return bt;
    }

    private TextView bubble(String s, int color, int bg) {
        TextView t = text(s, 15, color);
        t.setBackgroundColor(bg);
        int p = dp(10);
        t.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        messages.addView(t);
        scrollDown();
        return t;
    }

    private void addNote(String s) { bubble(s, DIM, BG); }

    private void scrollDown() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    private void refreshBackendLabel() {
        final BackendSelector sel = selector;
        new Thread(() -> {
            ExecBackend b = sel.select();
            String pin = sel.pinned();
            String s = (b == null)
                    ? (pin != null ? "Pinned backend '" + pin + "' is not available" : "No backend available (enable root, local ADB or install Termux)")
                    : "Backend: " + b.displayName() + (pin != null ? " (pinned)" : " (auto)");
            runOnUiThread(() -> backendLabel.setText(s + "   |   " + config.provider() + " / " + config.model()));
        }, "backend-probe").start();
    }

    private void pickBackend() {
        final String[] ids = new String[selector.all().size() + 1];
        final String[] names = new String[ids.length];
        ids[0] = ""; names[0] = "Automatic (first available)";
        for (int i = 0; i < selector.all().size(); i++) {
            ids[i + 1] = selector.all().get(i).id();
            names[i + 1] = selector.all().get(i).displayName();
        }
        new AlertDialog.Builder(this).setTitle("Where commands run").setItems(names, (d, which) -> {
            config.setBackendPin(ids[which]);
            selector.pin(ids[which].isEmpty() ? null : ids[which]);
            loop = null;
            refreshBackendLabel();
        }).show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, 0);
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, AgentConfig.PROVIDER_LABELS));
        int cur = 0;
        for (int i = 0; i < AgentConfig.PROVIDERS.length; i++) if (AgentConfig.PROVIDERS[i].equals(config.provider())) cur = i;
        sp.setSelection(cur);
        box.addView(sp);
        final EditText url = field("Base URL", false);
        final EditText model = field("Model", false);
        final EditText key = field("API key (stored encrypted)", true);
        box.addView(url); box.addView(model); box.addView(key);
        final int[] shown = {cur};
        fill(url, model, key);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shown[0]) return;
                config.setProvider(AgentConfig.PROVIDERS[pos]);
                shown[0] = pos;
                fill(url, model, key);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        new AlertDialog.Builder(this).setTitle("Model provider").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    config.setProvider(AgentConfig.PROVIDERS[sp.getSelectedItemPosition()]);
                    config.setBaseUrl(url.getText().toString().trim());
                    config.setModel(model.getText().toString().trim());
                    String k = key.getText().toString().trim();
                    if (!k.isEmpty()) config.setApiKey(k);
                    loop = null;
                    refreshBackendLabel();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void fill(EditText url, EditText model, EditText key) {
        url.setText(config.baseUrl());
        model.setText(config.model());
        key.setText("");
        key.setHint(config.apiKey().isEmpty() ? "API key (not set)" : "API key (saved — leave empty to keep)");
    }

    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private AgentLoop ensureLoop() {
        AgentLoop l = loop;
        if (l != null) return l;
        ExecBackend backend = selector.select();
        if (backend == null) return null;
        final Approver approver = (call, description) -> askUser(description);
        String system = "You are AgentDroid, an agent running on an Android phone. You act through tools: shell, read_file, "
                + "write_file, list_dir. Commands run through: " + backend.displayName() + ". Be concise, check results, "
                + "and explain what you did.";
        l = new AgentLoop(config.build(), Tools.standard(backend, AgentBackends.TERMUX_HOME), approver, system, 25);
        loop = l;
        return l;
    }

    /** Blocks the agent thread until the user answers. */
    private boolean askUser(final String description) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        runOnUiThread(() -> new AlertDialog.Builder(ChatActivity.this)
                .setTitle("Allow this action?").setMessage(description).setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> { ok.set(true); latch.countDown(); })
                .setNegativeButton("Deny", (d, w) -> latch.countDown()).show());
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok.get();
    }

    private void onSend() {
        if (busy) { AgentLoop l = loop; if (l != null) l.cancel(); return; }
        final String msg = input.getText().toString().trim();
        if (msg.isEmpty()) return;
        if (config.apiKey().isEmpty() && !config.provider().equals("custom")) { showSettings(); return; }
        input.setText("");
        bubble(msg, FG, PANEL);
        busy = true;
        sendBtn.setText("Stop");
        currentAssistant = null;
        new Thread(() -> {
            try {
                AgentLoop l = ensureLoop();
                if (l == null) { runOnUiThread(() -> addNote("No backend available. Choose one under Backend.")); return; }
                l.send(msg, new StreamListener() {
                    @Override public void onText(final String delta) {
                        runOnUiThread(() -> {
                            if (currentAssistant == null) currentAssistant = bubble("", ACCENT, BG);
                            currentAssistant.append(delta);
                            scrollDown();
                        });
                    }
                    @Override public void onToolCall(final ToolCall call) {
                        runOnUiThread(() -> { currentAssistant = null; addNote("▶ " + call.name + " " + com.agentdroid.agent.Json.stringify(call.args)); });
                    }
                    @Override public void onToolResult(ToolCall call, final String result, final boolean error) {
                        runOnUiThread(() -> {
                            String shown = result.length() > 1500 ? result.substring(0, 1500) + "\n…" : result;
                            addNote((error ? "✖ " : "✔ ") + shown);
                        });
                    }
                    @Override public void onError(final String m) { runOnUiThread(() -> addNote("⚠ " + m)); }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> addNote("⚠ " + e.getMessage()));
            } finally {
                runOnUiThread(() -> { busy = false; sendBtn.setText("Send"); currentAssistant = null; });
            }
        }, "agent-loop").start();
    }
}
