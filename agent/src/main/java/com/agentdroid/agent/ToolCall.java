package com.agentdroid.agent;

import java.util.Map;

/** A model's request to run a tool. */
public final class ToolCall {
    public final String id;
    public final String name;
    public final Map<String, Object> args;

    public ToolCall(String id, String name, Map<String, Object> args) {
        this.id = id;
        this.name = name;
        this.args = args;
    }
}
