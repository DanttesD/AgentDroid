package com.agentdroid.agent;

import static org.junit.Assert.*;

import java.util.*;

import org.junit.Test;

public class PhoneToolsTest {
    private final AgentTest.FakeBackend fb = new AgentTest.FakeBackend();

    private Map<String, Tool> all() {
        List<Tool> l = new ArrayList<>();
        l.addAll(UiTools.create(fb, t -> { }));
        l.addAll(A11yTools.create());
        l.addAll(AndroidTools.create(fb, null));
        l.addAll(DevTools.create(fb, "/w"));
        Map<String, Tool> m = new LinkedHashMap<>();
        for (Tool t : l) assertNull("duplicate tool " + t.spec().name, m.put(t.spec().name, t));
        return m;
    }

    @Test public void namesAreUniqueAndSchemasValid() {
        for (Tool t : all().values()) {
            Map<String, Object> s = t.spec().schema;
            assertEquals("object", s.get("type"));
            assertNotNull(Json.stringify(s));
        }
    }

    @Test public void tapAndKeyBuildInputCommands() throws Exception {
        Map<String, Tool> m = all();
        m.get("tap").execute(Json.obj("x", 10L, "y", 20.0));
        m.get("key").execute(Json.obj("key", "back"));
        m.get("type_text").execute(Json.obj("text", "a b%"));
        assertTrue(fb.commands.contains("input tap 10 20"));
        assertTrue(fb.commands.contains("input keyevent KEYCODE_BACK"));
        assertTrue(fb.commands.contains("input text 'a%sb%%'"));
    }

    @Test public void intentAndSensitivity() {
        Tool t = all().get("intent");
        Map<String, Object> a = Json.obj("action", "android.intent.action.VIEW", "data", "https://example.com");
        assertEquals("Intent: start -a android.intent.action.VIEW -d https://example.com",
                t.describe(a).replace("'", ""));
        assertFalse(t.sensitive(a));
        assertTrue(t.sensitive(Json.obj("action", "android.intent.action.CALL", "data", "tel:112")));
        assertTrue(all().get("app_uninstall").sensitive(Json.obj("package", "x")));
    }

    @Test public void a11yToolsExplainWhenServiceIsOff() {
        ScreenBridge.Holder.current = null;
        try {
            all().get("click").execute(Json.obj("id", 1L));
            fail();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("accessibility"));
        }
    }

    @Test public void a11yToolsUseBridge() throws Exception {
        final List<String> calls = new ArrayList<>();
        ScreenBridge.Holder.current = new ScreenBridge() {
            public String read(boolean all) { return "[0] Button \"OK\" clickable @(1,2)"; }
            public String click(int id) { calls.add("click" + id); return "clicked " + id; }
            public String longClick(int id) { return ""; }
            public String setText(int id, String t) { calls.add("text" + id + t); return "ok"; }
            public String scroll(int id, String d) { return ""; }
            public String global(String a) { return ""; }
            public byte[] screenshot() { return null; }
            public String foregroundPackage() { return "p"; }
        };
        try {
            Map<String, Tool> m = all();
            assertEquals("clicked 3", m.get("click").execute(Json.obj("id", 3L)));
            m.get("set_text").execute(Json.obj("id", 2L, "text", "привет"));
            assertEquals("text found", m.get("wait_for").execute(Json.obj("text", "ok", "timeout_ms", 500L)));
            assertEquals(Arrays.asList("click3", "text2привет"), calls);
        } finally {
            ScreenBridge.Holder.current = null;
        }
    }

    @Test public void editRequiresUniqueMatch() throws Exception {
        final Map<String, String> files = new HashMap<>();
        files.put("/w/a.txt", "x y x");
        AgentTest.FakeBackend b = new AgentTest.FakeBackend() {
            @Override public byte[] readFile(String p) { return files.get(p).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
            @Override public void writeFile(String p, byte[] d) { files.put(p, new String(d, java.nio.charset.StandardCharsets.UTF_8)); }
        };
        Tool edit = DevTools.create(b, "/w").get(0);
        try { edit.execute(Json.obj("path", "a.txt", "old", "x", "new", "z")); fail(); } catch (IllegalArgumentException e) { }
        edit.execute(Json.obj("path", "a.txt", "old", "y", "new", "q"));
        assertEquals("x q x", files.get("/w/a.txt"));
        edit.execute(Json.obj("path", "a.txt", "old", "x", "new", "z", "replace_all", true));
        assertEquals("z q z", files.get("/w/a.txt"));
    }

    @Test public void guardRefusesAndLogs() throws Exception {
        final List<String> log = new ArrayList<>();
        McpServer s = new McpServer(UiTools.create(fb, null), (c, d) -> true, "T", 0);
        s.setGuard(new Guard() {
            public void before(String tool, Map<String, Object> args, String d) { throw new SecurityException("stopped"); }
            public void after(String tool, Map<String, Object> args, String r, boolean err) { log.add(tool + ":" + err + ":" + r); }
        });
        String reply = s.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"tap\",\"arguments\":{\"x\":1,\"y\":2}}}");
        assertTrue(reply, reply.contains("refused: stopped"));
        assertTrue(reply.contains("\"isError\":true"));
        assertEquals(1, log.size());
        assertFalse(fb.commands.contains("input tap 1 2"));
    }

    @Test public void screenshotReturnsImageContent() throws Exception {
        AgentTest.FakeBackend b = new AgentTest.FakeBackend() {
            @Override public com.agentdroid.exec.ExecResult exec(com.agentdroid.exec.ExecRequest r) {
                byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
                return new com.agentdroid.exec.ExecResult(0, r.command().startsWith("screencap") ? png : "Physical size: 1x1".getBytes(), null, false);
            }
        };
        McpServer s = new McpServer(UiTools.create(b, null), (c, d) -> true, "T", 0);
        String reply = s.dispatch("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"screenshot\",\"arguments\":{}}}");
        assertTrue(reply, reply.contains("\"type\":\"image\"") && reply.contains("\"mimeType\":\"image/png\""));
        assertTrue(reply.contains(UiTools.base64(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4})));
    }
}
