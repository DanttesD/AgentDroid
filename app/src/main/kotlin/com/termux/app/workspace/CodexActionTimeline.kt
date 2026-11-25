package com.termux.app.workspace

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Presentation-only grouping. Original command rows remain the source for details.*/
object CodexActionTimeline {

    @JvmStatic
    fun shell(row: JSONObject?): Boolean {
        return row!!.optString("kind").equals("tool") && (row.optString("actionType").equals("shell") || row.optString("title").startsWith("Shell · "))
    }

    @JvmStatic
    fun status(row: JSONObject?): String {
        var value: String = row!!.optString("status")
        if (value.isEmpty()) {
            value = (row.optString("title") as java.lang.String).replaceFirst("^Shell · ", "")
        }
        when (value) {
            "success", "done" -> {
                return "completed"
            }
            "executing" -> {
                return "running"
            }
            "error" -> {
                return "failed"
            }
            "canceled" -> {
                return "cancelled"
            }
            else -> {
                return value
            }
        }
    }

    @JvmStatic
    fun running(row: JSONObject): Boolean {
        val value: String = status(row)
        return value.equals("inProgress") || value.equals("running")
    }

    @JvmStatic
    fun failed(row: JSONObject?): Boolean {
        val value: String = status(row)
        return value.equals("failed") || value.equals("declined") || value.equals("aborted") || value.equals("cancelled") || value.equals("canceled") || row!!.has("exitCode") && !row.isNull("exitCode") && row.optInt("exitCode") != 0
    }

    @JvmStatic
    fun command(row: JSONObject): String {
        var command: String = row.optString("command")
        if (command.isEmpty()) {
            command = (row.optString("body") as java.lang.String).split("\n", 2)[0]
        }
        return if (command.length > 240) command.substring(0, 240) + "…" else command
    }

    @JvmStatic
    fun group(rows: MutableList<JSONObject?>): MutableList<JSONObject?> {
        return group(rows, false)
    }

    @JvmStatic
    fun group(rows: MutableList<JSONObject?>, allTools: Boolean): MutableList<JSONObject?> {
        var rows = rows
        if (allTools) {
            rows = AgentActionMetadata.project(rows)
        }
        val result: MutableList<JSONObject?> = ArrayList<JSONObject?>()
        var i = 0
        while (i < rows.size) {
            val first: JSONObject? = rows.get(i)
            var skill: String = first!!.optString("skillContextName")
            if (skill.isEmpty()) {
                skill = first.optString("skillName")
            }
            val skillGroup: Boolean = !skill.isEmpty() && first.optString("kind").equals("tool")
            if (!(if (allTools) first.optString("kind").equals("tool") else shell(first)) && !skillGroup) {
                result.add(first)
                i++
                continue
            }
            val steps = JSONArray()
            val turn: String = first.optString("turn")
            while (i < rows.size) {
                val next: JSONObject? = rows.get(i)
                var nextSkill: String = next!!.optString("skillContextName")
                if (nextSkill.isEmpty()) {
                    nextSkill = next.optString("skillName")
                }
                if (if (skillGroup) !next.optString("kind").equals("tool") || !skill.equals(nextSkill) else !(if (allTools) next.optString("kind").equals("tool") else shell(next)) || !nextSkill.isEmpty()) {
                    break
                }
                if (steps.length() > 0 && !turn.isEmpty() && !next.optString("turn").isEmpty() && !turn.equals(next.optString("turn"))) {
                    break
                }
                steps.put(next)
                i++
            }
            try {
                result.add(JSONObject().put("key", "codex-actions:" + first.optString("key")).put("kind", "actionGroup").put("skillName", if (skillGroup) skill else "").put("steps", steps))
            } catch (impossible: JSONException) {
                throw IllegalStateException(impossible)
            }
        }
        return result
    }
}
