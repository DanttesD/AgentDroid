package com.termux.shared.data

import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable

object DataUtils {

    /** Max safe limit of data size to prevent TransactionTooLargeException when transferring data
     * inside or to other apps via transactions.*/
    const val TRANSACTION_SIZE_LIMIT_IN_BYTES: Int = 100 * 1024 // 100KB

    private val HEX_ARRAY: CharArray = "0123456789ABCDEF".toCharArray()

    @JvmStatic
    fun getTruncatedCommandOutput(text: String?, maxLength: Int, fromEnd: Boolean, onNewline: Boolean, addPrefix: Boolean): String? {
        var text = text
        var maxLength = maxLength
        if (text == null) {
            return null
        }

        val prefix = "(truncated) "

        if (addPrefix) {
            maxLength = maxLength - prefix.length
        }

        if (maxLength < 0 || text.length < maxLength) {
            return text
        }

        if (fromEnd) {
            text = text.substring(0, maxLength)
        } else {
            var cutOffIndex: Int = text!!.length - maxLength

            if (onNewline) {
                val nextNewlineIndex: Int = text!!.indexOf('\n', cutOffIndex)
                if (nextNewlineIndex != -1 && nextNewlineIndex != text!!.length - 1) {
                    cutOffIndex = nextNewlineIndex + 1
                }
            }
            text = text!!.substring(cutOffIndex)
        }

        if (addPrefix) {
            text = prefix + text
        }

        return text
    }

    /**
     * Replace a sub string in each item of a [String[]].
     *
     * @param array The [String[]] to replace in.
     * @param find The sub string to replace.
     * @param replace The sub string to replace with.
     **/
    @JvmStatic
    fun replaceSubStringsInStringArrayItems(array: Array<String>?, find: String, replace: String) {
        if (array == null || array.size == 0) {
            return
        }

        for (i in 0 until (array.size)) {
            array[i] = (array[i] as java.lang.String).replace(find, replace)
        }
    }

    /**
     * Get the `float` from a [String].
     *
     * @param value The [String] value.
     * @param def The default value if failed to read a valid value.
     * @return Returns the `float` value after parsing the [String] value, otherwise
     * returns default if failed to read a valid value, like in case of an exception.
     **/
    @JvmStatic
    fun getFloatFromString(value: String?, def: Float): Float {
        if (value == null) {
            return def
        }

        try {
            return java.lang.Float.parseFloat(value)
        } catch (e: Exception) {
            return def
        }
    }

    /**
     * Get the `int` from a [String].
     *
     * @param value The [String] value.
     * @param def The default value if failed to read a valid value.
     * @return Returns the `int` value after parsing the [String] value, otherwise
     * returns default if failed to read a valid value, like in case of an exception.
     **/
    @JvmStatic
    fun getIntFromString(value: String?, def: Int): Int {
        if (value == null) {
            return def
        }

        try {
            return Integer.parseInt(value)
        } catch (e: Exception) {
            return def
        }
    }

    /**
     * Get the `String` from an [Integer].
     *
     * @param value The [Integer] value.
     * @param def The default [String] value.
     * @return Returns `value` if it is not `null`, otherwise returns `def`.
     **/
    @JvmStatic
    fun getStringFromInteger(value: Int?, def: String): String {
        return if (value == null) def else java.lang.String.valueOf(value)
    }

    /**
     * Get the `hex string` from a [byte[]].
     *
     * @param bytes The [byte[]] value.
     * @return Returns the `hex string` value.
     **/
    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in 0 until (bytes.size)) {
            val v: Int = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = HEX_ARRAY[v ushr 4]
            hexChars[j * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Get an `int` from [Bundle] that is stored as a [String].
     *
     * @param bundle The [Bundle] to get the value from.
     * @param key The key for the value.
     * @param def The default value if failed to read a valid value.
     * @return Returns the `int` value after parsing the [String] value stored in
     * [Bundle], otherwise returns default if failed to read a valid value,
     * like in case of an exception.
     **/
    @JvmStatic
    fun getIntStoredAsStringFromBundle(bundle: Bundle?, key: String, def: Int): Int {
        if (bundle == null) {
            return def
        }
        return getIntFromString(bundle.getString(key, Integer.toString(def)), def)
    }

    /**
     * If value is not in the range [min, max], set it to either min or max.
     **/
    @JvmStatic
    fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.min(Math.max(value, min), max)
    }

    /**
     * If value is not in the range [min, max], set it to default.
     **/
    @JvmStatic
    fun rangedOrDefault(value: Float, def: Float, min: Float, max: Float): Float {
        if (value < min || value > max) {
            return def
        } else {
            return value
        }
    }

    /**
     * Get the object itself if it is not `null`, otherwise default.
     *
     * @param object The [Object] to check.
     * @param def The default [Object].
     * @return Returns `object` if it is not `null`, otherwise returns `def`.
     **/
    @JvmStatic
    fun <T> getDefaultIfNull(`object`: T?, def: T?): T? {
        return if (`object` == null) def else `object`
    }

    /**
     * Get the [String] itself if it is not `null` or empty, otherwise default.
     *
     * @param value The [String] to check.
     * @param def The default [String].
     * @return Returns `value` if it is not `null` or empty, otherwise returns `def`.
     **/
    @JvmStatic
    fun getDefaultIfUnset(value: String?, def: String): String {
        return if (value == null || value.isEmpty()) def else value
    }

    /** Check if a string is null or empty.*/
    @JvmStatic
    fun isNullOrEmpty(string: String?): Boolean {
        return string == null || string.isEmpty()
    }

    /** Get size of a serializable object.*/
    @JvmStatic
    fun getSerializedSize(`object`: Serializable?): Long {
        if (`object` == null) {
            return 0L
        }
        try {
            val byteOutputStream = ByteArrayOutputStream()
            val objectOutputStream = ObjectOutputStream(byteOutputStream)
            objectOutputStream.writeObject(`object`)
            objectOutputStream.flush()
            objectOutputStream.close()
            return byteOutputStream.toByteArray().size.toLong()
        } catch (e: Exception) {
            return (-1).toLong()
        }
    }
}
