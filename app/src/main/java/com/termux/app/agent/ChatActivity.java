package com.termux.app.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.agentdroid.agent.AgentLoop;
import com.agentdroid.agent.Attachment;
import com.agentdroid.agent.StreamListener;
import com.agentdroid.agent.ToolCall;
import com.agentdroid.exec.BackendSelector;
import com.agentdroid.exec.ExecBackend;
import com.termux.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Chat tab. The pill on top chooses who works (the built-in agent or a web account), the three-dot menu holds only what
 * concerns this chat, the gear opens Settings. Approvals appear as a card in the feed; files and voice go in through the composer.
 */
public class ChatActivity extends Activity implements ApprovalCenter.Inline {
    private static final int REQ_ATTACH = 7101, REQ_VOICE = 7102;
    private static final String PREFS = "agentdroid_agent";

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
    private TextView pillTitle, backendLabel;
    private volatile AgentLoop loop;
    private volatile boolean busy;
    private volatile int chatEpoch;
    private TextView currentAssistant;
    private LinearLayout lastCard;
    private LinearLayout nativeBox;
    private FrameLayout webBox;
    private FrameLayout outer;
    private BrowserHelper helper;
    private final java.util.Map<Integer, WebView> webViews = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> webUrls = new java.util.HashMap<>();
    private int currentWeb = -1;
    private WebAccounts webAccounts;
    private volatile boolean webMode;
    private InlineApproval approvals;
    private HorizontalScrollView attachScroll;
    private LinearLayout attachRow;
    private final List<Attachment> attachments = new ArrayList<>();
    private String lastPin = "";
    private String themeSig = "";

    /** Opens a web account in the chat window (used by the Agents tab). */
    static void showWebAccount(Activity from, int accountId) {
        from.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("web_mode", true).putInt("web_acc", accountId).apply();
        from.startActivity(new Intent(from, ChatActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.agentdroid.agent.ThemeColors th = AppTheme.apply(this);
        tcol = th;
        themeSig = AppTheme.signature(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        config = new AgentConfig(this);
        selector = AgentBackends.create(this);
        String pin = config.backendPin();
        if (!pin.isEmpty()) { try { selector.pin(pin); } catch (Exception ignored) { } }
        lastPin = pin;

        ui = new Ui(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.bg);
        root.setPadding(0, dp(24), 0, 0);
        root.addView(topBar());
        root.addView(chips());

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

        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.setVisibility(View.GONE);
        attachRow = new LinearLayout(this);
        attachRow.setPadding(dp(16), 0, dp(16), 0);
        attachScroll.addView(attachRow);
        nativeBox.addView(attachScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        composer(nativeBox);

        root.addView(NavBar.build(this, NavBar.CHAT));
        outer = new FrameLayout(this);
        outer.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(outer);
        approvals = new InlineApproval(this, ui, messages, this::scrollDown);
        helper = new BrowserHelper(this, outer, null);
        webAccounts = new WebAccounts(this, getString(R.string.agent_web_default));
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("web_mode", false)) {
            int id = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("web_acc", -1);
            WebAccounts.Account acc = find(webAccounts.accounts(), id);
            if (acc == null || acc.url.isEmpty() || !openWebAccount(acc)) setWebMode(false);
        }

        updatePill();
        refreshBackendLabel();
        updateComposer();
        addNote(getString(R.string.agent_intro));
    }

    // ---- layout pieces -------------------------------------------------------------------------------------------

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(8), dp(8));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(6), dp(6), dp(12), dp(6));
        pill.setBackground(ui.shape(tcol.s1, 30, tcol.line, 1));
        pill.setClickable(true);
        pill.setOnClickListener(v -> pickPerformer());
        TextView avatar = ui.text("✦", 18, tcol.onpc, false);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(ui.shape(tcol.pc, 18, 0, 0));
        pill.addView(avatar, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout names = ui.column();
        pillTitle = ui.text("AgentDroid", 15, tcol.on, true);
        pillTitle.setSingleLine(true);
        pillTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(pillTitle);
        backendLabel = ui.text("", 12, tcol.on2, false);
        backendLabel.setSingleLine(true);
        backendLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(backendLabel);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(10);
        pill.addView(names, nlp);
        pill.addView(ui.icon(R.drawable.ic_chev, 20, tcol.on2));
        bar.addView(pill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(ui.iconButton(R.drawable.ic_dots, tcol.on, getString(R.string.agent_menu), v -> showChatMenu()));
        bar.addView(ui.iconButton(R.drawable.ic_gear, tcol.on, getString(R.string.agent_settings),
                v -> startActivity(new Intent(this, AppSettingsActivity.class))));
        return bar;
    }

    private View chips() {
        LinearLayout chips = new LinearLayout(this);
        chipsRow = chips;
        chips.setPadding(dp(16), dp(4), dp(16), dp(8));
        chipExec = ui.statusChip("", false);
        chipScreen = ui.statusChip("", false);
        chipTunnel = ui.statusChip("", false);
        chipExec.setOnClickListener(v -> openTab(EnvActivity.class));
        chipScreen.setOnClickListener(v -> openTab(PhoneActivity.class));
        chipTunnel.setOnClickListener(v -> openTab(PhoneActivity.class));
        for (TextView c : new TextView[]{chipExec, chipScreen, chipTunnel}) {
            c.setClickable(true);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            cl.rightMargin = dp(8);
            chips.addView(c, cl);
        }
        return chips;
    }

    private void composer(LinearLayout into) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.shape(tcol.s2, 32, tcol.line, 1));
        row.setPadding(dp(6), dp(6), dp(6), dp(6));

        FrameLayout attach = new FrameLayout(this);
        android.widget.ImageView plus = new android.widget.ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setColorFilter(tcol.on2);
        attach.addView(plus, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        attach.setContentDescription(getString(R.string.chat_attach));
        attach.setClickable(true);
        attach.setOnClickListener(v -> pickFile());
        row.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(40)));

