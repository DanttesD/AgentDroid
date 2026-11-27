package com.termux.app.workspace

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Environment-scoped history, with optional project filtering on the client.*/
object CodexChatCatalog {
    class Entry {
        @JvmField val id: String
        @JvmField val name: String
        @JvmField val cwd: String
        @JvmField val updated: Long

        constructor(id: String, name: String, cwd: String, updated: Long) {
            this.id = id
            this.name = name
            this.cwd = cwd
            this.updated = updated
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun params(cursor: String?): JSONObject {
        val result: JSONObject = JSONObject().put("limit", 50).put("sortKey", "updated_at").put("archived", false).put("modelProviders", JSONArray())
        if (cursor != null && !cursor.isEmpty()) {
            result.put("cursor", cursor)
        }
        return result
    }

    @JvmStatic
    fun parse(chat: JSONObject): Entry? {
        val id: String = chat.optString("id")
        if (!CodexSessionShell.validThread(id)) {
            return null
        }
        var name: String = chat.optString("name", "")
        if (name.isEmpty() || name.equals("null")) {
            name = chat.optString("preview", WorkspaceText.tr("Чат Codex"))
        }
        if (name.length > 160) {
            name = name.substring(0, 160) + "…"
        }
        return Entry(id, name, chat.optString("cwd", ""), chat.optLong("updatedAt"))
    }

    @JvmStatic
    fun visible(chat: Entry, environment: String, directory: String, projectOnly: Boolean, query: String): Boolean {
        if (projectOnly && (chat.cwd.isEmpty() || !CodexProjects.matches(environment, directory, chat.cwd))) {
            return false
        }
        return ((chat.name + " " + chat.cwd) as java.lang.String).toLowerCase(Locale.ROOT).contains((query as java.lang.String).toLowerCase(Locale.ROOT))
    }
}
