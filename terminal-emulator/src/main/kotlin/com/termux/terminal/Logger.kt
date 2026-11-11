package com.termux.terminal

import android.util.Log
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter

object Logger {

    /**
     * android.util.Log declares the message as non-null and the framework throws this exact NPE for a null one
     * ("println needs a message"); the Java code passed the null straight through, so a null message keeps failing the same way.
     */
    private fun logMessage(message: String?): String = message ?: throw NullPointerException("println needs a message")

    @JvmStatic
    fun logError(client: TerminalSessionClient?, logTag: String?, message: String?) {
        if (client != null)
            client.logError(logTag, message)
        else
            Log.e(logTag, logMessage(message))
    }

    @JvmStatic
    fun logWarn(client: TerminalSessionClient?, logTag: String?, message: String?) {
        if (client != null)
            client.logWarn(logTag, message)
        else
            Log.w(logTag, logMessage(message))
    }

    @JvmStatic
    fun logInfo(client: TerminalSessionClient?, logTag: String?, message: String?) {
        if (client != null)
            client.logInfo(logTag, message)
        else
            Log.i(logTag, logMessage(message))
    }

    @JvmStatic
    fun logDebug(client: TerminalSessionClient?, logTag: String?, message: String?) {
        if (client != null)
            client.logDebug(logTag, message)
        else
            Log.d(logTag, logMessage(message))
    }

    @JvmStatic
    fun logVerbose(client: TerminalSessionClient?, logTag: String?, message: String?) {
        if (client != null)
            client.logVerbose(logTag, message)
        else
            Log.v(logTag, logMessage(message))
    }

    @JvmStatic
    fun logStackTraceWithMessage(client: TerminalSessionClient?, tag: String?, message: String?, throwable: Throwable?) {
        logError(client, tag, getMessageAndStackTraceString(message, throwable))
    }

    @JvmStatic
    fun getMessageAndStackTraceString(message: String?, throwable: Throwable?): String? {
        return if (message == null && throwable == null)
            null
        else if (message != null && throwable != null)
            message + ":\n" + getStackTraceString(throwable)
        else if (throwable == null)
            message
        else
            getStackTraceString(throwable)
    }

    @JvmStatic
    fun getStackTraceString(throwable: Throwable?): String? {
        if (throwable == null) return null

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
}
