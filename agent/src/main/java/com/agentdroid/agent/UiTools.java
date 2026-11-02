package com.agentdroid.agent;

import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ExecRequest;
import com.agentdroid.exec.ExecResult;
import com.agentdroid.exec.ShellQuote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tools that let a model see and drive the phone screen: screenshot, UI tree, taps, swipes, typing,
 * keys and app launching. Everything goes through the selected backend (root or ADB), so it works
 * with any agent that talks to the MCP server.
 */
public final class UiTools {
    private UiTools() {}

    /** Lets the app put text on the clipboard (needed to type non-ASCII text). */
    public interface ClipboardSink { void set(String text); }

    public static List<Tool> create(ExecBackend b, ClipboardSink clip) {
        List<Tool> l = new ArrayList<>();
        l.add(new Screenshot(b));
        l.add(new UiTree(b));
        l.add(new Tap(b));
        l.add(new Swipe(b));
        l.add(new TypeText(b, clip));
        l.add(new Key(b));
        l.add(new OpenApp(b));
        l.add(new ListApps(b));
        l.add(new CurrentApp(b));
        l.add(new Wait());
        return l;
    }

    // ---------- helpers ----------

    static ExecResult sh(ExecBackend b, String cmd, long timeoutMs) throws Exception {
        return b.exec(ExecRequest.builder(cmd).timeoutMs(timeoutMs).build());
    }

    static String sx(ExecBackend b, String cmd) throws Exception {
        ExecResult r = sh(b, cmd, 30_000L);
        if (!r.ok()) throw new IllegalStateException("exit " + r.exitCode() + ": " + (r.stderr().isEmpty() ? r.stdout() : r.stderr()).trim());
        return r.stdout();
    }

