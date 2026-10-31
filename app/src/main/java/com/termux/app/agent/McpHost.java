package com.termux.app.agent;

import android.content.Context;
import android.content.SharedPreferences;

import com.agentdroid.agent.McpServer;
import com.agentdroid.agent.Tools;

import java.io.IOException;
import java.security.SecureRandom;

/** Owns the MCP server inside the app process; started and stopped by {@link McpService}. */
public final class McpHost {
    public static final int DEFAULT_PORT = 8765;
    private static McpServer server;

    private McpHost() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("agentdroid_mcp", Context.MODE_PRIVATE);
    }

    public static synchronized String token(Context c) {
        SecretStore s = new SecretStore(c);
        String t = s.get("mcp_token");
        if (t.isEmpty()) t = newToken(c);
        return t;
    }

    public static synchronized String newToken(Context c) {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        new SecretStore(c).put("mcp_token", sb.toString());
        return sb.toString();
    }

    public static int port(Context c) { return prefs(c).getInt("port", DEFAULT_PORT); }

    public static synchronized boolean isRunning() { return server != null && server.isRunning(); }

    public static synchronized String localUrl(Context c) {
        return "http://127.0.0.1:" + port(c) + "/mcp/" + token(c);
    }

    public static synchronized void start(final Context context) throws IOException {
        if (isRunning()) return;
        final Context app = context.getApplicationContext();
        com.agentdroid.exec.BackendSelector sel = AgentBackends.create(app);
        String pin = new AgentConfig(app).backendPin();
        if (!pin.isEmpty()) { try { sel.pin(pin); } catch (Exception ignored) { } }
        server = new McpServer(Tools.standard(new SelectedBackend(sel), AgentBackends.TERMUX_HOME),
                (call, description) -> ApprovalCenter.ask(app, description), token(app), port(app));
        server.start();
    }

    public static synchronized void stop() {
        if (server != null) { server.stop(); server = null; }
    }
}
