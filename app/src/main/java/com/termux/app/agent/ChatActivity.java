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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import android.webkit.WebView;
import java.util.ArrayList;
import java.util.List;

import com.agentdroid.agent.AgentLoop;
import com.agentdroid.agent.Approver;
import com.agentdroid.agent.StreamListener;
import com.agentdroid.agent.ToolCall;
import com.agentdroid.agent.Tools;
import com.agentdroid.exec.BackendSelector;
import com.agentdroid.exec.ExecBackend;
import com.termux.R;
import com.termux.app.AdbConsoleActivity;
import com.termux.app.TermuxActivity;
import android.content.Intent;

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
    private LinearLayout nativeBox;
    private FrameLayout webBox;
    private FrameLayout outer;
    private BrowserHelper helper;
    private final java.util.Map<Integer, WebView> webViews = new java.util.HashMap<>();
    private int currentWeb = -1;
    private WebAccounts webAccounts;
    private boolean webMode;

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
        Button settingsBtn = button(getString(R.string.agent_settings));
        settingsBtn.setOnClickListener(v -> showSettings());
        bar.addView(settingsBtn);
        Button menuBtn = button("\u22ee");
        menuBtn.setContentDescription(getString(R.string.agent_menu));
        menuBtn.setOnClickListener(v -> showMenu());
        bar.addView(menuBtn);
        root.addView(bar);

        nativeBox = new LinearLayout(this);
        nativeBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(nativeBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webBox = new FrameLayout(this);
        webBox.setVisibility(View.GONE);
        root.addView(webBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        backendLabel = text("", 12, DIM);
        nativeBox.addView(backendLabel);

        scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages);
        nativeBox.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(DIM);
        input.setHint(R.string.agent_hint);
        input.setBackgroundColor(PANEL);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5);
        input.setPadding(pad, pad, pad, pad);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = button(getString(R.string.agent_send));
        sendBtn.setOnClickListener(v -> onSend());
        row.addView(sendBtn);
        nativeBox.addView(row);
        outer = new FrameLayout(this);
        outer.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(outer);
        helper = new BrowserHelper(this, outer, null);
        webAccounts = new WebAccounts(this, getString(R.string.agent_web_default));
        if (getSharedPreferences("agentdroid_agent", MODE_PRIVATE).getBoolean("web_mode", false)) {
            int id = getSharedPreferences("agentdroid_agent", MODE_PRIVATE).getInt("web_acc", -1);
            WebAccounts.Account acc = find(webAccounts.accounts(), id);
            if (acc != null && !acc.url.isEmpty()) openWebAccount(acc); else setWebMode(false);
        }

        refreshBackendLabel();
        addNote(getString(R.string.agent_intro));
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
                    ? (pin != null ? getString(R.string.agent_backend_pinned_missing, pin) : getString(R.string.agent_backend_none))
                    : getString(R.string.agent_backend_line, b.displayName(),
                            getString(pin != null ? R.string.agent_mode_pinned : R.string.agent_mode_auto));
            runOnUiThread(() -> backendLabel.setText(s + "   |   " + config.provider() + " / " + config.model()));
        }, "backend-probe").start();
    }

    private void showMenu() {
        final List<WebAccounts.Account> accs = webAccounts.accounts();
        final List<String> items = new ArrayList<>();
        items.add(getString(R.string.agent_menu_backend));
        items.add(getString(R.string.agent_menu_terminal));
        items.add(getString(R.string.agent_menu_adb));
        items.add(getString(R.string.agent_menu_mcp));
        items.add(getString(R.string.agent_menu_accounts));
        final int webStart = items.size();
        for (WebAccounts.Account a : accs) items.add(a.name + (a.url.isEmpty() ? "" : "  (" + hostOf(a.url) + ")"));
        items.add(getString(R.string.agent_menu_web_add));
        final int addIndex = items.size() - 1;
        if (webMode) items.add(getString(R.string.agent_menu_mode_native));
        AlertDialog dlg = new AlertDialog.Builder(this).setItems(items.toArray(new String[0]), (d, which) -> {
            if (which == 0) pickBackend();
            else if (which == 1) startActivity(new Intent(this, TermuxActivity.class));
            else if (which == 2) startActivity(new Intent(this, AdbConsoleActivity.class));
            else if (which == 3) startActivity(new Intent(this, McpActivity.class));
            else if (which == 4) startActivity(new Intent(this, AccountsActivity.class));
            else if (which == addIndex) { WebAccounts.Account n = webAccounts.add(); pickService(n); }
            else if (which > addIndex) setWebMode(false);
            else openWebAccount(accs.get(which - webStart));
        }).create();
        dlg.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos >= webStart && pos < addIndex) { dlg.dismiss(); editWebAccount(accs.get(pos - webStart)); return true; }
            return false;
        });
        dlg.show();
    }

    private static String hostOf(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? url : h.startsWith("www.") ? h.substring(4) : h;
        } catch (RuntimeException e) {
            return url;
        }
    }

    /** Opens the account in the main window; asks for a service first if none is chosen yet. */
    private void openWebAccount(final WebAccounts.Account acc) {
        if (acc.url.isEmpty()) { pickService(acc); return; }
        List<WebAccounts.Account> all = webAccounts.accounts();
        boolean first = !all.isEmpty() && all.get(0).id == acc.id;
        if (!first && !BrowserHelper.profilesSupported()) {
            // Old WebView: only the first account lives in this window, others open in separate profile screens.
            int idx = Math.max(0, all.indexOf(find(all, acc.id)));
            Intent i = new Intent(this, BrowserPicker.SLOTS[1 + (idx - 1) % (BrowserPicker.SLOTS.length - 1)]);
            i.putExtra("url", acc.url);
            startActivity(i);
            return;
        }
        currentWeb = acc.id;
        getSharedPreferences("agentdroid_agent", MODE_PRIVATE).edit().putInt("web_acc", acc.id).apply();
        WebView w = webViews.get(acc.id);
        if (w == null) {
            w = helper.create(first ? null : acc.profile());
            webViews.put(acc.id, w);
            webBox.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            w.loadUrl(acc.url);
        }
        setWebMode(true);
        for (java.util.Map.Entry<Integer, WebView> e : webViews.entrySet()) {
            e.getValue().setVisibility(e.getKey() == acc.id ? View.VISIBLE : View.GONE);
        }
    }

    private static WebAccounts.Account find(List<WebAccounts.Account> all, int id) {
        for (WebAccounts.Account a : all) if (a.id == id) return a;
        return null;
    }

    /** Window with the services (built-in and added by the user), plus "add service". */
    private void pickService(final WebAccounts.Account acc) {
        final List<WebAccounts.Service> svc = webAccounts.services();
        String[] names = new String[svc.size() + 1];
        for (int i = 0; i < svc.size(); i++) names[i] = svc.get(i).name;
        names[svc.size()] = getString(R.string.agent_web_add_service);
        new AlertDialog.Builder(this).setTitle(R.string.agent_web_pick_title).setItems(names, (d, which) -> {
            if (which == svc.size()) { addService(acc); return; }
            acc.url = svc.get(which).url;
            webAccounts.update(acc);
            reloadAccount(acc);
        }).show();
    }

    private void addService(final WebAccounts.Account acc) {
        final EditText name = new EditText(this);
        name.setHint(R.string.agent_web_service_name);
        name.setSingleLine(true);
        final EditText url = new EditText(this);
        url.setHint(R.string.agent_web_service_url);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 10, 40, 0);
        box.addView(name);
        box.addView(url);
        new AlertDialog.Builder(this).setTitle(R.string.agent_web_add_service).setView(box)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) return;
                    if (!u.contains("://")) u = "https://" + u;
                    webAccounts.addService(n, u);
                    acc.url = u;
                    webAccounts.update(acc);
                    reloadAccount(acc);
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    /** The service of an account changed: drop its old page and open the new one. */
    private void reloadAccount(WebAccounts.Account acc) {
        WebView old = webViews.remove(acc.id);
        if (old != null) { webBox.removeView(old); old.destroy(); }
        openWebAccount(acc);
    }

    private void editWebAccount(final WebAccounts.Account acc) {
        String[] opts = {getString(R.string.agent_web_rename), getString(R.string.agent_web_change_service), getString(R.string.agent_web_delete)};
        new AlertDialog.Builder(this).setTitle(acc.name).setItems(opts, (d, which) -> {
            if (which == 0) renameWebAccount(acc);
            else if (which == 1) pickService(acc);
            else {
                webAccounts.remove(acc);
                WebView old = webViews.remove(acc.id);
                if (old != null) { webBox.removeView(old); old.destroy(); }
                if (currentWeb == acc.id) setWebMode(false);
            }
        }).show();
    }

    private void renameWebAccount(final WebAccounts.Account acc) {
        final EditText input = new EditText(this);
        input.setText(acc.name);
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(R.string.agent_web_rename).setView(input)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    String n = input.getText().toString().trim();
                    if (n.isEmpty()) return;
                    acc.name = n;
                    webAccounts.update(acc);
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    /** Web mode: the chosen web account fills the main area (below the top bar) instead of the agent chat. */
    private void setWebMode(boolean on) {
        webMode = on;
        getSharedPreferences("agentdroid_agent", MODE_PRIVATE).edit().putBoolean("web_mode", on).apply();
        nativeBox.setVisibility(on ? View.GONE : View.VISIBLE);
        webBox.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private WebView currentWebView() { return webViews.get(currentWeb); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!helper.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onBackPressed() {
        if (helper.hasPopup()) { helper.closePopup(); return; }
        WebView cw = currentWebView();
        if (webMode && cw != null && cw.canGoBack()) { cw.goBack(); return; }
        super.onBackPressed();
    }

    private void pickBackend() {
        final String[] ids = new String[selector.all().size() + 1];
        final String[] names = new String[ids.length];
        ids[0] = ""; names[0] = getString(R.string.agent_backend_auto_item);
        for (int i = 0; i < selector.all().size(); i++) {
            ids[i + 1] = selector.all().get(i).id();
            names[i + 1] = selector.all().get(i).displayName();
        }
        new AlertDialog.Builder(this).setTitle(R.string.agent_backend_title).setItems(names, (d, which) -> {
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
        final int[] labelIds = {R.string.agent_provider_anthropic, R.string.agent_provider_openai,
                R.string.agent_provider_deepseek, R.string.agent_provider_custom};
        String[] labels = new String[labelIds.length];
        for (int i = 0; i < labelIds.length; i++) labels[i] = getString(labelIds[i]);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int cur = 0;
        for (int i = 0; i < AgentConfig.PROVIDERS.length; i++) if (AgentConfig.PROVIDERS[i].equals(config.provider())) cur = i;
        sp.setSelection(cur);
        box.addView(sp);
        final EditText url = field(getString(R.string.agent_base_url), false);
        final EditText model = field(getString(R.string.agent_model), false);
        final EditText key = field(getString(R.string.agent_key_hint), true);
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
        new AlertDialog.Builder(this).setTitle(R.string.agent_provider_title).setView(box)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    config.setProvider(AgentConfig.PROVIDERS[sp.getSelectedItemPosition()]);
                    config.setBaseUrl(url.getText().toString().trim());
                    config.setModel(model.getText().toString().trim());
                    String k = key.getText().toString().trim();
                    if (!k.isEmpty()) config.setApiKey(k);
                    loop = null;
                    refreshBackendLabel();
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    private void fill(EditText url, EditText model, EditText key) {
        url.setText(config.baseUrl());
        model.setText(config.model());
        key.setText("");
        key.setHint(config.apiKey().isEmpty() ? R.string.agent_key_unset : R.string.agent_key_saved);
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
                + "and explain what you did. Reply in the language the user writes in (Russian by default).";
        l = new AgentLoop(config.build(), Tools.standard(backend, AgentBackends.TERMUX_HOME), approver, system, 25);
        loop = l;
        return l;
    }

    /** Blocks the agent thread until the user answers. */
    private boolean askUser(final String description) {
        return ApprovalCenter.ask(getApplicationContext(), description);
    }

    @Override protected void onResume() { super.onResume(); ApprovalCenter.onResume(this); }

    @Override protected void onPause() { ApprovalCenter.onPause(this); super.onPause(); }

    private void onSend() {
        if (busy) { AgentLoop l = loop; if (l != null) l.cancel(); return; }
        final String msg = input.getText().toString().trim();
        if (msg.isEmpty()) return;
        if (config.apiKey().isEmpty() && !config.provider().equals("custom")) { showSettings(); return; }
        input.setText("");
        bubble(msg, FG, PANEL);
        busy = true;
        sendBtn.setText(R.string.agent_stop);
        currentAssistant = null;
        new Thread(() -> {
            try {
                AgentLoop l = ensureLoop();
                if (l == null) { runOnUiThread(() -> addNote(getString(R.string.agent_no_backend))); return; }
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
                runOnUiThread(() -> { busy = false; sendBtn.setText(R.string.agent_send); currentAssistant = null; });
            }
        }, "agent-loop").start();
    }
}
