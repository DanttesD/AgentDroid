package com.termux.app.fragments.settings.termux

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences

@Keep
open class TerminalIOPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context: Context? = getContext()
        if (context == null) {
            return
        }

        val preferenceManager: PreferenceManager = getPreferenceManager()
        preferenceManager.setPreferenceDataStore(TerminalIOPreferencesDataStore.create(context))

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey)
    }
}

open class TerminalIOPreferencesDataStore : PreferenceDataStore {

    private val mPreferences: TermuxAppSharedPreferences?

    private constructor(context: Context) : super() {
        mPreferences = TermuxAppSharedPreferences.build(context, true)
    }

    override fun putBoolean(key: String?, value: Boolean) {
        if (mPreferences == null) {
            return
        }
        if (key == null) {
            return
        }

        when (key) {
            "soft_keyboard_enabled" -> {
                mPreferences.setSoftKeyboardEnabled(value)
            }
            "soft_keyboard_enabled_only_if_no_hardware" -> {
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(value)
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
            "soft_keyboard_enabled" -> {
                return mPreferences.isSoftKeyboardEnabled()
            }
            "soft_keyboard_enabled_only_if_no_hardware" -> {
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware()
            }
            else -> {
                return false
            }
        }
    }

    companion object {

        // SharedPreferences owns the values; this adapter belongs only to its screen.
        @JvmStatic
        fun create(context: Context): TerminalIOPreferencesDataStore {
            return TerminalIOPreferencesDataStore(context)
        }
    }
}
