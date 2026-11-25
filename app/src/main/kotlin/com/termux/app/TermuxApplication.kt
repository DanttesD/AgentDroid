package com.termux.app

import android.app.Application
import com.termux.shared.crash.TermuxCrashUtils
import com.termux.shared.logger.Logger
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences

open class TermuxApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(this)

        // Set log level for the app
        setLogLevel()
    }

    private fun setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(getApplicationContext())
        if (preferences == null) {
            return
        }
        preferences.migrateForkLogDefault()
        preferences.setLogLevel(null, preferences.getLogLevel())
        Logger.logDebug("Starting Application")
    }
}
