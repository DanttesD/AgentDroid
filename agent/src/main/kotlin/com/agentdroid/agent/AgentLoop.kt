package com.agentdroid.agent

import java.io.IOException

/** The agent: model -> tool calls -> results -> model ... until the model answers without tools. */
class AgentLoop(
    private val provider: ModelProvider,
    toolList: List<Tool>,
    private val approver: Approver,
    private val system: String?,
    private val maxSteps: Int,
) {
    private val tools = LinkedHashMap<String, Tool>()
    private val history = ArrayList<Message>()

    @Volatile
    private var cancelled = false

    @Volatile
    private var guardRef: Guard? = null

    init {
        for (t in toolList) tools[t.spec().name] = t
    }

    fun history(): List<Message> = ArrayList(history)

    fun cancel() {
        cancelled = true
    }

    /** Emergency stop / blocked apps / action log, same hook as the MCP server uses. */
    fun setGuard(g: Guard?) {
        guardRef = g
    }

    /** Runs one user turn to completion. Returns the final assistant text. */
    @Throws(IOException::class)
    fun send(userText: String, listener: StreamListener): String {
        cancelled = false
        history.add(Message.user(userText))
        val specs = ArrayList<ToolSpec>()
        for (t in tools.values) specs.add(t.spec())
        var last = ""
        val stuck = StuckDetector()
        for (step in 0 until maxSteps) {
            if (cancelled) {
                listener.onError("cancelled")
                return last
            }
            val r = provider.chat(system, history, specs, listener)
            history.add(Message.assistant(r.text, r.toolCalls))
            last = r.text
            if (r.toolCalls.isEmpty()) return last
            for (call in r.toolCalls) {
                listener.onToolCall(call)
                var result: String
                var error = false
                // A call without a name (malformed stream) matches no tool and is answered with "unknown tool: null";
                // below, a found tool implies a non-null name.
                val tool = tools[call.name]
                if (cancelled) {
                    result = "cancelled by user"
                    error = true
                } else if (tool == null) {
                    result = "unknown tool: " + call.name
                    error = true
                } else {
                    var refused: String? = null
                    val g = guardRef
                    if (g != null) {
                        try {
                            g.before(call.name!!, call.args, tool.describe(call.args))
                        } catch (e: SecurityException) {
                            refused = "refused: " + e.message
                        }
                    }
                    if (refused != null) {
                        result = refused
                        error = true
                    } else if ((tool.needsApproval(call.args) || tool.sensitive(call.args)) &&
                        !approver.approve(call, (if (tool.sensitive(call.args)) "!" else "") + tool.describe(call.args))
                    ) {
                        result = "the user denied this action"
                        error = true
                    } else {
                        try {
                            result = tool.execute(call.args)
                        } catch (e: Exception) {
                            if (e is InterruptedException) Thread.currentThread().interrupt()
                            result = "error: " + e.message
                            error = true
                        }
                    }
                    if (g != null) {
                        try {
                            g.after(call.name!!, call.args, result, error)
                        } catch (ignored: RuntimeException) {
                        }
                    }
                }
                if (result.length > MAX_RESULT_CHARS) {
                    result = result.substring(0, MAX_RESULT_CHARS) + "\n...[truncated " + (result.length - MAX_RESULT_CHARS) + " chars]"
                }
                val hint = if (tool == null) null else stuck.record(call.name!!, Json.stringify(call.args), result, error)
                if (hint != null) result = result + "\n[hint] " + hint
                listener.onToolResult(call, result, error)
                history.add(Message.toolResult(call.id, call.name, result, error))
            }
            if (stuck.shouldGiveUp()) {
                listener.onError("the agent is stuck (repeating without progress); stopped")
                return last
            }
        }
        listener.onError("step limit reached ($maxSteps)")
        return last
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 25
        private const val MAX_RESULT_CHARS = 20_000
    }
}
