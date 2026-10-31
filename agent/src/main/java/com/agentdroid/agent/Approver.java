package com.agentdroid.agent;

/** Asks the user whether a risky tool call may run. Called on the agent thread; may block. */
public interface Approver {
    boolean approve(ToolCall call, String description);
}
