package com.agentdroid.agent

@JvmSuppressWildcards
interface Tool {
    fun spec(): ToolSpec

    /** True when the user must confirm before this call runs. */
    fun needsApproval(args: Map<String, Any?>): Boolean

    /** True for actions that must be confirmed even when the user allowed everything (sending, paying, deleting). */
    fun sensitive(args: Map<String, Any?>): Boolean = false

    /** Short human-readable description of what the call will do (for the approval prompt). */
    fun describe(args: Map<String, Any?>): String

    /** Runs the tool; return text for the model. Throw to report an error. */
    @Throws(Exception::class)
    fun execute(args: Map<String, Any?>): String

    /** Like [execute] but may also return an image. Default: text only. */
    @Throws(Exception::class)
    fun run(args: Map<String, Any?>): ToolResult = ToolResult(execute(args), null)
}
