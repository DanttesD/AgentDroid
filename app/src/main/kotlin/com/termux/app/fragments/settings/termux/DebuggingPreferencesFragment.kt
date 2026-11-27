package com.termux.app.fragments.settings.termux

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.logger.Logger
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences

@Keep
open class DebuggingPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context: Context? = getContext()
        if (context == null) {
            return
        }

        val preferenceManager: PreferenceManager = getPreferenceManager()
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.create(context))

        setPreferencesFromResource(R.xml.termux_debugging_preferences, rootKey)

        configureLoggingPreferences(context)
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory: PreferenceCategory? = findPreference("logging")
        if (loggingCategory == null) {
            return
        }

        val logLevelListPreference: ListPreference? = findPreference("log_level")
        if (logLevelListPreference != null) {
            val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)
            if (preferences == null) {
                return
            }

            setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel())
            loggingCategory.addPreference(logLevelListPreference)
        }
    }

    companion object {

        @JvmStatic
        fun setLogLevelListPreferenceData(logLevelListPreference: ListPreference?, context: Context, logLevel: Int): ListPreference? {
            var logLevelListPreference = logLevelListPreference
            if (logLevelListPreference == null) {
                logLevelListPreference = ListPreference(context)
            }

            val logLevels: Array<CharSequence> = Logger.getLogLevelsArray()
            val logLevelLabels: Array<CharSequence?>? = Logger.getLogLevelLabelsArray(context, logLevels, true)

            logLevelListPreference.setEntryValues(logLevels)
            logLevelListPreference.setEntries(logLevelLabels)

            logLevelListPreference.setValue(java.lang.String.valueOf(logLevel))
            logLevelListPreference.setDefaultValue(Logger.DEFAULT_LOG_LEVEL)

            return logLevelListPreference
        }
    }
}

open class DebuggingPreferencesDataStore : PreferenceDataStore {

    private val mContext: Context
    private val mPreferences: TermuxAppSharedPreferences?

    private constructor(context: Context) : super() {
        mContext = context
        mPreferences = TermuxAppSharedPreferences.build(context, true)
    }

    override fun getString(key: String?, defValue: String?): String? {
        if (mPreferences == null) {
            return null
        }
        if (key == null) {
            return null
        }

        when (key) {
            "log_level" -> {
                return java.lang.String.valueOf(mPreferences.getLogLevel())
            }
            else -> {
                return null
            }
        }
    }

    override fun putString(key: String?, value: String?) {
        if (mPreferences == null) {
            return
        }
        if (key == null) {
            return
        }

        when (key) {
            "log_level" -> {
                if (value != null) {
                    mPreferences.setLogLevel(mContext, Integer.parseInt(value))
                }
            }
            else -> {
            }
        }
    }

    override fun putBoolean(key: String?, value: Boolean) {
        if (mPreferences == null) {
            return
        }
        if (key == null) {
            return
        }

        when (key) {
            "terminal_view_key_logging_enabled" -> {
                mPreferences.setTerminalViewKeyLoggingEnabled(value)
            }
            "plugin_error_notifications_enabled" -> {
                mPreferences.setPluginErrorNotificationsEnabled(value)
            }
            "crash_report_notifications_enabled" -> {
                mPreferences.setCrashReportNotificationsEnabled(value)
            }
            else -> {
            }
        }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        if (mPreferences == null) {
            return false
        }
        when (key) {
            "terminal_view_key_logging_enabled" -> {
                return mPreferences.isTerminalViewKeyLoggingEnabled()
            }
            "plugin_error_notifications_enabled" -> {
                return mPreferences.arePluginErrorNotificationsEnabled()
            }
            "crash_report_notifications_enabled" -> {
                return mPreferences.areCrashReportNotificationsEnabled()
            }
            else -> {
                return false
            }
        }
    }

    companion object {

        // SharedPreferences owns the values; this adapter belongs only to its screen.
        @JvmStatic
        fun create(context: Context): DebuggingPreferencesDataStore {
            return DebuggingPreferencesDataStore(context)
        }
    }
}
