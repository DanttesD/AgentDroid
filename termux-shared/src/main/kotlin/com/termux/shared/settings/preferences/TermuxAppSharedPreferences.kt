package com.termux.shared.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue
import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import com.termux.shared.packages.PackageUtils
import com.termux.shared.termux.TermuxConstants

open class TermuxAppSharedPreferences {

    private val mContext: Context
    private val mSharedPreferences: SharedPreferences?

    private var MIN_FONTSIZE: Int = 0
    private var MAX_FONTSIZE: Int = 0
    private var DEFAULT_FONTSIZE: Int = 0

    private constructor(context: Context) {
        mContext = context
        mSharedPreferences = getPrivateSharedPreferences(mContext)

        setFontVariables(context)
    }

    open fun shouldShowTerminalToolbar(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR)
    }

    open fun setShowTerminalToolbar(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, value, false)
    }

    open fun toogleShowTerminalToolbar(): Boolean {
        val currentValue: Boolean = shouldShowTerminalToolbar()
        setShowTerminalToolbar(!currentValue)
        return !currentValue
    }

    open fun isTerminalMarginAdjustmentEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_TERMINAL_MARGIN_ADJUSTMENT)
    }

    open fun setTerminalMarginAdjustment(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, value, false)
    }

    open fun isSoftKeyboardEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED)
    }

    open fun setSoftKeyboardEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, value, false)
    }

    open fun isSoftKeyboardEnabledOnlyIfNoHardware(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE)
    }

    open fun setSoftKeyboardEnabledOnlyIfNoHardware(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value, false)
    }

    open fun shouldKeepScreenOn(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_KEEP_SCREEN_ON, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON)
    }

    open fun setKeepScreenOn(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_KEEP_SCREEN_ON, value, false)
    }

    open fun setFontVariables(context: Context) {
        val sizes: IntArray = getDefaultFontSizes(context)

        DEFAULT_FONTSIZE = sizes[0]
        MIN_FONTSIZE = sizes[1]
        MAX_FONTSIZE = sizes[2]
    }

    open fun getFontSize(): Int {
        val fontSize: Int = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE)
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE)
    }

    open fun setFontSize(value: Int) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_FONTSIZE, value, false)
    }

    open fun changeFontSize(increase: Boolean) {
        var fontSize: Int = getFontSize()

        fontSize += (if (increase) 1 else -1) * 2
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE))

        setFontSize(fontSize)
    }

    open fun getCurrentSession(): String? {
        return SharedPreferenceUtils.getString(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_CURRENT_SESSION, null, true)
    }

    open fun setCurrentSession(value: String?) {
        SharedPreferenceUtils.setString(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_CURRENT_SESSION, value, false)
    }

    /** One-time fork migration: the old normal default becomes off; later choices are preserved.*/
    open fun migrateForkLogDefault() {
        val key = "jobs_log_off_default_v1"
        if (!mSharedPreferences!!.getBoolean(key, false)) {
            if (getLogLevel() == Logger.LOG_LEVEL_NORMAL) {
                setLogLevel(null, Logger.LOG_LEVEL_OFF)
            }
            mSharedPreferences!!.edit().putBoolean(key, true).apply()
        }
    }

    open fun getLogLevel(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_LOG_LEVEL, Logger.LOG_LEVEL_OFF)
    }

    open fun setLogLevel(context: Context?, logLevel: Int) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_LOG_LEVEL, logLevel, false)
    }

    open fun getLastNotificationId(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_LAST_NOTIFICATION_ID, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID)
    }

    open fun setLastNotificationId(notificationId: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_LAST_NOTIFICATION_ID, notificationId, false)
    }

    open fun isTerminalViewKeyLoggingEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED)
    }

    open fun setTerminalViewKeyLoggingEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, false)
    }

    open fun arePluginErrorNotificationsEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED)
    }

    open fun setPluginErrorNotificationsEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, value, false)
    }

    open fun areCrashReportNotificationsEnabled(): Boolean {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED)
    }

    open fun setCrashReportNotificationsEnabled(value: Boolean) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, value, false)
    }

    companion object {

        private const val LOG_TAG: String = "TermuxAppSharedPreferences"

        /**
         * Get the [Context] for a package name.
         *
         * @param context The [Context] to use to get the [Context] of the
         *                [TermuxConstants.TERMUX_PACKAGE_NAME].
         * @return Returns the [TermuxAppSharedPreferences]. This will `null` if an exception is raised.
         **/
        @JvmStatic
        fun build(context: Context): TermuxAppSharedPreferences? {
            val termuxPackageContext: Context? = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME)
            if (termuxPackageContext == null) {
                return null
            } else {
                return TermuxAppSharedPreferences(termuxPackageContext)
            }
        }

        /**
         * Get the [Context] for a package name.
         *
         * @param context The [Activity] to use to get the [Context] of the
         *                [TermuxConstants.TERMUX_PACKAGE_NAME].
         * @param exitAppOnError If `true` and failed to get package context, then a dialog will
         *                       be shown which when dismissed will exit the app.
         * @return Returns the [TermuxAppSharedPreferences]. This will `null` if an exception is raised.
         **/
        @JvmStatic
        fun build(context: Context, exitAppOnError: Boolean): TermuxAppSharedPreferences? {
            val termuxPackageContext: Context? = PackageUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_PACKAGE_NAME, exitAppOnError)
            if (termuxPackageContext == null) {
                return null
            } else {
                return TermuxAppSharedPreferences(termuxPackageContext)
            }
        }

        @JvmStatic
        private fun getPrivateSharedPreferences(context: Context?): SharedPreferences? {
            if (context == null) {
                return null
            }
            return SharedPreferenceUtils.getPrivateSharedPreferences(context, TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
        }

        @JvmStatic
        fun getDefaultFontSizes(context: Context): IntArray {
            val dipInPixels: Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, context.getResources().getDisplayMetrics())

            val sizes = IntArray(3)

            // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
            // to prevent invisible text due to zoom be mistake:
            sizes[1] = (4f * dipInPixels).toInt() // min

            // http://www.google.com/design/spec/style/typography.html#typography-line-height
            var defaultFontSize: Int = Math.round(12 * dipInPixels)
            // Make it divisible by 2 since that is the minimal adjustment step:
            if (defaultFontSize % 2 == 1) {
                defaultFontSize--
            }

            sizes[0] = defaultFontSize // default

            sizes[2] = 256 // max

            return sizes
        }
    }
}
