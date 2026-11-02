package com.agentdroid.agent;

import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ShellQuote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Android-specific tools: intents, settings, logs, apps, notifications, SMS, contacts, calendar, location, toggles. */
public final class AndroidTools {
    private AndroidTools() {}

    public static List<Tool> create(ExecBackend b, PhoneHooks hooks) {
        List<Tool> l = new ArrayList<>();
        l.add(new DeviceInfo(b));
        l.add(new Intent(b));
        l.add(new Settings(b));
        l.add(new Logcat(b));
        l.add(new Dumpsys(b));
        l.add(new AppInstall(b));
        l.add(new AppUninstall(b));
        l.add(new Toggle(b));
        l.add(new Notifications(b));
        l.add(new Sms(b));
        l.add(new Contacts(b));
        l.add(new Calendar(b));
        l.add(new Location(b));
        l.add(new Notify(hooks));
        l.add(new ClipGet(hooks));
        l.add(new ClipSet(hooks));
        return l;
    }

    private static String sx(ExecBackend b, String cmd) throws Exception { return UiTools.sx(b, cmd); }

    private static String lim(String s, int max) { return s.length() > max ? s.substring(0, max) + "\n...[cut]" : s; }

    private static boolean flag(Map<String, Object> a, String k) {
        return Boolean.TRUE.equals(a.get(k)) || "true".equals(String.valueOf(a.get(k)));
    }

