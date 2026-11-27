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
open class TerminalViewPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context: Context? = getContext()
        if (context == null) {
            return
        }

        val preferenceManager: PreferenceManager = getPreferenceManager()
        preferenceManager.setPreferenceDataStore(TerminalViewPreferencesDataStore.create(context))

        setPreferencesFromResource(R.xml.termux_terminal_view_preferences, rootKey)
    }
}

open class TerminalViewPreferencesDataStore : PreferenceDataStore {

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
            "terminal_margin_adjustment" -> {
                mPreferences.setTerminalMarginAdjustment(value)
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
            "terminal_margin_adjustment" -> {
                return mPreferences.isTerminalMarginAdjustmentEnabled()
            }
            else -> {
                return false
            }
        }
    }

    companion object {

        // SharedPreferences owns the values; this adapter belongs only to its screen.
        @JvmStatic
        fun create(context: Context): TerminalViewPreferencesDataStore {
            return TerminalViewPreferencesDataStore(context)
        }
    }
}
