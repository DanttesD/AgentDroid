package com.termux.shared.crash

import android.content.Context
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.errors.Error
import com.termux.shared.termux.AndroidUtils
import java.nio.charset.Charset

/**
 * Catches uncaught exceptions and logs them.
 **/
open class CrashHandler : Thread.UncaughtExceptionHandler {

    private val mContext: Context
    private val mCrashHandlerClient: CrashHandlerClient
    private val defaultUEH: Thread.UncaughtExceptionHandler?

    private constructor(context: Context, crashHandlerClient: CrashHandlerClient) {
        this.mContext = context
        this.mCrashHandlerClient = crashHandlerClient
        this.defaultUEH = Thread.getDefaultUncaughtExceptionHandler()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        logCrash(mContext, mCrashHandlerClient, thread, throwable)
        defaultUEH!!.uncaughtException(thread, throwable)
    }

    interface CrashHandlerClient {

        /**
         * Get crash log file path.
         *
         * @param context The [Context] passed to [CrashHandler.CrashHandler].
         * @return Should return the crash log file path.
         **/
        fun getCrashLogFilePath(context: Context): String

        /**
         * Get app info markdown string to add to crash log.
         *
         * @param context The [Context] passed to [CrashHandler.CrashHandler].
         * @return Should return app info markdown string.
         **/
        fun getAppInfoMarkdownString(context: Context): String
    }

    companion object {

        private const val LOG_TAG: String = "CrashUtils"

        /**
         * Set default uncaught crash handler of current thread to [CrashHandler].
         **/
        @JvmStatic
        fun setCrashHandler(context: Context, crashHandlerClient: CrashHandlerClient) {
            if (!(Thread.getDefaultUncaughtExceptionHandler() is CrashHandler)) {
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, crashHandlerClient))
            }
        }

        /**
         * Log a crash in the crash log file at `crashlogFilePath`.
         *
         * @param context The [Context] for operations.
         * @param crashHandlerClient The [CrashHandlerClient] implementation.
         * @param thread The [Thread] in which the crash happened.
         * @param throwable The [Throwable] thrown for the crash.
         **/
        @JvmStatic
        fun logCrash(context: Context, crashHandlerClient: CrashHandlerClient, thread: Thread, throwable: Throwable) {
            val reportString = StringBuilder()

            reportString.append("## Crash Details\n")
            reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Thread", thread.toString(), "-"))
            reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Timestamp", AndroidUtils.getCurrentMilliSecondUTCTimeStamp(), "-"))
            reportString.append("\n\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Crash Message", throwable.message, "-"))
            reportString.append("\n\n").append(Logger.getStackTracesMarkdownString("Stacktrace", Logger.getStackTracesStringArray(throwable)))

            val appInfoMarkdownString: String? = crashHandlerClient.getAppInfoMarkdownString(context)
            if (appInfoMarkdownString != null && !appInfoMarkdownString.isEmpty()) {
                reportString.append("\n\n").append(appInfoMarkdownString)
            }

            reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context))

            // Log report string to logcat
            Logger.logError(reportString.toString())

            // Write report string to crash log file
            val error: Error? = FileUtils.writeStringToFile("crash log", crashHandlerClient.getCrashLogFilePath(context), Charset.defaultCharset(), reportString.toString(), false)
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, error.toString())
            }
        }
    }
}
