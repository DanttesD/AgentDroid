package com.agentdroid.agent;

/** Tool output: text for the model plus an optional PNG image (screenshots). */
public final class ToolResult {
    public final String text;
    public final byte[] png;

    public ToolResult(String text, byte[] png) {
        this.text = text;
        this.png = png;
    }
}
