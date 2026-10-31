package com.agentdroid.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Anthropic Messages API (/v1/messages) with streaming and tool use. */
public final class AnthropicProvider implements ModelProvider {
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public AnthropicProvider(String baseUrl, String apiKey, String model, int maxTokens) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public static AnthropicProvider claude(String apiKey, String model) {
        return new AnthropicProvider("https://api.anthropic.com", apiKey, model, 8192);
    }

    @Override public String id() { return "anthropic"; }

    String buildBody(String system, List<Message> history, List<ToolSpec> tools) {
        List<Object> msgs = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            Message m = history.get(i);
            switch (m.role) {
                case USER:
                    msgs.add(Json.obj("role", "user", "content", m.text));
                    break;
                case ASSISTANT: {
                    List<Object> blocks = new ArrayList<>();
                    if (!m.text.isEmpty()) blocks.add(Json.obj("type", "text", "text", m.text));
                    for (ToolCall c : m.toolCalls) {
                        blocks.add(Json.obj("type", "tool_use", "id", c.id, "name", c.name, "input", c.args));
                    }
                    msgs.add(Json.obj("role", "assistant", "content", blocks));
                    break;
                }
                case TOOL: {
                    // consecutive tool results go into one user message
                    List<Object> blocks = new ArrayList<>();
                    int j = i;
                    while (j < history.size() && history.get(j).role == Message.Role.TOOL) {
                        Message t = history.get(j);
                        Map<String, Object> b = Json.obj("type", "tool_result", "tool_use_id", t.toolCallId, "content", t.text);
                        if (t.error) b.put("is_error", Boolean.TRUE);
                        blocks.add(b);
                        j++;
                    }
                    i = j - 1;
                    msgs.add(Json.obj("role", "user", "content", blocks));
                    break;
                }
            }
        }
        Map<String, Object> body = Json.obj("model", model, "max_tokens", (long) maxTokens, "stream", true, "messages", msgs);
        if (system != null && !system.isEmpty()) body.put("system", system);
        if (!tools.isEmpty()) {
            List<Object> ts = new ArrayList<>();
            for (ToolSpec t : tools) ts.add(Json.obj("name", t.name, "description", t.description, "input_schema", t.schema));
            body.put("tools", ts);
        }
        return Json.stringify(body);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Reply chat(String system, List<Message> history, List<ToolSpec> tools, final StreamListener listener) throws IOException {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, String[]> blocks = new TreeMap<>(); // index -> {id, name, jsonArgs} for tool_use only
        final String[] stop = {null};
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey == null ? "" : apiKey);
        headers.put("anthropic-version", "2023-06-01");
        Http.postSse(baseUrl + "/v1/messages", headers, buildBody(system, history, tools), new Http.EventSink() {
            @Override public void onData(String data) throws IOException {
                Map<String, Object> ev = Json.parseObject(data);
                String type = (String) ev.get("type");
                if ("content_block_start".equals(type)) {
                    int idx = ((Number) ev.get("index")).intValue();
                    Map<String, Object> cb = (Map<String, Object>) ev.get("content_block");
                    if ("tool_use".equals(cb.get("type"))) {
                        blocks.put(idx, new String[]{(String) cb.get("id"), (String) cb.get("name"), ""});
                    }
                } else if ("content_block_delta".equals(type)) {
                    int idx = ((Number) ev.get("index")).intValue();
                    Map<String, Object> d = (Map<String, Object>) ev.get("delta");
                    String dt = (String) d.get("type");
                    if ("text_delta".equals(dt)) {
                        String t = (String) d.get("text");
                        text.append(t);
                        listener.onText(t);
                    } else if ("input_json_delta".equals(dt)) {
                        String[] b = blocks.get(idx);
                        if (b != null) b[2] += (String) d.get("partial_json");
                    }
                } else if ("message_delta".equals(type)) {
                    Map<String, Object> d = (Map<String, Object>) ev.get("delta");
                    if (d != null && d.get("stop_reason") != null) stop[0] = (String) d.get("stop_reason");
                } else if ("error".equals(type)) {
                    Map<String, Object> e = (Map<String, Object>) ev.get("error");
                    throw new IOException("Anthropic error: " + (e == null ? data : e.get("message")));
                }
            }
        });
        List<ToolCall> out = new ArrayList<>();
        for (String[] b : blocks.values()) {
            Map<String, Object> args = b[2].trim().isEmpty() ? new LinkedHashMap<String, Object>() : Json.parseObject(b[2]);
            out.add(new ToolCall(b[0], b[1], args));
        }
        return new Reply(text.toString(), out, stop[0]);
    }
}
