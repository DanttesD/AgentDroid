package com.termux.app.workspace

import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.util.function.LongSupplier
import org.json.JSONObject

/** Estimates only. Contains no Activity, worker or file contents. No timer or disk persistence.*/
class BackupInventoryCache {
    class Entry {
        @JvmField val inventory: JSONObject?
        @JvmField val folders: JSONObject
        @JvmField val created: Long
        @JvmField val bytes: Int

        constructor(i: JSONObject?, f: JSONObject, c: Long, b: Int) {
            inventory = i
            folders = f
            created = c
            bytes = b
        }
    }

    private val clock: LongSupplier
    private val entries: LinkedHashMap<String, Entry> = LinkedHashMap<String, Entry>()

    constructor(clock: LongSupplier) {
        this.clock = clock
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun get(key: String): Entry? {
        val e: Entry? = entries.get(key)
        if (e != null && (clock.getAsLong() < e.created || clock.getAsLong() - e.created >= MAX_AGE)) {
            entries.remove(key)
            return null
        }
        return e
    }

    @Synchronized
    fun put(key: String, inventory: JSONObject?, folders: JSONObject) {
        try {
            val bytes: Int = (inventory!!.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8).size + (folders.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8).size
            val previous: Entry? = get(key)
            entries.remove(key)
            if (bytes > MAX_BYTES) {
                return
            }
            val created: Long = if (inventory.optBoolean("reusedEstimate") && previous != null) previous.created else clock.getAsLong()
            entries.put(key, Entry(inventory, JSONObject(folders.toString()), created, bytes))
            var used = 0
            for (e in entries.values) {
                used += e.bytes
            }
            while (used > MAX_BYTES && !entries.isEmpty()) {
                val oldest: String = entries.keys.iterator().next()
                used -= entries.remove(oldest)!!.bytes
            }
        } catch (ignored: Exception) {
            entries.remove(key)
        }
    }

    companion object {
        @JvmField val SHARED: BackupInventoryCache = BackupInventoryCache(LongSupplier(SystemClock::elapsedRealtime))
        private const val MAX_AGE: Long = 600000L
        private const val MAX_BYTES: Int = 4 * 1024 * 1024
    }
}
