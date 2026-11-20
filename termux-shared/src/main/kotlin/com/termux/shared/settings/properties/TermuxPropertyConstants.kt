package com.termux.shared.settings.properties

import com.google.common.collect.ImmutableBiMap
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import java.io.File
import java.util.Arrays

/*
 * Version: v0.15.0
 *
 * Changelog
 *
 * - 0.1.0 (2021-03-11)
 *      - Initial Release.
 *
 * - 0.2.0 (2021-03-11)
 *      - Renamed `HOME_PATH` to `TERMUX_HOME_DIR_PATH`.
 *      - Renamed `TERMUX_PROPERTIES_PRIMARY_PATH` to `TERMUX_PROPERTIES_PRIMARY_FILE_PATH`.
 *      - Renamed `TERMUX_PROPERTIES_SECONDARY_FILE_PATH` to `TERMUX_PROPERTIES_SECONDARY_FILE_PATH`.
 *
 * - 0.3.0 (2021-03-16)
 *      - Add `*TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR*`.
 *
 * - 0.4.0 (2021-03-16)
 *      - Removed `MAP_GENERIC_BOOLEAN` and `MAP_GENERIC_INVERTED_BOOLEAN`.
 *
 * - 0.5.0 (2021-03-25)
 *      - Add `KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP`.
 *
 * - 0.6.0 (2021-04-07)
 *      - Updated javadocs.
 *
 * - 0.7.0 (2021-05-09)
 *      - Add `*SOFT_KEYBOARD_TOGGLE_BEHAVIOUR*`.
 *
 * - 0.8.0 (2021-05-10)
 *      - Change the `KEY_USE_BACK_KEY_AS_ESCAPE_KEY` and `KEY_VIRTUAL_VOLUME_KEYS_DISABLED` booleans
 *          to `KEY_BACK_KEY_BEHAVIOUR` and `KEY_VOLUME_KEYS_BEHAVIOUR` String internal values.
 *      - Renamed `SOFT_KEYBOARD_TOGGLE_BEHAVIOUR` to `KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR`.
 *
 * - 0.9.0 (2021-05-14)
 *      - Add `*KEY_TERMINAL_CURSOR_BLINK_RATE*`.
 *
 * - 0.10.0 (2021-05-15)
 *      - Add `MAP_BACK_KEY_BEHAVIOUR`, `MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR`, `MAP_VOLUME_KEYS_BEHAVIOUR`.
 *
 * - 0.11.0 (2021-06-10)
 *      - Add `*KEY_TERMINAL_TRANSCRIPT_ROWS*`.
 *
 * - 0.12.0 (2021-06-10)
 *      - Add `*KEY_TERMINAL_CURSOR_STYLE*`.
 *
 * - 0.13.0 (2021-08-25)
 *      - Add `*KEY_TERMINAL_MARGIN_HORIZONTAL*` and `*KEY_TERMINAL_MARGIN_VERTICAL*`.
 *
 * - 0.14.0 (2021-09-02)
 *      - Add `getTermuxFloatPropertiesFile()`.
 *
 * - 0.15.0 (2021-09-05)
 *      - Add `KEY_EXTRA_KEYS_TEXT_ALL_CAPS`.
 **/
/**
 * A class that defines shared constants of the SharedProperties used by Termux app and its plugins.
 * This class will be hosted by termux-shared lib and should be imported by other termux plugin
 * apps as is instead of copying constants to random classes. The 3rd party apps can also import
 * it for interacting with termux apps. If changes are made to this file, increment the version number
 * and add an entry in the Changelog section above.
 *
 * The properties are loaded from the first file found at
 * [TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH] or
 * [TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE_PATH]
 **/
object TermuxPropertyConstants {

    /* boolean*/

    /** Defines the key for whether hardware keyboard shortcuts are enabled.*/
    const val KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS: String = "disable-hardware-keyboard-shortcuts" // Default: "disable-hardware-keyboard-shortcuts"

    /** Defines the key for whether a toast will be shown when user changes the terminal session*/
    const val KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST: String = "disable-terminal-session-change-toast" // Default: "disable-terminal-session-change-toast"

