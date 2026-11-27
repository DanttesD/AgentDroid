package com.termux.app.agent

import android.content.Context
import android.content.SharedPreferences
import com.agentdroid.agent.Triggers

/** Notification rules, kept as JSON in private preferences.*/
class TriggerStore {
    private val prefs: SharedPreferences

    constructor(c: Context) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_triggers", Context.MODE_PRIVATE)
    }

    fun all(): MutableList<Triggers.Rule> {
        return Triggers.parse(prefs.getString("rules", "")) as MutableList<Triggers.Rule>
    }

    fun save(rules: MutableList<Triggers.Rule>) {
        prefs.edit().putString("rules", Triggers.toJson(rules)).apply()
    }

    fun find(id: String?): Triggers.Rule? {
        for (r in all()) {
            if (r.id.equals(id)) {
                return r
            }
        }
        return null
    }

    fun put(rule: Triggers.Rule) {
        val l: MutableList<Triggers.Rule> = all()
        var found = false
        var i = 0
        while (i < l.size) {
            if (l.get(i).id.equals(rule.id)) {
                l.set(i, rule)
                found = true
            }
            i++
        }
        if (!found) {
            l.add(rule)
        }
        save(l)
    }

    fun remove(id: String) {
        val l: MutableList<Triggers.Rule> = all()
        for (i in l.size - 1 downTo 0) {
            if (l.get(i).id.equals(id)) {
                l.removeAt(i)
            }
        }
        save(l)
    }
}
