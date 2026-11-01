package com.termux.app.agent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Web accounts shown in the main window (name, chosen service, own browser profile) and the list of services. */
public final class WebAccounts {
    public static final class Service {
        public final String name;
        public final String url;
        Service(String name, String url) { this.name = name; this.url = url; }
    }

    public static final class Account {
        public final int id;
        public String name;
        public String url;
        Account(int id, String name, String url) { this.id = id; this.name = name; this.url = url; }
        public String profile() { return "acc" + id; }
    }

    private static final Service[] BUILTIN = {
            new Service("Claude", "https://claude.ai/new"),
            new Service("ChatGPT", "https://chatgpt.com"),
            new Service("DeepSeek", "https://chat.deepseek.com"),
            new Service("Gemini", "https://gemini.google.com"),
            new Service("Grok", "https://grok.com"),
            new Service("Perplexity", "https://www.perplexity.ai"),
            new Service("Mistral Le Chat", "https://chat.mistral.ai"),
            new Service("Qwen", "https://chat.qwen.ai"),
            new Service("Copilot", "https://copilot.microsoft.com"),
    };

    private final SharedPreferences prefs;
    private final String defaultName;

    public WebAccounts(Context c, String defaultName) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_webaccounts", Context.MODE_PRIVATE);
        this.defaultName = defaultName;
    }

    public List<Service> services() {
        List<Service> out = new ArrayList<>();
        for (Service s : BUILTIN) out.add(s);
        try {
            JSONArray a = new JSONArray(prefs.getString("services", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Service(o.getString("name"), o.getString("url")));
            }
        } catch (JSONException ignored) { }
        return out;
    }

    public synchronized void addService(String name, String url) {
        try {
            JSONArray a = new JSONArray(prefs.getString("services", "[]"));
            a.put(new JSONObject().put("name", name).put("url", url));
            prefs.edit().putString("services", a.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public synchronized List<Account> accounts() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("accounts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Account(o.getInt("id"), o.getString("name"), o.optString("url", "")));
            }
        } catch (JSONException ignored) { }
        if (out.isEmpty()) {
            out.add(new Account(1, defaultName, ""));
            save(out);
        }
        return out;
    }

    public synchronized Account add() {
        List<Account> list = accounts();
        int id = prefs.getInt("next_id", 2);
        for (Account a : list) if (a.id >= id) id = a.id + 1;
        Account acc = new Account(id, defaultName, "");
        list.add(acc);
        prefs.edit().putInt("next_id", id + 1).apply();
        save(list);
        return acc;
    }

    public synchronized void update(Account acc) {
        List<Account> list = accounts();
        for (Account a : list) if (a.id == acc.id) { a.name = acc.name; a.url = acc.url; }
        save(list);
    }

    /** Removes the account; the last remaining one is only reset so the list is never empty. */
    public synchronized void remove(Account acc) {
        List<Account> list = accounts();
        List<Account> keep = new ArrayList<>();
        for (Account a : list) if (a.id != acc.id) keep.add(a);
        if (keep.isEmpty()) { keep.add(new Account(acc.id, defaultName, "")); }
        save(keep);
    }

    private void save(List<Account> list) {
        JSONArray a = new JSONArray();
        try {
            for (Account x : list) a.put(new JSONObject().put("id", x.id).put("name", x.name).put("url", x.url));
        } catch (JSONException ignored) { }
        prefs.edit().putString("accounts", a.toString()).apply();
    }
}