    /** Defines the key for whether to enforce character based input to fix the issue where for some devices like Samsung, the letters might not appear until enter is pressed*/
    const val KEY_ENFORCE_CHAR_BASED_INPUT: String = "enforce-char-based-input" // Default: "enforce-char-based-input"

    /** Defines the key for whether text for the extra keys buttons should be all capitalized automatically*/
    const val KEY_EXTRA_KEYS_TEXT_ALL_CAPS: String = "extra-keys-text-all-caps" // Default: "extra-keys-text-all-caps"

    /** Defines the key for whether to hide soft keyboard when termux app is started*/
    const val KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP: String = "hide-soft-keyboard-on-startup" // Default: "hide-soft-keyboard-on-startup"

    /** Defines the key for whether url links in terminal transcript will automatically open on click or on tap*/
    const val KEY_TERMINAL_ONCLICK_URL_OPEN: String = "terminal-onclick-url-open" // Default: "terminal-onclick-url-open"

    /** Defines the key for whether to use black UI*/
    const val KEY_USE_BLACK_UI: String = "use-black-ui" // Default: "use-black-ui"

    /** Defines the key for whether to use ctrl space workaround to fix the issue where ctrl+space does not work on some ROMs*/
    const val KEY_USE_CTRL_SPACE_WORKAROUND: String = "ctrl-space-workaround" // Default: "ctrl-space-workaround"

    /** Defines the key for whether to use fullscreen*/
    const val KEY_USE_FULLSCREEN: String = "fullscreen" // Default: "fullscreen"

    /** Defines the key for whether to use fullscreen workaround*/
    const val KEY_USE_FULLSCREEN_WORKAROUND: String = "use-fullscreen-workaround" // Default: "use-fullscreen-workaround"

    /* int*/

    /** Defines the key for the bell behaviour*/
    const val KEY_BELL_BEHAVIOUR: String = "bell-character" // Default: "bell-character"

    const val VALUE_BELL_BEHAVIOUR_VIBRATE: String = "vibrate"
    const val VALUE_BELL_BEHAVIOUR_BEEP: String = "beep"
    const val VALUE_BELL_BEHAVIOUR_IGNORE: String = "ignore"
    const val DEFAULT_VALUE_BELL_BEHAVIOUR: String = VALUE_BELL_BEHAVIOUR_VIBRATE

    const val IVALUE_BELL_BEHAVIOUR_VIBRATE: Int = 1
    const val IVALUE_BELL_BEHAVIOUR_BEEP: Int = 2
    const val IVALUE_BELL_BEHAVIOUR_IGNORE: Int = 3
    const val DEFAULT_IVALUE_BELL_BEHAVIOUR: Int = IVALUE_BELL_BEHAVIOUR_VIBRATE

    /** Defines the bidirectional map for bell behaviour values and their internal values*/
    @JvmField val MAP_BELL_BEHAVIOUR: ImmutableBiMap<String, Int> = ImmutableBiMap.Builder<String, Int>().put(VALUE_BELL_BEHAVIOUR_VIBRATE, IVALUE_BELL_BEHAVIOUR_VIBRATE).put(VALUE_BELL_BEHAVIOUR_BEEP, IVALUE_BELL_BEHAVIOUR_BEEP).put(VALUE_BELL_BEHAVIOUR_IGNORE, IVALUE_BELL_BEHAVIOUR_IGNORE).build()

    /** Defines the key for the terminal cursor blink rate*/
    const val KEY_TERMINAL_CURSOR_BLINK_RATE: String = "terminal-cursor-blink-rate" // Default: "terminal-cursor-blink-rate"
    const val IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN: Int = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN
    const val IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX: Int = TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX
    const val DEFAULT_IVALUE_TERMINAL_CURSOR_BLINK_RATE: Int = 0

    /** Defines the key for the terminal cursor style*/
    const val KEY_TERMINAL_CURSOR_STYLE: String = "terminal-cursor-style" // Default: "terminal-cursor-style"

    const val VALUE_TERMINAL_CURSOR_STYLE_BLOCK: String = "block"
    const val VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE: String = "underline"
    const val VALUE_TERMINAL_CURSOR_STYLE_BAR: String = "bar"

