package com.agentdroid.agent;

import static org.junit.Assert.*;

import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ExecRequest;
import com.agentdroid.exec.ExecResult;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

public class AgentTest {
    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final List<Map<String, String>> reqHeaders = new ArrayList<>();

    @After public void stop() { if (server != null) server.stop(0); }

    /** Serves the scripted SSE bodies one per request. */
    private String serve(final String path, final String... bodies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger n = new AtomicInteger();
        server.createContext(path, ex -> {
            byte[] in = readAll(ex.getRequestBody());
            requests.add(new String(in, StandardCharsets.UTF_8));
            Map<String, String> h = new HashMap<>();
            for (String k : ex.getRequestHeaders().keySet()) h.put(k.toLowerCase(), ex.getRequestHeaders().getFirst(k));
            reqHeaders.add(h);
            int i = Math.min(n.getAndIncrement(), bodies.length - 1);
            byte[] out = bodies[i].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096]; int r;
        while ((r = in.read(b)) > 0) o.write(b, 0, r);
        return o.toByteArray();
    }

    private static String sse(String... datas) {
        StringBuilder sb = new StringBuilder();
        for (String d : datas) sb.append("data: ").append(d).append("\n\n");
        return sb.toString();
    }

    /** Fake backend: records commands, answers with canned output. */
    static class FakeBackend implements ExecBackend {
        final List<String> commands = new ArrayList<>();
        public String id() { return "fake"; }
        public String displayName() { return "Fake"; }
        public boolean isAvailable() { return true; }
        public ExecResult exec(ExecRequest r) {
            commands.add(r.command());
            return new ExecResult(0, "hello\n".getBytes(StandardCharsets.UTF_8), null, false);
        }
        @Override public byte[] readFile(String p) { return ("content of " + p).getBytes(StandardCharsets.UTF_8); }
    }

    @Test public void jsonRoundTrip() {
        Map<String, Object> m = Json.obj("a", 1L, "b", "x\"y\n", "c", Arrays.<Object>asList(true, null, 2.5));
        String s = Json.stringify(m);
        Map<String, Object> back = Json.parseObject(s);
        assertEquals(1L, back.get("a"));
        assertEquals("x\"y\n", back.get("b"));
        assertEquals(Arrays.<Object>asList(true, null, 2.5), back.get("c"));
        assertEquals("é", Json.parse("\"\\u00e9\""));
    }

    @Test public void openAiStreamsTextAndAssemblesToolCalls() throws Exception {
        String base = serve("/chat/completions",
            sse("{\"choices\":[{\"delta\":{\"content\":\"Run\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"ning\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"comm\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"and\\\":\\\"echo hello\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
                "[DONE]"),
            sse("{\"choices\":[{\"delta\":{\"content\":\"Done: hello\"},\"finish_reason\":\"stop\"}]}", "[DONE]"));
        FakeBackend fb = new FakeBackend();
        final List<String> approvals = new ArrayList<>();
        AgentLoop loop = new AgentLoop(new OpenAiProvider("test", base, "KEY", "m"), Tools.standard(fb, "/w"),
            (call, d) -> { approvals.add(d); return true; }, "sys", 5);
        final StringBuilder streamed = new StringBuilder();
        String answer = loop.send("say hello", new StreamListener() {
            @Override public void onText(String t) { streamed.append(t); }
        });
        assertEquals("Done: hello", answer);
        assertEquals("Running" + "Done: hello", streamed.toString());
        assertEquals(1, fb.commands.size());
        assertEquals("echo hello", fb.commands.get(0));
        assertEquals(Collections.singletonList("Run: echo hello"), approvals);
        assertEquals("Bearer KEY", reqHeaders.get(0).get("authorization"));
        // second request must contain assistant tool_calls and the tool result
        Map<String, Object> second = Json.parseObject(requests.get(1));
        String s = Json.stringify(second);
        assertTrue(s.contains("\"tool_call_id\":\"c1\""));
        assertTrue(s.contains("\"role\":\"system\""));
        assertTrue(s.contains("exit code: 0"));
    }

    @Test public void anthropicToolUseRoundTrip() throws Exception {
        String base = serve("/v1/messages",
            sse("{\"type\":\"message_start\",\"message\":{}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Reading\"}}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"read_file\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"a.txt\\\"}\"}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}"),
            sse("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"It says hi\"}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}"));
        FakeBackend fb = new FakeBackend();
        AgentLoop loop = new AgentLoop(new AnthropicProvider(base, "AK", "claude-x", 100), Tools.standard(fb, "/w"),
            (c, d) -> { fail("read_file must not need approval"); return false; }, "sys", 5);
        String answer = loop.send("read a.txt", new StreamListener());
        assertEquals("It says hi", answer);
        assertEquals("AK", reqHeaders.get(0).get("x-api-key"));
        assertEquals("2023-06-01", reqHeaders.get(0).get("anthropic-version"));
        String second = requests.get(1);
        assertTrue(second.contains("\"type\":\"tool_use\""));
        assertTrue(second.contains("\"type\":\"tool_result\""));
        assertTrue(second.contains("content of /w/a.txt"));
        assertTrue(second.contains("\"system\":\"sys\""));
    }

    @Test public void deniedApprovalIsReportedToModel() throws Exception {
        String base = serve("/chat/completions",
            sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"rm -rf x\\\"}\"}}]}}]}"),
            sse("{\"choices\":[{\"delta\":{\"content\":\"ok, skipped\"}}]}"));
        FakeBackend fb = new FakeBackend();
        AgentLoop loop = new AgentLoop(OpenAiProvider.deepseek("k"), Tools.standard(fb, null), (c, d) -> false, null, 5);
        // point at mock instead of real DeepSeek
        loop = new AgentLoop(new OpenAiProvider("deepseek", base, "k", "deepseek-chat"), Tools.standard(fb, null), (c, d) -> false, null, 5);
        assertEquals("ok, skipped", loop.send("delete", new StreamListener()));
        assertTrue(fb.commands.isEmpty());
        assertTrue(requests.get(1).contains("the user denied this action"));
    }

    @Test public void stepLimitStops() throws Exception {
        String call = sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\"/\\\"}\"}}]}}]}");
        String base = serve("/chat/completions", call);
        final List<String> errors = new ArrayList<>();
        AgentLoop loop = new AgentLoop(new OpenAiProvider("t", base, "", "m"), Tools.standard(new FakeBackend(), null), (c, d) -> true, null, 3);
        loop.send("loop", new StreamListener() { @Override public void onError(String m) { errors.add(m); } });
        assertEquals(3, requests.size());
        assertEquals(1, errors.size());
    }

    @Test public void httpErrorSurfaces() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = "{\"error\":\"bad key\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        AgentLoop loop = new AgentLoop(new OpenAiProvider("t", "http://127.0.0.1:" + server.getAddress().getPort(), "x", "m"),
            Collections.<Tool>emptyList(), (c, d) -> true, null, 3);
        try { loop.send("hi", new StreamListener()); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("401")); assertTrue(e.getMessage().contains("bad key")); }
    }
}
