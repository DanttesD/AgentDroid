package com.termux.app.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

/**
 * Provider, API address, model and key of the built-in agent. One dialog for the whole app: the Agents tab opens it,
 * and the chat opens it when a message is sent without a key.
 */
final class ProviderDialog {
    private ProviderDialog() { }

    static void show(final Activity a, final AgentConfig config, final Runnable saved) {
        final Ui ui = new Ui(a);
        final String original = config.provider();
        final boolean[] done = {false};

        LinearLayout box = ui.column();
        box.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0);

        final Spinner sp = new Spinner(a);
        final int[] labelIds = {R.string.agent_provider_anthropic, R.string.agent_provider_openai,
                R.string.agent_provider_deepseek, R.string.agent_provider_custom};
        String[] labels = new String[labelIds.length];
        for (int i = 0; i < labelIds.length; i++) labels[i] = a.getString(labelIds[i]);
        sp.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, labels));
        int cur = 0;
        for (int i = 0; i < AgentConfig.PROVIDERS.length; i++) if (AgentConfig.PROVIDERS[i].equals(original)) cur = i;
        sp.setSelection(cur);
        box.addView(sp);

        final EditText url = field(a, false);
        final EditText model = field(a, false);
        final EditText key = field(a, true);
        box.addView(caption(ui, R.string.agent_base_url), ui.gap(10));
        box.addView(url);
        box.addView(caption(ui, R.string.agent_model), ui.gap(6));
        box.addView(model);
        box.addView(caption(ui, R.string.agent_key_hint), ui.gap(6));
        box.addView(key);

        final int[] shown = {cur};
        fill(config, url, model, key);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shown[0]) return;
                config.setProvider(AgentConfig.PROVIDERS[pos]);
                shown[0] = pos;
                fill(config, url, model, key);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        final AlertDialog dlg = new ThemedDialog(a).setTitle(R.string.agent_provider_title).setView(box)
                .setPositiveButton(R.string.agent_save, null)
                .setNegativeButton(R.string.agent_cancel, null).create();
        // Leaving without saving must not keep the provider that was only browsed in the spinner.
        dlg.setOnDismissListener(d -> { if (!done[0]) config.setProvider(original); });
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (!u.matches("(?i)https?://.+")) {
                Toast.makeText(a, R.string.agent_bad_url, Toast.LENGTH_LONG).show();
                return;
            }
            config.setProvider(AgentConfig.PROVIDERS[sp.getSelectedItemPosition()]);
            config.setBaseUrl(u);
            config.setModel(model.getText().toString().trim());
            String k = key.getText().toString().trim();
            if (!k.isEmpty()) config.setApiKey(k);
            done[0] = true;
            dlg.dismiss();
            if (saved != null) saved.run();
        });
    }

    private static void fill(AgentConfig config, EditText url, EditText model, EditText key) {
        url.setText(config.baseUrl());
        model.setText(config.model());
        key.setText("");
        key.setHint(config.apiKey().isEmpty() ? R.string.agent_key_unset : R.string.agent_key_saved);
    }

    private static TextView caption(Ui ui, int res) {
        return ui.text(ui.ctx.getString(res), 12.5f, ui.t.on2, true);
    }

    private static EditText field(Activity a, boolean secret) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    /** One line for the Agents tab: "model · provider · key saved". */
    static String summary(Activity a, AgentConfig config) {
        String model = config.model();
        boolean key = !config.apiKey().isEmpty();
        return (model == null || model.isEmpty() ? a.getString(R.string.agents_not_set) : model) + " · " + config.provider()
                + " · " + a.getString(key || config.provider().equals("custom") ? R.string.agents_key_saved : R.string.agents_key_none);
    }
}
