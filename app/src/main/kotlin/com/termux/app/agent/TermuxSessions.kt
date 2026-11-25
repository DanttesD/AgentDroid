package com.termux.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Opens visible Termux sessions that run an agent command (install, login, run).*/
object TermuxSessions {

    /** Script that opens a URL in a chosen built-in browser profile; agents call it through $BROWSER.*/
    private const val OPEN_SCRIPT: String = "#!" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/sh\ncls=\"\${AGENTDROID_BROWSER_CLASS:-com.termux.app.agent.WebBrowserActivity}\"\nexec am start --user 0 -n \"%PKG%/\$cls\" --es url \"\$1\" >/dev/null 2>&1\n"

    @JvmStatic
    fun ensureHelpers(c: Context) {
        try {
            val f = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/agentdroid-open")
            val want: ByteArray = ((OPEN_SCRIPT as java.lang.String).replace("%PKG%", c.getPackageName()) as java.lang.String).getBytes(StandardCharsets.UTF_8)
            (FileOutputStream(f)).use { os ->
                os.write(want)
            }
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false)
        } catch (ignored: IOException) {
            // the agent can still print the login URL; the user opens it manually
        }
    }

    /**
     * Starts a foreground session running `script` and keeps a shell open afterwards.
     * `browserClass` selects the browser profile used by login pages ($BROWSER).
     **/
    @JvmStatic
    fun run(c: Context, label: String, script: String, browserClass: Class<*>) {
        ensureHelpers(c)
        val full: String = "export BROWSER=agentdroid-open AGENTDROID_BROWSER_CLASS='" + browserClass.getName() + "'; " + script + "; echo; echo '[" + c.getString(R.string.acc_session_done) + "]'; exec bash -l"
        val exe: Uri = Uri.Builder().scheme("file").path(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/bash").build()
        val i = Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, exe)
        i.setClass(c, TermuxService::class.java)
        i.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf<String>("-lc", full))
        i.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH)
        i.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION, java.lang.String.valueOf(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY))
        i.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label)
        c.startService(i)
    }
}
