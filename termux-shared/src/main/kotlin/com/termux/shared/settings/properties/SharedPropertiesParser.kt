package com.termux.shared.settings.properties

import android.content.Context

/**
 * An interface that must be defined by the caller of the [SharedProperties] class.
 **/
fun interface SharedPropertiesParser {

    /**
     * A function that should return the internal [Object] to be stored for a key/value pair
     * read from properties file in the [HashMap] in-memory cache.
     *
     * @param context The context for operations.
     * @param key The key for which the internal object is required.
     * @param value The literal value for the property found is the properties file.
     * @return Returns the [Object] object to store in the [HashMap] in-memory cache.
     **/
    fun getInternalPropertyValueFromValue(context: Context, key: String, value: String?): Any?
}
