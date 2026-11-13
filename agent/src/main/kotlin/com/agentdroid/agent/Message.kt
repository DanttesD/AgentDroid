package com.agentdroid.agent

/** One conversation entry. Roles: user, assistant, tool (tool result). */
class Message private constructor(
    @JvmField val role: Role,
    text: String?,
    calls: List<ToolCall>?,
    @JvmField val toolCallId: String?,
    @JvmField val toolName: String?,
    @JvmField val error: Boolean,
) {
    enum class Role { USER, ASSISTANT, TOOL }

    @JvmField
    val text: String = text ?: ""

    @JvmField
    val toolCalls: List<ToolCall> = if (calls == null) emptyList() else ArrayList(calls)

    companion object {
        @JvmStatic
        fun user(text: String?): Message = Message(Role.USER, text, null, null, null, false)

        @JvmStatic
        fun assistant(text: String?, calls: List<ToolCall>?): Message =
            Message(Role.ASSISTANT, text, calls, null, null, false)

        @JvmStatic
        fun toolResult(callId: String?, toolName: String?, text: String?, error: Boolean): Message =
            Message(Role.TOOL, text, null, callId, toolName, error)
    }
}
