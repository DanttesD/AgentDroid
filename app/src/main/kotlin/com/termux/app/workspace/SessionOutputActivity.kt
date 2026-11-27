package com.termux.app.workspace

/** A redraw after viewport interaction must not start the output activity cue.*/
class SessionOutputActivity {
    private var ignoreUntil: Long = 0L
    private val resizeIgnore: LinkedHashMap<String, Long> = LinkedHashMap<String, Long>()
    private val output: LinkedHashMap<String, Long> = LinkedHashMap<String, Long>()

    fun viewport(now: Long) {
        ignoreUntil = now + 1600
        output.clear()
        resizeIgnore.clear()
    }

    // A keyboard resize or scroll must not erase ongoing work or extend its lifetime.
    fun resize(handle: String, now: Long) {
        if (active(handle, now)) {
            return
        }
        output.remove(handle)
        resizeIgnore.put(handle, now + 1600)
        while (resizeIgnore.size > 64) {
            resizeIgnore.remove(resizeIgnore.keys.iterator().next())
        }
    }

    fun output(handle: String, now: Long) {
        if (now < ignoreUntil || now < resizeIgnore.getOrDefault(handle, 0L)) {
            return
        }
        resizeIgnore.remove(handle)
        output.put(handle, now)
        while (output.size > 64) {
            output.remove(output.keys.iterator().next())
        }
    }

    fun active(handle: String, now: Long): Boolean {
        val last: Long? = output.get(handle)
        return last != null && now - last < 1600
    }
}
