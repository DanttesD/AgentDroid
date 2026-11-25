package com.termux.app.settings.properties

import android.content.Context
import com.termux.app.terminal.io.KeyboardShortcut
import com.termux.shared.logger.Logger
import com.termux.shared.settings.properties.TermuxPropertyConstants
import com.termux.shared.settings.properties.TermuxSharedProperties
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.TermuxConstants
import org.json.JSONException

open class TermuxAppSharedProperties : TermuxSharedProperties {

    private var mExtraKeysInfo: ExtraKeysInfo? = null
    private var mSessionShortcuts: MutableList<KeyboardShortcut>? = ArrayList<KeyboardShortcut>()

    constructor(context: Context) : super(context, TermuxConstants.TERMUX_APP_NAME, TermuxPropertyConstants.getTermuxPropertiesFile(), TermuxPropertyConstants.TERMUX_PROPERTIES_LIST, TermuxSharedProperties.SharedPropertiesParserClient())

    /**
     * Reload the termux properties from disk into an in-memory cache.
     **/
    override fun loadTermuxPropertiesFromDisk() {
        super.loadTermuxPropertiesFromDisk()

        setExtraKeys()
        setSessionShortcuts()
    }

    /**
     * Set the terminal extra keys and style.
     **/
    private fun setExtraKeys() {
        mExtraKeysInfo = null

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            val extrakeys: String? = getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true) as String?
            var extraKeysStyle: String? = getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true) as String?

            val extraKeyDisplayMap: ExtraKeysConstants.ExtraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle!!)
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.")
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE
            }

            mExtraKeysInfo = ExtraKeysInfo(extrakeys!!, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
        } catch (e: JSONException) {
            Logger.showToast(mContext, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true)
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e)

            try {
                mExtraKeysInfo = ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
            } catch (e2: JSONException) {
                Logger.showToast(mContext, "Can't create default extra keys", true)
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e)
                mExtraKeysInfo = null
            }
        }
    }

    /**
     * Set the terminal sessions shortcuts.
     **/
    private fun setSessionShortcuts() {
        if (mSessionShortcuts == null) {
            mSessionShortcuts = ArrayList<KeyboardShortcut>()
        } else {
            mSessionShortcuts!!.clear()
        }

        // The {@link TermuxPropertyConstants#MAP_SESSION_SHORTCUTS} stores the session shortcut key and action pair
        for (entry in TermuxPropertyConstants.MAP_SESSION_SHORTCUTS.entries) {
            // The mMap stores the code points for the session shortcuts while loading properties
            val codePoint: Int? = getInternalPropertyValue(entry.key, true) as Int?
            // If codePoint is null, then session shortcut did not exist in properties or was invalid
            // as parsed by {@link #getCodePointForSessionShortcuts(String,String)}
            // If codePoint is not null, then get the action for the MAP_SESSION_SHORTCUTS key and
            // add the code point to sessionShortcuts
            if (codePoint != null) {
                mSessionShortcuts!!.add(KeyboardShortcut(codePoint, entry.value))
            }
        }
    }

    open fun getSessionShortcuts(): MutableList<KeyboardShortcut>? {
        return mSessionShortcuts
    }

    open fun getExtraKeysInfo(): ExtraKeysInfo? {
        return mExtraKeysInfo
    }

    companion object {

        private const val LOG_TAG: String = "TermuxAppSharedProperties"

        /**
         * Load the [TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS] value from termux properties file on disk.
         **/
        @JvmStatic
        fun getTerminalTranscriptRows(context: Context): Int {
            return TermuxSharedProperties.getInternalPropertyValue(context, TermuxPropertyConstants.getTermuxPropertiesFile(), TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, TermuxSharedProperties.SharedPropertiesParserClient()) as Int
        }
    }
}
