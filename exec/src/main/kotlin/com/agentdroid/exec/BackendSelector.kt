package com.agentdroid.exec

import java.util.Collections

/**
 * Picks the backend for the agent. Default order is the order of registration
 * (root, own ADB on localhost, ADB over Wi-Fi, sandbox). The user can pin one by id.
 */
class BackendSelector(backendsInPriorityOrder: List<ExecBackend>) {
    private val backends: List<ExecBackend> = Collections.unmodifiableList(ArrayList(backendsInPriorityOrder))

    @Volatile
    private var pinnedId: String? = null

    fun all(): List<ExecBackend> = backends

    /** Pin a backend by id, or pass null to return to automatic selection. */
    fun pin(id: String?) {
        require(id == null || find(id) != null) { "unknown backend: $id" }
        pinnedId = id
    }

    fun pinned(): String? = pinnedId

    fun find(id: String?): ExecBackend? {
        for (b in backends) if (b.id() == id) return b
        return null
    }

    /**
     * The backend to use right now, or null if none is available.
     * A pinned backend that is not available is NOT replaced silently: returns null,
     * so the caller can tell the user instead of running with different rights.
     */
    fun select(): ExecBackend? {
        val pin = pinnedId
        if (pin != null) {
            val b = find(pin)
            return if (b != null && b.isAvailable()) b else null
        }
        for (b in backends) {
            if (b.isAvailable()) return b
        }
        return null
    }

    /** Availability of every backend, for the settings screen and the environment manifest. */
    fun report(): List<String> {
        val lines = ArrayList<String>()
        for (b in backends) {
            lines.add(b.id() + ": " + (if (b.isAvailable()) "available" else "unavailable"))
        }
        return lines
    }
}
