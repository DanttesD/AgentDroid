package com.termux.app.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.agentdroid.agent.Triggers;

import java.util.List;

/** Notification rules, kept as JSON in private preferences. */
public final class TriggerStore {
    private final SharedPreferences prefs;

    public TriggerStore(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_triggers", Context.MODE_PRIVATE);
    }

    public List<Triggers.Rule> all() { return Triggers.parse(prefs.getString("rules", "")); }

    public void save(List<Triggers.Rule> rules) { prefs.edit().putString("rules", Triggers.toJson(rules)).apply(); }

    public Triggers.Rule find(String id) {
        for (Triggers.Rule r : all()) if (r.id.equals(id)) return r;
        return null;
    }

    public void put(Triggers.Rule rule) {
        List<Triggers.Rule> l = all();
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(rule.id)) { l.set(i, rule); found = true; }
        if (!found) l.add(rule);
        save(l);
    }

    public void remove(String id) {
        List<Triggers.Rule> l = all();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        save(l);
    }
}
