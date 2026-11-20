package com.termux.shared.crash

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils

open class TermuxCrashUtils : CrashHandler.CrashHandlerClient {

    override fun getCrashLogFilePath(context: Context): String {
        return TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH
    }

    override fun getAppInfoMarkdownString(context: Context): String {
        return TermuxUtils.getAppInfoMarkdownString(context, true)
    }

    companion object {

        /**
         * Set default uncaught crash handler of current thread to [CrashHandler] for Termux app
         * and its plugin to log crashes at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH].
         **/
        @JvmStatic
        fun setCrashHandler(context: Context) {
            CrashHandler.setCrashHandler(context, TermuxCrashUtils())
        }
    }
}
