package com.agentdroid.agent

/** Asks the user whether a risky tool call may run. Called on the agent thread; may block. */
fun interface Approver {
    fun approve(call: ToolCall, description: String): Boolean
}
