package com.termux.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.agentdroid.agent.ThemeColors
import com.agentdroid.agent.Triggers
import com.termux.R

/** Notification rules: "when X arrives, do Y", with a confirm / auto / off mode per rule.*/
class TriggersActivity : Activity() {
    private var store: TriggerStore? = null
    private var list: ListView? = null
    private var access: TextView? = null
    private var ui: Ui? = null

    private var themeSig: String = ""

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val th: ThemeColors = AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        ui = Ui(this)
        setTitle(R.string.trig_title)
        store = TriggerStore(this)
        val pad: Int = Math.round(16 * getResources().getDisplayMetrics().density)
        val col = LinearLayout(this)
        col.setOrientation(LinearLayout.VERTICAL)
        col.setPadding(pad, pad, pad, pad)
        col.setBackgroundColor(th.bg)
        col.addView(ui!!.title(getString(R.string.trig_title)))
        col.addView(ui!!.text(getString(R.string.trig_info), 14.0f, th.on2, false), ui!!.gap(8))
        access = ui!!.text("", 14.0f, th.on, true)
        access!!.setPadding(0, pad / 2, 0, pad / 2)
        col.addView(access)
        col.addView(ui!!.button(getString(R.string.trig_access_btn), Ui.Kind.TONAL, View.OnClickListener { v -> startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }))
        col.addView(ui!!.button(getString(R.string.trig_add), Ui.Kind.FILLED, View.OnClickListener { v -> edit(Triggers.Rule(), true) }), ui!!.gap(8))
        list = ListView(this)
        list!!.setDivider(ColorDrawable(th.line))
        list!!.setDividerHeight(1)
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        refresh()
    }

    private fun accessGranted(): Boolean {
        val flat: String? = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners")
        return flat != null && flat.contains(ComponentName(this, AgentNotificationListener::class.java).flattenToString())
    }

    private fun modeName(m: Int): String {
        return getResources().getStringArray(R.array.trig_modes)[Math.max(0, Math.min(2, m))]
    }

    private fun refresh() {
        access!!.setText(getString(R.string.trig_access, getString(if (accessGranted()) R.string.assistant_yes else R.string.assistant_no)))
        val rules: MutableList<Triggers.Rule> = store!!.all()
        val rows = arrayOfNulls<String>(rules.size)
        for (i in 0 until (rows.size)) {
            val r: Triggers.Rule = rules.get(i)
            rows[i] = (if (r.name.isEmpty()) r.goal else r.name) + "\n" + modeName(r.mode) + " · " + (if (r.pkg.isEmpty()) getString(R.string.trig_any_app) else r.pkg) + (if (r.contains.isEmpty()) "" else " · «" + r.contains + "»")
        }
        list!!.setAdapter(object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val tv: TextView = super.getView(pos, cv, parent) as TextView
                tv.setTextColor(ui!!.t.on)
                tv.setTextSize(14.0f)
                tv.setPadding(ui!!.dp(4.0f), ui!!.dp(12.0f), ui!!.dp(4.0f), ui!!.dp(12.0f))
                return tv
            }
        })
        list!!.setOnItemClickListener(AdapterView.OnItemClickListener { p, v, pos, id -> edit(rules.get(pos), false) })
    }

    private fun field(col: LinearLayout, hint: Int, value: String, multiline: Boolean): EditText {
        val e = EditText(this)
        e.setHint(hint)
        e.setText(value)
        e.setInputType(if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT)
        col.addView(e)
        return e
    }

    private fun edit(rule: Triggers.Rule, isNew: Boolean) {
        val pad: Int = Math.round(16 * getResources().getDisplayMetrics().density)
        val col = LinearLayout(this)
        col.setOrientation(LinearLayout.VERTICAL)
        col.setPadding(pad, pad / 2, pad, 0)
        val name: EditText = field(col, R.string.trig_f_name, rule.name, false)
        val pkg: EditText = field(col, R.string.trig_f_pkg, rule.pkg, false)
        val kw: EditText = field(col, R.string.trig_f_contains, rule.contains, false)
        val goal: EditText = field(col, R.string.trig_f_goal, rule.goal, true)
        val cool: EditText = field(col, R.string.trig_f_cooldown, java.lang.String.valueOf(rule.cooldownSec), false)
        cool.setInputType(InputType.TYPE_CLASS_NUMBER)
        val mode = Spinner(this)
        mode.setAdapter(ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, getResources().getStringArray(R.array.trig_modes)))
        mode.setSelection(rule.mode)
        col.addView(mode)
        val db: AlertDialog.Builder = ThemedDialog(this).setTitle(R.string.trig_title).setView(col).setPositiveButton(R.string.trig_save, DialogInterface.OnClickListener { d, w ->
            rule.name = (name.getText().toString() as java.lang.String).trim()
            rule.pkg = (pkg.getText().toString() as java.lang.String).trim()
            rule.contains = (kw.getText().toString() as java.lang.String).trim()
            rule.goal = (goal.getText().toString() as java.lang.String).trim()
            rule.mode = mode.getSelectedItemPosition()
            try {
                rule.cooldownSec = Integer.parseInt((cool.getText().toString() as java.lang.String).trim())
            } catch (e: NumberFormatException) {
                rule.cooldownSec = 60
            }
            if (rule.goal.isEmpty()) {
                return@OnClickListener
            }
            store!!.put(rule)
            refresh()
        }).setNegativeButton(android.R.string.cancel, null)
        if (!isNew) {
            db.setNeutralButton(R.string.trig_delete, DialogInterface.OnClickListener { d, w ->
                store!!.remove(rule.id)
                refresh()
            })
        }
        db.show()
    }
}
