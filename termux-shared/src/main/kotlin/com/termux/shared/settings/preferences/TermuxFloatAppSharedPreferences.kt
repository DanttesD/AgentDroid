package com.termux.shared.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import com.termux.shared.packages.PackageUtils
import com.termux.shared.termux.TermuxConstants

open class TermuxFloatAppSharedPreferences {

    private val mContext: Context
    private val mSharedPreferences: SharedPreferences?
    private val mMultiProcessSharedPreferences: SharedPreferences?

    private var MIN_FONTSIZE: Int = 0
    private var MAX_FONTSIZE: Int = 0
    private var DEFAULT_FONTSIZE: Int = 0

    private constructor(context: Context) {
        mContext = context
        mSharedPreferences = getPrivateSharedPreferences(mContext)
        mMultiProcessSharedPreferences = getPrivateAndMultiProcessSharedPreferences(mContext)

        setFontVariables(context)
    }

    open fun getWindowX(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_X, 200)
    }

    open fun setWindowX(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_X, value, false)
    }

    open fun getWindowY(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_Y, 200)
    }

    open fun setWindowY(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_Y, value, false)
    }

    open fun getWindowWidth(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_WIDTH, 500)
    }

    open fun setWindowWidth(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_WIDTH, value, false)
    }

    open fun getWindowHeight(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_HEIGHT, 500)
    }

    open fun setWindowHeight(value: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_WINDOW_HEIGHT, value, false)
    }

    open fun setFontVariables(context: Context) {
        val sizes: IntArray = TermuxAppSharedPreferences.getDefaultFontSizes(context)

        DEFAULT_FONTSIZE = sizes[0]
        MIN_FONTSIZE = sizes[1]
        MAX_FONTSIZE = sizes[2]
    }

    open fun getFontSize(): Int {
        val fontSize: Int = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE)
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE)
    }

    open fun setFontSize(value: Int) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_FONTSIZE, value, false)
    }

    open fun changeFontSize(increase: Boolean) {
        var fontSize: Int = getFontSize()

        fontSize += (if (increase) 1 else -1) * 2
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE))

        setFontSize(fontSize)
    }

    open fun getLogLevel(readFromFile: Boolean): Int {
        if (readFromFile) {
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        } else {
            return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        }
    }

    open fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_LOG_LEVEL, logLevel, commitToFile)
    }

    open fun isTerminalViewKeyLoggingEnabled(readFromFile: Boolean): Boolean {
        if (readFromFile) {
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TermuxPreferenceConstants.TERMUX_FLOAT_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED)
        } else {
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TermuxPreferenceConstants.TERMUX_FLOAT_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED)
        }
    }

    open fun setTerminalViewKeyLoggingEnabled(value: Boolean, commitToFile: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_FLOAT_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, commitToFile)
    }

    companion object {

        private const val LOG_TAG: String = "TermuxFloatAppSharedPreferences"

        /**
         * Get the [Context] for a package name.
         *
         * @param context The [Context] to use to get the [Context] of the
         *                [TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME].
         * @return Returns the [TermuxFloatAppSharedPreferences]. This will `null` if an exception is raised.
         **/
        @JvmStatic
        fun build(context: Context): TermuxFloatAppSharedPreferences? {
            val termuxFloatPackageContext: Context? = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME)
            if (termuxFloatPackageContext == null) {
                return null
            } else {
                return TermuxFloatAppSharedPreferences(termuxFloatPackageContext)
            }
        }

        /**
         * Get the [Context] for a package name.
         *
         * @param context The [Activity] to use to get the [Context] of the
         *                [TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME].
         * @param exitAppOnError If `true` and failed to get package context, then a dialog will
         *                       be shown which when dismissed will exit the app.
         * @return Returns the [TermuxFloatAppSharedPreferences]. This will `null` if an exception is raised.
         **/
        @JvmStatic
        fun build(context: Context, exitAppOnError: Boolean): TermuxFloatAppSharedPreferences? {
            val termuxFloatPackageContext: Context? = PackageUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME, exitAppOnError)
            if (termuxFloatPackageContext == null) {
                return null
            } else {
                return TermuxFloatAppSharedPreferences(termuxFloatPackageContext)
            }
        }

        @JvmStatic
        private fun getPrivateSharedPreferences(context: Context?): SharedPreferences? {
            if (context == null) {
                return null
            }
            return SharedPreferenceUtils.getPrivateSharedPreferences(context, TermuxConstants.TERMUX_FLOAT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
        }

        @JvmStatic
        private fun getPrivateAndMultiProcessSharedPreferences(context: Context?): SharedPreferences? {
            if (context == null) {
                return null
            }
            return SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context, TermuxConstants.TERMUX_FLOAT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
        }
    }
}
