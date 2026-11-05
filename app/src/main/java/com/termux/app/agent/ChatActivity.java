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
    private int BG, PANEL, FG, DIM, ACCENT, BTN, ONBTN;

    private AgentConfig config;
    private BackendSelector selector;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private View sendBtn;
    private android.widget.ImageView sendIcon;
    private TextView chipExec, chipScreen, chipTunnel;
    private TextView toolState;
    private LinearLayout chipsRow;
    private Ui ui;
    private com.agentdroid.agent.ThemeColors tcol;
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
        com.agentdroid.agent.ThemeColors th = AppTheme.apply(this);
        tcol = th;
        themeSig = AppTheme.signature(this);
        BG = th.bg; PANEL = th.s2; FG = th.on; DIM = th.on2; ACCENT = th.p; BTN = th.pc; ONBTN = th.onpc;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        config = new AgentConfig(this);
        selector = AgentBackends.create(this);
        String pin = config.backendPin();
        if (!pin.isEmpty()) { try { selector.pin(pin); } catch (Exception ignored) { } }
        lastPin = pin;

        ui = new Ui(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(10);
        root.setPadding(0, dp(24), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(8), dp(8));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(6), dp(6), dp(12), dp(6));
        pill.setBackground(ui.shape(th.s1, 30, th.line, 1));
        pill.setClickable(true);
        pill.setOnClickListener(v -> pickBackend());
        TextView avatar = ui.text("\u2726", 18, th.onpc, false);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(ui.shape(th.pc, 18, 0, 0));
        pill.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout names = ui.column();
        TextView title = ui.text("AgentDroid", 15, th.on, true);
        names.addView(title);
        backendLabel = ui.text("", 12, th.on2, false);
        backendLabel.setSingleLine(true);
        backendLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(backendLabel);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(10);
        pill.addView(names, nlp);
        pill.addView(ui.icon(R.drawable.ic_chev, 20, th.on2));
        bar.addView(pill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(ui.iconButton(R.drawable.ic_dots, th.on, getString(R.string.agent_menu), v -> showMenu()));
        bar.addView(ui.iconButton(R.drawable.ic_gear, th.on, getString(R.string.agent_settings), v -> showSettings()));
        root.addView(bar);

        LinearLayout chips = new LinearLayout(this);
        chipsRow = chips;
        chips.setPadding(dp(16), dp(4), dp(16), dp(8));
        chipExec = ui.statusChip("", false);
        chipScreen = ui.statusChip("", false);
        chipTunnel = ui.statusChip("", false);
        for (TextView c : new TextView[]{chipExec, chipScreen, chipTunnel}) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            cl.rightMargin = dp(8);
            chips.addView(c, cl);
        }
        root.addView(chips);

        nativeBox = new LinearLayout(this);
        nativeBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(nativeBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webBox = new FrameLayout(this);
        webBox.setVisibility(View.GONE);
        root.addView(webBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(messages);
        nativeBox.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.shape(th.s2, 32, th.line, 1));
        row.setPadding(dp(16), dp(6), dp(6), dp(6));
        input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(DIM);
        input.setHint(R.string.agent_hint);
        input.setBackground(null);
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5);
        input.setPadding(0, dp(8), 0, dp(8));
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendBtn = new FrameLayout(this);
        sendIcon = new android.widget.ImageView(this);
        sendIcon.setImageResource(R.drawable.ic_send);
        sendIcon.setColorFilter(th.onp);
        ((FrameLayout) sendBtn).addView(sendIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        sendBtn.setBackground(ui.shape(th.p, 22, 0, 0));
        sendBtn.setContentDescription(getString(R.string.agent_send));
        sendBtn.setClickable(true);
        sendBtn.setOnClickListener(v -> onSend());
        row.addView(sendBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(12), dp(6), dp(12), dp(10));
        nativeBox.addView(row, rlp);
        root.addView(NavBar.build(this, NavBar.CHAT));
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

    private TextView userBubble(String s) {
        TextView t = ui.text(s, 15, tcol.onpc, false);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.15f);
        t.setBackground(ui.bubbleShape(tcol.pc, 22, 22, 6, 22));
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(14);
        lp.leftMargin = dp(48);
        t.setLayoutParams(lp);
        messages.addView(t);
        scrollDown();
        return t;
    }

    private TextView assistantText() {
        TextView t = ui.text("", 15, tcol.on, false);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        t.setLayoutParams(lp);
        messages.addView(t);
        scrollDown();
        return t;
    }

    private TextView note(String s, int color) {
        TextView t = ui.text(s, 13, color, false);
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        messages.addView(t);
        scrollDown();
        return t;
    }

    /** One tool call as a card: name in mono, state on the right, short result under it. */
    private LinearLayout toolCard(String name, String args) {
        LinearLayout card = ui.column();
        card.setBackground(ui.shape(tcol.s1, 14, tcol.line, 1));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = ui.text(name + (args.length() > 2 ? " " + args : ""), 12.5f, tcol.p, false);
        n.setTypeface(Typeface.MONOSPACE);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toolState = ui.text("…", 12, tcol.on2, false);
        top.addView(toolState);
        card.addView(top);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);
        messages.addView(card);
        scrollDown();
        return card;
    }
    private LinearLayout lastCard;

    private void setSending(boolean on) {
        sendIcon.setImageResource(on ? R.drawable.ic_stop : R.drawable.ic_send);
        sendIcon.setColorFilter(on ? tcol.err : tcol.onp);
        sendBtn.setBackground(ui.shape(on ? tcol.err2 : tcol.p, 22, 0, 0));
        sendBtn.setContentDescription(getString(on ? R.string.agent_stop : R.string.agent_send));
    }

    private void updateChips() {
        boolean screen = AgentAccessibilityService.isRunning();
        boolean tunnel = TunnelManager.isRunning();
        ui.styleChip(chipScreen, getString(R.string.chip_screen), screen);
        ui.styleChip(chipTunnel, getString(tunnel ? R.string.chip_tunnel_on : R.string.chip_tunnel_off), tunnel);
    }

    private void addNote(String s) { note(s, DIM); }

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
            final String exec = b == null ? getString(R.string.agent_backend_none) : b.displayName();
            final boolean okExec = b != null;
            runOnUiThread(() -> {
                backendLabel.setText(config.model() + " \u00b7 " + config.provider());
                ui.styleChip(chipExec, exec, okExec);
                updateChips();
            });
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
        items.add(getString(R.string.agent_menu_desktop));
        items.add(getString(R.string.agent_menu_assistant));
        items.add(getString(R.string.agent_menu_triggers));
        items.add(getString(R.string.agent_menu_appearance));
        final int webStart = items.size();
        for (WebAccounts.Account a : accs) items.add(a.name + (a.url.isEmpty() ? "" : "  (" + hostOf(a.url) + ")"));
        items.add(getString(R.string.agent_menu_web_add));
        final int addIndex = items.size() - 1;
        if (webMode) items.add(getString(R.string.agent_menu_mode_native));
        AlertDialog dlg = new com.termux.app.agent.ThemedDialog(this).setItems(items.toArray(new String[0]), (d, which) -> {
            if (which == 0) pickBackend();
            else if (which == 1) startActivity(new Intent(this, TermuxActivity.class));
            else if (which == 2) startActivity(new Intent(this, AdbConsoleActivity.class));
            else if (which == 3) startActivity(new Intent(this, McpActivity.class));
            else if (which == 4) startActivity(new Intent(this, AccountsActivity.class));
            else if (which == 5) {
                android.widget.Toast.makeText(this, R.string.agent_desktop_hint, android.widget.Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, TermuxActivity.class));
            }
            else if (which == 6) startActivity(new Intent(this, AssistantActivity.class));
            else if (which == 7) startActivity(new Intent(this, TriggersActivity.class));
            else if (which == 8) startActivity(new Intent(this, AppearanceActivity.class));
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
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.agent_web_pick_title).setItems(names, (d, which) -> {
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
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.agent_web_add_service).setView(box)
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
        new com.termux.app.agent.ThemedDialog(this).setTitle(acc.name).setItems(opts, (d, which) -> {
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
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.agent_web_rename).setView(input)
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
        if (chipsRow != null) chipsRow.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on && backendLabel != null) backendLabel.setText(R.string.chat_web_label);
        else if (backendLabel != null) refreshBackendLabel();
    }

    private WebView currentWebView() { return webViews.get(currentWeb); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (helper != null && helper.onRequestPermissionsResult(requestCode, permissions, grantResults)) return;
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

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
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.agent_backend_title).setItems(names, (d, which) -> {
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
        new com.termux.app.agent.ThemedDialog(this).setTitle(R.string.agent_provider_title).setView(box)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    config.setProvider(AgentConfig.PROVIDERS[sp.getSelectedItemPosition()]);
                    String u = url.getText().toString().trim();
                    if (!u.matches("(?i)https?://.+")) {
                        android.widget.Toast.makeText(this, R.string.agent_bad_url, android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    config.setBaseUrl(u);
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
        l = PhoneAgent.create(this, backend, PhoneAgent.approver(this));
        loop = l;
        return l;
    }

    /** Blocks the agent thread until the user answers. */
    private boolean askUser(final String description) {
        return ApprovalCenter.ask(getApplicationContext(), description);
    }

    @Override protected void onResume() {
        super.onResume();
        ApprovalCenter.onResume(this);
        if (!themeSig.equals(AppTheme.signature(this))) { recreate(); return; }
        updateChips();
        String pin = config.backendPin();
        if (!pin.equals(lastPin)) {
            lastPin = pin;
            try { selector.pin(pin.isEmpty() ? null : pin); } catch (Exception ignored) { }
            loop = null;
            refreshBackendLabel();
        }
    }
    private String lastPin = "";
    private String themeSig = "";

    @Override protected void onPause() { ApprovalCenter.onPause(this); super.onPause(); }

    private void onSend() {
        if (busy) { AgentLoop l = loop; if (l != null) l.cancel(); return; }
        final String msg = input.getText().toString().trim();
        if (msg.isEmpty()) return;
        if (config.apiKey().isEmpty() && !config.provider().equals("custom")) { showSettings(); return; }
        input.setText("");
        userBubble(msg);
        busy = true;
        setSending(true);
        currentAssistant = null;
        new Thread(() -> {
            try {
                AgentLoop l = ensureLoop();
                if (l == null) { runOnUiThread(() -> addNote(getString(R.string.agent_no_backend))); return; }
                l.send(msg, new StreamListener() {
                    @Override public void onText(final String delta) {
                        runOnUiThread(() -> {
                            if (currentAssistant == null) currentAssistant = assistantText();
                            currentAssistant.append(delta);
                            scrollDown();
                        });
                    }
                    @Override public void onToolCall(final ToolCall call) {
                        runOnUiThread(() -> { currentAssistant = null; lastCard = toolCard(call.name, com.agentdroid.agent.Json.stringify(call.args)); });
                    }
                    @Override public void onToolResult(ToolCall call, final String result, final boolean error) {
                        runOnUiThread(() -> {
                            String shown = result.length() > 600 ? result.substring(0, 600) + "\n…" : result;
                            if (toolState != null) {
                                toolState.setText(getString(error ? R.string.tool_failed : R.string.tool_done));
                                toolState.setTextColor(error ? tcol.err : tcol.ok);
                            }
                            if (lastCard != null && !shown.trim().isEmpty()) {
                                TextView r = ui.text(shown, 12, tcol.on2, false);
                                r.setTypeface(Typeface.MONOSPACE);
                                r.setTextIsSelectable(true);
                                lastCard.addView(r, ui.gap(6));
                            }
                        });
                    }
                    @Override public void onError(final String m) { runOnUiThread(() -> addNote("⚠ " + m)); }
                });
            } catch (final Exception e) {
                runOnUiThread(() -> addNote("⚠ " + e.getMessage()));
            } finally {
                runOnUiThread(() -> { busy = false; setSending(false); currentAssistant = null; lastCard = null; });
            }
        }, "agent-loop").start();
    }
}
