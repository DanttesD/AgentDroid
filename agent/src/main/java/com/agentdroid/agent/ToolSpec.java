package com.agentdroid.agent;

import java.util.Map;

/** Tool description sent to the model; {@code schema} is a JSON-Schema object. */
public final class ToolSpec {
    public final String name;
    public final String description;
    public final Map<String, Object> schema;

    public ToolSpec(String name, String description, Map<String, Object> schema) {
        this.name = name;
        this.description = description;
        this.schema = schema;
    }
}
