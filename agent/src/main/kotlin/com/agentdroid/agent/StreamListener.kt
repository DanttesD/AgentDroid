package com.agentdroid.agent

/** Receives live events while the agent works. All methods optional. */
open class StreamListener {
    open fun onText(delta: String) {}

    open fun onToolCall(call: ToolCall) {}

    open fun onToolResult(call: ToolCall, result: String, error: Boolean) {}

    open fun onError(message: String) {}
}
