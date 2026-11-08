package com.agentdroid.agent

import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ShellQuote

/** Android-specific tools: intents, settings, logs, apps, notifications, SMS, contacts, calendar, location, toggles. */
object AndroidTools {
    @JvmStatic
    fun create(b: ExecBackend, hooks: PhoneHooks?): List<Tool> {
        val l = ArrayList<Tool>()
        l.add(DeviceInfo(b))
        l.add(Intent(b))
        l.add(Settings(b))
        l.add(Logcat(b))
        l.add(Dumpsys(b))
        l.add(AppInstall(b))
        l.add(AppUninstall(b))
        l.add(Toggle(b))
        l.add(Notifications(b))
        l.add(Sms(b))
        l.add(Contacts(b))
        l.add(Calendar(b))
        l.add(Location(b))
        l.add(Notify(hooks))
        l.add(ClipGet(hooks))
        l.add(ClipSet(hooks))
        return l
    }

    @Throws(Exception::class)
    private fun sx(b: ExecBackend, cmd: String): String = UiTools.sx(b, cmd)

    private fun lim(s: String, max: Int): String = if (s.length > max) s.substring(0, max) + "\n...[cut]" else s

    private fun opt(a: Map<String, Any?>, k: String): String? {
        val v = a[k]
        return if (v is String && v.isNotEmpty()) v else null
    }

    internal abstract class Base : Tool {
        override fun needsApproval(args: Map<String, Any?>): Boolean = true
    }

    internal abstract class ReadOnly : Base() {
        override fun needsApproval(args: Map<String, Any?>): Boolean = false
    }

    internal class DeviceInfo(private val b: ExecBackend) : ReadOnly() {
        override fun spec(): ToolSpec = ToolSpec(
            "device_info",
            "Facts about this phone and the environment you run in: model, Android version, screen size, " +
                "battery, uid, free storage, foreground app.",
            UiTools.schema(),
        )

