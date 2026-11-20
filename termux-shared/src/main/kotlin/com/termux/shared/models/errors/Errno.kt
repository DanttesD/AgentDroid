package com.termux.shared.models.errors

import android.app.Activity
import com.termux.shared.logger.Logger
import java.util.Arrays
import java.util.Collections

/** The [Class] that defines error messages and codes.*/
open class Errno {

    /** The errno type.*/
    @JvmField protected var type: String
    /** The errno code.*/
    @JvmField protected val code: Int
    /** The errno message.*/
    @JvmField protected val message: String

    constructor(type: String, code: Int, message: String) {
        this.type = type
        this.code = code
        this.message = message
        map.put(type + ":" + code, this)
    }

    override fun toString(): String {
        return "type=" + type + ", code=" + code + ", message=\"" + message + "\""
    }

    open fun getType(): String {
        return type
    }

    open fun getMessage(): String {
        return message
    }

    open fun getCode(): Int {
        return code
    }

    open fun getError(): Error {
        return Error(getType(), getCode(), getMessage())
    }

    open fun getError(vararg args: Any?): Error {
        try {
            return Error(getType(), getCode(), java.lang.String.format(getMessage(), *args))
        } catch (e: Exception) {
            Logger.logWarn(LOG_TAG, "Exception raised while calling String.format() for error message of errno " + this + " with args" + Arrays.toString(args) + "\n" + e.message)
            // Return unformatted message as a backup
            return Error(getType(), getCode(), getMessage() + ": " + Arrays.toString(args))
        }
    }

    open fun getError(throwable: Throwable?, vararg args: Any?): Error {
        if (throwable == null) {
            return getError(*args)
        } else {
            return getError(Collections.singletonList(throwable), *args)
        }
    }

    open fun getError(throwablesList: MutableList<Throwable>?, vararg args: Any?): Error {
        try {
            if (throwablesList == null) {
                return Error(getType(), getCode(), java.lang.String.format(getMessage(), *args))
            } else {
                return Error(getType(), getCode(), java.lang.String.format(getMessage(), *args), throwablesList)
            }
        } catch (e: Exception) {
            Logger.logWarn(LOG_TAG, "Exception raised while calling String.format() for error message of errno " + this + " with args" + Arrays.toString(args) + "\n" + e.message)
            // Return unformatted message as a backup
            return Error(getType(), getCode(), getMessage() + ": " + Arrays.toString(args), throwablesList)
        }
    }

    companion object {

        private val map: HashMap<String, Errno> = HashMap<String, Errno>()

        const val TYPE: String = "Error"

        @JvmField val ERRNO_SUCCESS: Errno = Errno(TYPE, Activity.RESULT_OK, "Success")
        @JvmField val ERRNO_CANCELLED: Errno = Errno(TYPE, Activity.RESULT_CANCELED, "Cancelled")
        @JvmField val ERRNO_MINOR_FAILURES: Errno = Errno(TYPE, Activity.RESULT_FIRST_USER, "Minor failure")
        @JvmField val ERRNO_FAILED: Errno = Errno(TYPE, Activity.RESULT_FIRST_USER + 1, "Failed")

        private const val LOG_TAG: String = "Errno"

        /**
         * Get the [Errno] of a specific type and code.
         *
         * @param type The unique type of the [Errno].
         * @param code The unique code of the [Errno].
         **/
        @JvmStatic
        fun valueOf(type: String?, code: Int?): Errno? {
            if (type == null || type.isEmpty() || code == null) {
                return null
            }
            return map.get(type + ":" + code)
        }
    }
}
