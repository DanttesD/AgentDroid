package com.termux.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.termux.R

/**
 * Provider, API address, model and key of the built-in agent. One dialog for the whole app: the Agents tab opens it,
 * and the chat opens it when a message is sent without a key.
 **/
object ProviderDialog {

    @JvmStatic
    fun show(a: Activity, config: AgentConfig?, saved: Runnable?) {
        val ui = Ui(a)
        val original: String? = config!!.provider()
        val done: BooleanArray = booleanArrayOf(false)

        val box: LinearLayout = ui.column()
        box.setPadding(ui.dp(16.0f), ui.dp(12.0f), ui.dp(16.0f), 0)

        val sp = Spinner(a)
        val labelIds: IntArray = intArrayOf(R.string.agent_provider_anthropic, R.string.agent_provider_openai, R.string.agent_provider_deepseek, R.string.agent_provider_custom)
        val labels = arrayOfNulls<String>(labelIds.size)
        for (i in 0 until (labelIds.size)) {
            labels[i] = a.getString(labelIds[i])
        }
        sp.setAdapter(ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, labels))
        var cur = 0
        var i = 0
        while (i < AgentConfig.PROVIDERS.size) {
            if (AgentConfig.PROVIDERS[i].equals(original)) {
                cur = i
            }
            i++
        }
        sp.setSelection(cur)
        box.addView(sp)

        val url: EditText = field(a, false)
        val model: EditText = field(a, false)
        val key: EditText = field(a, true)
        box.addView(caption(ui, R.string.agent_base_url), ui.gap(10))
        box.addView(url)
        box.addView(caption(ui, R.string.agent_model), ui.gap(6))
        box.addView(model)
        box.addView(caption(ui, R.string.agent_key_hint), ui.gap(6))
        box.addView(key)

        val shown: IntArray = intArrayOf(cur)
        fill(config, url, model, key)
        sp.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, pos: Int, id: Long) {
                if (pos == shown[0]) {
                    return
                }
                config.setProvider(AgentConfig.PROVIDERS[pos])
                shown[0] = pos
                fill(config, url, model, key)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        })

        val dlg: AlertDialog = ThemedDialog(a).setTitle(R.string.agent_provider_title).setView(box).setPositiveButton(R.string.agent_save, null).setNegativeButton(R.string.agent_cancel, null).create()
        // Leaving without saving must not keep the provider that was only browsed in the spinner.
        dlg.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            if (!done[0]) {
                config.setProvider(original)
            }
        })
        dlg.show()
        dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
            val u: String = (url.getText().toString() as java.lang.String).trim()
            if (!(u as java.lang.String).matches("(?i)https?://.+")) {
                Toast.makeText(a, R.string.agent_bad_url, Toast.LENGTH_LONG).show()
                return@OnClickListener
            }
            config.setProvider(AgentConfig.PROVIDERS[sp.getSelectedItemPosition()])
            config.setBaseUrl(u)
            config.setModel((model.getText().toString() as java.lang.String).trim())
            val k: String = (key.getText().toString() as java.lang.String).trim()
            if (!k.isEmpty()) {
                config.setApiKey(k)
            }
            done[0] = true
            dlg.dismiss()
            if (saved != null) {
                saved.run()
            }
        })
    }

    @JvmStatic
    private fun fill(config: AgentConfig?, url: EditText, model: EditText, key: EditText) {
        url.setText(config!!.baseUrl())
        model.setText(config.model())
        key.setText("")
        key.setHint(if (config.apiKey().isEmpty()) R.string.agent_key_unset else R.string.agent_key_saved)
    }

    @JvmStatic
    private fun caption(ui: Ui, res: Int): TextView {
        return ui.text(ui.ctx.getString(res), 12.5f, ui.t.on2, true)
    }

    @JvmStatic
    private fun field(a: Activity, secret: Boolean): EditText {
        val e = EditText(a)
        e.setSingleLine(true)
        e.setInputType(if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        return e
    }

    /** One line for the Agents tab: "model · provider · key saved".*/
    @JvmStatic
    fun summary(a: Activity, config: AgentConfig): String {
        val model: String? = config.model()
        val key: Boolean = !config.apiKey().isEmpty()
        return (if (model == null || model.isEmpty()) a.getString(R.string.agents_not_set) else model) + " · " + config.provider() + " · " + a.getString(if (key || config.provider()!!.equals("custom")) R.string.agents_key_saved else R.string.agents_key_none)
    }
}
