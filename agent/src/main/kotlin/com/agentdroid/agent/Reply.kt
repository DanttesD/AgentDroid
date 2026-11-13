package com.agentdroid.agent

/** Complete model answer for one turn. */
class Reply(
    @JvmField val text: String,
    @JvmField val toolCalls: List<ToolCall>,
    @JvmField val stopReason: String?,
)
