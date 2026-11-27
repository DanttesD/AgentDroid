package com.termux.app.agent

import android.content.Context
import android.content.SharedPreferences
import com.agentdroid.agent.AnthropicProvider
import com.agentdroid.agent.ModelProvider
import com.agentdroid.agent.OpenAiProvider

/** Chosen provider, model, endpoint; the API key lives in [SecretStore].*/
class AgentConfig {

    private val prefs: SharedPreferences
    private val secrets: SecretStore

    constructor(c: Context) {
        prefs = c.getApplicationContext().getSharedPreferences("agentdroid_agent", Context.MODE_PRIVATE)
        secrets = SecretStore(c)
        appCtx = c.getApplicationContext()
    }

    private val appCtx: Context

    fun provider(): String? {
        return prefs.getString("provider", "anthropic")
    }

    fun model(): String? {
        return prefs.getString("model_" + provider(), defaultModel(provider()))
    }

    fun baseUrl(): String? {
        return prefs.getString("base_" + provider(), defaultBase(provider()))
    }

    fun apiKey(): String {
        return secrets.get("key_" + provider())
    }

    fun backendPin(): String? {
        return prefs.getString("backend", "")
    }

    fun setProvider(p: String?) {
        prefs.edit().putString("provider", p).apply()
    }

    fun setModel(m: String) {
        prefs.edit().putString("model_" + provider(), m).apply()
    }

    fun setBaseUrl(u: String) {
        prefs.edit().putString("base_" + provider(), u).apply()
    }

    fun setApiKey(k: String) {
        secrets.put("key_" + provider(), k)
    }

    fun setBackendPin(id: String?) {
        prefs.edit().putString("backend", if (id == null) "" else id).apply()
    }

    fun baseUrlValid(): Boolean {
        return (baseUrl()!! as java.lang.String).matches("(?i)https?://.+")
    }

    fun build(): ModelProvider {
        BrowserProxy.syncAgent(appCtx)
        val p: String? = provider()
        if (p!!.equals("anthropic")) {
            return AnthropicProvider(baseUrl()!!, apiKey(), model()!!, 8192)
        }
        return OpenAiProvider(p, baseUrl()!!, apiKey(), model()!!)
    }

    companion object {
        @JvmField val PROVIDERS: Array<String> = arrayOf<String>("anthropic", "openai", "deepseek", "custom")
        @JvmField val PROVIDER_LABELS: Array<String> = arrayOf<String>("Anthropic (Claude)", "OpenAI", "DeepSeek", "Custom (OpenAI-compatible)")

        @JvmStatic
        fun defaultModel(p: String?): String {
            when (p!!) {
                "openai" -> {
                    return "gpt-4o"
                }
                "deepseek" -> {
                    return "deepseek-chat"
                }
                "custom" -> {
                    return ""
                }
                else -> {
                    return "claude-sonnet-4-5"
                }
            }
        }

        @JvmStatic
        fun defaultBase(p: String?): String {
            when (p!!) {
                "openai" -> {
                    return "https://api.openai.com/v1"
                }
                "deepseek" -> {
                    return "https://api.deepseek.com"
                }
                "custom" -> {
                    return "http://127.0.0.1:11434/v1"
                }
                else -> {
                    return "https://api.anthropic.com"
                }
            }
        }
    }
}
