package com.agentdroid.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The agent: model -> tool calls -> results -> model ... until the model answers without tools. */
public final class AgentLoop {
    public static final int DEFAULT_MAX_STEPS = 25;
    private static final int MAX_RESULT_CHARS = 20_000;

    private final ModelProvider provider;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Approver approver;
    private final String system;
    private final int maxSteps;
    private final List<Message> history = new ArrayList<>();
    private volatile boolean cancelled;

    public AgentLoop(ModelProvider provider, List<Tool> toolList, Approver approver, String system, int maxSteps) {
        this.provider = provider;
        for (Tool t : toolList) tools.put(t.spec().name, t);
        this.approver = approver;
        this.system = system;
        this.maxSteps = maxSteps;
    }

    public List<Message> history() { return new ArrayList<>(history); }

    public void cancel() { cancelled = true; }

    /** Runs one user turn to completion. Returns the final assistant text. */
    public String send(String userText, StreamListener listener) throws IOException {
        cancelled = false;
        history.add(Message.user(userText));
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool t : tools.values()) specs.add(t.spec());
        String last = "";
        for (int step = 0; step < maxSteps; step++) {
            if (cancelled) { listener.onError("cancelled"); return last; }
            Reply r = provider.chat(system, history, specs, listener);
            history.add(Message.assistant(r.text, r.toolCalls));
            last = r.text;
            if (r.toolCalls.isEmpty()) return last;
            for (ToolCall call : r.toolCalls) {
                listener.onToolCall(call);
                String result;
                boolean error = false;
                Tool tool = tools.get(call.name);
                if (cancelled) {
                    result = "cancelled by user"; error = true;
                } else if (tool == null) {
                    result = "unknown tool: " + call.name; error = true;
                } else if (tool.needsApproval(call.args) && !approver.approve(call, tool.describe(call.args))) {
                    result = "the user denied this action"; error = true;
                } else {
                    try {
                        result = tool.execute(call.args);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        result = "error: " + e.getMessage(); error = true;
                    }
                }
                if (result.length() > MAX_RESULT_CHARS) {
                    result = result.substring(0, MAX_RESULT_CHARS) + "\n...[truncated " + (result.length() - MAX_RESULT_CHARS) + " chars]";
                }
                listener.onToolResult(call, result, error);
                history.add(Message.toolResult(call.id, call.name, result, error));
            }
        }
        listener.onError("step limit reached (" + maxSteps + ")");
        return last;
    }
}
