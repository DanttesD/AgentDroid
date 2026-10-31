package com.agentdroid.agent;

import java.util.List;

/** Complete model answer for one turn. */
public final class Reply {
    public final String text;
    public final List<ToolCall> toolCalls;
    public final String stopReason;

    public Reply(String text, List<ToolCall> toolCalls, String stopReason) {
        this.text = text;
        this.toolCalls = toolCalls;
        this.stopReason = stopReason;
    }
}
