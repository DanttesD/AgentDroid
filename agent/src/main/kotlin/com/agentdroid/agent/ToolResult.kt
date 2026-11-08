package com.agentdroid.agent

/** Tool output: text for the model plus an optional PNG image (screenshots). */
class ToolResult(
    @JvmField val text: String,
    @JvmField val png: ByteArray?,
)