        override fun describe(args: Map<String, Any?>): String = "Device info"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val script = "echo model=\$(getprop ro.product.model) brand=\$(getprop ro.product.brand); " +
                "echo android=\$(getprop ro.build.version.release) sdk=\$(getprop ro.build.version.sdk) patch=\$(getprop ro.build.version.security_patch); " +
                "echo locale=\$(getprop persist.sys.locale); id; wm size; wm density; " +
                "dumpsys battery | grep -E 'level|status|powered' | head -5; df -h /data | tail -1; " +
                "dumpsys window | grep -E 'mCurrentFocus' | head -1"
            return sx(b, script)
        }
    }

    internal class Intent(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "intent",
            "Send an Android intent (am start/startservice/broadcast). Set kind to activity (default), service or broadcast. " +
                "extras: one per line as 'type key value' with type es|ei|ez|el (string,int,bool,long).",
            UiTools.schema(
                "action", "string", "e.g. android.intent.action.VIEW", "data", "string", "URI, e.g. https://… or tel:… or geo:…",
                "package", "string", "Target package", "component", "string", "pkg/.Activity", "mime", "string", "MIME type",
                "extras", "string", "Extras, one per line", "kind", "string", "activity | service | broadcast",
            ),
        )

        private fun build(a: Map<String, Any?>): String {
            val kind = opt(a, "kind") ?: "activity"
            val sb = StringBuilder("am ")
            sb.append(if (kind == "service") "startservice" else if (kind == "broadcast") "broadcast" else "start")
            val action = opt(a, "action")
            if (action != null) sb.append(" -a ").append(ShellQuote.quote(action))
            val data = opt(a, "data")
            if (data != null) sb.append(" -d ").append(ShellQuote.quote(data))
            val mime = opt(a, "mime")
            if (mime != null) sb.append(" -t ").append(ShellQuote.quote(mime))
            val component = opt(a, "component")
            val pkg = opt(a, "package")
            if (component != null) {
                sb.append(" -n ").append(ShellQuote.quote(component))
            } else if (pkg != null) {
                sb.append(" -p ").append(ShellQuote.quote(pkg))
            }
            val ex = opt(a, "extras")
            if (ex != null) {
                for (line in ex.split("\n")) {
                    val p = line.jtrim().split(WHITESPACE, 3)
                    if (p.size == 3 && EXTRA_TYPE.matches(p[0])) {
                        sb.append(" --").append(p[0]).append(' ').append(ShellQuote.quote(p[1])).append(' ').append(ShellQuote.quote(p[2]))
                    }
                }
            }
            return sb.toString()
        }

        override fun sensitive(args: Map<String, Any?>): Boolean {
            val act = args["action"].toString()
            val data = args["data"].toString()
            return act.contains("CALL") || act.contains("SEND") || act.contains("DELETE") || data.startsWith("smsto:") ||
                data.startsWith("sms:") || data.startsWith("mailto:") || data.startsWith("tel:") && act.contains("CALL")
        }

        override fun describe(args: Map<String, Any?>): String = "Intent: " + build(args).substring(3)

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String = sx(b, build(args) + " 2>&1")

        private companion object {
            val WHITESPACE = Regex("\\s+")
            val EXTRA_TYPE = Regex("e[sizl]")
        }
    }

    internal class Settings(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "settings",
            "Read or change Android settings (brightness, volume, timeouts, rotation…). " +
                "Examples: system screen_brightness 120; system accelerometer_rotation 0; global airplane_mode_on.",
            UiTools.schema(
                "*op", "string", "get | put | list", "*namespace", "string", "system | secure | global",
                "key", "string", "Setting name", "value", "string", "New value (for put)",
            ),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = "put" == args["op"]

        override fun describe(args: Map<String, Any?>): String =
            "settings " + args["op"] + " " + args["namespace"] + " " + args["key"] + " " + args["value"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val op = Tools.str(args, "op")
            val ns = Tools.str(args, "namespace")
            if (!NAMESPACE.matches(ns)) throw IllegalArgumentException("namespace must be system, secure or global")
            if (op == "list") return lim(sx(b, "settings list $ns"), 20_000)
            val key = Tools.str(args, "key")
            if (op == "get") return sx(b, "settings get " + ns + " " + ShellQuote.quote(key))
            if (op == "put") {
                sx(b, "settings put " + ns + " " + ShellQuote.quote(key) + " " + ShellQuote.quote(Tools.str(args, "value")))
                return "ok"
            }
            throw IllegalArgumentException("op must be get, put or list")
        }

        private companion object {
            val NAMESPACE = Regex("system|secure|global")
        }
    }

    internal class Logcat(private val b: ExecBackend) : ReadOnly() {
        override fun spec(): ToolSpec = ToolSpec(
            "logcat", "Last lines of the system log. Filter by substring; level: V D I W E.",
            UiTools.schema(
                "lines", "integer", "How many recent lines (default 200, max 2000)", "filter", "string", "Substring or regex (grep -E)",
                "level", "string", "Minimum level: V D I W E (default V)",
            ),
        )

        override fun describe(args: Map<String, Any?>): String = "Read logcat"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val n = Math.max(10, Math.min(2000, UiTools.num(args, "lines", 200, false)))
            val level = opt(args, "level")
            var lvl = if (level == null) "V" else level.uppercase().substring(0, 1)
            if (!LEVEL.matches(lvl)) lvl = "V"
            var cmd = "logcat -d -v time *:$lvl"
            val filter = opt(args, "filter")
            if (filter != null) cmd += " | grep -E " + ShellQuote.quote(filter)
            return lim(sx(b, "$cmd | tail -n $n"), 40_000)
        }

        private companion object {
            val LEVEL = Regex("[VDIWE]")
        }
    }

    internal class Dumpsys(private val b: ExecBackend) : ReadOnly() {
        override fun spec(): ToolSpec = ToolSpec(
            "dumpsys",
            "Run dumpsys for a service (battery, activity, window, notification, wifi, package <name>…). Output is cut.",
            UiTools.schema("*service", "string", "Service and arguments", "filter", "string", "grep -E filter on the output"),
        )

        override fun describe(args: Map<String, Any?>): String = "dumpsys " + args["service"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val s = Tools.str(args, "service")
            if (!SERVICE.matches(s)) throw IllegalArgumentException("bad service name")
            var cmd = "dumpsys $s"
            val filter = opt(args, "filter")
            if (filter != null) cmd += " | grep -E " + ShellQuote.quote(filter)
            return lim(sx(b, cmd), 40_000)
        }

        private companion object {
            val SERVICE = Regex("[A-Za-z0-9_. -]+")
        }
    }

    internal class AppInstall(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "app_install", "Install an APK file from a path on the phone (pm install -r).",
            UiTools.schema("*path", "string", "APK path"),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Install app " + args["path"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String =
            sx(b, "pm install -r " + ShellQuote.quote(Tools.str(args, "path")) + " 2>&1")
    }

    internal class AppUninstall(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "app_uninstall", "Uninstall an app by package name; keep_data=true keeps its data.",
            UiTools.schema("*package", "string", "Package name", "keep_data", "boolean", "Keep app data"),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "UNINSTALL app " + args["package"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val p = Tools.str(args, "package")
            if (!PACKAGE.matches(p)) throw IllegalArgumentException("bad package name")
            return sx(b, "pm uninstall " + (if (flag(args, "keep_data")) "-k " else "") + p + " 2>&1")
        }

        private companion object {
            val PACKAGE = Regex("[A-Za-z0-9_.]+")
        }
    }

    internal class Toggle(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "toggle", "Turn wifi, bluetooth, mobile data or nfc on or off; or set screen: on/off (wake/sleep).",
            UiTools.schema("*what", "string", "wifi | bluetooth | data | nfc | screen", "*on", "boolean", "true = on"),
        )

        override fun describe(args: Map<String, Any?>): String =
            "Turn " + args["what"] + (if (flag(args, "on")) " on" else " off")

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val w = Tools.str(args, "what")
            val st = if (flag(args, "on")) "enable" else "disable"
            return when (w) {
                "wifi", "data", "bluetooth", "nfc" -> sx(b, "svc $w $st 2>&1") + "done"
                "screen" -> sx(b, "input keyevent " + (if (flag(args, "on")) "KEYCODE_WAKEUP" else "KEYCODE_SLEEP")) + "done"
                else -> throw IllegalArgumentException("unknown: $w")
            }
        }
    }

    internal class Notifications(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "notifications", "List current notifications (app, title, text). Needs root/ADB. Private content: confirm access first.",
            UiTools.schema("package", "string", "Only this app"),
        )

        override fun describe(args: Map<String, Any?>): String = "Read notifications"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val raw = sx(b, "dumpsys notification --noredact")
            val sb = StringBuilder()
            var pkg: String? = null
            var title: String? = null
            var text: String? = null
            val only = opt(args, "package")
            for (line in raw.split("\n")) {
                val t = line.jtrim()
                if (t.startsWith("NotificationRecord")) {
                    flush(sb, pkg, title, text, only)
                    pkg = null
                    title = null
                    text = null
                    val i = t.indexOf("pkg=")
                    if (i >= 0) {
                        val e = t.indexOf(' ', i)
                        pkg = t.substring(i + 4, if (e < 0) t.length else e)
                    }
                } else if (t.startsWith("android.title=")) {
                    title = value(t)
                } else if (t.startsWith("android.text=")) {
                    text = value(t)
                }
            }
            flush(sb, pkg, title, text, only)
            return if (sb.isEmpty()) "no notifications" else lim(sb.toString(), 30_000)
        }

        private fun value(t: String): String {
            var v = t.substring(t.indexOf('=') + 1).jtrim()
            if (v.startsWith("String (")) v = v.substring(8, v.length - 1)
            return v
        }

        private fun flush(sb: StringBuilder, pkg: String?, title: String?, text: String?, only: String?) {
            if (pkg == null || (title == null && text == null)) return
            if (only != null && only != pkg) return
            sb.append(pkg).append(": ").append(title ?: "").append(" — ").append(text ?: "").append('\n')
        }
    }

    internal class Sms(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "sms_read", "Read recent SMS (inbox by default). Private: needs approval.",
            UiTools.schema(
                "box", "string", "inbox | sent", "limit", "integer", "How many (default 20, max 100)",
                "from", "string", "Filter by number or sender substring",
            ),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Read SMS"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val box = if ("sent" == opt(args, "box")) "sent" else "inbox"
            val n = Math.max(1, Math.min(100, UiTools.num(args, "limit", 20, false)))
            var cmd = "content query --uri content://sms/$box --projection address:date:body --sort 'date DESC'"
            val from = opt(args, "from")
            if (from != null) cmd += " | grep -i -F " + ShellQuote.quote(from)
            return lim(sx(b, "$cmd | head -n $n"), 30_000)
        }
    }

    internal class Contacts(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "contacts", "Search contacts by name or number substring (empty = first 50).",
            UiTools.schema("query", "string", "Name or number part"),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Read contacts"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            var cmd = "content query --uri content://com.android.contacts/data/phones --projection display_name:data1"
            val query = opt(args, "query")
            if (query != null) cmd += " | grep -i -F " + ShellQuote.quote(query)
            return lim(sx(b, "$cmd | head -n 50"), 20_000)
        }
    }

    internal class Calendar(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "calendar", "List calendar events (title, start, end, place); times are epoch milliseconds.",
            UiTools.schema("limit", "integer", "How many (default 30)"),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Read calendar"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val n = Math.max(1, Math.min(200, UiTools.num(args, "limit", 30, false)))
            return lim(
                sx(
                    b,
                    "content query --uri content://com.android.calendar/events --projection title:dtstart:dtend:eventLocation " +
                        "--sort 'dtstart DESC' | head -n $n",
                ),
                20_000,
            )
        }
    }

    internal class Location(private val b: ExecBackend) : Base() {
        override fun spec(): ToolSpec =
            ToolSpec("location", "Last known location of the phone (from dumpsys location).", UiTools.schema())

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Read location"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val out = sx(b, "dumpsys location | grep -iE 'last location|last known|Location\\[' | head -n 8")
            return if (out.jtrim().isEmpty()) "no recent location (turn location on)" else out
        }
    }

    internal class Notify(private val h: PhoneHooks?) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "notify", "Show a notification to the user (task finished, question, result).",
            UiTools.schema("*title", "string", "Title", "text", "string", "Body"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Notify: " + args["title"]

        override fun execute(args: Map<String, Any?>): String {
            if (h == null) throw IllegalStateException("notifications unavailable")
            h.notifyUser(Tools.str(args, "title"), args["text"]?.toString() ?: "")
            return "shown"
        }
    }

    internal class ClipGet(private val h: PhoneHooks?) : Base() {
        override fun spec(): ToolSpec = ToolSpec("clipboard_get", "Read the clipboard text.", UiTools.schema())

        override fun sensitive(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Read clipboard"

        override fun execute(args: Map<String, Any?>): String {
            if (h == null) throw IllegalStateException("clipboard unavailable")
            val s = h.getClipboard()
            return if (s == null || s.isEmpty()) "(empty, or Android hides the clipboard from background apps)" else s
        }
    }

    internal class ClipSet(private val h: PhoneHooks?) : Base() {
        override fun spec(): ToolSpec =
            ToolSpec("clipboard_set", "Put text on the clipboard.", UiTools.schema("*text", "string", "Text"))

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Set clipboard"

        override fun execute(args: Map<String, Any?>): String {
            if (h == null) throw IllegalStateException("clipboard unavailable")
            h.setClipboard(Tools.str(args, "text"))
            return "copied"
        }
    }
}
