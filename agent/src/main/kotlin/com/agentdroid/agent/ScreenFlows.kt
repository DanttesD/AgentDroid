package com.agentdroid.agent

import java.util.Locale
import java.util.regex.Pattern

/**
 * Composite screen tools built on top of [ScreenBridge]: one call replaces a typical
 * read, pick, act, read loop (find by label, scroll-and-collect, send, wait for a reply).
 * The idea of these higher-level skills follows the MIT-licensed droidclaw project (unitedbyai/droidclaw).
 */
object ScreenFlows {
    @JvmStatic
    fun create(): List<Tool> {
        val l = ArrayList<Tool>()
        l.add(FindAndTap())
        l.add(ReadScreen())
        l.add(SubmitMessage())
        l.add(WaitForContent())
        return l
    }

    /** One element line of `ui_read`: `[3] Button "OK" desc="..." clickable @(1,2)`. */
    class El(
        @JvmField val id: Int,
        @JvmField val text: String,
        @JvmField val desc: String,
        flags: String,
    ) {
        @JvmField val clickable: Boolean = has(flags, "clickable")

        @JvmField val scrollable: Boolean = has(flags, "scrollable")

        @JvmField val editable: Boolean = has(flags, "editable")

        @JvmField val disabled: Boolean = has(flags, "disabled")

        fun label(): String = if (text.isEmpty()) desc else (if (desc.isEmpty()) text else "$text / $desc")

        private companion object {
            fun has(flags: String, w: String): Boolean = (" $flags ").contains(" $w ")
        }
    }

    private val LINE: Pattern = Pattern.compile("^\\[(\\d+)\\]\\s(.*)$")
    private val DESC: Pattern = Pattern.compile("desc=\"([^\"]*)\"")
    private val TEXT: Pattern = Pattern.compile("(?<!desc=)\"([^\"]*)\"")
    private val QUOTED = Regex("\"[^\"]*\"")

    @JvmStatic
    fun parse(screen: String): List<El> {
        val out = ArrayList<El>()
        for (line in screen.split("\n")) {
            val m = LINE.matcher(line)
            if (!m.matches()) continue
            val rest = m.group(2)
            val d = DESC.matcher(rest)
            val desc = if (d.find()) d.group(1) else ""
            val t = TEXT.matcher(rest)
            val text = if (t.find()) t.group(1) else ""
            var flags = rest.replace(QUOTED, " ").replace("desc=", " ")
            val at = flags.indexOf("@(")
            if (at >= 0) flags = flags.substring(0, at)
            out.add(El(Integer.parseInt(m.group(1)), text, desc, flags))
        }
        return out
    }

    /** Visible text of the screen without element numbers and geometry, in reading order. */
    internal fun texts(screen: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (e in parse(screen)) {
            if (e.text.isNotEmpty()) seen.add(e.text) else if (e.desc.isNotEmpty()) seen.add(e.desc)
        }
        return ArrayList(seen)
    }

    /** Best match for a label: exact beats prefix beats substring; clickable and enabled elements win ties. */
    internal fun find(els: List<El>, label: String, exact: Boolean, skip: Int): El? {
        val q = label.jtrim().lowercase(Locale.ROOT)
        val hits = ArrayList<El>()
        for (e in els) {
            if (e.disabled && !e.editable) continue
            val t = e.text.lowercase(Locale.ROOT)
            val d = e.desc.lowercase(Locale.ROOT)
            val ok = if (exact) (t == q || d == q) else (t.contains(q) || d.contains(q))
            if (ok) hits.add(e)
        }
        hits.sortWith { a, b -> score(b, q) - score(a, q) }
        return if (skip >= 0 && skip < hits.size) hits[skip] else null
    }

    private fun score(e: El, q: String): Int {
        val t = e.text.lowercase(Locale.ROOT)
        val d = e.desc.lowercase(Locale.ROOT)
        var s = 0
        if (t == q || d == q) s += 4 else if (t.startsWith(q) || d.startsWith(q)) s += 2
        if (e.clickable) s += 3
        return s
    }

    internal abstract class Base : Tool {
        override fun needsApproval(args: Map<String, Any?>): Boolean = true
    }

    private val RISKY: Pattern = Pattern.compile(
        "(?iu)\\b(send|pay|buy|purchase|delete|remove|confirm|order|transfer|submit)\\b|" +
            "отправ|оплат|куп[иь]|удал|" +
            "подтверд|заказ|перев[еo]д",
    )

    internal class FindAndTap : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "find_and_tap",
            "Find an element on the screen by its visible text or description and click it, " +
                "in one call (no ui_read needed first). Prefers clickable elements and exact matches. " +
                "If several elements match, use index (0 = best match) or a more specific label.",
            UiTools.schema(
                "*label", "string", "Text or description to look for (case-insensitive)",
                "exact", "boolean", "true = the whole text must equal the label",
                "index", "integer", "Which match to click, 0 = best (default)",
            ),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = RISKY.matcher(args["label"].toString()).find()

        override fun describe(args: Map<String, Any?>): String = "Find and tap: " + args["label"]

