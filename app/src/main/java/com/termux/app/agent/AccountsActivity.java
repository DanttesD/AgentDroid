package com.termux.app.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ExecRequest;
import com.agentdroid.exec.ExecResult;
import com.agentdroid.exec.ShellQuote;
import com.termux.R;

import java.util.List;

/** Accounts and sign-in: install the official agents, add accounts, sign in through the built-in browser. */
public class AccountsActivity extends Activity {
    private static final int BG = Color.parseColor("#121418");
    private static final int PANEL = Color.parseColor("#1c1f26");
    private static final int FG = Color.parseColor("#e6e8ee");
    private static final int DIM = Color.parseColor("#8b93a7");
    private static final int ACCENT = Color.parseColor("#7aa2ff");

    private AccountsStore store;
    private LinearLayout list;
    private ExecBackend termux;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new AccountsStore(this);
        termux = AgentBackends.create(this).find("termux");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        list.setPadding(p, dp(30), p, p);
        sv.addView(list);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); ApprovalCenter.onResume(this); render(); }

    @Override protected void onPause() { ApprovalCenter.onPause(this); super.onPause(); }

    private void render() {
        list.removeAllViews();
        TextView t = tv(getString(R.string.acc_title), 20, FG);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        list.addView(t);
        list.addView(tv(getString(R.string.acc_intro), 14, DIM));
        for (final AgentCatalog.Entry e : AgentCatalog.ALL) list.addView(agentCard(e));
    }

    private LinearLayout agentCard(final AgentCatalog.Entry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(PANEL);
        int p = dp(12);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        TextView title = tv(e.title, 17, FG);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(title);
        card.addView(tv(getString(e.noteRes), 13, DIM));
        final TextView status = tv(getString(R.string.acc_checking), 14, ACCENT);
        card.addView(status);
        checkInstalled(e, status);

        Button install = btn(getString(R.string.acc_install));
        install.setOnClickListener(v -> {
            Toast.makeText(this, R.string.acc_install_note, Toast.LENGTH_LONG).show();
            TermuxSessions.run(this, e.title, e.install, BrowserPicker.SLOTS[0]);
        });
        card.addView(install);
        Button add = btn(getString(R.string.acc_add));
        add.setOnClickListener(v -> askName(e));
        card.addView(add);

        for (final AccountsStore.Account a : store.of(e.id)) card.addView(accountRow(e, a));
        return card;
    }

    private LinearLayout accountRow(final AgentCatalog.Entry e, final AccountsStore.Account a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(tv(getString(R.string.acc_account_line, a.name, BrowserPicker.name(this, a.browserSlot)), 14, FG));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button run = small(getString(R.string.acc_run));
        run.setOnClickListener(v -> TermuxSessions.run(this, e.title + " " + a.name,
                "mkdir -p " + ShellQuote.quote(a.dir()) + "; " + e.withAccount(e.run, a.dir()), BrowserPicker.SLOTS[a.browserSlot]));
        Button relogin = small(getString(R.string.acc_relogin));
        relogin.setOnClickListener(v -> login(e, a));
        Button del = small(getString(R.string.acc_delete));
        del.setOnClickListener(v -> confirmDelete(e, a));
        btns.addView(run); btns.addView(relogin); btns.addView(del);
        row.addView(btns);
        return row;
    }

    private void login(AgentCatalog.Entry e, AccountsStore.Account a) {
        TermuxSessions.run(this, e.title + " " + a.name,
                "mkdir -p " + ShellQuote.quote(a.dir()) + "; " + e.withAccount(e.login, a.dir()), BrowserPicker.SLOTS[a.browserSlot]);
    }

    private void askName(final AgentCatalog.Entry e) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle(R.string.acc_name_title).setView(input)
                .setPositiveButton(R.string.agent_save, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!AccountsStore.validName(name)) { toast(R.string.acc_name_bad); return; }
                    if (store.of(e.id).size() >= AccountsStore.MAX_PER_AGENT) { toast(R.string.acc_limit); return; }
                    AccountsStore.Account acc = store.add(e.id, name);
                    if (acc == null) { toast(R.string.acc_name_exists); return; }
                    render();
                    login(e, acc);
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    private void confirmDelete(final AgentCatalog.Entry e, final AccountsStore.Account a) {
        new AlertDialog.Builder(this).setMessage(getString(R.string.acc_delete_confirm, a.name))
                .setPositiveButton(R.string.acc_delete, (d, w) -> {
                    store.remove(a);
                    new Thread(() -> {
                        try {
                            termux.exec(ExecRequest.builder("rm -rf " + ShellQuote.quote(a.dir())).build());
                        } catch (Exception ignored) { }
                    }, "acc-delete").start();
                    render();
                })
                .setNegativeButton(R.string.agent_cancel, null).show();
    }

    private void checkInstalled(final AgentCatalog.Entry e, final TextView status) {
        new Thread(() -> {
            boolean ok = false;
            try {
                if (termux != null) {
                    ExecResult r = termux.exec(ExecRequest.builder("command -v " + e.bin).timeoutMs(10_000).build());
                    ok = r.ok() && !r.stdout().trim().isEmpty();
                }
            } catch (Exception ignored) { }
            final boolean installed = ok;
            runOnUiThread(() -> status.setText(installed ? R.string.acc_installed : R.string.acc_not_installed));
        }, "acc-check").start();
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView tv(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private Button btn(String s) {
        Button bt = new Button(this);
        bt.setText(s); bt.setAllCaps(false); bt.setTextColor(Color.WHITE); bt.setBackgroundColor(Color.parseColor("#2a2f3a"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        bt.setLayoutParams(lp);
        return bt;
    }

    private Button small(String s) {
        Button bt = new Button(this);
        bt.setText(s); bt.setAllCaps(false); bt.setTextColor(Color.WHITE); bt.setBackgroundColor(Color.parseColor("#2a2f3a"));
        bt.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        bt.setLayoutParams(lp);
        return bt;
    }
}
