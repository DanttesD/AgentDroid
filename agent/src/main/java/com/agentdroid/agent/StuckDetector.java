package com.agentdroid.agent;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Notices when the agent is going in circles and returns a hint to append to the tool result.
 * Rules (the idea follows droidclaw's stuck/drift detection): the same call three times in a row,
 * the same result four times in a row, and five observation-only calls without any action.
 */
public final class StuckDetector {
    /** Tools that only look at the phone or wait. */
    static final Set<String> OBSERVE = new HashSet<>(Arrays.asList(
            "ui_read", "ui_tree", "read_screen", "screenshot", "wait", "wait_for", "wait_for_content", "scroll",
            "current_app", "list_apps", "device_info", "read_file", "list_dir", "glob", "grep"));

    /** After this many hints the loop should stop instead of hinting again. */
    public static final int GIVE_UP_AFTER = 3;

    private final Deque<String> calls = new ArrayDeque<>();
    private final Deque<String> results = new ArrayDeque<>();
    private int observing;
    private int hints;

    public int hints() { return hints; }

    public boolean shouldGiveUp() { return hints >= GIVE_UP_AFTER; }

    /** @return a hint for the model or null. */
    public String record(String tool, String argsJson, String result, boolean error) {
        String sig = tool + " " + argsJson;
        push(calls, sig, 3);
        push(results, "" + (result == null ? "" : result.hashCode()) + (error ? "!" : ""), 4);
        observing = OBSERVE.contains(tool) ? observing + 1 : 0;

        String hint = null;
        if (calls.size() == 3 && allEqual(calls)) {
            hint = "You made the same call (" + tool + ") three times in a row. It is not working: choose a different element, "
                    + "scroll, use another app or tool, or tell the user what blocks you.";
        } else if (results.size() == 4 && allEqual(results) && !error) {
            hint = "The last four results are identical, nothing is changing. Stop repeating; try a different approach "
                    + "(another element, a screenshot if the tree is empty, a programmatic way, or ask the user).";
        } else if (observing >= 5) {
            hint = "You have only been looking (" + observing + " calls) without acting. Decide the next action now or report what you see.";
            observing = 0;
        }
        if (hint != null) { hints++; calls.clear(); results.clear(); }
        return hint;
    }

    private static void push(Deque<String> d, String v, int max) {
        d.addLast(v);
        while (d.size() > max) d.removeFirst();
    }

    private static boolean allEqual(Deque<String> d) {
        String first = d.peekFirst();
        for (String s : d) if (!s.equals(first)) return false;
        return true;
    }
}
