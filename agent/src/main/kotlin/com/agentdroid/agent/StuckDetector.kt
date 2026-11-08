package com.agentdroid.agent

import java.util.ArrayDeque
import java.util.Deque

/**
 * Notices when the agent is going in circles and returns a hint to append to the tool result.
 * Rules (the idea follows droidclaw's stuck/drift detection): the same call three times in a row,
 * the same result four times in a row, and five observation-only calls without any action.
 */
class StuckDetector {
    private val calls: Deque<String> = ArrayDeque()
    private val results: Deque<String> = ArrayDeque()
    private var observing = 0
    private var hints = 0

    fun hints(): Int = hints

    fun shouldGiveUp(): Boolean = hints >= GIVE_UP_AFTER

    /** @return a hint for the model or null. */
    fun record(tool: String, argsJson: String?, result: String?, error: Boolean): String? {
        val sig = "$tool $argsJson"
        push(calls, sig, 3)
        push(results, "" + (result?.hashCode() ?: "") + (if (error) "!" else ""), 4)
        observing = if (OBSERVE.contains(tool)) observing + 1 else 0

        var hint: String? = null
        if (calls.size == 3 && allEqual(calls)) {
            hint = "You made the same call ($tool) three times in a row. It is not working: choose a different element, " +
                "scroll, use another app or tool, or tell the user what blocks you."
        } else if (results.size == 4 && allEqual(results) && !error) {
            hint = "The last four results are identical, nothing is changing. Stop repeating; try a different approach " +
                "(another element, a screenshot if the tree is empty, a programmatic way, or ask the user)."
        } else if (observing >= 5) {
            hint = "You have only been looking ($observing calls) without acting. Decide the next action now or report what you see."
            observing = 0
        }
        if (hint != null) {
            hints++
            calls.clear()
            results.clear()
        }
        return hint
    }

    private fun push(d: Deque<String>, v: String, max: Int) {
        d.addLast(v)
        while (d.size > max) d.removeFirst()
    }

    private fun allEqual(d: Deque<String>): Boolean {
        val first = d.peekFirst()
        for (s in d) if (s != first) return false
        return true
    }

    companion object {
        /** Tools that only look at the phone or wait. */
        internal val OBSERVE: Set<String> = hashSetOf(
            "ui_read", "ui_tree", "read_screen", "screenshot", "wait", "wait_for", "wait_for_content", "scroll",
            "current_app", "list_apps", "device_info", "read_file", "list_dir", "glob", "grep",
        )

        /** After this many hints the loop should stop instead of hinting again. */
        const val GIVE_UP_AFTER = 3
    }
}
