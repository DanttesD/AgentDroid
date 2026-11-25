package com.termux.app.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.widget.AdapterView
import android.widget.EditText
import com.termux.R

/** Lets the user choose which browser profile (account) to open. Long press renames a profile.*/
object BrowserPicker {
    @JvmField val SLOTS: Array<Class<*>> = arrayOf<Class<*>>(WebBrowserActivity::class.java, WebBrowserSlots.Slot1::class.java, WebBrowserSlots.Slot2::class.java, WebBrowserSlots.Slot3::class.java, WebBrowserSlots.Slot4::class.java, WebBrowserSlots.Slot5::class.java, WebBrowserSlots.Slot6::class.java, WebBrowserSlots.Slot7::class.java)

    @JvmStatic
    private fun prefs(c: Context): SharedPreferences {
        return c.getApplicationContext().getSharedPreferences("agentdroid_browser", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun name(c: Context, slot: Int): String? {
        val n: String? = prefs(c).getString("name" + slot, "")
        if (!n!!.isEmpty()) {
            return n
        }
        return if (slot == 0) c.getString(R.string.agent_browser_main) else c.getString(R.string.agent_browser_profile, slot + 1)
    }

    @JvmStatic
    fun show(a: Activity, url: String?) {
        val names = arrayOfNulls<String>(SLOTS.size)
        for (i in 0 until (names.size)) {
            names[i] = name(a, i)
        }
        val d: AlertDialog = ThemedDialog(a).setTitle(R.string.agent_browser_pick).setItems(names, DialogInterface.OnClickListener { dlg, which ->
            val i = Intent(a, SLOTS[which])
            if (url != null) {
                i.putExtra("url", url)
            }
            a.startActivity(i)
        }).setNegativeButton(R.string.agent_cancel, null).create()
        d.show()
        d.getListView().setOnItemLongClickListener(AdapterView.OnItemLongClickListener { parent, view, pos, id ->
            rename(a, pos)
            d.dismiss()
            return@OnItemLongClickListener true
        })
    }

    @JvmStatic
    private fun rename(a: Activity, slot: Int) {
        val e = EditText(a)
        e.setText(prefs(a).getString("name" + slot, ""))
        e.setHint(name(a, slot))
        ThemedDialog(a).setTitle(R.string.agent_browser_rename).setView(e).setPositiveButton(R.string.agent_save, DialogInterface.OnClickListener { dlg, w ->
            prefs(a).edit().putString("name" + slot, (e.getText().toString() as java.lang.String).trim()).apply()
        }).setNegativeButton(R.string.agent_cancel, null).show()
    }
}