    const val IVALUE_TERMINAL_CURSOR_STYLE_BLOCK: Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
    const val IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE: Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
    const val IVALUE_TERMINAL_CURSOR_STYLE_BAR: Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
    const val DEFAULT_IVALUE_TERMINAL_CURSOR_STYLE: Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    /** Defines the bidirectional map for terminal cursor styles and their internal values*/
    @JvmField val MAP_TERMINAL_CURSOR_STYLE: ImmutableBiMap<String, Int> = ImmutableBiMap.Builder<String, Int>().put(VALUE_TERMINAL_CURSOR_STYLE_BLOCK, IVALUE_TERMINAL_CURSOR_STYLE_BLOCK).put(VALUE_TERMINAL_CURSOR_STYLE_UNDERLINE, IVALUE_TERMINAL_CURSOR_STYLE_UNDERLINE).put(VALUE_TERMINAL_CURSOR_STYLE_BAR, IVALUE_TERMINAL_CURSOR_STYLE_BAR).build()

    /** Defines the key for the terminal margin on left and right in dp units*/
    const val KEY_TERMINAL_MARGIN_HORIZONTAL: String = "terminal-margin-horizontal" // Default: "terminal-margin-horizontal"
    const val IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN: Int = 0
    const val IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX: Int = 100
    const val DEFAULT_IVALUE_TERMINAL_HORIZONTAL_MARGIN: Int = 3

    /** Defines the key for the terminal margin on top and bottom in dp units*/
    const val KEY_TERMINAL_MARGIN_VERTICAL: String = "terminal-margin-vertical" // Default: "terminal-margin-vertical"
    const val IVALUE_TERMINAL_MARGIN_VERTICAL_MIN: Int = 0
    const val IVALUE_TERMINAL_MARGIN_VERTICAL_MAX: Int = 100
    const val DEFAULT_IVALUE_TERMINAL_VERTICAL_MARGIN: Int = 0

    /** Defines the key for the terminal transcript rows*/
    const val KEY_TERMINAL_TRANSCRIPT_ROWS: String = "terminal-transcript-rows" // Default: "terminal-transcript-rows"
    const val IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN: Int = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MIN
    const val IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX: Int = TerminalEmulator.TERMINAL_TRANSCRIPT_ROWS_MAX
    const val DEFAULT_IVALUE_TERMINAL_TRANSCRIPT_ROWS: Int = TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS

    /* float*/

    /** Defines the key for the terminal toolbar height*/
    const val KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR: String = "terminal-toolbar-height" // Default: "terminal-toolbar-height"
    const val IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN: Float = 0.4f
    const val IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX: Float = 3.0f
    const val DEFAULT_IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR: Float = 1.0f

    /* Integer*/

    /** Defines the key for create session shortcut*/
    const val KEY_SHORTCUT_CREATE_SESSION: String = "shortcut.create-session" // Default: "shortcut.create-session"
    /** Defines the key for next session shortcut*/
    const val KEY_SHORTCUT_NEXT_SESSION: String = "shortcut.next-session" // Default: "shortcut.next-session"
    /** Defines the key for previous session shortcut*/
    const val KEY_SHORTCUT_PREVIOUS_SESSION: String = "shortcut.previous-session" // Default: "shortcut.previous-session"
    /** Defines the key for rename session shortcut*/
    const val KEY_SHORTCUT_RENAME_SESSION: String = "shortcut.rename-session" // Default: "shortcut.rename-session"

    const val ACTION_SHORTCUT_CREATE_SESSION: Int = 1
    const val ACTION_SHORTCUT_NEXT_SESSION: Int = 2
    const val ACTION_SHORTCUT_PREVIOUS_SESSION: Int = 3
    const val ACTION_SHORTCUT_RENAME_SESSION: Int = 4

    /** Defines the bidirectional map for session shortcut values and their internal actions*/
    @JvmField val MAP_SESSION_SHORTCUTS: ImmutableBiMap<String, Int> = ImmutableBiMap.Builder<String, Int>().put(KEY_SHORTCUT_CREATE_SESSION, ACTION_SHORTCUT_CREATE_SESSION).put(KEY_SHORTCUT_NEXT_SESSION, ACTION_SHORTCUT_NEXT_SESSION).put(KEY_SHORTCUT_PREVIOUS_SESSION, ACTION_SHORTCUT_PREVIOUS_SESSION).put(KEY_SHORTCUT_RENAME_SESSION, ACTION_SHORTCUT_RENAME_SESSION).build()

