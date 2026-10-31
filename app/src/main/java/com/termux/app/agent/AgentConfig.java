package com.termux.app.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.agentdroid.agent.AnthropicProvider;
import com.agentdroid.agent.ModelProvider;
import com.agentdroid.agent.OpenAiProvider;

/** Chosen provider, model, endpoint; the API key lives in {@link SecretStore}. */
public final class AgentConfig {
    public static final String[] PROVIDERS = {"anthropic", "openai", "deepseek", "custom"};
    public static final String[] PROVIDER_LABELS = {
            "Anthropic (Claude)", "OpenAI", "DeepSeek", "Custom (OpenAI-compatible)"};

    private final SharedPreferences prefs;
    private final SecretStore secrets;

    public AgentConfig(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_agent", Context.MODE_PRIVATE);
        secrets = new SecretStore(c);
    }

    public String provider() { return prefs.getString("provider", "anthropic"); }
    public String model() { return prefs.getString("model_" + provider(), defaultModel(provider())); }
    public String baseUrl() { return prefs.getString("base_" + provider(), defaultBase(provider())); }
    public String apiKey() { return secrets.get("key_" + provider()); }
    public String backendPin() { return prefs.getString("backend", ""); }

    public void setProvider(String p) { prefs.edit().putString("provider", p).apply(); }
    public void setModel(String m) { prefs.edit().putString("model_" + provider(), m).apply(); }
    public void setBaseUrl(String u) { prefs.edit().putString("base_" + provider(), u).apply(); }
    public void setApiKey(String k) { secrets.put("key_" + provider(), k); }
    public void setBackendPin(String id) { prefs.edit().putString("backend", id == null ? "" : id).apply(); }

    public static String defaultModel(String p) {
        switch (p) {
            case "openai": return "gpt-4o";
            case "deepseek": return "deepseek-chat";
            case "custom": return "";
            default: return "claude-sonnet-4-5";
        }
    }

    public static String defaultBase(String p) {
        switch (p) {
            case "openai": return "https://api.openai.com/v1";
            case "deepseek": return "https://api.deepseek.com";
            case "custom": return "http://127.0.0.1:11434/v1";
            default: return "https://api.anthropic.com";
        }
    }

    public ModelProvider build() {
        String p = provider();
        if (p.equals("anthropic")) return new AnthropicProvider(baseUrl(), apiKey(), model(), 8192);
        return new OpenAiProvider(p, baseUrl(), apiKey(), model());
    }
}
