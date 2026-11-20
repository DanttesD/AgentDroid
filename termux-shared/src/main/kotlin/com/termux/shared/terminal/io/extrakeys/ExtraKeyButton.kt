package com.termux.shared.terminal.io.extrakeys

import android.text.TextUtils
import java.util.Arrays
import java.util.function.Function
import java.util.stream.Collectors
import org.json.JSONException
import org.json.JSONObject

open class ExtraKeyButton {

    /**
     * The key that will be sent to the terminal, either a control character, like defined in
     * [ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS] (LEFT, RIGHT, PGUP...) or some text.
     **/
    private val key: String

    /**
     * If the key is a macro, i.e. a sequence of keys separated by space.
     **/
    private val macro: Boolean

    /**
     * The text that will be displayed on the button.
     **/
    private val display: String

    /**
     * The [ExtraKeyButton] containing the information of the popup button (triggered by swipe up).
     **/
    private val popup: ExtraKeyButton?

    /**
     * Initialize a [ExtraKeyButton].
     *
     * @param config The [JSONObject] containing the info to create the [ExtraKeyButton].
     * @param extraKeyDisplayMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by [.KEY_DISPLAY_NAME].
     * @param extraKeyAliasMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           aliases for the actual key names.
     **/
    @Throws(JSONException::class)
    constructor(config: JSONObject, extraKeyDisplayMap: ExtraKeysConstants.ExtraKeyDisplayMap, extraKeyAliasMap: ExtraKeysConstants.ExtraKeyDisplayMap) : this(config, null, extraKeyDisplayMap, extraKeyAliasMap)

    /**
     * Initialize a [ExtraKeyButton].
     *
     * @param config The [JSONObject] containing the info to create the [ExtraKeyButton].
     * @param popup The [ExtraKeyButton] optional [.popup] button.
     * @param extraKeyDisplayMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           display text mapping for the keys if a custom value is not defined
     *                           by [.KEY_DISPLAY_NAME].
     * @param extraKeyAliasMap The [ExtraKeysConstants.ExtraKeyDisplayMap] that defines the
     *                           aliases for the actual key names.
     **/
    @Throws(JSONException::class)
    constructor(config: JSONObject, popup: ExtraKeyButton?, extraKeyDisplayMap: ExtraKeysConstants.ExtraKeyDisplayMap, extraKeyAliasMap: ExtraKeysConstants.ExtraKeyDisplayMap) {
        val keyFromConfig: String? = getStringFromJson(config, KEY_KEY_NAME)
        val macroFromConfig: String? = getStringFromJson(config, KEY_MACRO)
        var keys: Array<String?>
        if (keyFromConfig != null && macroFromConfig != null) {
            throw JSONException("Both key and macro can't be set for the same key. key: \"" + keyFromConfig + "\", macro: \"" + macroFromConfig + "\"")
        } else if (keyFromConfig != null) {
            keys = arrayOf<String?>(keyFromConfig)
            this.macro = false
        } else if (macroFromConfig != null) {
            keys = (macroFromConfig as java.lang.String).split(" ")
            this.macro = true
        } else {
            throw JSONException("All keys have to specify either key or macro")
        }

        for (i in 0 until (keys.size)) {
            keys[i] = replaceAlias(extraKeyAliasMap, keys[i])
        }

        this.key = TextUtils.join(" ", keys)

        val displayFromConfig: String? = getStringFromJson(config, KEY_DISPLAY_NAME)
        if (displayFromConfig != null) {
            this.display = displayFromConfig
        } else {
            this.display = Arrays.stream(keys).map(Function<String?, String?> { key -> extraKeyDisplayMap.get(key, key) }).collect(Collectors.joining(" "))
        }

        this.popup = popup
    }

    open fun getStringFromJson(config: JSONObject, key: String): String? {
        try {
            return config.getString(key)
        } catch (e: JSONException) {
            return null
        }
    }

    /** Get [.key].*/
    open fun getKey(): String {
        return key
    }

    /** Check whether a [.macro] is defined or not.*/
    open fun isMacro(): Boolean {
        return macro
    }

    /** Get [.display].*/
    open fun getDisplay(): String {
        return display
    }

    /** Get [.popup].*/
    open fun getPopup(): ExtraKeyButton? {
        return popup
    }

    companion object {

        /** The key name for the name of the extra key if using a dict to define the extra key. {key: name, ...}*/
        const val KEY_KEY_NAME: String = "key"

        /** The key name for the macro value of the extra key if using a dict to define the extra key. {macro: value, ...}*/
        const val KEY_MACRO: String = "macro"

        /** The key name for the alternate display name of the extra key if using a dict to define the extra key. {display: name, ...}*/
        const val KEY_DISPLAY_NAME: String = "display"

        /** The key name for the nested dict to define popup extra key info if using a dict to define the extra key. {popup: {key: name, ...}, ...}*/
        const val KEY_POPUP: String = "popup"

        /**
         * Replace the alias with its actual key name if found in extraKeyAliasMap.
         **/
        @JvmStatic
        fun replaceAlias(extraKeyAliasMap: ExtraKeysConstants.ExtraKeyDisplayMap, key: String?): String? {
            return extraKeyAliasMap.get(key, key)
        }
    }
}
