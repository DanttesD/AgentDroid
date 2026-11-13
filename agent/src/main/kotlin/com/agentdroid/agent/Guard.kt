package com.agentdroid.agent

/** Safety hook around every tool call coming from outside (emergency stop, blocked apps, action log). */
@JvmSuppressWildcards
interface Guard {
    /** Throw [SecurityException] with a message for the model to refuse the call. */
    fun before(tool: String, args: Map<String, Any?>, description: String)

    /** Called after a call finished (also when it failed or was denied). */
    fun after(tool: String, args: Map<String, Any?>, result: String, error: Boolean)
}
