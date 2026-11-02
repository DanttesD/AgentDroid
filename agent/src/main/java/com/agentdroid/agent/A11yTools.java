package com.agentdroid.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Screen tools that work through the accessibility service: elements are addressed by number
 * ({@code click(12)}), no coordinates and no root needed. Without the service they say how to enable it.
 */
public final class A11yTools {
    private A11yTools() {}

    static final String NEED = "The AgentDroid accessibility service is not running. Turn it on in Android Settings > "
            + "Accessibility > AgentDroid (or from the MCP screen), or use ui_tree + tap with root/ADB.";

    public static List<Tool> create() {
        List<Tool> l = new ArrayList<>();
        l.add(new Read());
        l.add(new Click(false));
        l.add(new Click(true));
        l.add(new SetText());
        l.add(new Scroll());
        l.add(new Global());
        l.add(new WaitFor());
        return l;
    }

    static ScreenBridge bridge() {
        ScreenBridge b = ScreenBridge.Holder.current;
        if (b == null) throw new IllegalStateException(NEED);
        return b;
    }

    private abstract static class Base implements Tool {
        public boolean needsApproval(Map<String, Object> a) { return true; }
    }

    static final class Read extends Base {
        public ToolSpec spec() {
            return new ToolSpec("ui_read", "Read the current screen as numbered elements (role, label, state, actions). "
                    + "Then act with click(id), set_text(id,text), scroll(id,direction). Numbers are valid until the next ui_read. "
                    + "Password fields are masked.",
                    UiTools.schema("all", "boolean", "true = include non-interactive elements without text"));
        }
        public String describe(Map<String, Object> a) { return "Read the screen"; }
        public String execute(Map<String, Object> a) {
            boolean all = Boolean.TRUE.equals(a.get("all")) || "true".equals(String.valueOf(a.get("all")));
            return bridge().read(all);
        }
    }

    static final class Click extends Base {
        private final boolean longPress;
        Click(boolean longPress) { this.longPress = longPress; }
        public ToolSpec spec() {
            return new ToolSpec(longPress ? "long_click" : "click", (longPress ? "Long-press" : "Click")
                    + " the element with this number from the last ui_read.",
                    UiTools.schema("*id", "integer", "Element number"));
        }
        public String describe(Map<String, Object> a) { return (longPress ? "Long-press" : "Click") + " element " + a.get("id"); }
        public String execute(Map<String, Object> a) {
            int id = UiTools.num(a, "id", 0, true);
            return longPress ? bridge().longClick(id) : bridge().click(id);
        }
    }

    static final class SetText extends Base {
        public ToolSpec spec() {
            return new ToolSpec("set_text", "Replace the text of an input field (element number from ui_read). Works with any language.",
                    UiTools.schema("*id", "integer", "Element number", "*text", "string", "New text"));
        }
        public String describe(Map<String, Object> a) {
            String t = String.valueOf(a.get("text"));
            return "Set text of element " + a.get("id") + ": " + (t.length() > 80 ? t.substring(0, 80) + "…" : t);
        }
        public String execute(Map<String, Object> a) {
            Object t = a.get("text");
            return bridge().setText(UiTools.num(a, "id", 0, true), t == null ? "" : t.toString());
        }
    }

    static final class Scroll extends Base {
        public ToolSpec spec() {
            return new ToolSpec("scroll", "Scroll a scrollable element (number from ui_read) up, down, left or right. "
                    + "'down' shows content further down the list.",
                    UiTools.schema("*id", "integer", "Element number", "*direction", "string", "up | down | left | right"));
        }
        public String describe(Map<String, Object> a) { return "Scroll element " + a.get("id") + " " + a.get("direction"); }
        public String execute(Map<String, Object> a) {
            return bridge().scroll(UiTools.num(a, "id", 0, true), Tools.str(a, "direction"));
        }
    }

    static final class Global extends Base {
        public ToolSpec spec() {
            return new ToolSpec("global_action", "System action: back, home, recents, notifications, quick_settings, lock_screen, power_dialog, screenshot.",
                    UiTools.schema("*action", "string", "Action name"));
        }
        public String describe(Map<String, Object> a) { return "System action: " + a.get("action"); }
        public String execute(Map<String, Object> a) { return bridge().global(Tools.str(a, "action")); }
    }

    static final class WaitFor implements Tool {
        public ToolSpec spec() {
            return new ToolSpec("wait_for", "Wait until some text appears on the screen (or disappears), up to timeout_ms (default 5000, max 30000).",
                    UiTools.schema("*text", "string", "Text to look for (case-insensitive substring)",
                            "gone", "boolean", "true = wait until the text is no longer shown", "timeout_ms", "integer", "Timeout"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Wait for text: " + a.get("text"); }
        public String execute(Map<String, Object> a) throws Exception {
            ScreenBridge b = bridge();
            String text = Tools.str(a, "text").toLowerCase();
            boolean gone = Boolean.TRUE.equals(a.get("gone")) || "true".equals(String.valueOf(a.get("gone")));
            long end = System.currentTimeMillis() + Math.max(500, Math.min(30_000, UiTools.num(a, "timeout_ms", 5000, false)));
            while (true) {
                boolean present = b.read(true).toLowerCase().contains(text);
                if (present != gone) return gone ? "text is gone" : "text found";
                if (System.currentTimeMillis() >= end) return gone ? "timeout: text is still shown" : "timeout: text not found";
                Thread.sleep(400);
            }
        }
    }
}
