package com.agentdroid.agent

import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ExecRequest
import com.agentdroid.exec.ExecResult
import com.agentdroid.exec.ShellQuote
import java.util.regex.Pattern

/**
 * Tools that let a model see and drive the phone screen: screenshot, UI tree, taps, swipes, typing,
 * keys and app launching. Everything goes through the selected backend (root or ADB), so it works
 * with any agent that talks to the MCP server.
 */
object UiTools {
    /** Lets the app put text on the clipboard (needed to type non-ASCII text). */
    fun interface ClipboardSink {
        fun set(text: String)
    }

    @JvmStatic
    fun create(b: ExecBackend, clip: ClipboardSink?): List<Tool> {
        val l = ArrayList<Tool>()
        l.add(Screenshot(b))
        l.add(UiTree(b))
        l.add(Tap(b))
        l.add(Swipe(b))
        l.add(TypeText(b, clip))
        l.add(Key(b))
        l.add(OpenApp(b))
        l.add(ListApps(b))
        l.add(CurrentApp(b))
        l.add(Wait())
        return l
    }

    // ---------- helpers ----------

    @Throws(Exception::class)
    internal fun sh(b: ExecBackend, cmd: String, timeoutMs: Long): ExecResult =
        b.exec(ExecRequest.builder(cmd).timeoutMs(timeoutMs).build())

    @Throws(Exception::class)
    internal fun sx(b: ExecBackend, cmd: String): String {
        val r = sh(b, cmd, 30_000L)
        if (!r.ok()) {
            throw IllegalStateException("exit " + r.exitCode + ": " + (if (r.stderr().isEmpty()) r.stdout() else r.stderr()).jtrim())
        }
        return r.stdout()
    }

    internal fun num(a: Map<String, Any?>, k: String, def: Int, required: Boolean): Int {
        val v = a[k]
        if (v == null) {
            if (required) throw IllegalArgumentException("missing argument: $k")
            return def
        }
        if (v is Number) return Math.round(v.toDouble()).toInt()
        try {
            return Math.round(java.lang.Double.parseDouble(v.toString().jtrim())).toInt()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("not a number: $k")
        }
    }

