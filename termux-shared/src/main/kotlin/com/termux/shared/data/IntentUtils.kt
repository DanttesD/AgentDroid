package com.termux.shared.data

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import java.util.Arrays

object IntentUtils {

    private const val LOG_TAG: String = "IntentUtils"

    /**
     * Get a [String] extra from an [Intent] if its not `null` or empty.
     *
     * @param intent The [Intent] to get the extra from.
     * @param key The [String] key name.
     * @param def The default value if extra is not set.
     * @param throwExceptionIfNotSet If set to `true`, then an exception will be thrown if extra
     *                               is not set.
     * @return Returns the [String] extra if set, otherwise `null`.
     **/
    @Throws(Exception::class)
    @JvmStatic
    fun getStringExtraIfSet(intent: Intent, key: String, def: String, throwExceptionIfNotSet: Boolean): String? {
        val value: String? = getStringExtraIfSet(intent, key, def)
        if (value == null && throwExceptionIfNotSet) {
            throw Exception("The \"" + key + "\" key string value is null or empty")
        }
        return value
    }

    /**
     * Get a [String] extra from an [Intent] if its not `null` or empty.
     *
     * @param intent The [Intent] to get the extra from.
     * @param key The [String] key name.
     * @param def The default value if extra is not set.
     * @return Returns the [String] extra if set, otherwise `null`.
     **/
    @JvmStatic
    fun getStringExtraIfSet(intent: Intent, key: String, def: String?): String? {
        val value: String? = intent.getStringExtra(key)
        if (value == null || value.isEmpty()) {
            if (def != null && !def.isEmpty()) {
                return def
            } else {
                return null
            }
        }
        return value
    }

    /**
     * Get an [Integer] from an [Intent] stored as a [String] extra if its not
     * `null` or empty.
     *
     * @param intent The [Intent] to get the extra from.
     * @param key The [String] key name.
     * @param def The default value if extra is not set.
     * @return Returns the [Integer] extra if set, otherwise `null`.
     **/
    @JvmStatic
    fun getIntegerExtraIfSet(intent: Intent, key: String, def: Int?): Int? {
        try {
            val value: String? = intent.getStringExtra(key)
            if (value == null || value.isEmpty()) {
                return def
            }

            return Integer.parseInt(value)
        } catch (e: Exception) {
            return def
        }
    }

    /**
     * Get a [String[]] extra from an [Intent] if its not `null` or empty.
     *
     * @param intent The [Intent] to get the extra from.
     * @param key The [String] key name.
     * @param def The default value if extra is not set.
     * @param throwExceptionIfNotSet If set to `true`, then an exception will be thrown if extra
     *                               is not set.
     * @return Returns the [String[]] extra if set, otherwise `null`.
     **/
    @Throws(Exception::class)
    @JvmStatic
    fun getStringArrayExtraIfSet(intent: Intent, key: String, def: Array<String>, throwExceptionIfNotSet: Boolean): Array<String>? {
        val value: Array<String>? = getStringArrayExtraIfSet(intent, key, def)
        if (value == null && throwExceptionIfNotSet) {
            throw Exception("The \"" + key + "\" key string array is null or empty")
        }
        return value
    }

    /**
     * Get a [String[]] extra from an [Intent] if its not `null` or empty.
     *
     * @param intent The [Intent] to get the extra from.
     * @param key The [String] key name.
     * @param def The default value if extra is not set.
     * @return Returns the [String[]] extra if set, otherwise `null`.
     **/
    @JvmStatic
    fun getStringArrayExtraIfSet(intent: Intent, key: String, def: Array<String>?): Array<String>? {
        val value: Array<String>? = intent.getStringArrayExtra(key)
        if (value == null || value.size == 0) {
            if (def != null && def.size != 0) {
                return def
            } else {
                return null
            }
        }
        return value
    }

    @JvmStatic
    fun getIntentString(intent: Intent?): String? {
        if (intent == null) {
            return null
        }

        return intent.toString() + "\n" + getBundleString(intent.getExtras())
    }

    @JvmStatic
    fun getBundleString(bundle: Bundle?): String {
        if (bundle == null || bundle.size() == 0) {
            return "Bundle[]"
        }

        val bundleString = StringBuilder("Bundle[\n")
        var first = true
        for (key in bundle.keySet()) {
            if (!first) {
                bundleString.append("\n")
            }

            bundleString.append(key).append(": `")

            val value: Any? = bundle.get(key)
            if (value is IntArray) {
                bundleString.append(Arrays.toString(value as IntArray))
            } else if (value is ByteArray) {
                bundleString.append(Arrays.toString(value as ByteArray))
            } else if (value is BooleanArray) {
                bundleString.append(Arrays.toString(value as BooleanArray))
            } else if (value is ShortArray) {
                bundleString.append(Arrays.toString(value as ShortArray))
            } else if (value is LongArray) {
                bundleString.append(Arrays.toString(value as LongArray))
            } else if (value is FloatArray) {
                bundleString.append(Arrays.toString(value as FloatArray))
            } else if (value is DoubleArray) {
                bundleString.append(Arrays.toString(value as DoubleArray))
            } else if (Array<String>::class.java.isInstance(value)) {
                bundleString.append(Arrays.toString(value as Array<String>))
            } else if (Array<CharSequence>::class.java.isInstance(value)) {
                bundleString.append(Arrays.toString(value as Array<CharSequence>))
            } else if (Array<Parcelable>::class.java.isInstance(value)) {
                bundleString.append(Arrays.toString(value as Array<Parcelable>))
            } else if (value is Bundle) {
                bundleString.append(getBundleString(value as Bundle))
            } else {
                bundleString.append(value)
            }

            bundleString.append("`")

            first = false
        }

        bundleString.append("\n]")
        return bundleString.toString()
    }
}
