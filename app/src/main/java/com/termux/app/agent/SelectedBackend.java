package com.termux.app.agent;

import com.agentdroid.exec.BackendSelector;
import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ExecRequest;
import com.agentdroid.exec.ExecResult;

import java.io.IOException;

/** Forwards every call to whatever backend the selector picks at that moment. */
public final class SelectedBackend implements ExecBackend {
    private final BackendSelector selector;

    public SelectedBackend(BackendSelector selector) { this.selector = selector; }

    private ExecBackend current() throws IOException {
        ExecBackend b = selector.select();
        if (b == null) throw new IOException("no backend available (root, local ADB or Termux)");
        return b;
    }

    @Override public String id() { return "selected"; }
    @Override public String displayName() { ExecBackend b = selector.select(); return b == null ? "-" : b.displayName(); }
    @Override public boolean isAvailable() { return selector.select() != null; }
    @Override public ExecResult exec(ExecRequest r) throws IOException, InterruptedException { return current().exec(r); }
    @Override public byte[] readFile(String p) throws IOException, InterruptedException { return current().readFile(p); }
    @Override public void writeFile(String p, byte[] d) throws IOException, InterruptedException { current().writeFile(p, d); }
}
