package com.agentdroid.agent;

import static org.junit.Assert.*;

import java.util.*;

import org.junit.After;
import org.junit.Test;

public class ScreenFlowsTest {
    private final List<String> calls = new ArrayList<>();
    private final Deque<String> screens = new ArrayDeque<>();
    private String lastScreen = "";

    private void install() {
        ScreenBridge.Holder.current = new ScreenBridge() {
            public String read(boolean all) { if (!screens.isEmpty()) lastScreen = screens.poll(); return lastScreen; }
            public String click(int id) { calls.add("click" + id); return "clicked " + id; }
            public String longClick(int id) { return ""; }
            public String setText(int id, String t) { return ""; }
            public String scroll(int id, String d) { calls.add("scroll" + id + d); return "scrolled"; }
            public String global(String a) { return ""; }
            public byte[] screenshot() { return null; }
            public String foregroundPackage() { return "p"; }
        };
    }

    @After public void off() { ScreenBridge.Holder.current = null; }

    private static Map<String, Tool> tools() {
        Map<String, Tool> m = new LinkedHashMap<>();
        for (Tool t : ScreenFlows.create()) m.put(t.spec().name, t);
        return m;
    }

    private static final String SCREEN = "package: x\n"
            + "[0] TextView \"Привет\" @(1,1)\n"
            + "[1] EditText editable focused @(1,2)\n"
            + "[2] ImageButton desc=\"Отправить\" clickable @(1,3)\n"
            + "[3] Button \"Settings\" clickable @(1,4)\n"
            + "[4] TextView \"Settings help\" @(1,5)\n";

    @Test public void parsesElements() {
        List<ScreenFlows.El> els = ScreenFlows.parse(SCREEN);
        assertEquals(5, els.size());
        assertEquals("Привет", els.get(0).text);
        assertTrue(els.get(1).editable);
        assertEquals("Отправить", els.get(2).desc);
        assertTrue(els.get(2).clickable);
    }

    @Test public void findAndTapPrefersClickableExact() throws Exception {
        install();
        screens.add(SCREEN);
        String r = tools().get("find_and_tap").execute(Json.obj("label", "settings"));
        assertTrue(r.contains("clicked 3"));
        assertEquals(Arrays.asList("click3"), calls);
    }

    @Test public void findAndTapReportsVisibleWhenMissing() throws Exception {
        install();
        screens.add(SCREEN);
        screens.add(SCREEN);
        screens.add(SCREEN);
        String r = tools().get("find_and_tap").execute(Json.obj("label", "nothing"));
        assertTrue(r.startsWith("not found"));
        assertTrue(r.contains("Привет"));
        assertTrue(calls.isEmpty());
    }

    @Test public void riskyLabelsAreSensitive() {
        Tool t = tools().get("find_and_tap");
        assertTrue(t.sensitive(Json.obj("label", "Оплатить")));
        assertTrue(t.sensitive(Json.obj("label", "Delete account")));
        assertFalse(t.sensitive(Json.obj("label", "Settings")));
        assertTrue(tools().get("submit_message").sensitive(Json.obj()));
    }

    @Test public void submitMessageClicksSend() throws Exception {
        install();
        screens.add(SCREEN);
        assertTrue(tools().get("submit_message").execute(Json.obj()).contains("clicked 2"));
    }

    @Test public void readScreenCollectsUntilNothingNew() throws Exception {
        install();
        screens.add("package: x\n[0] RecyclerView scrollable @(1,1)\n[1] TextView \"A\" @(1,2)\n[2] TextView \"B\" @(1,3)\n");
        screens.add("package: x\n[0] RecyclerView scrollable @(1,1)\n[1] TextView \"B\" @(1,2)\n[2] TextView \"C\" @(1,3)\n");
        screens.add("package: x\n[0] RecyclerView scrollable @(1,1)\n[1] TextView \"B\" @(1,2)\n[2] TextView \"C\" @(1,3)\n");
        String r = tools().get("read_screen").execute(Json.obj("max_pages", 6L));
        assertTrue(r.contains("A\nB\nC\n"));
        assertEquals(Arrays.asList("scroll0down", "scroll0down"), calls);
    }

    @Test public void waitForContentReportsNewText() throws Exception {
        install();
        screens.add("[0] TextView \"Вопрос\" @(1,1)\n");
        screens.add("[0] TextView \"Вопрос\" @(1,1)\n[1] TextView \"Ответ\" @(1,2)\n");
        String r = tools().get("wait_for_content").execute(Json.obj("timeout_ms", 6000L, "stable_ms", 800L));
        assertTrue(r.startsWith("screen settled"));
        assertTrue(r.contains("Ответ"));
        assertFalse(r.contains("Вопрос"));
    }

    @Test public void unchangedScreenTimesOut() throws Exception {
        install();
        screens.add("[0] TextView \"X\" @(1,1)\n");
        assertTrue(tools().get("wait_for_content").execute(Json.obj("timeout_ms", 1000L)).startsWith("timeout"));
    }
}