    /** propsKv: name, type, description, ... ; names prefixed with '*' are required. */
    internal fun schema(vararg propsKv: Any?): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        val req = ArrayList<Any?>()
        var i = 0
        while (i + 2 < propsKv.size) {
            var n = propsKv[i] as String
            if (n.startsWith("*")) {
                n = n.substring(1)
                req.add(n)
            }
            props[n] = Json.obj("type", propsKv[i + 1], "description", propsKv[i + 2])
            i += 3
        }
        return Json.obj("type", "object", "properties", props, "required", req)
    }

    private val B64: CharArray = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()

    @JvmStatic
    fun base64(d: ByteArray): String {
        val sb = StringBuilder((d.size + 2) / 3 * 4)
        var i = 0
        while (i < d.size) {
            val b0 = d[i].toInt() and 0xff
            val b1 = if (i + 1 < d.size) d[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < d.size) d[i + 2].toInt() and 0xff else 0
            sb.append(B64[b0 shr 2]).append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < d.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < d.size) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    internal abstract class Base(protected val b: ExecBackend) : Tool {
        override fun needsApproval(args: Map<String, Any?>): Boolean = true

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String = run(args).text
    }

    // ---------- see ----------

    internal class Screenshot(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "screenshot",
            "Take a screenshot of the phone screen and return it as an image. " +
                "Use together with ui_tree to find exact coordinates.",
            schema(),
        )

        override fun describe(args: Map<String, Any?>): String = "Take a screenshot of the screen"

        @Throws(Exception::class)
        override fun run(args: Map<String, Any?>): ToolResult {
            var r: ExecResult? = null
            var d = ByteArray(0)
            try {
                val res = sh(b, "screencap -p", 30_000L)
                r = res
                d = res.stdoutBytes()
            } catch (ignored: Exception) {
            }
            if (r == null || !r.ok() || d.size < 8 || (d[0].toInt() and 0xff) != 0x89 || d[1] != 'P'.code.toByte()) {
                val sb = ScreenBridge.Holder.current
                val viaService = sb?.screenshot()
                if (viaService != null) return ToolResult("Screenshot (accessibility service).", viaService)
                if (r == null) {
                    throw IllegalStateException(
                        "screencap failed. Screenshots need root/ADB or the accessibility service (Android 11+).",
                    )
                }
                throw IllegalStateException(
                    "screencap failed (" + (if (r.stderr().isEmpty()) "exit " + r.exitCode else r.stderr().jtrim()) +
                        "). Screenshots need the root or ADB backend.",
                )
            }
            var size = ""
            try {
                size = sx(b, "wm size").jtrim()
            } catch (ignored: Exception) {
            }
            return ToolResult("Screenshot. $size", d)
        }
    }

    internal class UiTree(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "ui_tree",
            "Read the current screen as a list of UI elements: text, description, id, " +
                "type, flags and the center point (x,y) to tap. Prefer this over screenshots for finding buttons.",
            schema("all", "boolean", "true = include non-interactive elements without text too (default false)"),
        )

        override fun describe(args: Map<String, Any?>): String = "Read the UI tree of the screen"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val all = flag(args, "all")
            val f = "/data/local/tmp/ad_ui_" + System.nanoTime() + ".xml"
            val xml: String
            try {
                sx(b, "uiautomator dump $f >/dev/null 2>&1")
                xml = sx(b, "cat $f")
            } finally {
                try {
                    sh(b, "rm -f $f", 5_000L)
                } catch (ignored: Exception) {
                }
            }
            return format(xml, all)
        }

        companion object {
            private val NODE: Pattern = Pattern.compile("<node ([^>]*?)/?>")
            private val ATTR: Pattern = Pattern.compile("([\\w-]+)=\"([^\"]*)\"")
            private val BOUNDS: Pattern = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

            fun format(xml: String, all: Boolean): String {
                val sb = StringBuilder()
                val m = NODE.matcher(xml)
                var pkg: String? = null
                var n = 0
                while (m.find()) {
                    val at = LinkedHashMap<String, String>()
                    val am = ATTR.matcher(m.group(1))
                    while (am.find()) at[am.group(1)] = unescape(am.group(2))
                    if (pkg == null) {
                        pkg = at["package"]
                        sb.append("package: ").append(pkg).append('\n')
                    }
                    val text = at["text"]
                    val desc = at["content-desc"]
                    val id = at["resource-id"]
                    val click = "true" == at["clickable"] || "true" == at["long-clickable"] ||
                        "true" == at["checkable"] || "true" == at["scrollable"]
                    val has = (text != null && text.isNotEmpty()) || (desc != null && desc.isNotEmpty())
                    if (!all && !has && !click) continue
                    val bd = bounds(at["bounds"]) ?: continue
                    if (!all && (bd[2] <= bd[0] || bd[3] <= bd[1])) continue
                    sb.append('[').append(++n).append("] ")
                    val cls = at["class"]
                    if (cls != null) sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append(' ')
                    if (text != null && text.isNotEmpty()) sb.append('"').append(text.replace("\n", " ")).append("\" ")
                    if (desc != null && desc.isNotEmpty()) sb.append("desc=\"").append(desc.replace("\n", " ")).append("\" ")
                    if (id != null && id.isNotEmpty()) sb.append("id=").append(id.substring(id.indexOf('/') + 1)).append(' ')
                    if ("true" == at["clickable"]) sb.append("clickable ")
                    if ("true" == at["long-clickable"]) sb.append("long-clickable ")
                    if ("true" == at["scrollable"]) sb.append("scrollable ")
                    if ("true" == at["checked"]) sb.append("checked ")
                    if ("true" == at["selected"]) sb.append("selected ")
                    if ("true" == at["focused"]) sb.append("focused ")
                    if ("false" == at["enabled"]) sb.append("disabled ")
                    sb.append("@(").append((bd[0] + bd[2]) / 2).append(',').append((bd[1] + bd[3]) / 2).append(")\n")
                }
                if (n == 0) sb.append("(no elements; the app may block accessibility reading — use screenshot)\n")
                return sb.toString()
            }

            fun bounds(s: String?): IntArray? {
                if (s == null) return null
                val m = BOUNDS.matcher(s)
                if (!m.find()) return null
                return intArrayOf(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                )
            }

            fun unescape(s: String): String =
                s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&#10;", "\n").replace("&amp;", "&")
        }
    }

    internal class CurrentApp(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec =
            ToolSpec("current_app", "Which app/activity is in the foreground right now.", schema())

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Check foreground app"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val out = sx(b, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4")
            return if (out.jtrim().isEmpty()) "unknown" else out.jtrim()
        }
    }

    internal class ListApps(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "list_apps", "List installed app package names. By default only user-installed apps.",
            schema("system", "boolean", "true = include system apps", "filter", "string", "substring to filter package names"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "List installed apps"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val sys = flag(args, "system")
            val out = sx(b, "pm list packages" + (if (sys) "" else " -3")).replace("package:", "")
            val f = args["filter"]
            if (f is String && f.isNotEmpty()) {
                val sb = StringBuilder()
                for (line in out.split("\n")) if (line.lowercase().contains(f.lowercase())) sb.append(line).append('\n')
                return sb.toString()
            }
            return out
        }
    }

    // ---------- act ----------

    internal class Tap(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "tap", "Tap the screen at x,y (pixels, as in ui_tree). duration_ms > 300 makes a long press.",
            schema("*x", "integer", "X pixel", "*y", "integer", "Y pixel", "duration_ms", "integer", "Hold time for long press"),
        )

        override fun describe(args: Map<String, Any?>): String = "Tap at (" + args["x"] + ", " + args["y"] + ")"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val x = num(args, "x", 0, true)
            val y = num(args, "y", 0, true)
            val d = num(args, "duration_ms", 0, false)
            if (d > 300) sx(b, "input swipe $x $y $x $y $d") else sx(b, "input tap $x $y")
            return "tapped ($x,$y)" + (if (d > 300) " for " + d + "ms" else "")
        }
    }

    internal class Swipe(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "swipe", "Swipe from (x1,y1) to (x2,y2). Use for scrolling and gestures.",
            schema(
                "*x1", "integer", "Start X", "*y1", "integer", "Start Y", "*x2", "integer", "End X",
                "*y2", "integer", "End Y", "duration_ms", "integer", "Duration, default 300",
            ),
        )

        override fun describe(args: Map<String, Any?>): String =
            "Swipe (" + args["x1"] + "," + args["y1"] + ") -> (" + args["x2"] + "," + args["y2"] + ")"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val x1 = num(args, "x1", 0, true)
            val y1 = num(args, "y1", 0, true)
            val x2 = num(args, "x2", 0, true)
            val y2 = num(args, "y2", 0, true)
            val d = num(args, "duration_ms", 300, false)
            sx(b, "input swipe $x1 $y1 $x2 $y2 $d")
            return "swiped"
        }
    }

    internal class TypeText(b: ExecBackend, private val clip: ClipboardSink?) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "type_text",
            "Type text into the focused input field (tap the field first). " +
                "Non-ASCII text (e.g. Russian) is pasted through the clipboard.",
            schema("*text", "string", "Text to type"),
        )

        override fun describe(args: Map<String, Any?>): String {
            val t = args["text"].toString()
            return "Type text: " + (if (t.length > 80) t.substring(0, 80) + "…" else t)
        }

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val t = Tools.str(args, "text")
            var ascii = true
            for (i in t.indices) {
                if (t[i].code > 126 || t[i].code < 32) {
                    ascii = false
                    break
                }
            }
            if (ascii) {
                val sb = StringBuilder()
                for (c in t) {
                    if (c == ' ') sb.append("%s") else if (c == '%') sb.append("%%") else sb.append(c)
                }
                sx(b, "input text " + ShellQuote.quote(sb.toString()))
                return "typed " + t.length + " chars"
            }
            if (clip == null) throw IllegalStateException("non-ASCII text needs the clipboard, which is unavailable")
            clip.set(t)
            Thread.sleep(150)
            sx(b, "input keyevent 279") // KEYCODE_PASTE
            return "pasted " + t.length + " chars via clipboard"
        }
    }

    internal class Key(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "key",
            "Press a hardware/navigation key: back, home, recents, enter, delete, tab, " +
                "search, power, volume_up, volume_down, menu, wake, sleep, or a numeric Android keycode.",
            schema("*key", "string", "Key name or keycode"),
        )

        override fun describe(args: Map<String, Any?>): String = "Press key: " + args["key"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val k = Tools.str(args, "key").lowercase().jtrim()
            val code: String = when (k) {
                "back" -> "KEYCODE_BACK"
                "home" -> "KEYCODE_HOME"
                "recents", "app_switch" -> "KEYCODE_APP_SWITCH"
                "enter" -> "KEYCODE_ENTER"
                "delete", "backspace" -> "KEYCODE_DEL"
                "tab" -> "KEYCODE_TAB"
                "search" -> "KEYCODE_SEARCH"
                "power" -> "KEYCODE_POWER"
                "volume_up" -> "KEYCODE_VOLUME_UP"
                "volume_down" -> "KEYCODE_VOLUME_DOWN"
                "menu" -> "KEYCODE_MENU"
                "wake" -> "KEYCODE_WAKEUP"
                "sleep" -> "KEYCODE_SLEEP"
                else -> {
                    if (DIGITS.matches(k)) {
                        k
                    } else if (KEYCODE.matches(k)) {
                        k.uppercase()
                    } else {
                        throw IllegalArgumentException("unknown key: $k")
                    }
                }
            }
            sx(b, "input keyevent $code")
            return "pressed $k"
        }

        private companion object {
            val DIGITS = Regex("\\d{1,3}")
            val KEYCODE = Regex("keycode_[a-z0-9_]+")
        }
    }

    internal class OpenApp(b: ExecBackend) : Base(b) {
        override fun spec(): ToolSpec = ToolSpec(
            "open_app", "Launch an app by package name (see list_apps), or open a URL/intent if 'url' is given.",
            schema("package", "string", "Package name, e.g. com.android.settings", "url", "string", "URL to open with the default handler"),
        )

        override fun describe(args: Map<String, Any?>): String =
            "Open " + (if (args["url"] != null) args["url"] else args["package"])

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val url = args["url"]
            if (url is String && url.isNotEmpty()) {
                sx(b, "am start -a android.intent.action.VIEW -d " + ShellQuote.quote(url))
                return "opened $url"
            }
            val pkg = Tools.str(args, "package")
            val r = sh(b, "monkey -p " + ShellQuote.quote(pkg) + " -c android.intent.category.LAUNCHER 1", 20_000L)
            if (!r.ok() || r.stdout().contains("No activities found")) {
                throw IllegalStateException("cannot launch " + pkg + ": " + r.stdout().jtrim())
            }
            return "launched $pkg"
        }
    }

    internal class Wait : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "wait", "Wait for the given number of milliseconds (max 10000), e.g. for an app to load.",
            schema("*ms", "integer", "Milliseconds"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Wait"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val ms = Math.max(0, Math.min(10_000, num(args, "ms", 0, true)))
            Thread.sleep(ms.toLong())
            return "waited " + ms + "ms"
        }
    }
}
