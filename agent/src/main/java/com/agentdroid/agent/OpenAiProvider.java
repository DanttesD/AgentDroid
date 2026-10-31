package com.agentdroid.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** OpenAI-compatible /chat/completions with streaming and tool calls (OpenAI, DeepSeek, OpenRouter, local servers...). */
public final class OpenAiProvider implements ModelProvider {
    private final String id;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiProvider(String id, String baseUrl, String apiKey, String model) {
        this.id = id;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public static OpenAiProvider openai(String apiKey, String model) {
        return new OpenAiProvider("openai", "https://api.openai.com/v1", apiKey, model);
    }

    public static OpenAiProvider deepseek(String apiKey) {
        return new OpenAiProvider("deepseek", "https://api.deepseek.com", apiKey, "deepseek-chat");
    }

    @Override public String id() { return id; }

    String buildBody(String system, List<Message> history, List<ToolSpec> tools) {
        List<Object> msgs = new ArrayList<>();
        if (system != null && !system.isEmpty()) msgs.add(Json.obj("role", "system", "content", system));
        for (Message m : history) {
            switch (m.role) {
                case USER:
                    msgs.add(Json.obj("role", "user", "content", m.text));
                    break;
                case ASSISTANT: {
                    Map<String, Object> a = Json.obj("role", "assistant", "content", m.text.isEmpty() ? null : m.text);
                    if (!m.toolCalls.isEmpty()) {
                        List<Object> calls = new ArrayList<>();
                        for (ToolCall c : m.toolCalls) {
                            calls.add(Json.obj("id", c.id, "type", "function",
                                    "function", Json.obj("name", c.name, "arguments", Json.stringify(c.args))));
                        }
                        a.put("tool_calls", calls);
                    }
                    msgs.add(a);
                    break;
                }
                case TOOL:
                    msgs.add(Json.obj("role", "tool", "tool_call_id", m.toolCallId, "content", m.text));
                    break;
            }
        }
        Map<String, Object> body = Json.obj("model", model, "messages", msgs, "stream", true);
        if (!tools.isEmpty()) {
            List<Object> ts = new ArrayList<>();
            for (ToolSpec t : tools) {
                ts.add(Json.obj("type", "function", "function",
                        Json.obj("name", t.name, "description", t.description, "parameters", t.schema)));
            }
            body.put("tools", ts);
        }
        return Json.stringify(body);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Reply chat(String system, List<Message> history, List<ToolSpec> tools, final StreamListener listener) throws IOException {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, String[]> calls = new TreeMap<>(); // index -> {id, name, args}
        final String[] finish = {null};
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) headers.put("Authorization", "Bearer " + apiKey);
        Http.postSse(baseUrl + "/chat/completions", headers, buildBody(system, history, tools), new Http.EventSink() {
            @Override public void onData(String data) {
                if (data.trim().equals("[DONE]")) return;
                Map<String, Object> ev = Json.parseObject(data);
                List<Object> choices = (List<Object>) ev.get("choices");
                if (choices == null || choices.isEmpty()) return;
                Map<String, Object> ch = (Map<String, Object>) choices.get(0);
                if (ch.get("finish_reason") != null) finish[0] = (String) ch.get("finish_reason");
                Map<String, Object> delta = (Map<String, Object>) ch.get("delta");
                if (delta == null) return;
                Object content = delta.get("content");
                if (content instanceof String && !((String) content).isEmpty()) {
                    text.append((String) content);
                    listener.onText((String) content);
                }
                List<Object> tcs = (List<Object>) delta.get("tool_calls");
                if (tcs != null) {
                    for (Object o : tcs) {
                        Map<String, Object> tc = (Map<String, Object>) o;
                        int idx = tc.get("index") instanceof Number ? ((Number) tc.get("index")).intValue() : 0;
                        String[] slot = calls.get(idx);
                        if (slot == null) { slot = new String[]{"", "", ""}; calls.put(idx, slot); }
                        if (tc.get("id") instanceof String) slot[0] = (String) tc.get("id");
                        Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                        if (fn != null) {
                            if (fn.get("name") instanceof String) slot[1] += (String) fn.get("name");
                            if (fn.get("arguments") instanceof String) slot[2] += (String) fn.get("arguments");
                        }
                    }
                }
            }
        });
        List<ToolCall> out = new ArrayList<>();
        for (String[] s : calls.values()) {
            Map<String, Object> args = s[2].trim().isEmpty() ? new LinkedHashMap<String, Object>() : Json.parseObject(s[2]);
            out.add(new ToolCall(s[0].isEmpty() ? "call_" + out.size() : s[0], s[1], args));
        }
        return new Reply(text.toString(), out, finish[0]);
    }
}
