package com.termux.shared.settings.properties

import android.content.Context
import android.content.res.Configuration
import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import java.io.File
import java.util.Locale
import java.util.Properties

abstract class TermuxSharedProperties {

    @JvmField protected val mContext: Context
    @JvmField protected val mLabel: String
    @JvmField protected val mPropertiesFile: File
    @JvmField protected val mSharedProperties: SharedProperties

    constructor(context: Context, label: String, propertiesFile: File, propertiesList: MutableSet<String>, sharedPropertiesParser: SharedPropertiesParser) {
        mContext = context
        mLabel = label
        mPropertiesFile = propertiesFile
        mSharedProperties = SharedProperties(context, mPropertiesFile, propertiesList, sharedPropertiesParser)
        loadTermuxPropertiesFromDisk()
    }

    /**
     * Reload the termux properties from disk into an in-memory cache.
     **/
    open fun loadTermuxPropertiesFromDisk() {
        mSharedProperties.loadPropertiesFromDisk()
        dumpPropertiesToLog()
        dumpInternalPropertiesToLog()
    }

    /**
     * Get the [Properties] from the [.mPropertiesFile] file.
     *
     * @param cached If `true`, then the [Properties] in-memory cache is returned.
     *               Otherwise the [Properties] object is read directly from the
     *               [.mPropertiesFile] file.
     * @return Returns the [Properties] object. It will be `null` if an exception is
     * raised while reading the file.
     **/
    open fun getProperties(cached: Boolean): Properties? {
        return mSharedProperties.getProperties(cached)
    }

    /**
     * Get the [String] value for the key passed from the [.mPropertiesFile] file.
     *
     * @param key The key to read.
     * @param def The default value.
     * @param cached If `true`, then the value is returned from the the [Properties] in-memory cache.
     *               Otherwise the [Properties] object is read directly from the file
     *               and value is returned from it against the key.
     * @return Returns the [String] object. This will be `null` if key is not found.
     **/
    open fun getPropertyValue(key: String, def: String?, cached: Boolean): String? {
        return SharedProperties.getDefaultIfNull(mSharedProperties.getProperty(key, cached), def)
    }

    /**
     * A function to check if the value is `true` for [Properties] key read from
     * the [.mPropertiesFile] file.
     *
     * @param key The key to read.
     * @param cached If `true`, then the value is checked from the the [Properties] in-memory cache.
     *               Otherwise the [Properties] object is read directly from the file
     *               and value is checked from it.
     * @param logErrorOnInvalidValue If `true`, then an error will be logged if key value
     *                               was found in [Properties] but was invalid.
     * @return Returns the `true` if the [Properties] key [String] value equals "true",
     * regardless of case. If the key does not exist in the file or does not equal "true", then
     * `false` will be returned.
     **/
    open fun isPropertyValueTrue(key: String, cached: Boolean, logErrorOnInvalidValue: Boolean): Boolean {
        return SharedProperties.getBooleanValueForStringValue(key, getPropertyValue(key, null, cached), false, logErrorOnInvalidValue, LOG_TAG)
    }

    /**
     * A function to check if the value is `false` for [Properties] key read from
     * the [.mPropertiesFile] file.
     *
     * @param key The key to read.
     * @param cached If `true`, then the value is checked from the the [Properties] in-memory cache.
     *               Otherwise the [Properties] object is read directly from the file
     *               and value is checked from it.
     * @param logErrorOnInvalidValue If `true`, then an error will be logged if key value
     *                               was found in [Properties] but was invalid.
     * @return Returns `true` if the [Properties] key [String] value equals "false",
     * regardless of case. If the key does not exist in the file or does not equal "false", then
     * `true` will be returned.
     **/
    open fun isPropertyValueFalse(key: String, cached: Boolean, logErrorOnInvalidValue: Boolean): Boolean {
        return SharedProperties.getInvertedBooleanValueForStringValue(key, getPropertyValue(key, null, cached), true, logErrorOnInvalidValue, LOG_TAG)
    }

    /**
     * Get the internal value [Object] [HashMap] in-memory cache for the
     * [.mPropertiesFile] file. A call to [.loadTermuxPropertiesFromDisk] must be made
     * before this.
     *
     * @return Returns a copy of [Map] object.
     **/
    open fun getInternalProperties(): MutableMap<String, Any?>? {
        return mSharedProperties.getInternalProperties()
    }

