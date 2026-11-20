package com.termux.shared.models

import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.errors.Errno
import com.termux.shared.models.errors.Error
import java.io.Serializable
import java.util.Collections

open class ResultData : Serializable {

    /** The stdout of command.*/
    @JvmField val stdout: StringBuilder = StringBuilder()
    /** The stderr of command.*/
    @JvmField val stderr: StringBuilder = StringBuilder()
    /** The exit code of command.*/
    @JvmField var exitCode: Int? = null

    /** The internal errors list of command.*/
    @JvmField var errorsList: MutableList<Error>? = ArrayList<Error>()

    constructor()

    open fun clearStdout() {
        stdout.setLength(0)
    }

    open fun prependStdout(message: String): StringBuilder {
        return stdout.insert(0, message)
    }

    open fun prependStdoutLn(message: String): StringBuilder {
        return stdout.insert(0, message + "\n")
    }

    open fun appendStdout(message: String): StringBuilder {
        return stdout.append(message)
    }

    open fun appendStdoutLn(message: String): StringBuilder {
        return stdout.append(message).append("\n")
    }

    open fun clearStderr() {
        stderr.setLength(0)
    }

    open fun prependStderr(message: String): StringBuilder {
        return stderr.insert(0, message)
    }

    open fun prependStderrLn(message: String): StringBuilder {
        return stderr.insert(0, message + "\n")
    }

    open fun appendStderr(message: String): StringBuilder {
        return stderr.append(message)
    }

    open fun appendStderrLn(message: String): StringBuilder {
        return stderr.append(message).append("\n")
    }

    @Synchronized
    open fun setStateFailed(error: Error): Boolean {
        return setStateFailed(error.getType(), error.getCode()!!, error.getMessage(), null)
    }

    @Synchronized
    open fun setStateFailed(error: Error, throwable: Throwable): Boolean {
        return setStateFailed(error.getType(), error.getCode()!!, error.getMessage(), Collections.singletonList(throwable))
    }

    @Synchronized
    open fun setStateFailed(error: Error, throwablesList: MutableList<Throwable>): Boolean {
        return setStateFailed(error.getType(), error.getCode()!!, error.getMessage(), throwablesList)
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String): Boolean {
        return setStateFailed(null, code, message, null)
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String, throwable: Throwable): Boolean {
        return setStateFailed(null, code, message, Collections.singletonList(throwable))
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String, throwablesList: MutableList<Throwable>): Boolean {
        return setStateFailed(null, code, message, throwablesList)
    }

    @Synchronized
    open fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: MutableList<Throwable>?): Boolean {
        if (errorsList == null) {
            errorsList = ArrayList<Error>()
        }

        val error = Error()
        errorsList!!.add(error)

        return error.setStateFailed(type, code, message, throwablesList)
    }

    open fun isStateFailed(): Boolean {
        if (errorsList != null) {
            for (error in errorsList!!) {
                if (error.isStateFailed()) {
                    return true
                }
            }
        }

        return false
    }

    open fun getErrCode(): Int {
        if (errorsList != null && errorsList!!.size > 0) {
            return errorsList!!.get(errorsList!!.size - 1).getCode()!!
        } else {
            return Errno.ERRNO_SUCCESS.getCode()
        }
    }

    override fun toString(): String {
        return getResultDataLogString(this, true)
    }

    open fun getStdoutLogString(): String {
        if (stdout.toString().isEmpty()) {
            return Logger.getSingleLineLogStringEntry("Stdout", null, "-")
        } else {
            return Logger.getMultiLineLogStringEntry("Stdout", DataUtils.getTruncatedCommandOutput(stdout.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5, false, false, true), "-")
        }
    }

    open fun getStderrLogString(): String {
        if (stderr.toString().isEmpty()) {
            return Logger.getSingleLineLogStringEntry("Stderr", null, "-")
        } else {
            return Logger.getMultiLineLogStringEntry("Stderr", DataUtils.getTruncatedCommandOutput(stderr.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5, false, false, true), "-")
        }
    }

    open fun getExitCodeLogString(): String {
        return Logger.getSingleLineLogStringEntry("Exit Code", exitCode, "-")
    }

    companion object {

        /**
         * Get a log friendly [String] for [ResultData] parameters.
         *
         * @param resultData The [ResultData] to convert.
         * @param logStdoutAndStderr Set to `true` if [.stdout] and [.stderr] should be logged.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getResultDataLogString(resultData: ResultData?, logStdoutAndStderr: Boolean): String {
            if (resultData == null) {
                return "null"
            }

            val logString = StringBuilder()

            if (logStdoutAndStderr) {
                logString.append("\n").append(resultData.getStdoutLogString())
                logString.append("\n").append(resultData.getStderrLogString())
            }
            logString.append("\n").append(resultData.getExitCodeLogString())

            logString.append("\n\n").append(getErrorsListLogString(resultData))

            return logString.toString()
        }

        @JvmStatic
        fun getErrorsListLogString(resultData: ResultData?): String {
            if (resultData == null) {
                return "null"
            }

            val logString = StringBuilder()

            if (resultData.errorsList != null) {
                for (error in resultData.errorsList!!) {
                    if (error.isStateFailed()) {
                        if (!logString.toString().isEmpty()) {
                            logString.append("\n")
                        }
                        logString.append(Error.getErrorLogString(error))
                    }
                }
            }

            return logString.toString()
        }

        /**
         * Get a markdown [String] for [ResultData].
         *
         * @param resultData The [ResultData] to convert.
         * @return Returns the markdown [String].
         **/
        @JvmStatic
        fun getResultDataMarkdownString(resultData: ResultData?): String {
            if (resultData == null) {
                return "null"
            }

            val markdownString = StringBuilder()

            if (resultData.stdout.toString().isEmpty()) {
                markdownString.append(MarkdownUtils.getSingleLineMarkdownStringEntry("Stdout", null, "-"))
            } else {
                markdownString.append(MarkdownUtils.getMultiLineMarkdownStringEntry("Stdout", resultData.stdout.toString(), "-"))
            }

            if (resultData.stderr.toString().isEmpty()) {
                markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Stderr", null, "-"))
            } else {
                markdownString.append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Stderr", resultData.stderr.toString(), "-"))
            }

            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Exit Code", resultData.exitCode, "-"))

            markdownString.append("\n\n").append(getErrorsListMarkdownString(resultData))

            return markdownString.toString()
        }

        @JvmStatic
        fun getErrorsListMarkdownString(resultData: ResultData?): String {
            if (resultData == null) {
                return "null"
            }

            val markdownString = StringBuilder()

            if (resultData.errorsList != null) {
                for (error in resultData.errorsList!!) {
                    if (error.isStateFailed()) {
                        if (!markdownString.toString().isEmpty()) {
                            markdownString.append("\n")
                        }
                        markdownString.append(Error.getErrorMarkdownString(error))
                    }
                }
            }

            return markdownString.toString()
        }

        @JvmStatic
        fun getErrorsListMinimalString(resultData: ResultData?): String {
            if (resultData == null) {
                return "null"
            }

            val minimalString = StringBuilder()

            if (resultData.errorsList != null) {
                for (error in resultData.errorsList!!) {
                    if (error.isStateFailed()) {
                        if (!minimalString.toString().isEmpty()) {
                            minimalString.append("\n")
                        }
                        minimalString.append(Error.getMinimalErrorString(error))
                    }
                }
            }

            return minimalString.toString()
        }
    }
}
