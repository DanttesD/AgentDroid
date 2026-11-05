package com.termux.app.agent;

import com.agentdroid.agent.ThemeColors;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.agentdroid.agent.Triggers;
import com.termux.R;

import java.util.List;

/** Notification rules: "when X arrives, do Y", with a confirm / auto / off mode per rule. */
public final class TriggersActivity extends Activity {
    private TriggerStore store;
    private ListView list;
    private TextView access;
    private Ui ui;

    private String themeSig = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeColors th = AppTheme.apply(this);
        themeSig = AppTheme.signature(this);
        ui = new Ui(this);
        setTitle(R.string.trig_title);
        store = new TriggerStore(this);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(th.bg);
        col.addView(ui.title(getString(R.string.trig_title)));
        col.addView(ui.text(getString(R.string.trig_info), 14, th.on2, false), ui.gap(8));
        access = ui.text("", 14, th.on, true);
        access.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(access);
        col.addView(ui.button(getString(R.string.trig_access_btn), Ui.Kind.TONAL,
                v -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))));
        col.addView(ui.button(getString(R.string.trig_add), Ui.Kind.FILLED, v -> edit(new Triggers.Rule(), true)), ui.gap(8));
        list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(th.line));
        list.setDividerHeight(1);
        col.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
    }

    @Override protected void onResume() { super.onResume(); if (!themeSig.equals(AppTheme.signature(this))) { recreate(); return; } refresh(); }

    private boolean accessGranted() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, AgentNotificationListener.class).flattenToString());
    }

    private String modeName(int m) {
        return getResources().getStringArray(R.array.trig_modes)[Math.max(0, Math.min(2, m))];
    }

    private void refresh() {
        access.setText(getString(R.string.trig_access, getString(accessGranted() ? R.string.assistant_yes : R.string.assistant_no)));
        final List<Triggers.Rule> rules = store.all();
        String[] rows = new String[rules.size()];
        for (int i = 0; i < rows.length; i++) {
            Triggers.Rule r = rules.get(i);
            rows[i] = (r.name.isEmpty() ? r.goal : r.name) + "\n" + modeName(r.mode) + " · "
                    + (r.pkg.isEmpty() ? getString(R.string.trig_any_app) : r.pkg)
                    + (r.contains.isEmpty() ? "" : " · «" + r.contains + "»");
        }
        list.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override public android.view.View getView(int pos, android.view.View cv, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(ui.t.on);
                tv.setTextSize(14);
                tv.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));
                return tv;
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> edit(rules.get(pos), false));
    }

    private EditText field(LinearLayout col, int hint, String value, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT);
        col.addView(e);
        return e;
    }

    private void edit(final Triggers.Rule rule, final boolean isNew) {
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad / 2, pad, 0);
        final EditText name = field(col, R.string.trig_f_name, rule.name, false);
        final EditText pkg = field(col, R.string.trig_f_pkg, rule.pkg, false);
        final EditText kw = field(col, R.string.trig_f_contains, rule.contains, false);
        final EditText goal = field(col, R.string.trig_f_goal, rule.goal, true);
        final EditText cool = field(col, R.string.trig_f_cooldown, String.valueOf(rule.cooldownSec), false);
        cool.setInputType(InputType.TYPE_CLASS_NUMBER);
        final Spinner mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, getResources().getStringArray(R.array.trig_modes)));
        mode.setSelection(rule.mode);
        col.addView(mode);
        AlertDialog.Builder db = new AlertDialog.Builder(this).setTitle(R.string.trig_title).setView(col)
                .setPositiveButton(R.string.trig_save, (d, w) -> {
                    rule.name = name.getText().toString().trim();
                    rule.pkg = pkg.getText().toString().trim();
                    rule.contains = kw.getText().toString().trim();
                    rule.goal = goal.getText().toString().trim();
                    rule.mode = mode.getSelectedItemPosition();
                    try { rule.cooldownSec = Integer.parseInt(cool.getText().toString().trim()); } catch (NumberFormatException e) { rule.cooldownSec = 60; }
                    if (rule.goal.isEmpty()) return;
                    store.put(rule);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!isNew) db.setNeutralButton(R.string.trig_delete, (d, w) -> { store.remove(rule.id); refresh(); });
        db.show();
    }
}