    /**
     * Get the internal [Object] value for the key passed from the [.mPropertiesFile] file.
     * If cache is `true`, then value is returned from the [HashMap] in-memory cache,
     * so a call to [.loadTermuxPropertiesFromDisk] must be made before this.
     *
     * @param key The key to read from the [HashMap<>] in-memory cache.
     * @param cached If `true`, then the value is returned from the the [HashMap] in-memory cache,
     *               but if the value is null, then an attempt is made to return the default value.
     *               If `false`, then the [Properties] object is read directly from the file
     *               and internal value is returned for the property value against the key.
     * @return Returns the [Object] object. This will be `null` if key is not found or
     * the object stored against the key is `null`.
     **/
    open fun getInternalPropertyValue(key: String, cached: Boolean): Any? {
        var value: Any?
        if (cached) {
            value = mSharedProperties.getInternalProperty(key)
            // If the value is not null since key was found or if the value was null since the
            // object stored for the key was itself null, we detect the later by checking if the key
            // exists in the map.
            if (value != null || mSharedProperties.getInternalProperties()!!.containsKey(key)) {
                return value
            } else {
                // This should not happen normally unless mMap was modified after the
                // {@link #loadTermuxPropertiesFromDisk()} call
                // A null value can still be returned by
                // {@link #getInternalPropertyValueFromValue(Context,String,String)} for some keys
                value = getInternalTermuxPropertyValueFromValue(mContext, key, null)
                Logger.logWarn(LOG_TAG, "The value for \"" + key + "\" not found in SharedProperties cache, force returning default value: `" + value + "`")
                return value
            }
        } else {
            // We get the property value directly from file and return its internal value
            return getInternalTermuxPropertyValueFromValue(mContext, key, mSharedProperties.getProperty(key, false))
        }
    }

    /**
     * The class that implements the [SharedPropertiesParser] interface.
     **/
    open class SharedPropertiesParserClient : SharedPropertiesParser {
        /**
         * Override the
         * [SharedPropertiesParser.getInternalPropertyValueFromValue]
         * interface function.
         **/
        override fun getInternalPropertyValueFromValue(context: Context, key: String, value: String?): Any? {
            return getInternalTermuxPropertyValueFromValue(context, key, value)
        }
    }

