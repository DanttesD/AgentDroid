package com.termux.app.agent;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Opens visible Termux sessions that run an agent command (install, login, run). */
public final class TermuxSessions {
    private TermuxSessions() {}

    /** Script that opens a URL in a chosen built-in browser profile; agents call it through $BROWSER. */
    private static final String OPEN_SCRIPT = "#!" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/sh\n"
            + "cls=\"${AGENTDROID_BROWSER_CLASS:-com.termux.app.agent.WebBrowserActivity}\"\n"
            + "exec am start --user 0 -n \"" + "%PKG%" + "/$cls\" --es url \"$1\" >/dev/null 2>&1\n";

    public static void ensureHelpers(Context c) {
        try {
            File f = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/agentdroid-open");
            byte[] want = OPEN_SCRIPT.replace("%PKG%", c.getPackageName()).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream os = new FileOutputStream(f)) { os.write(want); }
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false);
        } catch (IOException ignored) {
            // the agent can still print the login URL; the user opens it manually
        }
    }

    /**
     * Starts a foreground session running {@code script} and keeps a shell open afterwards.
     * {@code browserClass} selects the browser profile used by login pages ($BROWSER).
     */
    public static void run(Context c, String label, String script, Class<?> browserClass) {
        ensureHelpers(c);
        String full = "export BROWSER=agentdroid-open AGENTDROID_BROWSER_CLASS='" + browserClass.getName() + "'; "
                + script + "; echo; echo '[" + c.getString(com.termux.R.string.acc_session_done) + "]'; exec bash -l";
        Uri exe = new Uri.Builder().scheme("file").path(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash").build();
        Intent i = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, exe);
        i.setClass(c, TermuxService.class);
        i.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{"-lc", full});
        i.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
        i.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                String.valueOf(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY));
        i.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label);
        c.startService(i);
    }
}
