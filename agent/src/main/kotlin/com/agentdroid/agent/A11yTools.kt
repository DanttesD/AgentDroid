package com.agentdroid.agent

/**
 * Screen tools that work through the accessibility service: elements are addressed by number
 * (`click(12)`), no coordinates and no root needed. Without the service they say how to enable it.
 */
object A11yTools {
    internal const val NEED = "The AgentDroid accessibility service is not running. Turn it on in Android Settings > " +
        "Accessibility > AgentDroid (or from the MCP screen), or use ui_tree + tap with root/ADB."

    @JvmStatic
    fun create(): List<Tool> {
        val l = ArrayList<Tool>()
        l.add(Read())
        l.add(Click(false))
        l.add(Click(true))
        l.add(SetText())
        l.add(Scroll())
        l.add(Global())
        l.add(WaitFor())
        return l
    }

    internal fun bridge(): ScreenBridge {
        val b = ScreenBridge.Holder.current
        if (b == null) throw IllegalStateException(NEED)
        return b
    }

    internal abstract class Base : Tool {
        override fun needsApproval(args: Map<String, Any?>): Boolean = true
    }

    internal class Read : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "ui_read",
            "Read the current screen as numbered elements (role, label, state, actions). " +
                "Then act with click(id), set_text(id,text), scroll(id,direction). Numbers are valid until the next ui_read. " +
                "Password fields are masked.",
            UiTools.schema("all", "boolean", "true = include non-interactive elements without text"),
        )

        override fun describe(args: Map<String, Any?>): String = "Read the screen"

        override fun execute(args: Map<String, Any?>): String = bridge().read(flag(args, "all"))
    }

    internal class Click(private val longPress: Boolean) : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            if (longPress) "long_click" else "click",
            (if (longPress) "Long-press" else "Click") + " the element with this number from the last ui_read.",
            UiTools.schema("*id", "integer", "Element number"),
        )

        override fun describe(args: Map<String, Any?>): String =
            (if (longPress) "Long-press" else "Click") + " element " + args["id"]

        override fun execute(args: Map<String, Any?>): String {
            val id = UiTools.num(args, "id", 0, true)
            return if (longPress) bridge().longClick(id) else bridge().click(id)
        }
    }

    internal class SetText : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "set_text", "Replace the text of an input field (element number from ui_read). Works with any language.",
            UiTools.schema("*id", "integer", "Element number", "*text", "string", "New text"),
        )

        override fun describe(args: Map<String, Any?>): String {
            val t = args["text"].toString()
            return "Set text of element " + args["id"] + ": " + (if (t.length > 80) t.substring(0, 80) + "…" else t)
        }

        override fun execute(args: Map<String, Any?>): String {
            val t = args["text"]
            return bridge().setText(UiTools.num(args, "id", 0, true), t?.toString() ?: "")
        }
    }

    internal class Scroll : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "scroll",
            "Scroll a scrollable element (number from ui_read) up, down, left or right. " +
                "'down' shows content further down the list.",
            UiTools.schema("*id", "integer", "Element number", "*direction", "string", "up | down | left | right"),
        )

        override fun describe(args: Map<String, Any?>): String = "Scroll element " + args["id"] + " " + args["direction"]

        override fun execute(args: Map<String, Any?>): String =
            bridge().scroll(UiTools.num(args, "id", 0, true), Tools.str(args, "direction"))
    }

    internal class Global : Base() {
        override fun spec(): ToolSpec = ToolSpec(
            "global_action",
            "System action: back, home, recents, notifications, quick_settings, lock_screen, power_dialog, screenshot.",
            UiTools.schema("*action", "string", "Action name"),
        )

        override fun describe(args: Map<String, Any?>): String = "System action: " + args["action"]

        override fun execute(args: Map<String, Any?>): String = bridge().global(Tools.str(args, "action"))
    }

    internal class WaitFor : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "wait_for",
            "Wait until some text appears on the screen (or disappears), up to timeout_ms (default 5000, max 30000).",
            UiTools.schema(
                "*text", "string", "Text to look for (case-insensitive substring)",
                "gone", "boolean", "true = wait until the text is no longer shown", "timeout_ms", "integer", "Timeout",
            ),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Wait for text: " + args["text"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val b = bridge()
            val text = Tools.str(args, "text").lowercase()
            val gone = flag(args, "gone")
            val end = System.currentTimeMillis() + Math.max(500, Math.min(30_000, UiTools.num(args, "timeout_ms", 5000, false)))
            while (true) {
                val present = b.read(true).lowercase().contains(text)
                if (present != gone) return if (gone) "text is gone" else "text found"
                if (System.currentTimeMillis() >= end) {
                    return if (gone) "timeout: text is still shown" else "timeout: text not found"
                }
                Thread.sleep(400)
            }
        }
    }
}
