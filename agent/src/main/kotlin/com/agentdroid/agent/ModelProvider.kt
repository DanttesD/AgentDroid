package com.agentdroid.agent

import java.io.IOException

interface ModelProvider {
    fun id(): String

    /** Streams the answer through `listener` and returns the complete reply. */
    @Throws(IOException::class)
    fun chat(system: String?, history: List<Message>, tools: List<ToolSpec>, listener: StreamListener): Reply
}
