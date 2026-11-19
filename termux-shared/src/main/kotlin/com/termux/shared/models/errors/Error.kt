package com.termux.shared.models.errors

import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import java.io.Serializable
import java.util.Collections

open class Error : Serializable {

    /** The optional error label.*/
    private var label: String? = null
    /** The error type.*/
    private var type: String? = null
    /** The error code.*/
    private var code: Int = 0
    /** The error message.*/
    private var message: String? = null
    /** The error exceptions.*/
    private var throwablesList: MutableList<Throwable>? = ArrayList<Throwable>()

    constructor() {
        InitError(null, null, null, null)
    }

    constructor(type: String, code: Int?, message: String, throwablesList: MutableList<Throwable>?) {
        InitError(type, code, message, throwablesList)
    }

    constructor(type: String, code: Int?, message: String, throwable: Throwable) {
        InitError(type, code, message, Collections.singletonList(throwable))
    }

    constructor(type: String, code: Int?, message: String) {
        InitError(type, code, message, null)
    }

    constructor(code: Int?, message: String, throwablesList: MutableList<Throwable>) {
        InitError(null, code, message, throwablesList)
    }

    constructor(code: Int?, message: String, throwable: Throwable) {
        InitError(null, code, message, Collections.singletonList(throwable))
    }

    constructor(code: Int?, message: String) {
        InitError(null, code, message, null)
    }

    constructor(message: String, throwable: Throwable) {
        InitError(null, null, message, Collections.singletonList(throwable))
    }

    constructor(message: String, throwablesList: MutableList<Throwable>) {
        InitError(null, null, message, throwablesList)
    }

    constructor(message: String) {
        InitError(null, null, message, null)
    }

    private fun InitError(type: String?, code: Int?, message: String?, throwablesList: MutableList<Throwable>?) {
        if (type != null && !type.isEmpty()) {
            this.type = type
        } else {
            this.type = Errno.TYPE
        }

        if (code != null && code > Errno.ERRNO_SUCCESS.getCode()) {
            this.code = code
        } else {
            this.code = Errno.ERRNO_SUCCESS.getCode()
        }

        this.message = message

        if (throwablesList != null) {
            this.throwablesList = throwablesList
        }
    }

    open fun setLabel(label: String?): Error {
        this.label = label
        return this
    }

    open fun getLabel(): String? {
        return label
    }

    open fun getType(): String? {
        return type
    }

    open fun getCode(): Int? {
        return code
    }

    open fun getMessage(): String? {
        return message
    }

    open fun prependMessage(message: String?) {
        if (message != null && isStateFailed()) {
            this.message = message + this.message
        }
    }

    open fun appendMessage(message: String?) {
        if (message != null && isStateFailed()) {
            this.message = this.message + message
        }
    }

    open fun getThrowablesList(): MutableList<Throwable> {
        return Collections.unmodifiableList(throwablesList)
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
        return setStateFailed(this.type, code, message, null)
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String, throwable: Throwable): Boolean {
        return setStateFailed(this.type, code, message, Collections.singletonList(throwable))
    }

    @Synchronized
    open fun setStateFailed(code: Int, message: String, throwablesList: MutableList<Throwable>): Boolean {
        return setStateFailed(this.type, code, message, throwablesList)
    }

    @Synchronized
    open fun setStateFailed(type: String?, code: Int, message: String?, throwablesList: MutableList<Throwable>?): Boolean {
        this.message = message
        this.throwablesList = throwablesList

        if (type != null && !type.isEmpty()) {
            this.type = type
        }

        if (code > Errno.ERRNO_SUCCESS.getCode()) {
            this.code = code
            return true
        } else {
            Logger.logWarn(LOG_TAG, "Ignoring invalid error code value \"" + code + "\". Force setting it to RESULT_CODE_FAILED \"" + Errno.ERRNO_FAILED.getCode() + "\"")
            this.code = Errno.ERRNO_FAILED.getCode()
            return false
        }
    }

    open fun isStateFailed(): Boolean {
        return code > Errno.ERRNO_SUCCESS.getCode()
    }

    override fun toString(): String {
        return getErrorLogString(this)
    }

    open fun getCodeString(): String {
        return Logger.getSingleLineLogStringEntry("Error Code", code, "-")
    }

    open fun getTypeAndMessageLogString(): String {
        return Logger.getMultiLineLogStringEntry(if (Errno.TYPE.equals(type)) "Error Message" else "Error Message (" + type + ")", message, "-")
    }

    open fun geStackTracesLogString(): String {
        return Logger.getStackTracesString("StackTraces:", Logger.getStackTracesStringArray(throwablesList))
    }

    open fun geStackTracesMarkdownString(): String {
        return Logger.getStackTracesMarkdownString("StackTraces", Logger.getStackTracesStringArray(throwablesList))
    }

    companion object {

        private const val LOG_TAG: String = "Error"

        /**
         * Get a log friendly [String] for [Error] error parameters.
         *
         * @param error The [Error] to convert.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getErrorLogString(error: Error?): String {
            if (error == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append(error.getCodeString())
            logString.append("\n").append(error.getTypeAndMessageLogString())
            if (error.throwablesList != null) {
                logString.append("\n").append(error.geStackTracesLogString())
            }

            return logString.toString()
        }

        /**
         * Get a minimal log friendly [String] for [Error] error parameters.
         *
         * @param error The [Error] to convert.
         * @return Returns the log friendly [String].
         **/
        @JvmStatic
        fun getMinimalErrorLogString(error: Error?): String {
            if (error == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append(error.getCodeString())
            logString.append(error.getTypeAndMessageLogString())

            return logString.toString()
        }

        /**
         * Get a minimal [String] for [Error] error parameters.
         *
         * @param error The [Error] to convert.
         * @return Returns the [String].
         **/
        @JvmStatic
        fun getMinimalErrorString(error: Error?): String {
            if (error == null) {
                return "null"
            }

            val logString = StringBuilder()

            logString.append("(").append(error.getCode()).append(") ")
            logString.append(error.getType()).append(": ").append(error.getMessage())

            return logString.toString()
        }

        /**
         * Get a markdown [String] for [Error].
         *
         * @param error The [Error] to convert.
         * @return Returns the markdown [String].
         **/
        @JvmStatic
        fun getErrorMarkdownString(error: Error?): String {
            if (error == null) {
                return "null"
            }

            val markdownString = StringBuilder()

            markdownString.append(MarkdownUtils.getSingleLineMarkdownStringEntry("Error Code", error.getCode(), "-"))
            markdownString.append("\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry(if (Errno.TYPE.equals(error.getType())) "Error Message" else "Error Message (" + error.getType() + ")", error.message, "-"))
            markdownString.append("\n\n").append(error.geStackTracesMarkdownString())

            return markdownString.toString()
        }
    }
}
