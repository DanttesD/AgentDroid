package com.agentdroid.agent;

import static org.junit.Assert.*;

import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.junit.After;
import org.junit.Test;

public class McpServerTest {
    private McpServer server;
    private final AgentTest.FakeBackend fb = new AgentTest.FakeBackend();
    private final List<String> approvals = new ArrayList<>();
    private boolean allow = true;

    @After public void stop() { if (server != null) server.stop(); }

    private int start() throws Exception {
        server = new McpServer(Tools.standard(fb, "/w"), (c, d) -> { approvals.add(d); return allow; }, "SECRET", 0);
        return server.start();
    }

    private String[] post(int port, String path, String body, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096]; int r;
        while (in != null && (r = in.read(b)) > 0) bo.write(b, 0, r);
        return new String[]{String.valueOf(code), new String(bo.toByteArray(), StandardCharsets.UTF_8)};
    }

    @Test public void handshakeAndToolList() throws Exception {
        int port = start();
        String[] r = post(port, "/mcp/SECRET", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", null);
        assertEquals("200", r[0]);
        Map<String, Object> res = (Map<String, Object>) Json.parseObject(r[1]).get("result");
        assertEquals(McpServer.PROTOCOL_VERSION, res.get("protocolVersion"));
        assertEquals("202", post(port, "/mcp/SECRET", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null)[0]);
        r = post(port, "/mcp/SECRET", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", null);
        assertTrue(r[1].contains("\"name\":\"shell\""));
        assertTrue(r[1].contains("\"inputSchema\""));
    }

    @Test public void toolCallRunsThroughBackendAfterApproval() throws Exception {
        int port = start();
        String[] r = post(port, "/mcp/SECRET", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"shell\",\"arguments\":{\"command\":\"echo hi\"}}}", null);
        assertTrue(r[1], r[1].contains("exit code: 0"));
        assertTrue(r[1].contains("\"isError\":false"));
        assertEquals(Collections.singletonList("Run: echo hi"), approvals);
        assertEquals(Collections.singletonList("echo hi"), fb.commands);
    }

    @Test public void deniedCallDoesNotRun() throws Exception {
        allow = false;
        int port = start();
        String[] r = post(port, "/mcp/SECRET", "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"shell\",\"arguments\":{\"command\":\"rm -rf /\"}}}", null);
        assertTrue(r[1].contains("\"isError\":true"));
        assertTrue(fb.commands.isEmpty());
    }

    @Test public void wrongTokenRejectedAndBearerAccepted() throws Exception {
        int port = start();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals("404", post(port, "/mcp/WRONG", body, null)[0]);
        assertEquals("404", post(port, "/mcp", body, "Bearer nope")[0]);
        assertEquals("200", post(port, "/mcp", body, "Bearer SECRET")[0]);
    }

    @Test public void unknownMethodIsJsonRpcError() throws Exception {
        int port = start();
        String[] r = post(port, "/mcp/SECRET", "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nope\"}", null);
        assertTrue(r[1].contains("-32601"));
    }
}
