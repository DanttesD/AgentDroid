package com.agentdroid.exec;

import java.io.IOException;

/**
 * One way of running commands and touching files on the device:
 * root, own ADB, Termux, sandbox. All agent tools go through this interface.
 */
public interface ExecBackend {
    /** Stable id, e.g. "root", "adb-local", "termux", "sandbox". */
    String id();

    String displayName();

    /** Cheap-enough probe; may run a tiny command. Never throws. */
    boolean isAvailable();

    ExecResult exec(ExecRequest request) throws IOException, InterruptedException;

    default byte[] readFile(String path) throws IOException, InterruptedException {
        return ShellFiles.read(this, path);
    }

    default void writeFile(String path, byte[] data) throws IOException, InterruptedException {
        ShellFiles.write(this, path, data);
    }
}
