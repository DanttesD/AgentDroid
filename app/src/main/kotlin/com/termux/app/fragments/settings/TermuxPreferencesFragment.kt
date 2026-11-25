package com.termux.app.fragments.settings

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.termux.R
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences

@Keep
open class TermuxPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context: Context? = getContext()
        if (context == null) {
            return
        }

        val preferenceManager: PreferenceManager = getPreferenceManager()
        preferenceManager.setPreferenceDataStore(TermuxPreferencesDataStore.create(context))

        setPreferencesFromResource(R.xml.termux_preferences, rootKey)
    }
}

open class TermuxPreferencesDataStore : PreferenceDataStore {

    private val mPreferences: TermuxAppSharedPreferences?

    private constructor(context: Context) : super() {
        mPreferences = TermuxAppSharedPreferences.build(context, true)
    }

    companion object {

        // SharedPreferences owns the values; this adapter belongs only to its screen.
        @JvmStatic
        fun create(context: Context): TermuxPreferencesDataStore {
            return TermuxPreferencesDataStore(context)
        }
    }
}
