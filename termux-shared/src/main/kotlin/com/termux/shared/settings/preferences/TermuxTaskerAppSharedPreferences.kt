package com.termux.shared.settings.preferences

import android.content.Context
import android.content.SharedPreferences
import com.termux.shared.logger.Logger
import com.termux.shared.packages.PackageUtils
import com.termux.shared.termux.TermuxConstants

open class TermuxTaskerAppSharedPreferences {

    private val mContext: Context
    private val mSharedPreferences: SharedPreferences?
    private val mMultiProcessSharedPreferences: SharedPreferences?

    private constructor(context: Context) {
        mContext = context
        mSharedPreferences = getPrivateSharedPreferences(mContext)
        mMultiProcessSharedPreferences = getPrivateAndMultiProcessSharedPreferences(mContext)
    }

    open fun getLogLevel(readFromFile: Boolean): Int {
        if (readFromFile) {
            return SharedPreferenceUtils.getInt(mMultiProcessSharedPreferences, TermuxPreferenceConstants.TERMUX_TASKER_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        } else {
            return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_TASKER_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL)
        }
    }

    open fun setLogLevel(context: Context, logLevel: Int, commitToFile: Boolean) {
        var logLevel = logLevel
        logLevel = Logger.setLogLevel(context, logLevel)
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_TASKER_APP.KEY_LOG_LEVEL, logLevel, commitToFile)
    }

    open fun getLastPendingIntentRequestCode(): Int {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_TASKER_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, TermuxPreferenceConstants.TERMUX_TASKER_APP.DEFAULT_VALUE_KEY_LAST_PENDING_INTENT_REQUEST_CODE)
    }

    open fun setLastPendingIntentRequestCode(lastPendingIntentRequestCode: Int) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TermuxPreferenceConstants.TERMUX_TASKER_APP.KEY_LAST_PENDING_INTENT_REQUEST_CODE, lastPendingIntentRequestCode, false)
    }

    companion object {

        private const val LOG_TAG: String = "TermuxTaskerAppSharedPreferences"

        /**
         * Get the [Context] for a package name.
         *
         * @param context The [Context] to use to get the [Context] of the
         *                [TermuxConstants.TERMUX_TASKER_PACKAGE_NAME].
         * @return Returns the [TermuxTaskerAppSharedPreferences]. This will `null` if an exception is raised.
         **/
        @JvmStatic
        fun build(context: Context): TermuxTaskerAppSharedPreferences? {
            val termuxTaskerPackageContext: Context? = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_TASKER_PACKAGE_NAME)
            if (termuxTaskerPackageContext == null) {
                return null
            } else {
                return TermuxTaskerAppSharedPreferences(termuxTaskerPackageContext)
            }
        }

        /**
         * Get the [Context] for a package name.
         *
         * @param context The [Activity] to use to get the [Context] of the
         *                [TermuxConstants.TERMUX_TASKER_PACKAGE_NAME].
         * @param exitAppOnError If `true` and failed to get package context, then a dialog will
         *                       be shown which when dismissed will exit the app.
         * @return Returns the [TermuxTaskerAppSharedPreferences]. This will `null` if an exception is raised.
         **/
        @JvmStatic
        fun build(context: Context, exitAppOnError: Boolean): TermuxTaskerAppSharedPreferences? {
            val termuxTaskerPackageContext: Context? = PackageUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_TASKER_PACKAGE_NAME, exitAppOnError)
            if (termuxTaskerPackageContext == null) {
                return null
            } else {
                return TermuxTaskerAppSharedPreferences(termuxTaskerPackageContext)
            }
        }

        @JvmStatic
        private fun getPrivateSharedPreferences(context: Context?): SharedPreferences? {
            if (context == null) {
                return null
            }
            return SharedPreferenceUtils.getPrivateSharedPreferences(context, TermuxConstants.TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
        }

        @JvmStatic
        private fun getPrivateAndMultiProcessSharedPreferences(context: Context?): SharedPreferences? {
            if (context == null) {
                return null
            }
            return SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context, TermuxConstants.TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION)
        }
    }
}
