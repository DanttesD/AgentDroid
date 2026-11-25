package com.termux.app.workspace

import org.json.JSONObject

/** Bounded presentation cache only. Native history, inference and live permissions are not cached here.*/
class AgentChatCache {
    class State {
        @JvmField val rows: LinkedHashMap<String, JSONObject?> = LinkedHashMap<String, JSONObject?>()
        @JvmField var older: String = ""
        @JvmField var model: String? = ""
        @JvmField var session: String = ""
        @JvmField var notice: String = ""
        @JvmField var trimmed: Boolean = false
        @JvmField var historyExhausted: Boolean = false
        @JvmField var nativeChat: Boolean = false
        @JvmField var resolved: Boolean = false
        @JvmField var busy: Boolean = false
        @JvmField var directShell: Boolean = false
        @JvmField var needsTerminal: Boolean = false
        @JvmField var connected: Boolean = false
        @JvmField var failed: Boolean = false
        @JvmField var position: AgentChatView.Position? = null
        @JvmField var chars: Int = 0
    }

    private val entries: LinkedHashMap<String, State> = LinkedHashMap<String, State>()
    private var chars: Int = 0

    fun put(owner: String, state: State) {
        if (owner.isEmpty()) {
            return
        }
        take(owner)
        val list: MutableList<MutableMap.MutableEntry<String, JSONObject?>> = ArrayList<MutableMap.MutableEntry<String, JSONObject?>>(state.rows.entries)
        state.rows.clear()
        var size = 0
        val reverse = LinkedHashMap<String, JSONObject?>()
        for (i in list.size - 1 downTo 0) {
            val row: MutableMap.MutableEntry<String, JSONObject?> = list.get(i)
            val n: Int = row.key.length + row.value!!.toString().length
            if (size + n > MAX_ENTRY_CHARS) {
                break
            }
            reverse.put(row.key, row.value)
            size += n
        }
        val keys: MutableList<String> = ArrayList<String>(reverse.keys)
        for (i in keys.size - 1 downTo 0) {
            val key: String = keys.get(i)
            state.rows.put(key, reverse.get(key))
        }
        state.trimmed = state.rows.size < list.size
        state.chars = size + state.older.length + state.model!!.length + state.session.length + state.notice.length
        if (state.chars > MAX_TOTAL_CHARS) {
            return
        }
        entries.put(owner, state)
        chars += state.chars
        while (entries.size > MAX_ENTRIES || chars > MAX_TOTAL_CHARS) {
            take(entries.keys.iterator().next())
        }
    }

    fun take(owner: String): State? {
        val s: State? = entries.remove(owner)
        if (s != null) {
            chars -= s.chars
        }
        return s
    }

    fun clear() {
        entries.clear()
        chars = 0
    }

    fun size(): Int {
        return entries.size
    }

    fun characters(): Int {
        return chars
    }

    companion object {
        const val MAX_ENTRIES: Int = 8
        const val MAX_ENTRY_CHARS: Int = 512 * 1024
        const val MAX_TOTAL_CHARS: Int = 2 * 1024 * 1024
    }
}