    open fun areHardwareKeyboardShortcutsDisabled(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, true) as Boolean
    }

    open fun areTerminalSessionChangeToastsDisabled(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, true) as Boolean
    }

    open fun isEnforcingCharBasedInput(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, true) as Boolean
    }

    open fun shouldExtraKeysTextBeAllCaps(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS, true) as Boolean
    }

    open fun shouldSoftKeyboardBeHiddenOnStartup(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, true) as Boolean
    }

    open fun shouldOpenTerminalTranscriptURLOnClick(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN, true) as Boolean
    }

    open fun isUsingBlackUI(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_BLACK_UI, true) as Boolean
    }

    open fun isUsingCtrlSpaceWorkaround(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_CTRL_SPACE_WORKAROUND, true) as Boolean
    }

    open fun isUsingFullScreen(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_FULLSCREEN, true) as Boolean
    }

    open fun isUsingFullScreenWorkAround(): Boolean {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_USE_FULLSCREEN_WORKAROUND, true) as Boolean
    }

    open fun getBellBehaviour(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, true) as Int
    }

    open fun getTerminalCursorBlinkRate(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, true) as Int
    }

    open fun getTerminalCursorStyle(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, true) as Int
    }

    open fun getTerminalMarginHorizontal(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL, true) as Int
    }

    open fun getTerminalMarginVertical(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL, true) as Int
    }

    open fun getTerminalTranscriptRows(): Int {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, true) as Int
    }

    open fun getTerminalToolbarHeightScaleFactor(): Float {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, true) as Float
    }

    open fun isBackKeyTheEscapeKey(): Boolean {
        return TermuxPropertyConstants.IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE.equals(getInternalPropertyValue(TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, true))
    }

    open fun getDefaultWorkingDirectory(): String? {
        return getInternalPropertyValue(TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY, true) as String?
    }

    open fun shouldEnableDisableSoftKeyboardOnToggle(): Boolean {
        return TermuxPropertyConstants.IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE.equals(getInternalPropertyValue(TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true))
    }

    open fun areVirtualVolumeKeysDisabled(): Boolean {
        return TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME.equals(getInternalPropertyValue(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, true))
    }

    open fun dumpPropertiesToLog() {
        val properties: Properties? = getProperties(true)
        val propertiesDump = StringBuilder()

        propertiesDump.append(mLabel).append(" Termux Properties:")
        if (properties != null) {
            for (key in properties.stringPropertyNames()) {
                propertiesDump.append("\n").append(key).append(": `").append(properties.get(key)).append("`")
            }
        } else {
            propertiesDump.append(" null")
        }

        Logger.logVerbose(LOG_TAG, propertiesDump.toString())
    }

    open fun dumpInternalPropertiesToLog() {
        val internalProperties: HashMap<String, Any?>? = getInternalProperties() as HashMap<String, Any?>?
        val internalPropertiesDump = StringBuilder()

        internalPropertiesDump.append(mLabel).append(" Internal Properties:")
        if (internalProperties != null) {
            for (key in internalProperties.keys) {
                internalPropertiesDump.append("\n").append(key).append(": `").append(internalProperties.get(key)).append("`")
            }
        }

        Logger.logVerbose(LOG_TAG, internalPropertiesDump.toString())
    }

    companion object {

        const val LOG_TAG: String = "TermuxSharedProperties"

        /**
         * Get the internal [Object] value for the key passed from the file returned by
         * `propertiesFile`. The [Properties] object is
         * read directly from the file and internal value is returned for the property value against the key.
         *
         * @param context The context for operations.
         * @param key The key for which the internal object is required.
         * @return Returns the [Object] object. This will be `null` if key is not found or
         * the object stored against the key is `null`.
         **/
        @JvmStatic
        fun getInternalPropertyValue(context: Context, propertiesFile: File, key: String, sharedPropertiesParser: SharedPropertiesParser): Any? {
            return SharedProperties.getInternalProperty(context, propertiesFile, key, sharedPropertiesParser)
        }

        /**
         * A static function that should return the internal termux [Object] for a key/value pair
         * read from properties file.
         *
         * @param context The context for operations.
         * @param key The key for which the internal object is required.
         * @param value The literal value for the property found is the properties file.
         * @return Returns the internal termux [Object] object.
         **/
        @JvmStatic
        fun getInternalTermuxPropertyValueFromValue(context: Context, key: String?, value: String?): Any? {
            if (key == null) {
                return null
            }
            /*
             For keys where a MAP_* is checked by respective functions. Note that value to this function
             would actually be the key for the MAP_*:
             - If the value is currently null, then searching MAP_* should also return null and internal default value will be used.
             - If the value is not null and does not exist in MAP_*, then internal default value will be used.
             - If the value is not null and does exist in MAP_*, then internal value returned by map will be used.
             **/
            when (key) {
                TermuxPropertyConstants.KEY_USE_BLACK_UI -> {
                    return getUseBlackUIInternalPropertyValueFromValue(context, value)
                }
                TermuxPropertyConstants.KEY_BELL_BEHAVIOUR -> {
                    return getBellBehaviourInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE -> {
                    return getTerminalCursorBlinkRateInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE -> {
                    return getTerminalCursorStyleInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL -> {
                    return getTerminalMarginHorizontalInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL -> {
                    return getTerminalMarginVerticalInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS -> {
                    return getTerminalTranscriptRowsInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR -> {
                    return getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_SHORTCUT_CREATE_SESSION, TermuxPropertyConstants.KEY_SHORTCUT_NEXT_SESSION, TermuxPropertyConstants.KEY_SHORTCUT_PREVIOUS_SESSION, TermuxPropertyConstants.KEY_SHORTCUT_RENAME_SESSION -> {
                    return getCodePointForSessionShortcuts(key, value)
                }
                TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR -> {
                    return getBackKeyBehaviourInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY -> {
                    return getDefaultWorkingDirectoryInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_EXTRA_KEYS -> {
                    return getExtraKeysInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE -> {
                    return getExtraKeysStyleInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR -> {
                    return getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(value)
                }
                TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR -> {
                    return getVolumeKeysBehaviourInternalPropertyValueFromValue(value)
                }
                else -> {
                    // default false boolean behaviour
                    if (TermuxPropertyConstants.TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key)) {
                        return SharedProperties.getBooleanValueForStringValue(key, value, false, true, LOG_TAG)
                    }
                    // default true boolean behaviour
                    if (TermuxPropertyConstants.TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key)) {
                        return SharedProperties.getBooleanValueForStringValue(key, value, true, true, LOG_TAG)
                    } else {
                        return value
                    }
                }
            }
        }

        /**
         * Returns `true` or `false` if value is the literal string "true" or "false" respectively regardless of case.
         * Otherwise returns `true` if the night mode is currently enabled in the system.
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getUseBlackUIInternalPropertyValueFromValue(context: Context, value: String?): Boolean {
            val nightMode: Int = context.getResources().getConfiguration().uiMode and Configuration.UI_MODE_NIGHT_MASK
            return SharedProperties.getBooleanValueForStringValue(TermuxPropertyConstants.KEY_USE_BLACK_UI, value, nightMode == Configuration.UI_MODE_NIGHT_YES, true, LOG_TAG)
        }

        /**
         * Returns the internal value after mapping it based on
         * `TermuxPropertyConstants#MAP_BELL_BEHAVIOUR` if the value is not `null`
         * and is valid, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getBellBehaviourInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, TermuxPropertyConstants.MAP_BELL_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BELL_BEHAVIOUR, true, LOG_TAG) as Int
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getTerminalCursorBlinkRateInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE, TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX, true, true, LOG_TAG)
        }

        /**
         * Returns the internal value after mapping it based on
         * [TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE] if the value is not `null`
         * and is valid, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getTerminalCursorStyleInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_STYLE, TermuxPropertyConstants.MAP_TERMINAL_CURSOR_STYLE, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE, true, LOG_TAG) as Int
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_HORIZONTAL_MARGIN].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getTerminalMarginHorizontalInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL, DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_HORIZONTAL_MARGIN), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_HORIZONTAL_MARGIN, TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX, true, true, LOG_TAG)
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_VERTICAL_MARGIN].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getTerminalMarginVerticalInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_VERTICAL, DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_VERTICAL_MARGIN), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_VERTICAL_MARGIN, TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_MARGIN_VERTICAL_MAX, true, true, LOG_TAG)
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getTerminalTranscriptRowsInternalPropertyValueFromValue(value: String?): Int {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, DataUtils.getIntFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS, TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX, true, true, LOG_TAG)
        }

        /**
         * Returns the int for the value if its not null and is between
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN] and
         * [TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX],
         * otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getTerminalToolbarHeightScaleFactorInternalPropertyValueFromValue(value: String?): Float {
            return SharedProperties.getDefaultIfNotInRange(TermuxPropertyConstants.KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, DataUtils.getFloatFromString(value, TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR), TermuxPropertyConstants.DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN, TermuxPropertyConstants.IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX, true, true, LOG_TAG)
        }

        /**
         * Returns the code point for the value if key is not `null` and value is not `null` and is valid,
         * otherwise returns `null`.
         *
         * @param key The key for session shortcut.
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getCodePointForSessionShortcuts(key: String?, value: String?): Int? {
            if (key == null) {
                return null
            }
            if (value == null) {
                return null
            }
            val parts: Array<String> = (((value as java.lang.String).toLowerCase(Locale.ROOT) as java.lang.String).trim() as java.lang.String).split("\\+")
            val input: String? = if (parts.size == 2) (parts[1] as java.lang.String).trim() else null
            if (!(parts.size == 2 && (parts[0] as java.lang.String).trim().equals("ctrl")) || input!!.isEmpty() || input!!.length > 2) {
                Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>")
                return null
            }

            val c: Char = input!![0]
            var codePoint: Int = c.code
            if (Character.isLowSurrogate(c)) {
                if (input!!.length != 2 || Character.isHighSurrogate(input!![1])) {
                    Logger.logError(LOG_TAG, "Keyboard shortcut '" + key + "' is not Ctrl+<something>")
                    return null
                } else {
                    codePoint = Character.toCodePoint(input!![1], c)
                }
            }

            return codePoint
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR].
         *
         * @param value [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getBackKeyBehaviourInternalPropertyValueFromValue(value: String?): String {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_BACK_KEY_BEHAVIOUR, TermuxPropertyConstants.MAP_BACK_KEY_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR, true, LOG_TAG) as String
        }

        /**
         * Returns the path itself if a directory exists at it and is readable, otherwise returns
         *  [TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY].
         *
         * @param path The [String] path to check.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getDefaultWorkingDirectoryInternalPropertyValueFromValue(path: String?): String {
            if (path == null || path.isEmpty()) {
                return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY
            }
            val workDir = File(path)
            if (!workDir.exists() || !workDir.isDirectory() || !workDir.canRead()) {
                // Fallback to default directory if user configured working directory does not exist,
                // is not a directory or is not readable.
                Logger.logError(LOG_TAG, "The path \"" + path + "\" for the key \"" + TermuxPropertyConstants.KEY_DEFAULT_WORKING_DIRECTORY + "\" does not exist, is not a directory or is not readable. Using default value \"" + TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY + "\" instead.")
                return TermuxPropertyConstants.DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY
            } else {
                return path
            }
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS].
         *
         * @param value The [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getExtraKeysInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNullOrEmpty(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS)
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE].
         *
         * @param value [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getExtraKeysStyleInternalPropertyValueFromValue(value: String?): String? {
            return SharedProperties.getDefaultIfNullOrEmpty(value, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE)
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR].
         *
         * @param value [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getSoftKeyboardToggleBehaviourInternalPropertyValueFromValue(value: String?): String {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, TermuxPropertyConstants.MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, true, LOG_TAG) as String
        }

        /**
         * Returns the value itself if it is not `null`, otherwise returns [TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR].
         *
         * @param value [String] value to convert.
         * @return Returns the internal value for value.
         **/
        @JvmStatic
        fun getVolumeKeysBehaviourInternalPropertyValueFromValue(value: String?): String {
            return SharedProperties.getDefaultIfNotInMap(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, TermuxPropertyConstants.MAP_VOLUME_KEYS_BEHAVIOUR, SharedProperties.toLowerCase(value), TermuxPropertyConstants.DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR, true, LOG_TAG) as String
        }
    }
}