    private static String opt(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    private abstract static class Base implements Tool {
        final ExecBackend b;
        Base(ExecBackend b) { this.b = b; }
        public boolean needsApproval(Map<String, Object> a) { return true; }
    }

    private abstract static class ReadOnly extends Base {
        ReadOnly(ExecBackend b) { super(b); }
        public boolean needsApproval(Map<String, Object> a) { return false; }
    }

    static final class DeviceInfo extends ReadOnly {
        DeviceInfo(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("device_info", "Facts about this phone and the environment you run in: model, Android version, screen size, "
                    + "battery, uid, free storage, foreground app.", UiTools.schema());
        }
        public String describe(Map<String, Object> a) { return "Device info"; }
        public String execute(Map<String, Object> a) throws Exception {
            String script = "echo model=$(getprop ro.product.model) brand=$(getprop ro.product.brand); "
                    + "echo android=$(getprop ro.build.version.release) sdk=$(getprop ro.build.version.sdk) patch=$(getprop ro.build.version.security_patch); "
                    + "echo locale=$(getprop persist.sys.locale); id; wm size; wm density; "
                    + "dumpsys battery | grep -E 'level|status|powered' | head -5; df -h /data | tail -1; "
                    + "dumpsys window | grep -E 'mCurrentFocus' | head -1";
            return sx(b, script);
        }
    }

    static final class Intent extends Base {
        Intent(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("intent", "Send an Android intent (am start/startservice/broadcast). Set kind to activity (default), service or broadcast. "
                    + "extras: one per line as 'type key value' with type es|ei|ez|el (string,int,bool,long).",
                    UiTools.schema("action", "string", "e.g. android.intent.action.VIEW", "data", "string", "URI, e.g. https://… or tel:… or geo:…",
                            "package", "string", "Target package", "component", "string", "pkg/.Activity", "mime", "string", "MIME type",
                            "extras", "string", "Extras, one per line", "kind", "string", "activity | service | broadcast"));
        }
        private String build(Map<String, Object> a) {
            String kind = opt(a, "kind") == null ? "activity" : opt(a, "kind");
            StringBuilder sb = new StringBuilder("am ");
            sb.append(kind.equals("service") ? "startservice" : kind.equals("broadcast") ? "broadcast" : "start");
            if (opt(a, "action") != null) sb.append(" -a ").append(ShellQuote.quote(opt(a, "action")));
            if (opt(a, "data") != null) sb.append(" -d ").append(ShellQuote.quote(opt(a, "data")));
            if (opt(a, "mime") != null) sb.append(" -t ").append(ShellQuote.quote(opt(a, "mime")));
            if (opt(a, "component") != null) sb.append(" -n ").append(ShellQuote.quote(opt(a, "component")));
            else if (opt(a, "package") != null) sb.append(" -p ").append(ShellQuote.quote(opt(a, "package")));
            String ex = opt(a, "extras");
            if (ex != null) {
                for (String line : ex.split("\n")) {
                    String[] p = line.trim().split("\\s+", 3);
                    if (p.length == 3 && p[0].matches("e[sizl]")) sb.append(" --").append(p[0]).append(' ')
                            .append(ShellQuote.quote(p[1])).append(' ').append(ShellQuote.quote(p[2]));
                }
            }
            return sb.toString();
        }
        public boolean sensitive(Map<String, Object> a) {
            String act = String.valueOf(a.get("action")), data = String.valueOf(a.get("data"));
            return act.contains("CALL") || act.contains("SEND") || act.contains("DELETE") || data.startsWith("smsto:") || data.startsWith("sms:")
                    || data.startsWith("mailto:") || data.startsWith("tel:") && act.contains("CALL");
        }
        public String describe(Map<String, Object> a) { return "Intent: " + build(a).substring(3); }
        public String execute(Map<String, Object> a) throws Exception {
            return sx(b, build(a) + " 2>&1");
        }
    }

    static final class Settings extends Base {
        Settings(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("settings", "Read or change Android settings (brightness, volume, timeouts, rotation…). "
                    + "Examples: system screen_brightness 120; system accelerometer_rotation 0; global airplane_mode_on.",
                    UiTools.schema("*op", "string", "get | put | list", "*namespace", "string", "system | secure | global",
                            "key", "string", "Setting name", "value", "string", "New value (for put)"));
        }
        public boolean needsApproval(Map<String, Object> a) { return "put".equals(a.get("op")); }
        public String describe(Map<String, Object> a) { return "settings " + a.get("op") + " " + a.get("namespace") + " " + a.get("key") + " " + a.get("value"); }
        public String execute(Map<String, Object> a) throws Exception {
            String op = Tools.str(a, "op"), ns = Tools.str(a, "namespace");
            if (!ns.matches("system|secure|global")) throw new IllegalArgumentException("namespace must be system, secure or global");
            if (op.equals("list")) return lim(sx(b, "settings list " + ns), 20_000);
            String key = Tools.str(a, "key");
            if (op.equals("get")) return sx(b, "settings get " + ns + " " + ShellQuote.quote(key));
            if (op.equals("put")) {
                sx(b, "settings put " + ns + " " + ShellQuote.quote(key) + " " + ShellQuote.quote(Tools.str(a, "value")));
                return "ok";
            }
            throw new IllegalArgumentException("op must be get, put or list");
        }
    }

    static final class Logcat extends ReadOnly {
        Logcat(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("logcat", "Last lines of the system log. Filter by substring; level: V D I W E.",
                    UiTools.schema("lines", "integer", "How many recent lines (default 200, max 2000)", "filter", "string", "Substring or regex (grep -E)",
                            "level", "string", "Minimum level: V D I W E (default V)"));
        }
        public String describe(Map<String, Object> a) { return "Read logcat"; }
        public String execute(Map<String, Object> a) throws Exception {
            int n = Math.max(10, Math.min(2000, UiTools.num(a, "lines", 200, false)));
            String lvl = opt(a, "level") == null ? "V" : opt(a, "level").toUpperCase().substring(0, 1);
            if (!lvl.matches("[VDIWE]")) lvl = "V";
            String cmd = "logcat -d -v time *:" + lvl;
            if (opt(a, "filter") != null) cmd += " | grep -E " + ShellQuote.quote(opt(a, "filter"));
            return lim(sx(b, cmd + " | tail -n " + n), 40_000);
        }
    }

    static final class Dumpsys extends ReadOnly {
        Dumpsys(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("dumpsys", "Run dumpsys for a service (battery, activity, window, notification, wifi, package <name>…). Output is cut.",
                    UiTools.schema("*service", "string", "Service and arguments", "filter", "string", "grep -E filter on the output"));
        }
        public String describe(Map<String, Object> a) { return "dumpsys " + a.get("service"); }
        public String execute(Map<String, Object> a) throws Exception {
            String s = Tools.str(a, "service");
            if (!s.matches("[A-Za-z0-9_. -]+")) throw new IllegalArgumentException("bad service name");
            String cmd = "dumpsys " + s;
            if (opt(a, "filter") != null) cmd += " | grep -E " + ShellQuote.quote(opt(a, "filter"));
            return lim(sx(b, cmd), 40_000);
        }
    }

    static final class AppInstall extends Base {
        AppInstall(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("app_install", "Install an APK file from a path on the phone (pm install -r).",
                    UiTools.schema("*path", "string", "APK path"));
        }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Install app " + a.get("path"); }
        public String execute(Map<String, Object> a) throws Exception {
            return sx(b, "pm install -r " + ShellQuote.quote(Tools.str(a, "path")) + " 2>&1");
        }
    }

    static final class AppUninstall extends Base {
        AppUninstall(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("app_uninstall", "Uninstall an app by package name; keep_data=true keeps its data.",
                    UiTools.schema("*package", "string", "Package name", "keep_data", "boolean", "Keep app data"));
        }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "UNINSTALL app " + a.get("package"); }
        public String execute(Map<String, Object> a) throws Exception {
            String p = Tools.str(a, "package");
            if (!p.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("bad package name");
            return sx(b, "pm uninstall " + (flag(a, "keep_data") ? "-k " : "") + p + " 2>&1");
        }
    }

    static final class Toggle extends Base {
        Toggle(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("toggle", "Turn wifi, bluetooth, mobile data or nfc on or off; or set screen: on/off (wake/sleep).",
                    UiTools.schema("*what", "string", "wifi | bluetooth | data | nfc | screen", "*on", "boolean", "true = on"));
        }
        public String describe(Map<String, Object> a) { return "Turn " + a.get("what") + (flag(a, "on") ? " on" : " off"); }
        public String execute(Map<String, Object> a) throws Exception {
            String w = Tools.str(a, "what");
            String st = flag(a, "on") ? "enable" : "disable";
            switch (w) {
                case "wifi": case "data": case "bluetooth": case "nfc": return sx(b, "svc " + w + " " + st + " 2>&1") + "done";
                case "screen": return sx(b, "input keyevent " + (flag(a, "on") ? "KEYCODE_WAKEUP" : "KEYCODE_SLEEP")) + "done";
                default: throw new IllegalArgumentException("unknown: " + w);
            }
        }
    }

    static final class Notifications extends Base {
        Notifications(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("notifications", "List current notifications (app, title, text). Needs root/ADB. Private content: confirm access first.",
                    UiTools.schema("package", "string", "Only this app"));
        }
        public String describe(Map<String, Object> a) { return "Read notifications"; }
        public String execute(Map<String, Object> a) throws Exception {
            String raw = sx(b, "dumpsys notification --noredact");
            StringBuilder sb = new StringBuilder();
            String pkg = null, title = null, text = null;
            String only = opt(a, "package");
            for (String line : raw.split("\n")) {
                String t = line.trim();
                if (t.startsWith("NotificationRecord")) {
                    flush(sb, pkg, title, text, only);
                    pkg = null; title = null; text = null;
                    int i = t.indexOf("pkg=");
                    if (i >= 0) { int e = t.indexOf(' ', i); pkg = t.substring(i + 4, e < 0 ? t.length() : e); }
                } else if (t.startsWith("android.title=")) title = val(t);
                else if (t.startsWith("android.text=")) text = val(t);
            }
            flush(sb, pkg, title, text, only);
            return sb.length() == 0 ? "no notifications" : lim(sb.toString(), 30_000);
        }
        private static String val(String t) {
            String v = t.substring(t.indexOf('=') + 1).trim();
            int i = v.indexOf(" (");
            if (v.startsWith("String (") ) v = v.substring(8, v.length() - 1);
            return v;
        }
        private static void flush(StringBuilder sb, String pkg, String title, String text, String only) {
            if (pkg == null || (title == null && text == null)) return;
            if (only != null && !only.equals(pkg)) return;
            sb.append(pkg).append(": ").append(title == null ? "" : title).append(" — ").append(text == null ? "" : text).append('\n');
        }
    }

    static final class Sms extends Base {
        Sms(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("sms_read", "Read recent SMS (inbox by default). Private: needs approval.",
                    UiTools.schema("box", "string", "inbox | sent", "limit", "integer", "How many (default 20, max 100)", "from", "string", "Filter by number or sender substring"));
        }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Read SMS"; }
        public String execute(Map<String, Object> a) throws Exception {
            String box = "sent".equals(opt(a, "box")) ? "sent" : "inbox";
            int n = Math.max(1, Math.min(100, UiTools.num(a, "limit", 20, false)));
            String cmd = "content query --uri content://sms/" + box + " --projection address:date:body --sort 'date DESC'";
            if (opt(a, "from") != null) cmd += " | grep -i -F " + ShellQuote.quote(opt(a, "from"));
            return lim(sx(b, cmd + " | head -n " + n), 30_000);
        }
    }

    static final class Contacts extends Base {
        Contacts(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("contacts", "Search contacts by name or number substring (empty = first 50).",
                    UiTools.schema("query", "string", "Name or number part"));
        }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Read contacts"; }
        public String execute(Map<String, Object> a) throws Exception {
            String cmd = "content query --uri content://com.android.contacts/data/phones --projection display_name:data1";
            if (opt(a, "query") != null) cmd += " | grep -i -F " + ShellQuote.quote(opt(a, "query"));
            return lim(sx(b, cmd + " | head -n 50"), 20_000);
        }
    }

    static final class Calendar extends Base {
        Calendar(ExecBackend b) { super(b); }
        public ToolSpec spec() {
            return new ToolSpec("calendar", "List calendar events (title, start, end, place); times are epoch milliseconds.",
                    UiTools.schema("limit", "integer", "How many (default 30)"));
        }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Read calendar"; }
        public String execute(Map<String, Object> a) throws Exception {
            int n = Math.max(1, Math.min(200, UiTools.num(a, "limit", 30, false)));
            return lim(sx(b, "content query --uri content://com.android.calendar/events --projection title:dtstart:dtend:eventLocation --sort 'dtstart DESC' | head -n " + n), 20_000);
        }
    }

    static final class Location extends Base {
        Location(ExecBackend b) { super(b); }
        public ToolSpec spec() { return new ToolSpec("location", "Last known location of the phone (from dumpsys location).", UiTools.schema()); }
        public boolean sensitive(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Read location"; }
        public String execute(Map<String, Object> a) throws Exception {
            String out = sx(b, "dumpsys location | grep -iE 'last location|last known|Location\\[' | head -n 8");
            return out.trim().isEmpty() ? "no recent location (turn location on)" : out;
        }
    }

    static final class Notify extends Base {
        private final PhoneHooks h;
        Notify(PhoneHooks h) { super(null); this.h = h; }
        public ToolSpec spec() {
            return new ToolSpec("notify", "Show a notification to the user (task finished, question, result).",
                    UiTools.schema("*title", "string", "Title", "text", "string", "Body"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Notify: " + a.get("title"); }
        public String execute(Map<String, Object> a) {
            if (h == null) throw new IllegalStateException("notifications unavailable");
            h.notifyUser(Tools.str(a, "title"), a.get("text") == null ? "" : a.get("text").toString());
            return "shown";
        }
    }

    static final class ClipGet extends Base {
        private final PhoneHooks h;
        ClipGet(PhoneHooks h) { super(null); this.h = h; }
        public ToolSpec spec() { return new ToolSpec("clipboard_get", "Read the clipboard text.", UiTools.schema()); }
        public boolean sensitive(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Read clipboard"; }
        public String execute(Map<String, Object> a) {
            if (h == null) throw new IllegalStateException("clipboard unavailable");
            String s = h.getClipboard();
            return s == null || s.isEmpty() ? "(empty, or Android hides the clipboard from background apps)" : s;
        }
    }

    static final class ClipSet extends Base {
        private final PhoneHooks h;
        ClipSet(PhoneHooks h) { super(null); this.h = h; }
        public ToolSpec spec() { return new ToolSpec("clipboard_set", "Put text on the clipboard.", UiTools.schema("*text", "string", "Text")); }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Set clipboard"; }
        public String execute(Map<String, Object> a) {
            if (h == null) throw new IllegalStateException("clipboard unavailable");
            h.setClipboard(Tools.str(a, "text"));
            return "copied";
        }
    }
}