        override fun execute(args: Map<String, Any?>): String {
            val b = A11yTools.bridge()
            val label = Tools.str(args, "label")
            val index = UiTools.num(args, "index", 0, false)
            var els = parse(b.read(false))
            var e = find(els, label, flag(args, "exact"), index)
            if (e == null) {
                els = parse(b.read(true))
                e = find(els, label, flag(args, "exact"), index)
            }
            if (e == null) {
                val sb = StringBuilder("not found: $label. Visible: ")
                var n = 0
                for (t in texts(b.read(false))) {
                    if (n++ >= 25) break
                    sb.append('"').append(t).append("\" ")
                }
                return sb.toString().jtrim()
            }
            return "[" + e.label() + "] " + b.click(e.id)
        }
    }

    internal class ReadScreen : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "read_screen",
            "Read the whole page as plain text: scrolls the first scrollable area down " +
                "and collects new text until nothing new appears (max_pages, default 5, max 15). " +
                "Use it for articles, long chats and lists instead of repeated ui_read + scroll. " +
                "The page is left scrolled down; scroll up to return.",
            UiTools.schema("max_pages", "integer", "How many screens to read (1-15)"),
        )

        override fun describe(args: Map<String, Any?>): String = "Read the whole page"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val b = A11yTools.bridge()
            val pages = Math.max(1, Math.min(15, UiTools.num(args, "max_pages", 5, false)))
            val all = LinkedHashSet<String>()
            var pkg = ""
            var read = 0
            while (read < pages) {
                val screen = b.read(false)
                if (read == 0 && screen.startsWith("package: ")) {
                    val nl = screen.indexOf('\n')
                    pkg = screen.substring(9, if (nl < 0) screen.length else nl)
                }
                val before = all.size
                all.addAll(texts(screen))
                if (read > 0 && all.size == before) break
                var sc: El? = null
                for (e in parse(screen)) {
                    if (e.scrollable) {
                        sc = e
                        break
                    }
                }
                if (sc == null || read == pages - 1) {
                    read++
                    break
                }
                b.scroll(sc.id, "down")
                Thread.sleep(450)
                read++
            }
            val sb = StringBuilder()
            if (pkg.isNotEmpty()) sb.append("package: ").append(pkg).append('\n')
            sb.append("screens read: ").append(read).append('\n')
            for (t in all) sb.append(t).append('\n')
            return sb.toString()
        }
    }

    private val SEND = arrayOf(
        "send", "отправить", "отправ", "submit", "post", "reply",
        "ответить",
        "отправить сообщение",
    )

    internal class SubmitMessage : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "submit_message",
            "Press the send button in a chat or form (looks for Send / Отправить / Submit / Reply, " +
                "falls back to the Enter key). Type the text first with set_text.",
            UiTools.schema(),
        )

        override fun sensitive(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Send the typed message"

        override fun execute(args: Map<String, Any?>): String {
            val b = A11yTools.bridge()
            val els = parse(b.read(false))
            for (w in SEND) {
                val e = find(els, w, true, 0)
                if (e != null && e.clickable) return "[" + e.label() + "] " + b.click(e.id)
            }
            for (w in SEND) {
                val e = find(els, w, false, 0)
                if (e != null && e.clickable) return "[" + e.label() + "] " + b.click(e.id)
            }
            return "no send button found; press Enter on the keyboard (key ENTER) or look at ui_read"
        }
    }

    internal class WaitForContent : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "wait_for_content",
            "Wait until the screen changes and then stops changing (for example an AI chatbot " +
                "finishing its answer, a page loading). Returns the text that appeared. timeout_ms default 20000, max 90000; " +
                "stable_ms (default 2500) is how long the screen must stay unchanged.",
            UiTools.schema("timeout_ms", "integer", "Timeout", "stable_ms", "integer", "Quiet period that counts as finished"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Wait for the screen to settle"

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val b = A11yTools.bridge()
            val end = System.currentTimeMillis() + Math.max(1000, Math.min(90_000, UiTools.num(args, "timeout_ms", 20_000, false)))
            val stable = Math.max(800, Math.min(15_000, UiTools.num(args, "stable_ms", 2500, false))).toLong()
            val base = texts(b.read(false))
            var last = base
            var lastChange = System.currentTimeMillis()
            var changed = false
            while (System.currentTimeMillis() < end) {
                Thread.sleep(500)
                val cur = texts(b.read(false))
                if (cur != last) {
                    last = cur
                    lastChange = System.currentTimeMillis()
                    changed = true
                } else if (changed && System.currentTimeMillis() - lastChange >= stable) {
                    break
                }
            }
            if (!changed) return "timeout: the screen did not change"
            val added = ArrayList(last)
            added.removeAll(base)
            val settled = System.currentTimeMillis() - lastChange >= stable
            val sb = StringBuilder(if (settled) "screen settled; new text:\n" else "timeout: still changing; new text so far:\n")
            for (t in added) sb.append(t).append('\n')
            return sb.toString()
        }
    }
}
