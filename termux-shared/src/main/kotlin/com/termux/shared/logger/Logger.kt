package com.termux.shared.logger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.termux.TermuxConstants
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Collections

object Logger {

    const val DEFAULT_LOG_TAG: String = TermuxConstants.TERMUX_APP_NAME

    const val LOG_LEVEL_OFF: Int = 0 // log nothing
    const val LOG_LEVEL_NORMAL: Int = 1 // start logging error, warn and info messages and stacktraces
    const val LOG_LEVEL_DEBUG: Int = 2 // start logging debug messages
    const val LOG_LEVEL_VERBOSE: Int = 3 // start logging verbose messages

    const val DEFAULT_LOG_LEVEL: Int = LOG_LEVEL_OFF
    const val MAX_LOG_LEVEL: Int = LOG_LEVEL_VERBOSE
    private var CURRENT_LOG_LEVEL: Int = DEFAULT_LOG_LEVEL

    /**
     * The maximum size of the log entry payload that can be written to the logger. An attempt to
     * write more than this amount will result in a truncated log entry.
     *
     * The limit is 4068 but this includes log tag and log level prefix "D/" before log tag and ": "
     * suffix after it.
     *
     * #define LOGGER_ENTRY_MAX_PAYLOAD 4068
     * https://cs.android.com/android/_/android/platform/system/core/+/android10-release:liblog/include/log/log_read.h;l=127
     **/
    const val LOGGER_ENTRY_MAX_PAYLOAD: Int = 4068 // 4068 bytes

    /**
     * The maximum safe size of the log entry payload that can be written to the logger, based on
     * [.LOGGER_ENTRY_MAX_PAYLOAD]. Using 4000 as a safe limit to give log tag and its
     * prefix/suffix max 68 characters for itself. Use "log*Extended()" functions to use max possible
     * limit if tag is already known.
     **/
    const val LOGGER_ENTRY_MAX_SAFE_PAYLOAD: Int = 4000 // 4000 bytes

    @JvmStatic
    fun logMessage(logPriority: Int, tag: String?, message: String?) {
        if (logPriority == Log.ERROR && CURRENT_LOG_LEVEL >= LOG_LEVEL_NORMAL) {
            Log.e(getFullTag(tag), message!!)
        } else if (logPriority == Log.WARN && CURRENT_LOG_LEVEL >= LOG_LEVEL_NORMAL) {
            Log.w(getFullTag(tag), message!!)
        } else if (logPriority == Log.INFO && CURRENT_LOG_LEVEL >= LOG_LEVEL_NORMAL) {
            Log.i(getFullTag(tag), message!!)
        } else if (logPriority == Log.DEBUG && CURRENT_LOG_LEVEL >= LOG_LEVEL_DEBUG) {
            Log.d(getFullTag(tag), message!!)
        } else if (logPriority == Log.VERBOSE && CURRENT_LOG_LEVEL >= LOG_LEVEL_VERBOSE) {
            Log.v(getFullTag(tag), message!!)
        }
    }

    @JvmStatic
    fun logExtendedMessage(logLevel: Int, tag: String?, message: String?) {
        var message = message
        if (message == null) {
            return
        }

        var cutOffIndex: Int
        var nextNewlineIndex: Int
        var prefix = ""

        // -8 for prefix "(xx/xx)" (max 99 sections), - log tag length, -4 for log tag prefix "D/" and suffix ": "
        val maxEntrySize: Int = LOGGER_ENTRY_MAX_PAYLOAD - 8 - getFullTag(tag)!!.length - 4

        val messagesList: MutableList<String?> = ArrayList<String?>()

        while (!message!!.isEmpty()) {
            if (message!!.length > maxEntrySize) {
                cutOffIndex = maxEntrySize
                nextNewlineIndex = message!!.lastIndexOf('\n', cutOffIndex)
                if (nextNewlineIndex != -1) {
                    cutOffIndex = nextNewlineIndex + 1
                }
                messagesList.add(message!!.substring(0, cutOffIndex))
                message = message!!.substring(cutOffIndex)
            } else {
                messagesList.add(message)
                break
            }
        }

        var i = 0
        while (i < messagesList.size) {
            if (messagesList.size > 1) {
                prefix = "(" + (i + 1) + "/" + messagesList.size + ")\n"
            }
            logMessage(logLevel, tag, prefix + messagesList.get(i))
            i++
        }
    }

