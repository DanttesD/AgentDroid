package com.agentdroid.agent;

import java.util.Map;

public interface Tool {
    ToolSpec spec();

    /** True when the user must confirm before this call runs. */
    boolean needsApproval(Map<String, Object> args);

    /** Short human-readable description of what the call will do (for the approval prompt). */
    String describe(Map<String, Object> args);

    /** Runs the tool; return text for the model. Throw to report an error. */
    String execute(Map<String, Object> args) throws Exception;
}
