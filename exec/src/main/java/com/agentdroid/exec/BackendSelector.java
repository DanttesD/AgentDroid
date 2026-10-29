package com.agentdroid.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Picks the backend for the agent. Default order is the order of registration
 * (root, own ADB on localhost, ADB over Wi-Fi, sandbox). The user can pin one by id.
 */
public final class BackendSelector {
    private final List<ExecBackend> backends;
    private volatile String pinnedId;

    public BackendSelector(List<ExecBackend> backendsInPriorityOrder) {
        this.backends = Collections.unmodifiableList(new ArrayList<>(backendsInPriorityOrder));
    }

    public List<ExecBackend> all() { return backends; }

    /** Pin a backend by id, or pass null to return to automatic selection. */
    public void pin(String id) {
        if (id != null && find(id) == null) throw new IllegalArgumentException("unknown backend: " + id);
        this.pinnedId = id;
    }

    public String pinned() { return pinnedId; }

    public ExecBackend find(String id) {
        for (ExecBackend b : backends) if (b.id().equals(id)) return b;
        return null;
    }

    /**
     * The backend to use right now, or null if none is available.
     * A pinned backend that is not available is NOT replaced silently: returns null,
     * so the caller can tell the user instead of running with different rights.
     */
    public ExecBackend select() {
        String pin = pinnedId;
        if (pin != null) {
            ExecBackend b = find(pin);
            return b != null && b.isAvailable() ? b : null;
        }
        for (ExecBackend b : backends) {
            if (b.isAvailable()) return b;
        }
        return null;
    }

    /** Availability of every backend, for the settings screen and the environment manifest. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        for (ExecBackend b : backends) {
            lines.add(b.id() + ": " + (b.isAvailable() ? "available" : "unavailable"));
        }
        return lines;
    }
}
