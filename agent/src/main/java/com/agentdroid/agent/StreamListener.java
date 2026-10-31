package com.agentdroid.agent;

/** Receives live events while the agent works. All methods optional. */
public class StreamListener {
    public void onText(String delta) {}
    public void onToolCall(ToolCall call) {}
    public void onToolResult(ToolCall call, String result, boolean error) {}
    public void onError(String message) {}
}
