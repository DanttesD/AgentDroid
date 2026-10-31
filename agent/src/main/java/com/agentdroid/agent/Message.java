package com.agentdroid.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One conversation entry. Roles: user, assistant, tool (tool result). */
public final class Message {
    public enum Role { USER, ASSISTANT, TOOL }

    public final Role role;
    public final String text;
    public final List<ToolCall> toolCalls;
    public final String toolCallId;
    public final String toolName;
    public final boolean error;

    private Message(Role role, String text, List<ToolCall> calls, String toolCallId, String toolName, boolean error) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.toolCalls = calls == null ? Collections.<ToolCall>emptyList() : new ArrayList<>(calls);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.error = error;
    }

    public static Message user(String text) { return new Message(Role.USER, text, null, null, null, false); }

    public static Message assistant(String text, List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, text, calls, null, null, false);
    }

    public static Message toolResult(String callId, String toolName, String text, boolean error) {
        return new Message(Role.TOOL, text, null, callId, toolName, error);
    }
}
