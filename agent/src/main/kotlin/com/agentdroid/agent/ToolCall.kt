package com.agentdroid.agent

/** A model's request to run a tool. */
@JvmSuppressWildcards
class ToolCall(
    @JvmField val id: String?,
    @JvmField val name: String?,
    @JvmField val args: Map<String, Any?>,
)
