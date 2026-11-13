package com.agentdroid.agent

import java.io.IOException
import java.util.TreeMap

/** Anthropic Messages API (/v1/messages) with streaming and tool use. */
class AnthropicProvider(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val maxTokens: Int,
) : ModelProvider {
    private val baseUrl: String = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl

    override fun id(): String = "anthropic"

    internal fun buildBody(system: String?, history: List<Message>, tools: List<ToolSpec>): String {
        val msgs = ArrayList<Any?>()
        var i = 0
        while (i < history.size) {
            val m = history[i]
            when (m.role) {
                Message.Role.USER -> msgs.add(Json.obj("role", "user", "content", m.text))
                Message.Role.ASSISTANT -> {
                    val blocks = ArrayList<Any?>()
                    if (m.text.isNotEmpty()) blocks.add(Json.obj("type", "text", "text", m.text))
                    for (c in m.toolCalls) {
                        blocks.add(Json.obj("type", "tool_use", "id", c.id, "name", c.name, "input", c.args))
                    }
                    msgs.add(Json.obj("role", "assistant", "content", blocks))
                }
                Message.Role.TOOL -> {
                    // consecutive tool results go into one user message
                    val blocks = ArrayList<Any?>()
                    var j = i
                    while (j < history.size && history[j].role == Message.Role.TOOL) {
                        val t = history[j]
                        val b = Json.obj("type", "tool_result", "tool_use_id", t.toolCallId, "content", t.text)
                        if (t.error) b["is_error"] = true
                        blocks.add(b)
                        j++
                    }
                    i = j - 1
                    msgs.add(Json.obj("role", "user", "content", blocks))
                }
            }
            i++
        }
        val body = Json.obj("model", model, "max_tokens", maxTokens.toLong(), "stream", true, "messages", msgs)
        if (system != null && system.isNotEmpty()) body["system"] = system
        if (tools.isNotEmpty()) {
            val ts = ArrayList<Any?>()
            for (t in tools) ts.add(Json.obj("name", t.name, "description", t.description, "input_schema", t.schema))
            body["tools"] = ts
        }
        return Json.stringify(body)
    }

    /** A streamed tool_use block: id, name and the JSON arguments arriving in pieces. */
    private class ToolBlock(val id: String?, val name: String?) {
        val args = StringBuilder()
    }

    @Throws(IOException::class)
    @Suppress("UNCHECKED_CAST")
    override fun chat(system: String?, history: List<Message>, tools: List<ToolSpec>, listener: StreamListener): Reply {
        val text = StringBuilder()
        val blocks = TreeMap<Int, ToolBlock>() // index -> tool_use block
        var stop: String? = null
        val headers = LinkedHashMap<String, String>()
        headers["x-api-key"] = apiKey ?: ""
        headers["anthropic-version"] = "2023-06-01"
        Http.postSse(baseUrl + "/v1/messages", headers, buildBody(system, history, tools)) { data ->
            val ev = Json.parseObject(data)
            val type = ev["type"] as String?
            if ("content_block_start" == type) {
                val idx = (ev["index"] as Number).toInt()
                val cb = ev["content_block"] as Map<String, Any?>
                if ("tool_use" == cb["type"]) {
                    blocks[idx] = ToolBlock(cb["id"] as String?, cb["name"] as String?)
                }
            } else if ("content_block_delta" == type) {
                val idx = (ev["index"] as Number).toInt()
                val d = ev["delta"] as Map<String, Any?>
                val dt = d["type"] as String?
                if ("text_delta" == dt) {
                    val t = d["text"] as String? ?: ""
                    text.append(t)
                    listener.onText(t)
                } else if ("input_json_delta" == dt) {
                    val b = blocks[idx]
                    if (b != null) b.args.append(d["partial_json"] as String?)
                }
            } else if ("message_delta" == type) {
                val d = ev["delta"] as Map<String, Any?>?
                if (d != null && d["stop_reason"] != null) stop = d["stop_reason"] as String?
            } else if ("error" == type) {
                val e = ev["error"] as Map<String, Any?>?
                throw IOException("Anthropic error: " + (if (e == null) data else e["message"]))
            }
        }
        val out = ArrayList<ToolCall>()
        for (b in blocks.values) {
            val raw = b.args.toString()
            val args: Map<String, Any?> = if (raw.jtrim().isEmpty()) LinkedHashMap() else Json.parseObject(raw)
            out.add(ToolCall(b.id, b.name, args))
        }
        return Reply(text.toString(), out, stop)
    }

    companion object {
        @JvmStatic
        fun claude(apiKey: String?, model: String): AnthropicProvider =
            AnthropicProvider("https://api.anthropic.com", apiKey, model, 8192)
    }
}
