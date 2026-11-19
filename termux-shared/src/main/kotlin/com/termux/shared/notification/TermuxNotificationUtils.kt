package com.termux.shared.notification

import android.content.Context
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.settings.preferences.TermuxPreferenceConstants
import com.termux.shared.termux.TermuxConstants

object TermuxNotificationUtils {
    /**
     * Try to get the next unique notification id that isn't already being used by the app.
     *
     * Termux app and its plugin must use unique notification ids from the same pool due to usage of android:sharedUserId.
     * https://commonsware.com/blog/2017/06/07/jobscheduler-job-ids-libraries.html
     *
     * @param context The [Context] for operations.
     * @return Returns the notification id that should be safe to use.
     **/
    @Synchronized
    @JvmStatic
    fun getNextNotificationId(context: Context?): Int {
        if (context == null) {
            return TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID
        }

        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context)
        if (preferences == null) {
            return TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID
        }

        val lastNotificationId: Int = preferences.getLastNotificationId()

        var nextNotificationId: Int = lastNotificationId + 1
        while (nextNotificationId == TermuxConstants.TERMUX_APP_NOTIFICATION_ID || nextNotificationId == TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_ID) {
            nextNotificationId++
        }

        if (nextNotificationId == Integer.MAX_VALUE || nextNotificationId < 0) {
            nextNotificationId = TermuxPreferenceConstants.TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID
        }

        preferences.setLastNotificationId(nextNotificationId)
        return nextNotificationId
    }
}