    @JvmStatic
    fun logError(tag: String?, message: String?) {
        logMessage(Log.ERROR, tag, message)
    }

    @JvmStatic
    fun logError(message: String) {
        logMessage(Log.ERROR, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logErrorExtended(tag: String?, message: String?) {
        logExtendedMessage(Log.ERROR, tag, message)
    }

    @JvmStatic
    fun logErrorExtended(message: String) {
        logExtendedMessage(Log.ERROR, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logWarn(tag: String?, message: String?) {
        logMessage(Log.WARN, tag, message)
    }

    @JvmStatic
    fun logWarn(message: String) {
        logMessage(Log.WARN, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logWarnExtended(tag: String, message: String) {
        logExtendedMessage(Log.WARN, tag, message)
    }

    @JvmStatic
    fun logWarnExtended(message: String) {
        logExtendedMessage(Log.WARN, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logInfo(tag: String?, message: String?) {
        logMessage(Log.INFO, tag, message)
    }

    @JvmStatic
    fun logInfo(message: String) {
        logMessage(Log.INFO, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logInfoExtended(tag: String, message: String) {
        logExtendedMessage(Log.INFO, tag, message)
    }

    @JvmStatic
    fun logInfoExtended(message: String) {
        logExtendedMessage(Log.INFO, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logDebug(tag: String?, message: String?) {
        logMessage(Log.DEBUG, tag, message)
    }

    @JvmStatic
    fun logDebug(message: String) {
        logMessage(Log.DEBUG, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logDebugExtended(tag: String?, message: String) {
        logExtendedMessage(Log.DEBUG, tag, message)
    }

    @JvmStatic
    fun logDebugExtended(message: String) {
        logExtendedMessage(Log.DEBUG, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logVerbose(tag: String?, message: String?) {
        logMessage(Log.VERBOSE, tag, message)
    }

    @JvmStatic
    fun logVerbose(message: String) {
        logMessage(Log.VERBOSE, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logVerboseExtended(tag: String, message: String) {
        logExtendedMessage(Log.VERBOSE, tag, message)
    }

    @JvmStatic
    fun logVerboseExtended(message: String) {
        logExtendedMessage(Log.VERBOSE, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logVerboseForce(tag: String, message: String) {
        Log.v(tag, message)
    }

    @JvmStatic
    fun logErrorAndShowToast(context: Context?, tag: String, message: String?) {
        if (context == null) {
            return
        }

        if (CURRENT_LOG_LEVEL >= LOG_LEVEL_NORMAL) {
            logError(tag, message)
            showToast(context, message, true)
        }
    }

    @JvmStatic
    fun logErrorAndShowToast(context: Context, message: String) {
        logErrorAndShowToast(context, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logDebugAndShowToast(context: Context?, tag: String, message: String) {
        if (context == null) {
            return
        }

        if (CURRENT_LOG_LEVEL >= LOG_LEVEL_DEBUG) {
            logDebug(tag, message)
            showToast(context, message, true)
        }
    }

    @JvmStatic
    fun logDebugAndShowToast(context: Context, message: String) {
        logDebugAndShowToast(context, DEFAULT_LOG_TAG, message)
    }

    @JvmStatic
    fun logStackTraceWithMessage(tag: String?, message: String?, throwable: Throwable?) {
        Logger.logErrorExtended(tag, getMessageAndStackTraceString(message, throwable))
    }

    @JvmStatic
    fun logStackTraceWithMessage(message: String, throwable: Throwable) {
        logStackTraceWithMessage(DEFAULT_LOG_TAG, message, throwable)
    }

    @JvmStatic
    fun logStackTrace(tag: String?, throwable: Throwable?) {
        logStackTraceWithMessage(tag, null, throwable)
    }

    @JvmStatic
    fun logStackTrace(throwable: Throwable) {
        logStackTraceWithMessage(DEFAULT_LOG_TAG, null, throwable)
    }

    @JvmStatic
    fun logStackTracesWithMessage(tag: String, message: String, throwablesList: MutableList<Throwable>) {
        Logger.logErrorExtended(tag, getMessageAndStackTracesString(message, throwablesList))
    }

    @JvmStatic
    fun getMessageAndStackTraceString(message: String?, throwable: Throwable?): String? {
        if (message == null && throwable == null) {
            return null
        } else if (message != null && throwable != null) {
            return message + ":\n" + getStackTraceString(throwable)
        } else if (throwable == null) {
            return message
        } else {
            return getStackTraceString(throwable)
        }
    }

    @JvmStatic
    fun getMessageAndStackTracesString(message: String?, throwablesList: MutableList<Throwable>?): String? {
        if (message == null && (throwablesList == null || throwablesList.size == 0)) {
            return null
        } else if (message != null && (throwablesList != null && throwablesList.size != 0)) {
            return message + ":\n" + getStackTracesString(null, getStackTracesStringArray(throwablesList))
        } else if (throwablesList == null || throwablesList.size == 0) {
            return message
        } else {
            return getStackTracesString(null, getStackTracesStringArray(throwablesList))
        }
    }

    @JvmStatic
    fun getStackTraceString(throwable: Throwable?): String? {
        if (throwable == null) {
            return null
        }

        var stackTraceString: String? = null

        try {
            val errors = StringWriter()
            val pw = PrintWriter(errors)
            throwable.printStackTrace(pw)
            pw.close()
            stackTraceString = errors.toString()
            errors.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        return stackTraceString
    }

    @JvmStatic
    fun getStackTracesStringArray(throwable: Throwable): Array<String?>? {
        return getStackTracesStringArray(Collections.singletonList(throwable))
    }

    @JvmStatic
    fun getStackTracesStringArray(throwablesList: MutableList<Throwable>?): Array<String?>? {
        if (throwablesList == null) {
            return null
        }
        val stackTraceStringArray = arrayOfNulls<String>(throwablesList.size)
        var i = 0
        while (i < throwablesList.size) {
            stackTraceStringArray[i] = getStackTraceString(throwablesList.get(i))
            i++
        }
        return stackTraceStringArray
    }

    @JvmStatic
    fun getStackTracesString(label: String?, stackTraceStringArray: Array<String?>?): String {
        var label = label
        if (label == null) {
            label = "StackTraces:"
        }
        val stackTracesString = StringBuilder(label)

        if (stackTraceStringArray == null || stackTraceStringArray.size == 0) {
            stackTracesString.append(" -")
        } else {
            var i = 0
            while (i != stackTraceStringArray.size) {
                if (stackTraceStringArray.size > 1) {
                    stackTracesString.append("\n\nStacktrace ").append(i + 1)
                }

                stackTracesString.append("\n```\n").append(stackTraceStringArray[i]).append("\n```\n")
                i++
            }
        }

        return stackTracesString.toString()
    }

    @JvmStatic
    fun getStackTracesMarkdownString(label: String?, stackTraceStringArray: Array<String?>?): String {
        var label = label
        if (label == null) {
            label = "StackTraces"
        }
        val stackTracesString = StringBuilder("### " + label)

        if (stackTraceStringArray == null || stackTraceStringArray.size == 0) {
            stackTracesString.append("\n\n`-`")
        } else {
            var i = 0
            while (i != stackTraceStringArray.size) {
                if (stackTraceStringArray.size > 1) {
                    stackTracesString.append("\n\n\n#### Stacktrace ").append(i + 1)
                }

                stackTracesString.append("\n\n```\n").append(stackTraceStringArray[i]).append("\n```")
                i++
            }
        }

        stackTracesString.append("\n##\n")

        return stackTracesString.toString()
    }

    @JvmStatic
    fun getSingleLineLogStringEntry(label: String, `object`: Any?, def: String): String {
        if (`object` != null) {
            return label + ": `" + `object` + "`"
        } else {
            return label + ": " + def
        }
    }

    @JvmStatic
    fun getMultiLineLogStringEntry(label: String, `object`: Any?, def: String): String {
        if (`object` != null) {
            return label + ":\n```\n" + `object` + "\n```\n"
        } else {
            return label + ": " + def
        }
    }

    @JvmStatic
    fun showToast(context: Context?, toastText: String?, longDuration: Boolean) {
        if (context == null || DataUtils.isNullOrEmpty(toastText)) {
            return
        }

        Handler(Looper.getMainLooper()).post(Runnable {
            Toast.makeText(context, toastText, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        })
    }

    @JvmStatic
    fun getLogLevelsArray(): Array<CharSequence> {
        return arrayOf<CharSequence>(java.lang.String.valueOf(LOG_LEVEL_OFF), java.lang.String.valueOf(LOG_LEVEL_NORMAL), java.lang.String.valueOf(LOG_LEVEL_DEBUG), java.lang.String.valueOf(LOG_LEVEL_VERBOSE))
    }

    @JvmStatic
    fun getLogLevelLabelsArray(context: Context, logLevels: Array<CharSequence>?, addDefaultTag: Boolean): Array<CharSequence?>? {
        if (logLevels == null) {
            return null
        }

        val logLevelLabels = arrayOfNulls<CharSequence>(logLevels.size)

        for (i in 0 until (logLevels.size)) {
            logLevelLabels[i] = getLogLevelLabel(context, Integer.parseInt(logLevels[i].toString()), addDefaultTag)
        }

        return logLevelLabels
    }

    @JvmStatic
    fun getLogLevelLabel(context: Context, logLevel: Int, addDefaultTag: Boolean): String {
        var logLabel: String
        when (logLevel) {
            LOG_LEVEL_OFF -> {
                logLabel = context.getString(R.string.log_level_off)
            }
            LOG_LEVEL_NORMAL -> {
                logLabel = context.getString(R.string.log_level_normal)
            }
            LOG_LEVEL_DEBUG -> {
                logLabel = context.getString(R.string.log_level_debug)
            }
            LOG_LEVEL_VERBOSE -> {
                logLabel = context.getString(R.string.log_level_verbose)
            }
            else -> {
                logLabel = context.getString(R.string.log_level_unknown)
            }
        }

        if (addDefaultTag && logLevel == DEFAULT_LOG_LEVEL) {
            return logLabel + " (default)"
        } else {
            return logLabel
        }
    }

    @JvmStatic
    fun getLogLevel(): Int {
        return CURRENT_LOG_LEVEL
    }

    @JvmStatic
    fun setLogLevel(context: Context?, logLevel: Int): Int {
        if (isLogLevelValid(logLevel)) {
            CURRENT_LOG_LEVEL = logLevel
        } else {
            CURRENT_LOG_LEVEL = DEFAULT_LOG_LEVEL
        }

        if (context != null) {
            showToast(context, context.getString(R.string.log_level_value, getLogLevelLabel(context, CURRENT_LOG_LEVEL, false)), true)
        }

        return CURRENT_LOG_LEVEL
    }

    @JvmStatic
    fun getFullTag(tag: String?): String? {
        if (DEFAULT_LOG_TAG.equals(tag)) {
            return tag
        } else {
            return DEFAULT_LOG_TAG + ":" + tag
        }
    }

    @JvmStatic
    fun isLogLevelValid(logLevel: Int?): Boolean {
        return logLevel != null && logLevel >= LOG_LEVEL_OFF && logLevel <= MAX_LOG_LEVEL
    }

    /** Check if custom log level is valid and >= [.CURRENT_LOG_LEVEL]. If custom log level is
     * not valid then [.LOG_LEVEL_VERBOSE] must be >= [.CURRENT_LOG_LEVEL].*/
    @JvmStatic
    fun shouldEnableLoggingForCustomLogLevel(customLogLevel: Int?): Boolean {
        var customLogLevel = customLogLevel
        if (customLogLevel == null || CURRENT_LOG_LEVEL <= LOG_LEVEL_OFF || customLogLevel <= LOG_LEVEL_OFF) {
            return false
        }
        customLogLevel = if (Logger.isLogLevelValid(customLogLevel)) customLogLevel else Logger.LOG_LEVEL_VERBOSE
        return customLogLevel!! >= CURRENT_LOG_LEVEL
    }
}
