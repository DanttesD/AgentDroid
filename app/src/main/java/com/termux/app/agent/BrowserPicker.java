package com.termux.app.agent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.EditText;

import com.termux.R;

/** Lets the user choose which browser profile (account) to open. Long press renames a profile. */
public final class BrowserPicker {
    public static final Class<?>[] SLOTS = {
            WebBrowserActivity.class,
            WebBrowserSlots.Slot1.class, WebBrowserSlots.Slot2.class, WebBrowserSlots.Slot3.class,
            WebBrowserSlots.Slot4.class, WebBrowserSlots.Slot5.class, WebBrowserSlots.Slot6.class,
            WebBrowserSlots.Slot7.class};

    private BrowserPicker() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("agentdroid_browser", Context.MODE_PRIVATE);
    }

    public static String name(Context c, int slot) {
        String n = prefs(c).getString("name" + slot, "");
        if (!n.isEmpty()) return n;
        return slot == 0 ? c.getString(R.string.agent_browser_main) : c.getString(R.string.agent_browser_profile, slot + 1);
    }

    public static void show(final Activity a, final String url) {
        String[] names = new String[SLOTS.length];
        for (int i = 0; i < names.length; i++) names[i] = name(a, i);
        final AlertDialog d = new AlertDialog.Builder(a).setTitle(R.string.agent_browser_pick)
                .setItems(names, (dlg, which) -> {
                    Intent i = new Intent(a, SLOTS[which]);
                    if (url != null) i.putExtra("url", url);
                    a.startActivity(i);
                }).setNegativeButton(R.string.agent_cancel, null).create();
        d.show();
        d.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            rename(a, pos);
            d.dismiss();
            return true;
        });
    }

    private static void rename(final Activity a, final int slot) {
        final EditText e = new EditText(a);
        e.setText(prefs(a).getString("name" + slot, ""));
        e.setHint(name(a, slot));
        new AlertDialog.Builder(a).setTitle(R.string.agent_browser_rename).setView(e)
                .setPositiveButton(R.string.agent_save, (dlg, w) ->
                        prefs(a).edit().putString("name" + slot, e.getText().toString().trim()).apply())
                .setNegativeButton(R.string.agent_cancel, null).show();
    }
}
