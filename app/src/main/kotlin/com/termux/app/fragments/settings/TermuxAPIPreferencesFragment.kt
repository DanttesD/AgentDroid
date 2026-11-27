package com.termux.app.fragments.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.settings.preferences.TermuxAPIAppSharedPreferences

@Keep
open class TermuxAPIPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context: Context? = getContext()
        if (context == null) {
            return
        }

        val preferenceManager: PreferenceManager = getPreferenceManager()
        preferenceManager.setPreferenceDataStore(TermuxAPIPreferencesDataStore.create(context))

        setPreferencesFromResource(R.xml.termux_api_preferences, rootKey)
    }
}

open class TermuxAPIPreferencesDataStore : PreferenceDataStore {

    private val mPreferences: TermuxAPIAppSharedPreferences?

    private constructor(context: Context) : super() {
        mPreferences = TermuxAPIAppSharedPreferences.build(context, true)
    }

    companion object {

        // SharedPreferences owns the values; this adapter belongs only to its screen.
        @JvmStatic
        fun create(context: Context): TermuxAPIPreferencesDataStore {
            return TermuxAPIPreferencesDataStore(context)
        }
    }
}
