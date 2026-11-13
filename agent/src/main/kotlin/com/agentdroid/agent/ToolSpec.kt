package com.agentdroid.agent

/** Tool description sent to the model; `schema` is a JSON-Schema object. */
@JvmSuppressWildcards
class ToolSpec(
    @JvmField val name: String,
    @JvmField val description: String,
    @JvmField val schema: Map<String, Any?>,
)