    /* String*/

    /** Defines the key for whether back key will behave as escape key or literal back key*/
    const val KEY_BACK_KEY_BEHAVIOUR: String = "back-key" // Default: "back-key"

    const val IVALUE_BACK_KEY_BEHAVIOUR_BACK: String = "back"
    const val IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE: String = "escape"
    const val DEFAULT_IVALUE_BACK_KEY_BEHAVIOUR: String = IVALUE_BACK_KEY_BEHAVIOUR_BACK

    /** Defines the bidirectional map for back key behaviour values and their internal values*/
    @JvmField val MAP_BACK_KEY_BEHAVIOUR: ImmutableBiMap<String, String> = ImmutableBiMap.Builder<String, String>().put(IVALUE_BACK_KEY_BEHAVIOUR_BACK, IVALUE_BACK_KEY_BEHAVIOUR_BACK).put(IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE, IVALUE_BACK_KEY_BEHAVIOUR_ESCAPE).build()

    /** Defines the key for the default working directory*/
    const val KEY_DEFAULT_WORKING_DIRECTORY: String = "default-working-directory" // Default: "default-working-directory"
    /** Defines the default working directory*/
    const val DEFAULT_IVALUE_DEFAULT_WORKING_DIRECTORY: String = TermuxConstants.TERMUX_HOME_DIR_PATH

    /** Defines the key for extra keys*/
    const val KEY_EXTRA_KEYS: String = "extra-keys" // Default: "extra-keys"
    //public static final String DEFAULT_IVALUE_EXTRA_KEYS = "[[ESC, TAB, CTRL, ALT, {key: '-', popup: '|'}, DOWN, UP]]"; // Single row
    const val DEFAULT_IVALUE_EXTRA_KEYS: String = "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]" // Double row

    /** Defines the key for extra keys style*/
    const val KEY_EXTRA_KEYS_STYLE: String = "extra-keys-style" // Default: "extra-keys-style"
    const val DEFAULT_IVALUE_EXTRA_KEYS_STYLE: String = "default"

    /** Defines the key for whether toggle soft keyboard request will show/hide or enable/disable keyboard*/
    const val KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR: String = "soft-keyboard-toggle-behaviour" // Default: "soft-keyboard-toggle-behaviour"

    const val IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE: String = "show/hide"
    const val IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE: String = "enable/disable"
    const val DEFAULT_IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR: String = IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE

    /** Defines the bidirectional map for toggle soft keyboard behaviour values and their internal values*/
    @JvmField val MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR: ImmutableBiMap<String, String> = ImmutableBiMap.Builder<String, String>().put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_SHOW_HIDE).put(IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE, IVALUE_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR_ENABLE_DISABLE).build()

    /** Defines the key for whether volume keys will behave as virtual or literal volume keys*/
    const val KEY_VOLUME_KEYS_BEHAVIOUR: String = "volume-keys" // Default: "volume-keys"

    const val IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL: String = "virtual"
    const val IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME: String = "volume"
    const val DEFAULT_IVALUE_VOLUME_KEYS_BEHAVIOUR: String = IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL

    /** Defines the bidirectional map for volume keys behaviour values and their internal values*/
    @JvmField val MAP_VOLUME_KEYS_BEHAVIOUR: ImmutableBiMap<String, String> = ImmutableBiMap.Builder<String, String>().put(IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL, IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL).put(IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME, IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME).build()

    /** Defines the set for keys loaded by termux
     * Setting this to `null` will make [SharedProperties] throw an exception.
     **/
    @JvmField val TERMUX_PROPERTIES_LIST: MutableSet<String> = HashSet<String>(Arrays.asList(KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, KEY_ENFORCE_CHAR_BASED_INPUT, KEY_EXTRA_KEYS_TEXT_ALL_CAPS, KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, KEY_TERMINAL_ONCLICK_URL_OPEN, KEY_USE_BLACK_UI, KEY_USE_CTRL_SPACE_WORKAROUND, KEY_USE_FULLSCREEN, KEY_USE_FULLSCREEN_WORKAROUND, TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, KEY_BELL_BEHAVIOUR, KEY_TERMINAL_CURSOR_BLINK_RATE, KEY_TERMINAL_CURSOR_STYLE, KEY_TERMINAL_MARGIN_HORIZONTAL, KEY_TERMINAL_MARGIN_VERTICAL, KEY_TERMINAL_TRANSCRIPT_ROWS, KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR, KEY_SHORTCUT_CREATE_SESSION, KEY_SHORTCUT_NEXT_SESSION, KEY_SHORTCUT_PREVIOUS_SESSION, KEY_SHORTCUT_RENAME_SESSION, KEY_BACK_KEY_BEHAVIOUR, KEY_DEFAULT_WORKING_DIRECTORY, KEY_EXTRA_KEYS, KEY_EXTRA_KEYS_STYLE, KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, KEY_VOLUME_KEYS_BEHAVIOUR))

    /** Defines the set for keys loaded by termux that have default boolean behaviour with false as default.
     * "true" -> true
     * "false" -> false
     * default: false
     **/
    @JvmField val TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: MutableSet<String> = HashSet<String>(Arrays.asList(KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS, KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, KEY_ENFORCE_CHAR_BASED_INPUT, KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP, KEY_TERMINAL_ONCLICK_URL_OPEN, KEY_USE_CTRL_SPACE_WORKAROUND, KEY_USE_FULLSCREEN, KEY_USE_FULLSCREEN_WORKAROUND, TermuxConstants.PROP_ALLOW_EXTERNAL_APPS))

    /** Defines the set for keys loaded by termux that have default boolean behaviour with true as default.
     * "true" -> true
     * "false" -> false
     * default: true
     **/
    @JvmField val TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: MutableSet<String> = HashSet<String>(Arrays.asList(KEY_EXTRA_KEYS_TEXT_ALL_CAPS))

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with false as default.
     * "false" -> true
     * "true" -> false
     * default: false
     **/
    @JvmField val TERMUX_DEFAULT_INVERETED_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: MutableSet<String> = HashSet<String>(Arrays.asList())

    /** Defines the set for keys loaded by termux that have default inverted boolean behaviour with true as default.
     * "false" -> true
     * "true" -> false
     * default: true
     **/
    @JvmField val TERMUX_DEFAULT_INVERETED_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST: MutableSet<String> = HashSet<String>(Arrays.asList())

    /** Returns the first [File] found at
     * [TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH] or
     * [TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE_PATH]
     * from which termux properties can be loaded.
     * If the [File] found is not a regular file or is not readable then null is returned.
     *
     * @return Returns the [File] object for termux properties.
     **/
    @JvmStatic
    fun getTermuxPropertiesFile(): File? {
        return getPropertiesFile(arrayOf<String>(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH, TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE_PATH))
    }

    /** Returns the first [File] found at
     * [TermuxConstants.TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE_PATH] or
     * [TermuxConstants.TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE_PATH]
     * from which termux properties can be loaded.
     * If the [File] found is not a regular file or is not readable then null is returned.
     *
     * @return Returns the [File] object for termux properties.
     **/
    @JvmStatic
    fun getTermuxFloatPropertiesFile(): File? {
        return getPropertiesFile(arrayOf<String>(TermuxConstants.TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE_PATH, TermuxConstants.TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE_PATH))
    }

    @JvmStatic
    fun getPropertiesFile(possiblePropertiesFileLocations: Array<String>): File? {
        var propertiesFile = File(possiblePropertiesFileLocations[0])
        var i = 0
        while (!propertiesFile.exists() && i < possiblePropertiesFileLocations.size) {
            propertiesFile = File(possiblePropertiesFileLocations[i])
            i += 1
        }

        if (propertiesFile.isFile() && propertiesFile.canRead()) {
            return propertiesFile
        } else {
            Logger.logDebug("No readable properties file found at: " + Arrays.toString(possiblePropertiesFileLocations))
            return null
        }
    }
}