        input = new EditText(this);
        input.setTextColor(tcol.on);
        input.setHintTextColor(tcol.on2);
        input.setHint(R.string.agent_hint);
        input.setBackground(null);
        input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5);
        input.setPadding(dp(4), dp(8), 0, dp(8));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateComposer(); }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout send = new FrameLayout(this);
        sendBtn = send;
        sendIcon = new android.widget.ImageView(this);
        send.addView(sendIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        send.setClickable(true);
        send.setOnClickListener(v -> onSend());
        row.addView(send, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(12), dp(6), dp(12), dp(10));
        into.addView(row, rlp);
    }

    /** One button, three jobs: stop while the agent works, send when there is something to send, microphone otherwise. */
    private void updateComposer() {
        if (sendIcon == null || input == null) return;
        boolean something = input.getText().toString().trim().length() > 0 || !attachments.isEmpty();
        int icon, fill, tint, desc;
        if (busy) { icon = R.drawable.ic_stop; fill = tcol.err2; tint = tcol.err; desc = R.string.agent_stop; }
        else if (something) { icon = R.drawable.ic_send; fill = tcol.p; tint = tcol.onp; desc = R.string.agent_send; }
        else { icon = R.drawable.ic_mic; fill = tcol.pc; tint = tcol.onpc; desc = R.string.chat_voice; }
        sendIcon.setImageResource(icon);
        sendIcon.setColorFilter(tint);
        sendBtn.setBackground(ui.shape(fill, 22, 0, 0));
        sendBtn.setContentDescription(getString(desc));
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---- feed ----------------------------------------------------------------------------------------------------

    private TextView userBubble(String typed, List<Attachment> files) {
        StringBuilder text = new StringBuilder(typed);
        for (Attachment a : files) {
            if (text.length() > 0) text.append('\n');
            text.append("📎 ").append(a.name);
        }
        TextView t = ui.text(text.toString(), 15, tcol.onpc, false);
        t.setTextIsSelectable(true);
        t.setTag("user");
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

    private void addNote(String s) { note(s, tcol.on2); }

    private void scrollDown() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    private void updateChips() {
        boolean screen = AgentAccessibilityService.isRunning();
        boolean tunnel = TunnelManager.isRunning();
        ui.styleChip(chipScreen, getString(R.string.chip_screen), screen);
        ui.styleChip(chipTunnel, getString(tunnel ? R.string.chip_tunnel_on : R.string.chip_tunnel_off), tunnel);
    }

    /** Pill text: the built-in agent with its model, or the web account with its site. */
    private void updatePill() {
        if (pillTitle == null) return;
        if (webMode) {
            WebAccounts.Account a = find(webAccounts.accounts(), currentWeb);
            pillTitle.setText(a == null ? getString(R.string.chat_web_label) : a.name);
            backendLabel.setText(a == null || a.url.isEmpty() ? "" : WebAccountsUi.hostOf(a.url));
        } else {
            pillTitle.setText("AgentDroid");
            String model = config.model();
            backendLabel.setText((model == null || model.isEmpty() ? getString(R.string.agents_not_set) : model) + " · " + config.provider());
        }
    }

    private void refreshBackendLabel() {
        final BackendSelector sel = selector;
        new Thread(() -> {
            ExecBackend b = sel.select();
            final String exec = b == null ? getString(R.string.agent_backend_none) : b.displayName();
            final boolean okExec = b != null;
            runOnUiThread(() -> {
                updatePill();
                ui.styleChip(chipExec, exec, okExec);
                updateChips();
            });
        }, "backend-probe").start();
    }

    private void openTab(Class<?> tab) {
        startActivity(new Intent(this, tab).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    // ---- performer (pill) and chat menu --------------------------------------------------------------------------

    /** Who works: the built-in agent or one of the web accounts; long press on an account edits it. */
    private void pickPerformer() {
        final List<WebAccounts.Account> accs = webAccounts.accounts();
        final List<String> items = new ArrayList<>();
        String model = config.model();
        items.add(mark(!webMode) + getString(R.string.chat_pick_native, model == null || model.isEmpty() ? getString(R.string.agents_not_set) : model));
        for (WebAccounts.Account a : accs) items.add(mark(webMode && a.id == currentWeb) + WebAccountsUi.title(a));
        items.add("+  " + getString(R.string.agent_menu_web_add));
        final int addIndex = items.size() - 1;
        final AlertDialog dlg = new ThemedDialog(this).setTitle(R.string.chat_pick_title).setItems(items.toArray(new String[0]), (d, which) -> {
            if (which == 0) setWebMode(false);
            else if (which == addIndex) WebAccountsUi.add(this, webAccounts, this::openWebAccount);
            else openWebAccount(accs.get(which - 1));
        }).create();
        dlg.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos >= 1 && pos < addIndex) { dlg.dismiss(); editWebAccount(accs.get(pos - 1)); return true; }
            return false;
        });
        dlg.show();
    }

    private static String mark(boolean current) { return current ? "●  " : "○  "; }

    /** Only what concerns this chat: for the agent a new chat or a copy of the conversation, for a web account its page and account. */
    private void showChatMenu() {
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final WebAccounts.Account acc = webMode ? find(webAccounts.accounts(), currentWeb) : null;
        if (webMode && acc != null) {
            items.add(getString(R.string.chat_menu_reload));
            actions.add(() -> { WebView w = currentWebView(); if (w != null) w.reload(); });
            items.add(getString(R.string.chat_menu_service));
            actions.add(() -> WebAccountsUi.changeService(this, webAccounts, acc, this::reloadAccount));
            items.add(getString(R.string.agent_web_rename));
            actions.add(() -> WebAccountsUi.rename(this, webAccounts, acc, accountListener));
            items.add(getString(R.string.agent_web_delete));
            actions.add(() -> WebAccountsUi.confirmDelete(this, webAccounts, acc, accountListener));
        } else {
            items.add(getString(R.string.chat_menu_new));
            actions.add(this::newChat);
            items.add(getString(R.string.chat_menu_copy));
            actions.add(this::copyDialog);
        }
        new ThemedDialog(this).setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }

    /** Starts over: the running task is stopped, the history of the agent and the feed are cleared. */
    private void newChat() {
        AgentLoop l = loop;
        if (l != null) l.cancel();
        approvals.denyAll();
        chatEpoch++;
        busy = false;
        loop = null;
        currentAssistant = null;
        lastCard = null;
        toolState = null;
        attachments.clear();
        renderAttachments();
        messages.removeAllViews();
        addNote(getString(R.string.agent_intro));
        updateComposer();
    }

    private void copyDialog() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < messages.getChildCount(); i++) {
            View v = messages.getChildAt(i);
            String t = textOf(v);
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append("user".equals(v.getTag()) ? "> " : "").append(t);
        }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("agentdroid", b.toString()));
        Toast.makeText(this, R.string.chat_copied_dialog, Toast.LENGTH_SHORT).show();
    }

    private static String textOf(View v) {
        if (v instanceof TextView) return ((TextView) v).getText().toString().trim();
        if (!(v instanceof ViewGroup)) return "";
        StringBuilder b = new StringBuilder();
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            String t = textOf(g.getChildAt(i));
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(t);
        }
        return b.toString();
    }

    // ---- web accounts --------------------------------------------------------------------------------------------

    private final WebAccountsUi.Listener accountListener = new WebAccountsUi.Listener() {
        @Override public void renamed(WebAccounts.Account acc) { updatePill(); }
        @Override public void serviceChanged(WebAccounts.Account acc) { reloadAccount(acc); }
        @Override public void deleted(WebAccounts.Account acc) {
            dropWebView(acc.id);
            BrowserHelper.deleteProfile(acc.profile());
            if (currentWeb == acc.id) { currentWeb = -1; setWebMode(false); }
        }
    };

    private void editWebAccount(WebAccounts.Account acc) { WebAccountsUi.edit(this, webAccounts, acc, accountListener); }

    private void dropWebView(int id) {
        webUrls.remove(id);
        WebView old = webViews.remove(id);
        if (old != null) { webBox.removeView(old); old.destroy(); }
    }

    /** Opens the account in the main window; asks for a service first if none is chosen yet. False when it went to another screen. */
    private boolean openWebAccount(final WebAccounts.Account acc) {
        if (acc.url.isEmpty()) { WebAccountsUi.changeService(this, webAccounts, acc, this::reloadAccount); return false; }
        List<WebAccounts.Account> all = webAccounts.accounts();
        boolean first = !all.isEmpty() && all.get(0).id == acc.id;
        if (!first && !BrowserHelper.profilesSupported()) {
            // Old WebView: only the first account lives in this window, others open in separate profile screens.
            int idx = Math.max(0, all.indexOf(find(all, acc.id)));
            Intent i = new Intent(this, BrowserPicker.SLOTS[1 + (idx - 1) % (BrowserPicker.SLOTS.length - 1)]);
            i.putExtra("url", acc.url);
            startActivity(i);
            return false;
        }
        currentWeb = acc.id;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("web_acc", acc.id).apply();
        WebView w = webViews.get(acc.id);
        if (w == null) {
            w = helper.create(first ? null : acc.profile());
            webViews.put(acc.id, w);
            webUrls.put(acc.id, acc.url);
            webBox.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            w.loadUrl(acc.url);
        }
        setWebMode(true);
        for (java.util.Map.Entry<Integer, WebView> e : webViews.entrySet()) {
            e.getValue().setVisibility(e.getKey() == acc.id ? View.VISIBLE : View.GONE);
        }
        return true;
    }

    private static WebAccounts.Account find(List<WebAccounts.Account> all, int id) {
        for (WebAccounts.Account a : all) if (a.id == id) return a;
        return null;
    }

    /** The service of an account changed: drop its old page and open the new one. */
    private void reloadAccount(WebAccounts.Account acc) {
        dropWebView(acc.id);
        openWebAccount(acc);
    }

    /** Web mode: the chosen web account fills the main area (below the top bar) instead of the agent chat. */
    private void setWebMode(boolean on) {
        webMode = on;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("web_mode", on).apply();
        nativeBox.setVisibility(on ? View.GONE : View.VISIBLE);
        webBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (chipsRow != null) chipsRow.setVisibility(on ? View.GONE : View.VISIBLE);
        updatePill();
        if (!on) refreshBackendLabel();
    }

    /** The Agents tab may have asked for a web account (or for the agent) while this screen was in the background. */
    private void syncWebFromPrefs() {
        // Accounts deleted or switched to another service on the Agents tab: their pages here are stale.
        List<WebAccounts.Account> all = webAccounts.accounts();
        for (Integer id : new ArrayList<>(webViews.keySet())) {
            WebAccounts.Account a = find(all, id);
            if (a != null && !a.url.isEmpty() && a.url.equals(webUrls.get(id))) continue;
            boolean showing = webMode && currentWeb == id;
            dropWebView(id);
            if (a == null) BrowserHelper.deleteProfile("acc" + id);
            if (a != null && !a.url.isEmpty()) { if (showing) openWebAccount(a); }
            else if (currentWeb == id) { currentWeb = -1; if (webMode) setWebMode(false); }
        }
        android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean want = sp.getBoolean("web_mode", false);
        int id = sp.getInt("web_acc", -1);
        if (want && (!webMode || currentWeb != id)) {
            WebAccounts.Account acc = find(webAccounts.accounts(), id);
            if (acc == null || acc.url.isEmpty() || !openWebAccount(acc)) {
                if (!webMode) sp.edit().putBoolean("web_mode", false).apply();
            }
        } else if (!want && webMode) {
            setWebMode(false);
        }
    }

    private WebView currentWebView() { return webViews.get(currentWeb); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (helper != null && helper.onRequestPermissionsResult(requestCode, permissions, grantResults)) return;
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ATTACH) {
            if (resultCode == RESULT_OK && data != null) addAttachments(data);
            return;
        }
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (heard != null && !heard.isEmpty()) {
                    String now = input.getText().toString();
                    input.setText(now.isEmpty() ? heard.get(0) : now + " " + heard.get(0));
                    input.setSelection(input.getText().length());
                }
            }
            return;
        }
        if (!helper.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onBackPressed() {
        if (helper.hasPopup()) { helper.closePopup(); return; }
        WebView cw = currentWebView();
        if (webMode && cw != null && cw.canGoBack()) { cw.goBack(); return; }
        super.onBackPressed();
    }

    // ---- model and key ------------------------------------------------------------------------------------------

    /** A message was written without an API key: the same dialog as on the Agents tab. */
    private void askForKey() {
        ProviderDialog.show(this, config, () -> { loop = null; updatePill(); });
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

    // ---- approvals in the feed -----------------------------------------------------------------------------------

    /** The question appears as a card in the feed; in web mode there is no feed on screen, so the dialog is used. */
    @Override public Boolean askInline(String description, boolean force) {
        if (webMode || approvals == null) return null;
        return approvals.ask(description, force);
    }

    // ---- files and voice -----------------------------------------------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_ATTACH); } catch (ActivityNotFoundException e) { toast(R.string.chat_attach_bad); }
    }

    private void addAttachments(Intent data) {
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        new Thread(() -> {
            final List<Attachment> read = new ArrayList<>();
            int bad = 0;
            for (Uri u : uris) {
                Attachment a = readAttachment(u);
                if (a == null) bad++; else read.add(a);
            }
            final int unreadable = bad;
            runOnUiThread(() -> {
                int rejected = unreadable;
                for (Attachment a : read) {
                    if (Attachment.fits(attachments, a)) attachments.add(a); else rejected++;
                }
                renderAttachments();
                if (rejected > 0) toast(R.string.chat_attach_bad);
            });
        }, "attach-read").start();
    }

    /** The file as text, or null when it is too big, binary or unreadable. */
    private Attachment readAttachment(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > Attachment.MAX_BYTES) return null;
            }
            return Attachment.of(name, out.toByteArray());
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private void renderAttachments() {
        attachRow.removeAllViews();
        for (final Attachment a : attachments) {
            TextView chip = ui.text("📎 " + a.name + "  ✕", 12.5f, tcol.on, false);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(ui.shape(tcol.s2, 16, tcol.line, 1));
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setContentDescription(getString(R.string.chat_attach_remove, a.name));
            chip.setClickable(true);
            chip.setOnClickListener(v -> { attachments.remove(a); renderAttachments(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.rightMargin = dp(8);
            attachRow.addView(chip, lp);
        }
        attachScroll.setVisibility(attachments.isEmpty() ? View.GONE : View.VISIBLE);
        updateComposer();
    }

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.chat_voice));
        try { startActivityForResult(i, REQ_VOICE); } catch (ActivityNotFoundException e) { toast(R.string.chat_voice_missing); }
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override protected void onResume() {
        super.onResume();
        ApprovalCenter.onResume(this);
        if (!themeSig.equals(AppTheme.signature(this))) { recreate(); return; }
        syncWebFromPrefs();
        updateChips();
        updatePill();
        String pin = config.backendPin();
        if (!pin.equals(lastPin)) {
            lastPin = pin;
            try { selector.pin(pin.isEmpty() ? null : pin); } catch (Exception ignored) { }
            loop = null;
            refreshBackendLabel();
        }
    }

    @Override protected void onPause() {
        // First leave the "visible" slot, then give the open cards back: the agent thread asks again where the user is now.
        ApprovalCenter.onPause(this);
        if (approvals != null) approvals.releasePending();
        super.onPause();
    }

    // ---- sending -------------------------------------------------------------------------------------------------

    private void onSend() {
        if (busy) {
            AgentLoop l = loop;
            if (l != null) l.cancel();
            approvals.denyAll();
            return;
        }
        final String typed = input.getText().toString().trim();
        if (typed.isEmpty() && attachments.isEmpty()) { startVoice(); return; }
        if (config.apiKey().isEmpty() && !config.provider().equals("custom")) { askForKey(); return; }
        final List<Attachment> files = new ArrayList<>(attachments);
        final String msg = Attachment.compose(typed, files);
        input.setText("");
        attachments.clear();
        renderAttachments();
        userBubble(typed, files);
        busy = true;
        updateComposer();
        currentAssistant = null;
        final int epoch = chatEpoch;
        new Thread(() -> {
            try {
                AgentLoop l = ensureLoop();
                if (l == null) { post(epoch, () -> addNote(getString(R.string.agent_no_backend))); return; }
                l.send(msg, new StreamListener() {
                    @Override public void onText(final String delta) {
                        post(epoch, () -> {
                            if (currentAssistant == null) currentAssistant = assistantText();
                            currentAssistant.append(delta);
                            scrollDown();
                        });
                    }
                    @Override public void onToolCall(final ToolCall call) {
                        post(epoch, () -> { currentAssistant = null; lastCard = toolCard(call.name, com.agentdroid.agent.Json.stringify(call.args)); });
                    }
                    @Override public void onToolResult(ToolCall call, final String result, final boolean error) {
                        post(epoch, () -> {
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
                    @Override public void onError(final String m) { post(epoch, () -> addNote("⚠ " + m)); }
                });
            } catch (final Exception e) {
                post(epoch, () -> addNote("⚠ " + e.getMessage()));
            } finally {
                post(epoch, () -> { busy = false; currentAssistant = null; lastCard = null; updateComposer(); });
            }
        }, "agent-loop").start();
    }

    /** Runs on the UI thread unless the chat was cleared meanwhile (a late answer of the old run must not leak into the new chat). */
    private void post(final int epoch, final Runnable r) {
        runOnUiThread(() -> { if (epoch == chatEpoch) r.run(); });
    }
}
