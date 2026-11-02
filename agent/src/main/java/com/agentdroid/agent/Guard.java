package com.agentdroid.agent;

import java.util.Map;

/** Safety hook around every tool call coming from outside (emergency stop, blocked apps, action log). */
public interface Guard {
    /** Throw {@link SecurityException} with a message for the model to refuse the call. */
    void before(String tool, Map<String, Object> args, String description);

    /** Called after a call finished (also when it failed or was denied). */
    void after(String tool, Map<String, Object> args, String result, boolean error);
}
