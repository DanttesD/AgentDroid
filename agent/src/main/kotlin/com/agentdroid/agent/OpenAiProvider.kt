package com.agentdroid.agent

import java.io.IOException
import java.util.TreeMap

/** OpenAI-compatible /chat/completions with streaming and tool calls (OpenAI, DeepSeek, OpenRouter, local servers...). */
class OpenAiProvider(
    private val providerId: String,
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
) : ModelProvider {
    private val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl

    override fun id(): String = providerId

    internal fun buildBody(system: String?, history: List<Message>, tools: List<ToolSpec>): String {
        val msgs = ArrayList<Any?>()
        if (system != null && system.isNotEmpty()) msgs.add(Json.obj("role", "system", "content", system))
        for (m in history) {
            when (m.role) {
                Message.Role.USER -> msgs.add(Json.obj("role", "user", "content", m.text))
                Message.Role.ASSISTANT -> {
                    val a = Json.obj("role", "assistant", "content", if (m.text.isEmpty()) null else m.text)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = ArrayList<Any?>()
                        for (c in m.toolCalls) {
                            calls.add(
                                Json.obj(
                                    "id", c.id, "type", "function",
                                    "function", Json.obj("name", c.name, "arguments", Json.stringify(c.args)),
                                ),
                            )
                        }
                        a["tool_calls"] = calls
                    }
                    msgs.add(a)
                }
                Message.Role.TOOL -> msgs.add(Json.obj("role", "tool", "tool_call_id", m.toolCallId, "content", m.text))
            }
        }
        val body = Json.obj("model", model, "messages", msgs, "stream", true)
        if (tools.isNotEmpty()) {
            val ts = ArrayList<Any?>()
            for (t in tools) {
                ts.add(
                    Json.obj(
                        "type", "function",
                        "function", Json.obj("name", t.name, "description", t.description, "parameters", t.schema),
                    ),
                )
            }
            body["tools"] = ts
        }
        return Json.stringify(body)
    }

    /** A streamed tool call: id, name and arguments arriving in pieces. */
    private class Slot {
        var id = ""
        val name = StringBuilder()
        val args = StringBuilder()
    }

    @Throws(IOException::class)
    @Suppress("UNCHECKED_CAST")
    override fun chat(system: String?, history: List<Message>, tools: List<ToolSpec>, listener: StreamListener): Reply {
        val text = StringBuilder()
        val calls = TreeMap<Int, Slot>() // index -> tool call being assembled
        var finish: String? = null
        val headers = LinkedHashMap<String, String>()
        if (apiKey != null && apiKey.isNotEmpty()) headers["Authorization"] = "Bearer $apiKey"
        Http.postSse(
            baseUrl + "/chat/completions", headers, buildBody(system, history, tools),
            Http.EventSink { data ->
                if (data.jtrim() == "[DONE]") return@EventSink
                val ev = Json.parseObject(data)
                val choices = ev["choices"] as List<Any?>?
                if (choices == null || choices.isEmpty()) return@EventSink
                val ch = choices[0] as Map<String, Any?>
                if (ch["finish_reason"] != null) finish = ch["finish_reason"] as String?
                val delta = ch["delta"] as Map<String, Any?>? ?: return@EventSink
                val content = delta["content"]
                if (content is String && content.isNotEmpty()) {
                    text.append(content)
                    listener.onText(content)
                }
                val tcs = delta["tool_calls"] as List<Any?>?
                if (tcs != null) {
                    for (o in tcs) {
                        val tc = o as Map<String, Any?>
                        val index = tc["index"]
                        val idx = if (index is Number) index.toInt() else 0
                        var slot = calls[idx]
                        if (slot == null) {
                            slot = Slot()
                            calls[idx] = slot
                        }
                        val id = tc["id"]
                        if (id is String) slot.id = id
                        val fn = tc["function"] as Map<String, Any?>?
                        if (fn != null) {
                            val name = fn["name"]
                            if (name is String) slot.name.append(name)
                            val arguments = fn["arguments"]
                            if (arguments is String) slot.args.append(arguments)
                        }
                    }
                }
            },
        )
        val out = ArrayList<ToolCall>()
        for (s in calls.values) {
            val raw = s.args.toString()
            val args: Map<String, Any?> = if (raw.jtrim().isEmpty()) LinkedHashMap() else Json.parseObject(raw)
            out.add(ToolCall(if (s.id.isEmpty()) "call_" + out.size else s.id, s.name.toString(), args))
        }
        return Reply(text.toString(), out, finish)
    }

    companion object {
        @JvmStatic
        fun openai(apiKey: String?, model: String): OpenAiProvider =
            OpenAiProvider("openai", "https://api.openai.com/v1", apiKey, model)

        @JvmStatic
        fun deepseek(apiKey: String?): OpenAiProvider =
            OpenAiProvider("deepseek", "https://api.deepseek.com", apiKey, "deepseek-chat")
    }
}
