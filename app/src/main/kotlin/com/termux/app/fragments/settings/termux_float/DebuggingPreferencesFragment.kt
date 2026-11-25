package com.termux.app.fragments.settings.termux_float

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.settings.preferences.TermuxFloatAppSharedPreferences

@Keep
open class DebuggingPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context: Context? = getContext()
        if (context == null) {
            return
        }

        val preferenceManager: PreferenceManager = getPreferenceManager()
        preferenceManager.setPreferenceDataStore(DebuggingPreferencesDataStore.create(context))

        setPreferencesFromResource(R.xml.termux_float_debugging_preferences, rootKey)

        configureLoggingPreferences(context)
    }

    private fun configureLoggingPreferences(context: Context) {
        val loggingCategory: PreferenceCategory? = findPreference("logging")
        if (loggingCategory == null) {
            return
        }

        val logLevelListPreference: ListPreference? = findPreference("log_level")
        if (logLevelListPreference != null) {
            val preferences: TermuxFloatAppSharedPreferences? = TermuxFloatAppSharedPreferences.build(context, true)
            if (preferences == null) {
                return
            }

            com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment.setLogLevelListPreferenceData(logLevelListPreference, context, preferences.getLogLevel(true))
            loggingCategory.addPreference(logLevelListPreference)
        }
    }
}

open class DebuggingPreferencesDataStore : PreferenceDataStore {

    private val mContext: Context
    private val mPreferences: TermuxFloatAppSharedPreferences?

    private constructor(context: Context) : super() {
        mContext = context
        mPreferences = TermuxFloatAppSharedPreferences.build(context, true)
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
                return java.lang.String.valueOf(mPreferences.getLogLevel(true))
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
                    mPreferences.setLogLevel(mContext, Integer.parseInt(value), true)
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
                mPreferences.setTerminalViewKeyLoggingEnabled(value, true)
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
                return mPreferences.isTerminalViewKeyLoggingEnabled(true)
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
