package com.agentdroid.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal MCP server (Streamable HTTP, JSON responses) exposing the same tools as the built-in agent.
 * Plain sockets only, so it runs on Android and on a desktop JVM without extra libraries.
 *
 * Access control: the endpoint is {@code /mcp/<token>} (claude.ai custom connectors cannot send a
 * static header), and a {@code Authorization: Bearer <token>} header is accepted as well. Every tool that
 * needs approval still goes through the {@link Approver} (a dialog on the phone).
 */
public final class McpServer {
    public static final String PROTOCOL_VERSION = "2025-03-26";
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Approver approver;
    private final String token;
    private final int requestedPort;
    private volatile ServerSocket server;
    private volatile boolean running;

    public McpServer(List<Tool> toolList, Approver approver, String token, int port) {
        for (Tool t : toolList) tools.put(t.spec().name, t);
        this.approver = approver;
        this.token = token;
        this.requestedPort = port;
    }

    public synchronized int start() throws IOException {
        if (running) return server.getLocalPort();
        server = new ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread acceptor = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "mcp-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    public boolean isRunning() { return running; }

    public int port() { return server == null ? -1 : server.getLocalPort(); }

    /** Path part of the public URL. */
    public String path() { return "/mcp/" + token; }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }, "mcp-conn");
                t.setDaemon(true);
                t.start();
            } catch (SocketException e) {
                return;
            } catch (IOException e) {
                // keep serving
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(300_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (running) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) return;
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String target = parts[1];
                Map<String, String> headers = new LinkedHashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    int i = line.indexOf(':');
                    if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
                }
                int len = 0;
                try { len = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); }
                catch (NumberFormatException ignored) { }
                if (len > MAX_BODY) { respond(out, 413, "text/plain", "too large"); return; }
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int r = in.read(body, off, len - off);
                    if (r < 0) return;
                    off += r;
                }
                boolean authorized = target.equals(path()) || target.startsWith(path() + "?")
                        || (target.equals("/mcp") && ("Bearer " + token).equals(headers.get("authorization")));
                if (!authorized) { respond(out, 404, "text/plain", "not found"); continue; }
                if (!method.equals("POST")) { respond(out, 405, "text/plain", "POST only"); continue; }
                String reply;
                try {
                    reply = dispatch(new String(body, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    respond(out, 400, "text/plain", "bad request: " + e.getMessage());
                    continue;
                }
                if (reply == null) respond(out, 202, "text/plain", "");
                else respond(out, 200, "application/json", reply);
                if ("close".equalsIgnoreCase(headers.get("connection"))) return;
            }
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** Returns the JSON-RPC reply, or null for notifications. */
    @SuppressWarnings("unchecked")
    String dispatch(String json) {
        Object parsed = Json.parse(json);
        if (parsed instanceof List) {
            List<Object> replies = new ArrayList<>();
            for (Object o : (List<Object>) parsed) {
                String r = one((Map<String, Object>) o);
                if (r != null) replies.add(Json.parseObject(r));
            }
            return replies.isEmpty() ? null : Json.stringify(replies);
        }
        return one((Map<String, Object>) parsed);
    }

    @SuppressWarnings("unchecked")
    private String one(Map<String, Object> req) {
        Object id = req.get("id");
        String method = (String) req.get("method");
        if (method == null) return null; // a response from the client: ignore
        if (id == null) return null;     // notification
        Map<String, Object> params = req.get("params") instanceof Map ? (Map<String, Object>) req.get("params") : new LinkedHashMap<String, Object>();
        try {
            Object result;
            switch (method) {
                case "initialize":
                    result = Json.obj("protocolVersion", PROTOCOL_VERSION,
                            "capabilities", Json.obj("tools", Json.obj()),
                            "serverInfo", Json.obj("name", "agentdroid", "version", "0.1"));
                    break;
                case "ping":
                    result = Json.obj();
                    break;
                case "tools/list": {
                    List<Object> l = new ArrayList<>();
                    for (Tool t : tools.values()) {
                        ToolSpec sp = t.spec();
                        l.add(Json.obj("name", sp.name, "description", sp.description, "inputSchema", sp.schema));
                    }
                    result = Json.obj("tools", l);
                    break;
                }
                case "tools/call":
                    result = call(params);
                    break;
                default:
                    return Json.stringify(Json.obj("jsonrpc", "2.0", "id", id,
                            "error", Json.obj("code", -32601L, "message", "method not found: " + method)));
            }
            return Json.stringify(Json.obj("jsonrpc", "2.0", "id", id, "result", result));
        } catch (RuntimeException e) {
            return Json.stringify(Json.obj("jsonrpc", "2.0", "id", id,
                    "error", Json.obj("code", -32602L, "message", String.valueOf(e.getMessage()))));
        }
    }

    @SuppressWarnings("unchecked")
    private Object call(Map<String, Object> params) {
        String name = (String) params.get("name");
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("unknown tool: " + name);
        Map<String, Object> args = params.get("arguments") instanceof Map
                ? (Map<String, Object>) params.get("arguments") : new LinkedHashMap<String, Object>();
        String text;
        boolean error = false;
        if (tool.needsApproval(args) && !approver.approve(new ToolCall("mcp", name, args), tool.describe(args))) {
            text = "the user denied this action"; error = true;
        } else {
            try {
                text = tool.execute(args);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                text = "error: " + e.getMessage(); error = true;
            }
        }
        if (text.length() > 50_000) text = text.substring(0, 50_000) + "\n...[truncated]";
        List<Object> content = new ArrayList<>();
        content.add(Json.obj("type", "text", "text", text));
        return Json.obj("content", content, "isError", error);
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 404 ? "Not Found"
                : code == 405 ? "Method Not Allowed" : code == 413 ? "Payload Too Large" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nConnection: keep-alive\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 16 * 1024) throw new IOException("line too long");
        }
        if (c < 0 && bo.size() == 0) return null;
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
