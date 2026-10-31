package com.agentdroid.agent;

import java.io.IOException;
import java.util.List;

public interface ModelProvider {
    String id();

    /** Streams the answer through {@code listener} and returns the complete reply. */
    Reply chat(String system, List<Message> history, List<ToolSpec> tools, StreamListener listener) throws IOException;
}
