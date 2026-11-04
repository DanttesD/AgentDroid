package com.agentdroid.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composite screen tools built on top of {@link ScreenBridge}: one call replaces a typical
 * read → pick → act → read loop (find by label, scroll-and-collect, send, wait for a reply).
 * The idea of these higher-level skills follows the MIT-licensed droidclaw project (unitedbyai/droidclaw).
 */
public final class ScreenFlows {
    private ScreenFlows() {}

    public static List<Tool> create() {
        List<Tool> l = new ArrayList<>();
        l.add(new FindAndTap());
        l.add(new ReadScreen());
        l.add(new SubmitMessage());
        l.add(new WaitForContent());
        return l;
    }

    /** One element line of {@code ui_read}: {@code [3] Button "OK" desc="…" clickable @(1,2)}. */
    static final class El {
        final int id;
        final String text;
        final String desc;
        final boolean clickable;
        final boolean scrollable;
        final boolean editable;
        final boolean disabled;

        El(int id, String text, String desc, String flags) {
            this.id = id;
            this.text = text;
            this.desc = desc;
            this.clickable = has(flags, "clickable");
            this.scrollable = has(flags, "scrollable");
            this.editable = has(flags, "editable");
            this.disabled = has(flags, "disabled");
        }

        String label() { return text.isEmpty() ? desc : (desc.isEmpty() ? text : text + " / " + desc); }

        private static boolean has(String flags, String w) { return (" " + flags + " ").contains(" " + w + " "); }
    }

    private static final Pattern LINE = Pattern.compile("^\\[(\\d+)\\]\\s(.*)$");
    private static final Pattern DESC = Pattern.compile("desc=\"([^\"]*)\"");
    private static final Pattern TEXT = Pattern.compile("(?<!desc=)\"([^\"]*)\"");

    static List<El> parse(String screen) {
        List<El> out = new ArrayList<>();
        for (String line : screen.split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String rest = m.group(2);
            Matcher d = DESC.matcher(rest);
            String desc = d.find() ? d.group(1) : "";
            Matcher t = TEXT.matcher(rest);
            String text = t.find() ? t.group(1) : "";
            String flags = rest.replaceAll("\"[^\"]*\"", " ").replace("desc=", " ");
            int at = flags.indexOf("@(");
            if (at >= 0) flags = flags.substring(0, at);
            out.add(new El(Integer.parseInt(m.group(1)), text, desc, flags));
        }
        return out;
    }

    /** Visible text of the screen without element numbers and geometry, in reading order. */
    static List<String> texts(String screen) {
        Set<String> seen = new LinkedHashSet<>();
        for (El e : parse(screen)) {
            if (!e.text.isEmpty()) seen.add(e.text);
            else if (!e.desc.isEmpty()) seen.add(e.desc);
        }
        return new ArrayList<>(seen);
    }

    /** Best match for a label: exact beats prefix beats substring; clickable and enabled elements win ties. */
    static El find(List<El> els, String label, boolean exact, int skip) {
        String q = label.trim().toLowerCase(Locale.ROOT);
        List<El> hits = new ArrayList<>();
        for (El e : els) {
            if (e.disabled && !e.editable) continue;
            String t = e.text.toLowerCase(Locale.ROOT), d = e.desc.toLowerCase(Locale.ROOT);
            boolean ok = exact ? (t.equals(q) || d.equals(q)) : (t.contains(q) || d.contains(q));
            if (ok) hits.add(e);
        }
        hits.sort((a, b) -> score(b, q) - score(a, q));
        return skip >= 0 && skip < hits.size() ? hits.get(skip) : null;
    }

    private static int score(El e, String q) {
        String t = e.text.toLowerCase(Locale.ROOT), d = e.desc.toLowerCase(Locale.ROOT);
        int s = 0;
        if (t.equals(q) || d.equals(q)) s += 4;
        else if (t.startsWith(q) || d.startsWith(q)) s += 2;
        if (e.clickable) s += 3;
        return s;
    }

    private abstract static class Base implements Tool {
        public boolean needsApproval(Map<String, Object> a) { return true; }
    }

    private static boolean flag(Map<String, Object> a, String k) {
        return Boolean.TRUE.equals(a.get(k)) || "true".equals(String.valueOf(a.get(k)));
    }

    private static final Pattern RISKY = Pattern.compile(
            "(?iu)\\b(send|pay|buy|purchase|delete|remove|confirm|order|transfer|submit)\\b|отправ|оплат|куп[иь]|удал|подтверд|заказ|перев[еo]д");

    static final class FindAndTap extends Base {
        public ToolSpec spec() {
            return new ToolSpec("find_and_tap", "Find an element on the screen by its visible text or description and click it, "
                    + "in one call (no ui_read needed first). Prefers clickable elements and exact matches. "
                    + "If several elements match, use index (0 = best match) or a more specific label.",
                    UiTools.schema("*label", "string", "Text or description to look for (case-insensitive)",
                            "exact", "boolean", "true = the whole text must equal the label",
                            "index", "integer", "Which match to click, 0 = best (default)"));
        }
        public boolean sensitive(Map<String, Object> a) { return RISKY.matcher(String.valueOf(a.get("label"))).find(); }
        public String describe(Map<String, Object> a) { return "Find and tap: " + a.get("label"); }
        public String execute(Map<String, Object> a) {
            ScreenBridge b = A11yTools.bridge();
            String label = Tools.str(a, "label");
            int index = UiTools.num(a, "index", 0, false);
            List<El> els = parse(b.read(false));
            El e = find(els, label, flag(a, "exact"), index);
            if (e == null) {
                els = parse(b.read(true));
                e = find(els, label, flag(a, "exact"), index);
            }
            if (e == null) {
                StringBuilder sb = new StringBuilder("not found: " + label + ". Visible: ");
                int n = 0;
                for (String t : texts(b.read(false))) { if (n++ >= 25) break; sb.append('"').append(t).append("\" "); }
                return sb.toString().trim();
            }
            return "[" + e.label() + "] " + b.click(e.id);
        }
    }

