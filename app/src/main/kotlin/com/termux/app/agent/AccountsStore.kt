package com.termux.app.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Named accounts per agent. Each has its own data directory and its own browser profile.*/
class AccountsStore {

    class Account {
        @JvmField val agentId: String
        @JvmField val name: String
        @JvmField val browserSlot: Int

        constructor(agentId: String, name: String, browserSlot: Int) {
            this.agentId = agentId
            this.name = name
            this.browserSlot = browserSlot
        }

        fun dir(): String {
            return AgentBackends.TERMUX_HOME + "/.agentdroid/accounts/" + agentId + "/" + name
        }
    }

    private val prefs: SharedPreferences

    constructor(c: Context) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_accounts", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): MutableList<Account> {
        val out: MutableList<Account> = ArrayList<Account>()
        try {
            val a = JSONArray(prefs.getString("list", "[]"))
            var i = 0
            while (i < a.length()) {
                val o: JSONObject = a.getJSONObject(i)
                out.add(Account(o.getString("agent"), o.getString("name"), o.getInt("slot")))
                i++
            }
        } catch (ignored: JSONException) {
        }
        return out
    }

    fun of(agentId: String): MutableList<Account> {
        val out: MutableList<Account> = ArrayList<Account>()
        for (a in all()) {
            if (a.agentId.equals(agentId)) {
                out.add(a)
            }
        }
        return out
    }

    /** Adds an account with the lowest free browser profile; null when the name is taken or there is no free profile.*/
    @Synchronized
    fun add(agentId: String, name: String): Account? {
        val mine: MutableList<Account> = of(agentId)
        val used = BooleanArray(BrowserPicker.SLOTS.size)
        for (a in mine) {
            if (a.name.equals(name)) {
                return null
            }
            used[a.browserSlot] = true
        }
        var slot = -1
        for (i in 1 until (used.size)) {
            if (!used[i]) {
                slot = i
                break
            }
        }
        if (slot < 0) {
            return null
        }
        val acc = Account(agentId, name, slot)
        val all: MutableList<Account> = all()
        all.add(acc)
        save(all)
        return acc
    }

    @Synchronized
    fun remove(acc: Account) {
        val keep: MutableList<Account> = ArrayList<Account>()
        for (a in all()) {
            if (!(a.agentId.equals(acc.agentId) && a.name.equals(acc.name))) {
                keep.add(a)
            }
        }
        save(keep)
    }

    private fun save(list: MutableList<Account>) {
        val a = JSONArray()
        try {
            for (x in list) {
                a.put(JSONObject().put("agent", x.agentId).put("name", x.name).put("slot", x.browserSlot))
            }
        } catch (ignored: JSONException) {
        }
        prefs.edit().putString("list", a.toString()).apply()
    }

    companion object {
        @JvmField val MAX_PER_AGENT: Int = BrowserPicker.SLOTS.size - 1 // slot 0 is the main profile

        @JvmStatic
        fun validName(n: String?): Boolean {
            return n != null && (n as java.lang.String).matches("[A-Za-z0-9_-]{1,32}")
        }
    }
}
