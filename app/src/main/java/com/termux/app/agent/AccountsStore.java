package com.termux.app.agent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Named accounts per agent. Each has its own data directory and its own browser profile. */
public final class AccountsStore {
    public static final int MAX_PER_AGENT = BrowserPicker.SLOTS.length - 1; // slot 0 is the main profile

    public static final class Account {
        public final String agentId;
        public final String name;
        public final int browserSlot;

        Account(String agentId, String name, int browserSlot) {
            this.agentId = agentId; this.name = name; this.browserSlot = browserSlot;
        }

        public String dir() {
            return AgentBackends.TERMUX_HOME + "/.agentdroid/accounts/" + agentId + "/" + name;
        }
    }

    private final SharedPreferences prefs;

    public AccountsStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_accounts", Context.MODE_PRIVATE);
    }

    public synchronized List<Account> all() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Account(o.getString("agent"), o.getString("name"), o.getInt("slot")));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    public List<Account> of(String agentId) {
        List<Account> out = new ArrayList<>();
        for (Account a : all()) if (a.agentId.equals(agentId)) out.add(a);
        return out;
    }

    public static boolean validName(String n) { return n != null && n.matches("[A-Za-z0-9_-]{1,32}"); }

    /** Adds an account with the lowest free browser profile; null when the name is taken or there is no free profile. */
    public synchronized Account add(String agentId, String name) {
        List<Account> mine = of(agentId);
        boolean[] used = new boolean[BrowserPicker.SLOTS.length];
        for (Account a : mine) {
            if (a.name.equals(name)) return null;
            used[a.browserSlot] = true;
        }
        int slot = -1;
        for (int i = 1; i < used.length; i++) if (!used[i]) { slot = i; break; }
        if (slot < 0) return null;
        Account acc = new Account(agentId, name, slot);
        List<Account> all = all();
        all.add(acc);
        save(all);
        return acc;
    }

    public synchronized void remove(Account acc) {
        List<Account> keep = new ArrayList<>();
        for (Account a : all()) if (!(a.agentId.equals(acc.agentId) && a.name.equals(acc.name))) keep.add(a);
        save(keep);
    }

    private void save(List<Account> list) {
        JSONArray a = new JSONArray();
        try {
            for (Account x : list) a.put(new JSONObject().put("agent", x.agentId).put("name", x.name).put("slot", x.browserSlot));
        } catch (JSONException ignored) { }
        prefs.edit().putString("list", a.toString()).apply();
    }
}
