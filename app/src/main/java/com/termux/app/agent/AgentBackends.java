package com.termux.app.agent;

import android.content.Context;

import com.agentdroid.adb.AgentAdb;
import com.agentdroid.exec.AdbBackend;
import com.agentdroid.exec.BackendSelector;
import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ProcessBackend;
import com.agentdroid.exec.RootBackend;
import com.agentdroid.exec.ShellTransport;
import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds the backends the agent can use: root, own ADB on localhost:5555, Termux. */
public final class AgentBackends {
    public static final int LOCAL_ADB_PORT = 5555;
    public static final String TERMUX_HOME = TermuxConstants.TERMUX_HOME_DIR_PATH;

    private AgentBackends() {}

    public static BackendSelector create(Context context) {
        final AgentAdb adb = new AgentAdb(context);
        List<ExecBackend> l = new ArrayList<>();
        l.add(new RootBackend());
        l.add(new AdbBackend("adb-local", "ADB (127.0.0.1:" + LOCAL_ADB_PORT + ")", new ShellTransport() {
            @Override public Output run(String script, long timeoutMs) throws IOException {
                try {
                    AgentAdb.ShellResult r = adb.shell("127.0.0.1", LOCAL_ADB_PORT, script);
                    return new Output(r.getExitCode(), r.getOutput());
                } catch (Exception e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
            @Override public boolean isReachable() { return adb.isReachable("127.0.0.1", LOCAL_ADB_PORT, 1500); }
        }));
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        l.add(new ProcessBackend("termux", "Termux (app sandbox)",
                prefix + "/bin/env", "PATH=" + prefix + "/bin", "HOME=" + TERMUX_HOME,
                "PREFIX=" + prefix, "TMPDIR=" + prefix + "/tmp", prefix + "/bin/bash", "-c"));
        return new BackendSelector(l);
    }
}