    static final class ReadScreen extends Base {
        public ToolSpec spec() {
            return new ToolSpec("read_screen", "Read the whole page as plain text: scrolls the first scrollable area down "
                    + "and collects new text until nothing new appears (max_pages, default 5, max 15). "
                    + "Use it for articles, long chats and lists instead of repeated ui_read + scroll. "
                    + "The page is left scrolled down; scroll up to return.",
                    UiTools.schema("max_pages", "integer", "How many screens to read (1-15)"));
        }
        public String describe(Map<String, Object> a) { return "Read the whole page"; }
        public String execute(Map<String, Object> a) throws Exception {
            ScreenBridge b = A11yTools.bridge();
            int pages = Math.max(1, Math.min(15, UiTools.num(a, "max_pages", 5, false)));
            Set<String> all = new LinkedHashSet<>();
            String pkg = "";
            int read = 0;
            for (; read < pages; read++) {
                String screen = b.read(false);
                if (read == 0 && screen.startsWith("package: ")) pkg = screen.substring(9, screen.indexOf('\n') < 0 ? screen.length() : screen.indexOf('\n'));
                int before = all.size();
                all.addAll(texts(screen));
                if (read > 0 && all.size() == before) break;
                El sc = null;
                for (El e : parse(screen)) if (e.scrollable) { sc = e; break; }
                if (sc == null || read == pages - 1) { read++; break; }
                b.scroll(sc.id, "down");
                Thread.sleep(450);
            }
            StringBuilder sb = new StringBuilder();
            if (!pkg.isEmpty()) sb.append("package: ").append(pkg).append('\n');
            sb.append("screens read: ").append(read).append('\n');
            for (String t : all) sb.append(t).append('\n');
            return sb.toString();
        }
    }

    private static final String[] SEND = {"send", "отправить", "отправ", "submit", "post", "reply", "ответить", "отправить сообщение"};

    static final class SubmitMessage extends Base {
        public ToolSpec spec() {
            return new ToolSpec("submit_message", "Press the send button in a chat or form (looks for Send / Отправить / Submit / Reply, "
                    + "falls back to the Enter key). Type the text first with set_text.", UiTools.schema());
        }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Send the typed message"; }
        public String execute(Map<String, Object> a) {
            ScreenBridge b = A11yTools.bridge();
            List<El> els = parse(b.read(false));
            for (String w : SEND) {
                El e = find(els, w, true, 0);
                if (e != null && e.clickable) return "[" + e.label() + "] " + b.click(e.id);
            }
            for (String w : SEND) {
                El e = find(els, w, false, 0);
                if (e != null && e.clickable) return "[" + e.label() + "] " + b.click(e.id);
            }
            return "no send button found; press Enter on the keyboard (key ENTER) or look at ui_read";
        }
    }

    static final class WaitForContent implements Tool {
        public ToolSpec spec() {
            return new ToolSpec("wait_for_content", "Wait until the screen changes and then stops changing (for example an AI chatbot "
                    + "finishing its answer, a page loading). Returns the text that appeared. timeout_ms default 20000, max 90000; "
                    + "stable_ms (default 2500) is how long the screen must stay unchanged.",
                    UiTools.schema("timeout_ms", "integer", "Timeout", "stable_ms", "integer", "Quiet period that counts as finished"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Wait for the screen to settle"; }
        public String execute(Map<String, Object> a) throws Exception {
            ScreenBridge b = A11yTools.bridge();
            long end = System.currentTimeMillis() + Math.max(1000, Math.min(90_000, UiTools.num(a, "timeout_ms", 20_000, false)));
            long stable = Math.max(800, Math.min(15_000, UiTools.num(a, "stable_ms", 2500, false)));
            List<String> base = texts(b.read(false));
            List<String> last = base;
            long lastChange = System.currentTimeMillis();
            boolean changed = false;
            while (System.currentTimeMillis() < end) {
                Thread.sleep(500);
                List<String> cur = texts(b.read(false));
                if (!cur.equals(last)) { last = cur; lastChange = System.currentTimeMillis(); changed = true; }
                else if (changed && System.currentTimeMillis() - lastChange >= stable) break;
            }
            if (!changed) return "timeout: the screen did not change";
            List<String> added = new ArrayList<>(last);
            added.removeAll(base);
            boolean settled = System.currentTimeMillis() - lastChange >= stable;
            StringBuilder sb = new StringBuilder(settled ? "screen settled; new text:\n" : "timeout: still changing; new text so far:\n");
            for (String t : added) sb.append(t).append('\n');
            return sb.toString();
        }
    }
}
