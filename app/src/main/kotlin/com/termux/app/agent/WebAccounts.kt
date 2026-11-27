package com.termux.app.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Web accounts shown in the main window (name, chosen service, own browser profile) and the list of services.*/
class WebAccounts {
    class Service {
        @JvmField val name: String
        @JvmField val url: String

        constructor(name: String, url: String) {
            this.name = name
            this.url = url
        }
    }

    class Account {
        @JvmField val id: Int
        @JvmField var name: String
        @JvmField var url: String

        constructor(id: Int, name: String, url: String) {
            this.id = id
            this.name = name
            this.url = url
        }

        fun profile(): String {
            return "acc" + id
        }
    }

    private val prefs: SharedPreferences
    private val defaultName: String

    constructor(c: Context, defaultName: String) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_webaccounts", Context.MODE_PRIVATE)
        this.defaultName = defaultName
    }

    fun services(): MutableList<Service> {
        val out: MutableList<Service> = ArrayList<Service>()
        for (s in BUILTIN) {
            out.add(s)
        }
        try {
            val a = JSONArray(prefs.getString("services", "[]"))
            var i = 0
            while (i < a.length()) {
                val o: JSONObject = a.getJSONObject(i)
                out.add(Service(o.getString("name"), o.getString("url")))
                i++
            }
        } catch (ignored: JSONException) {
        }
        return out
    }

    @Synchronized
    fun addService(name: String, url: String) {
        try {
            val a = JSONArray(prefs.getString("services", "[]"))
            a.put(JSONObject().put("name", name).put("url", url))
            prefs.edit().putString("services", a.toString()).apply()
        } catch (ignored: JSONException) {
        }
    }

    @Synchronized
    fun accounts(): MutableList<Account> {
        val out: MutableList<Account> = ArrayList<Account>()
        try {
            val a = JSONArray(prefs.getString("accounts", "[]"))
            var i = 0
            while (i < a.length()) {
                val o: JSONObject = a.getJSONObject(i)
                out.add(Account(o.getInt("id"), o.getString("name"), o.optString("url", "")))
                i++
            }
        } catch (ignored: JSONException) {
        }
        if (out.isEmpty()) {
            out.add(Account(1, defaultName, ""))
            save(out)
        }
        return out
    }

    @Synchronized
    fun add(): Account {
        val list: MutableList<Account> = accounts()
        var id: Int = prefs.getInt("next_id", 2)
        for (a in list) {
            if (a.id >= id) {
                id = a.id + 1
            }
        }
        val acc = Account(id, defaultName, "")
        list.add(acc)
        prefs.edit().putInt("next_id", id + 1).apply()
        save(list)
        return acc
    }

    @Synchronized
    fun update(acc: Account) {
        val list: MutableList<Account> = accounts()
        for (a in list) {
            if (a.id == acc.id) {
                a.name = acc.name
                a.url = acc.url
            }
        }
        save(list)
    }

    /** Removes the account; the last remaining one is only reset so the list is never empty.*/
    @Synchronized
    fun remove(acc: Account) {
        val list: MutableList<Account> = accounts()
        val keep: MutableList<Account> = ArrayList<Account>()
        for (a in list) {
            if (a.id != acc.id) {
                keep.add(a)
            }
        }
        if (keep.isEmpty()) {
            keep.add(Account(acc.id, defaultName, ""))
        }
        save(keep)
    }

    private fun save(list: MutableList<Account>) {
        val a = JSONArray()
        try {
            for (x in list) {
                a.put(JSONObject().put("id", x.id).put("name", x.name).put("url", x.url))
            }
        } catch (ignored: JSONException) {
        }
        prefs.edit().putString("accounts", a.toString()).apply()
    }

    companion object {

        private val BUILTIN: Array<Service> = arrayOf<Service>(Service("Claude", "https://claude.ai/new"), Service("ChatGPT", "https://chatgpt.com"), Service("DeepSeek", "https://chat.deepseek.com"), Service("Gemini", "https://gemini.google.com"), Service("Grok", "https://grok.com"), Service("Perplexity", "https://www.perplexity.ai"), Service("Mistral Le Chat", "https://chat.mistral.ai"), Service("Qwen", "https://chat.qwen.ai"), Service("Copilot", "https://copilot.microsoft.com"))
    }
}
