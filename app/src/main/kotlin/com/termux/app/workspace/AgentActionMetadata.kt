package com.termux.app.workspace

import org.json.JSONException
import org.json.JSONObject

/** Presentation only. Never reads files or executes the command being described.*/
object AgentActionMetadata {
    @JvmStatic
    fun readSkill(row: JSONObject?): String {
        if (!CodexActionTimeline.status(row).equals("completed") || CodexActionTimeline.failed(row)) {
            return ""
        }
        val tool: String = row!!.optString("toolName")
        val path: String = row.optString("toolPath")
        if (!(tool.equals("Read") || tool.equals("read") || tool.equals("read_file"))) {
            return ""
        }
        if (!path.startsWith("/") || !path.endsWith("/SKILL.md") || path.contains("/../") || path.contains("/./")) {
            return ""
        }
        val parent: String = path.substring(0, path.length - 9)
        val slash: Int = parent.lastIndexOf('/')
        val name: String = parent.substring(slash + 1)
        return if ((name as java.lang.String).matches("[\\p{L}\\p{N}_.:-]{1,100}")) name else ""
    }

    @JvmStatic
    fun project(rows: MutableList<JSONObject?>): MutableList<JSONObject?> {
        val result: MutableList<JSONObject?> = ArrayList<JSONObject?>()
        var skill = ""
        var turn = ""
        for (row in rows) {
            val kind: String = row!!.optString("kind")
            val nextTurn: String = row.optString("turn")
            if (kind.equals("user") || !nextTurn.isEmpty() && !turn.isEmpty() && !turn.equals(nextTurn)) {
                skill = ""
            }
            if (!nextTurn.isEmpty()) {
                turn = nextTurn
            }
            val read: String = if (kind.equals("tool")) readSkill(row) else ""
            // An unsuccessful explicit instruction read must not carry the previous skill forward.
            if (kind.equals("tool") && row.optString("toolPath").endsWith("/SKILL.md")) {
                skill = read
            }
            if (!skill.isEmpty() && kind.equals("tool")) {
                val copy = JSONObject()
                try {
                    val keys: MutableIterator<String> = row.keys()
                    while (keys.hasNext()) {
                        val key: String = keys.next()
                        copy.put(key, row.get(key))
                    }
                    copy.put("skillContextName", skill)
                    if (!read.isEmpty()) {
                        copy.put("skillName", skill).put("skillSource", "read")
                    }
                } catch (impossible: JSONException) {
                    throw IllegalStateException(impossible)
                }
                result.add(copy)
            } else {
                result.add(row)
            }
        }
        return result
    }
}