    static int num(Map<String, Object> a, String k, int def, boolean required) {
        Object v = a.get(k);
        if (v == null) { if (required) throw new IllegalArgumentException("missing argument: " + k); return def; }
        if (v instanceof Number) return (int) Math.round(((Number) v).doubleValue());
        try { return (int) Math.round(Double.parseDouble(v.toString().trim())); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + k); }
    }

    static Map<String, Object> schema(Object... propsKv) {
        // propsKv: name, type, description, ... ; names prefixed with '*' are required
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> req = new ArrayList<>();
        for (int i = 0; i + 2 < propsKv.length; i += 3) {
            String n = (String) propsKv[i];
            if (n.startsWith("*")) { n = n.substring(1); req.add(n); }
            props.put(n, Json.obj("type", propsKv[i + 1], "description", propsKv[i + 2]));
        }
        return Json.obj("type", "object", "properties", props, "required", req);
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff, b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0, b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            sb.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < d.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < d.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }

    private abstract static class Base implements Tool {
        final ExecBackend b;
        Base(ExecBackend b) { this.b = b; }
        public boolean needsApproval(Map<String, Object> a) { return true; }
        public String execute(Map<String, Object> a) throws Exception { return run(a).text; }
    }

    // ---------- see ----------

    static final class Screenshot extends Base {
        Screenshot(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("screenshot", "Take a screenshot of the phone screen and return it as an image. "
                    + "Use together with ui_tree to find exact coordinates.", schema());
        }
        public String describe(Map<String, Object> a) { return "Take a screenshot of the screen"; }
        public ToolResult run(Map<String, Object> a) throws Exception {
            ExecResult r = null;
            byte[] d = new byte[0];
            try { r = sh(b, "screencap -p", 30_000L); d = r.stdoutBytes(); } catch (Exception ignored) { }
            if (r == null || !r.ok() || d.length < 8 || (d[0] & 0xff) != 0x89 || d[1] != 'P') {
                ScreenBridge sb = ScreenBridge.Holder.current;
                byte[] viaService = sb == null ? null : sb.screenshot();
                if (viaService != null) return new ToolResult("Screenshot (accessibility service).", viaService);
                if (r == null) throw new IllegalStateException("screencap failed. Screenshots need root/ADB or the accessibility service (Android 11+).");
                throw new IllegalStateException("screencap failed (" + (r.stderr().isEmpty() ? "exit " + r.exitCode() : r.stderr().trim())
                        + "). Screenshots need the root or ADB backend.");
            }
            String size = "";
            try { size = sx(b, "wm size").trim(); } catch (Exception ignored) { }
            return new ToolResult("Screenshot. " + size, d);
        }
    }

    static final class UiTree extends Base {
        private static final Pattern NODE = Pattern.compile("<node ([^>]*?)/?>");
        private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
        UiTree(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("ui_tree", "Read the current screen as a list of UI elements: text, description, id, "
                    + "type, flags and the center point (x,y) to tap. Prefer this over screenshots for finding buttons.",
                    schema("all", "boolean", "true = include non-interactive elements without text too (default false)"));
        }
        public String describe(Map<String, Object> a) { return "Read the UI tree of the screen"; }
        public String execute(Map<String, Object> a) throws Exception {
            boolean all = Boolean.TRUE.equals(a.get("all")) || "true".equals(String.valueOf(a.get("all")));
            String f = "/data/local/tmp/ad_ui_" + System.nanoTime() + ".xml";
            String xml;
            try {
                sx(b, "uiautomator dump " + f + " >/dev/null 2>&1");
                xml = sx(b, "cat " + f);
            } finally {
                try { sh(b, "rm -f " + f, 5_000L); } catch (Exception ignored) { }
            }
            return format(xml, all);
        }

        static String format(String xml, boolean all) {
            StringBuilder sb = new StringBuilder();
            Matcher m = NODE.matcher(xml);
            String pkg = null;
            int n = 0;
            while (m.find()) {
                Map<String, String> at = new LinkedHashMap<>();
                Matcher am = ATTR.matcher(m.group(1));
                while (am.find()) at.put(am.group(1), unescape(am.group(2)));
                if (pkg == null) { pkg = at.get("package"); sb.append("package: ").append(pkg).append('\n'); }
                String text = at.get("text"), desc = at.get("content-desc"), id = at.get("resource-id");
                boolean click = "true".equals(at.get("clickable")) || "true".equals(at.get("long-clickable"))
                        || "true".equals(at.get("checkable")) || "true".equals(at.get("scrollable"));
                boolean has = (text != null && !text.isEmpty()) || (desc != null && !desc.isEmpty());
                if (!all && !has && !click) continue;
                int[] bd = bounds(at.get("bounds"));
                if (bd == null) continue;
                if (!all && (bd[2] <= bd[0] || bd[3] <= bd[1])) continue;
                sb.append('[').append(++n).append("] ");
                String cls = at.get("class");
                if (cls != null) sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append(' ');
                if (text != null && !text.isEmpty()) sb.append('"').append(text.replace("\n", " ")).append("\" ");
                if (desc != null && !desc.isEmpty()) sb.append("desc=\"").append(desc.replace("\n", " ")).append("\" ");
                if (id != null && !id.isEmpty()) sb.append("id=").append(id.substring(id.indexOf('/') + 1)).append(' ');
                if ("true".equals(at.get("clickable"))) sb.append("clickable ");
                if ("true".equals(at.get("long-clickable"))) sb.append("long-clickable ");
                if ("true".equals(at.get("scrollable"))) sb.append("scrollable ");
                if ("true".equals(at.get("checked"))) sb.append("checked ");
                if ("true".equals(at.get("selected"))) sb.append("selected ");
                if ("true".equals(at.get("focused"))) sb.append("focused ");
                if ("false".equals(at.get("enabled"))) sb.append("disabled ");
                sb.append("@(").append((bd[0] + bd[2]) / 2).append(',').append((bd[1] + bd[3]) / 2).append(")\n");
            }
            if (n == 0) sb.append("(no elements; the app may block accessibility reading — use screenshot)\n");
            return sb.toString();
        }

        static int[] bounds(String s) {
            if (s == null) return null;
            Matcher m = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(s);
            if (!m.find()) return null;
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
        }

        static String unescape(String s) {
            return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&#10;", "\n").replace("&amp;", "&");
        }
    }

    static final class CurrentApp extends Base {
        CurrentApp(ExecBackend b) { super(b); }
        public ToolSpec spec() { return new ToolSpec("current_app", "Which app/activity is in the foreground right now.", schema()); }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Check foreground app"; }
        public String execute(Map<String, Object> a) throws Exception {
            String out = sx(b, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4");
            return out.trim().isEmpty() ? "unknown" : out.trim();
        }
    }

    static final class ListApps extends Base {
        ListApps(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("list_apps", "List installed app package names. By default only user-installed apps.",
                    schema("system", "boolean", "true = include system apps", "filter", "string", "substring to filter package names"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "List installed apps"; }
        public String execute(Map<String, Object> a) throws Exception {
            boolean sys = Boolean.TRUE.equals(a.get("system")) || "true".equals(String.valueOf(a.get("system")));
            String out = sx(b, "pm list packages" + (sys ? "" : " -3")).replace("package:", "");
            Object f = a.get("filter");
            if (f instanceof String && !((String) f).isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String line : out.split("\n")) if (line.toLowerCase().contains(((String) f).toLowerCase())) sb.append(line).append('\n');
                return sb.toString();
            }
            return out;
        }
    }

    // ---------- act ----------

    static final class Tap extends Base {
        Tap(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("tap", "Tap the screen at x,y (pixels, as in ui_tree). duration_ms > 300 makes a long press.",
                    schema("*x", "integer", "X pixel", "*y", "integer", "Y pixel", "duration_ms", "integer", "Hold time for long press"));
        }
        public String describe(Map<String, Object> a) { return "Tap at (" + a.get("x") + ", " + a.get("y") + ")"; }
        public String execute(Map<String, Object> a) throws Exception {
            int x = num(a, "x", 0, true), y = num(a, "y", 0, true), d = num(a, "duration_ms", 0, false);
            if (d > 300) sx(b, "input swipe " + x + " " + y + " " + x + " " + y + " " + d);
            else sx(b, "input tap " + x + " " + y);
            return "tapped (" + x + "," + y + ")" + (d > 300 ? " for " + d + "ms" : "");
        }
    }

    static final class Swipe extends Base {
        Swipe(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("swipe", "Swipe from (x1,y1) to (x2,y2). Use for scrolling and gestures.",
                    schema("*x1", "integer", "Start X", "*y1", "integer", "Start Y", "*x2", "integer", "End X",
                            "*y2", "integer", "End Y", "duration_ms", "integer", "Duration, default 300"));
        }
        public String describe(Map<String, Object> a) {
            return "Swipe (" + a.get("x1") + "," + a.get("y1") + ") -> (" + a.get("x2") + "," + a.get("y2") + ")";
        }
        public String execute(Map<String, Object> a) throws Exception {
            int x1 = num(a, "x1", 0, true), y1 = num(a, "y1", 0, true), x2 = num(a, "x2", 0, true), y2 = num(a, "y2", 0, true);
            int d = num(a, "duration_ms", 300, false);
            sx(b, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + d);
            return "swiped";
        }
    }

    static final class TypeText extends Base {
        private final ClipboardSink clip;
        TypeText(ExecBackend b, ClipboardSink clip) { super(b); this.clip = clip; }
        public ToolSpec spec() {
            return new ToolSpec("type_text", "Type text into the focused input field (tap the field first). "
                    + "Non-ASCII text (e.g. Russian) is pasted through the clipboard.",
                    schema("*text", "string", "Text to type"));
        }
        public String describe(Map<String, Object> a) {
            String t = String.valueOf(a.get("text"));
            return "Type text: " + (t.length() > 80 ? t.substring(0, 80) + "…" : t);
        }
        public String execute(Map<String, Object> a) throws Exception {
            String t = Tools.str(a, "text");
            boolean ascii = true;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) > 126 || t.charAt(i) < 32) { ascii = false; break; }
            if (ascii) {
                StringBuilder sb = new StringBuilder();
                for (char c : t.toCharArray()) {
                    if (c == ' ') sb.append("%s");
                    else if (c == '%') sb.append("%%");
                    else sb.append(c);
                }
                sx(b, "input text " + ShellQuote.quote(sb.toString()));
                return "typed " + t.length() + " chars";
            }
            if (clip == null) throw new IllegalStateException("non-ASCII text needs the clipboard, which is unavailable");
            clip.set(t);
            Thread.sleep(150);
            sx(b, "input keyevent 279"); // KEYCODE_PASTE
            return "pasted " + t.length() + " chars via clipboard";
        }
    }

    static final class Key extends Base {
        Key(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("key", "Press a hardware/navigation key: back, home, recents, enter, delete, tab, "
                    + "search, power, volume_up, volume_down, menu, wake, sleep, or a numeric Android keycode.",
                    schema("*key", "string", "Key name or keycode"));
        }
        public String describe(Map<String, Object> a) { return "Press key: " + a.get("key"); }
        public String execute(Map<String, Object> a) throws Exception {
            String k = Tools.str(a, "key").toLowerCase().trim();
            String code;
            switch (k) {
                case "back": code = "KEYCODE_BACK"; break;
                case "home": code = "KEYCODE_HOME"; break;
                case "recents": case "app_switch": code = "KEYCODE_APP_SWITCH"; break;
                case "enter": code = "KEYCODE_ENTER"; break;
                case "delete": case "backspace": code = "KEYCODE_DEL"; break;
                case "tab": code = "KEYCODE_TAB"; break;
                case "search": code = "KEYCODE_SEARCH"; break;
                case "power": code = "KEYCODE_POWER"; break;
                case "volume_up": code = "KEYCODE_VOLUME_UP"; break;
                case "volume_down": code = "KEYCODE_VOLUME_DOWN"; break;
                case "menu": code = "KEYCODE_MENU"; break;
                case "wake": code = "KEYCODE_WAKEUP"; break;
                case "sleep": code = "KEYCODE_SLEEP"; break;
                default:
                    if (k.matches("\\d{1,3}")) code = k;
                    else if (k.matches("keycode_[a-z0-9_]+")) code = k.toUpperCase();
                    else throw new IllegalArgumentException("unknown key: " + k);
            }
            sx(b, "input keyevent " + code);
            return "pressed " + k;
        }
    }

    static final class OpenApp extends Base {
        OpenApp(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("open_app", "Launch an app by package name (see list_apps), or open a URL/intent if 'url' is given.",
                    schema("package", "string", "Package name, e.g. com.android.settings", "url", "string", "URL to open with the default handler"));
        }
        public String describe(Map<String, Object> a) { return "Open " + (a.get("url") != null ? a.get("url") : a.get("package")); }
        public String execute(Map<String, Object> a) throws Exception {
            Object url = a.get("url");
            if (url instanceof String && !((String) url).isEmpty()) {
                sx(b, "am start -a android.intent.action.VIEW -d " + ShellQuote.quote((String) url));
                return "opened " + url;
            }
            String pkg = Tools.str(a, "package");
            ExecResult r = sh(b, "monkey -p " + ShellQuote.quote(pkg) + " -c android.intent.category.LAUNCHER 1", 20_000L);
            if (!r.ok() || r.stdout().contains("No activities found")) throw new IllegalStateException("cannot launch " + pkg + ": " + r.stdout().trim());
            return "launched " + pkg;
        }
    }

    static final class Wait implements Tool {
        public ToolSpec spec() {
            return new ToolSpec("wait", "Wait for the given number of milliseconds (max 10000), e.g. for an app to load.",
                    schema("*ms", "integer", "Milliseconds"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Wait"; }
        public String execute(Map<String, Object> a) throws Exception {
            int ms = Math.max(0, Math.min(10_000, num(a, "ms", 0, true)));
            Thread.sleep(ms);
            return "waited " + ms + "ms";
        }
    }
}
