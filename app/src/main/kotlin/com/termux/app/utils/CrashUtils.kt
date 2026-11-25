package com.termux.app.utils

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Environment
import com.termux.R
import com.termux.app.models.UserAction
import com.termux.shared.activities.ReportActivity
import com.termux.shared.data.DataUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.ReportInfo
import com.termux.shared.models.errors.Error
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.notification.TermuxNotificationUtils
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.AndroidUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import java.nio.charset.Charset

object CrashUtils {

    private const val LOG_TAG: String = "CrashUtils"

    /**
     * Notify the user of an app crash at last run by reading the crash info from the crash log file
     * at [TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH]. The crash log file would have been
     * created by [com.termux.shared.crash.CrashHandler].
     *
     * If the crash log file exists and is not empty and
     * [TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED] is
     * enabled, then a notification will be shown for the crash on the
     * [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME] channel, otherwise nothing will be done.
     *
     * After reading from the crash log file, it will be moved to [TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH].
     *
     * @param context The [Context] for operations.
     * @param logTagParam The log tag to use for logging.
     **/
    @JvmStatic
    fun notifyAppCrashOnLastRun(context: Context?, logTagParam: String) {
        if (context == null) {
            return
        }

        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context)
        if (preferences == null) {
            return
        }

        // If user has disabled notifications for crashes
        if (!preferences.areCrashReportNotificationsEnabled()) {
            return
        }

        object : Thread() {
            override fun run() {
                val logTag: String? = DataUtils.getDefaultIfNull(logTagParam, LOG_TAG)

                if (!FileUtils.regularFileExists(TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, false)) {
                    return
                }

                var error: Error?
                val reportStringBuilder = StringBuilder()

                // Read report string from crash log file
                error = FileUtils.readStringFromFile("crash log", TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, Charset.defaultCharset(), reportStringBuilder, false)
                if (error != null) {
                    Logger.logErrorExtended(logTag, error.toString())
                    return
                }

                // Move crash log file to backup location if it exists
                error = FileUtils.moveRegularFile("crash log", TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH, TermuxConstants.TERMUX_CRASH_LOG_BACKUP_FILE_PATH, true)
                if (error != null) {
                    Logger.logErrorExtended(logTag, error.toString())
                }

                val reportString: String = reportStringBuilder.toString()

                if (reportString.isEmpty()) {
                    return
                }

                Logger.logDebug(logTag, "A crash log file found at \"" + TermuxConstants.TERMUX_CRASH_LOG_FILE_PATH + "\".")

                sendCrashReportNotification(context, logTag, reportString, false, false)
            }
        }.start()
    }

    /**
     * Send a crash report notification for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
     * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
     *
     * @param context The [Context] for operations.
     * @param logTag The log tag to use for logging.
     * @param message The message for the crash report.
     * @param forceNotification If set to `true`, then a notification will be shown
     *                          regardless of if pending intent is `null` or
     *                          [TermuxPreferenceConstants.TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED]
     *                          is `false`.
     * @param addAppAndDeviceInfo If set to `true`, then app and device info will be appended
     *                            to the message.
     **/
    @JvmStatic
    fun sendCrashReportNotification(context: Context?, logTag: String?, message: String, forceNotification: Boolean, addAppAndDeviceInfo: Boolean) {
        var logTag = logTag
        if (context == null) {
            return
        }

        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context)
        if (preferences == null) {
            return
        }

        // If user has disabled notifications for crashes
        if (!preferences.areCrashReportNotificationsEnabled() && !forceNotification) {
            return
        }

        logTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)

        // Send a notification to show the crash log which when clicked will open the {@link ReportActivity}
        // to show the details of the crash
        val title: String = TermuxConstants.TERMUX_APP_NAME + " Crash Report"

        Logger.logDebug(logTag, "Sending \"" + title + "\" notification.")

        val reportString = StringBuilder(message)

        if (addAppAndDeviceInfo) {
            reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(context, true))
            reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context))
        }

        val userActionName: String = UserAction.CRASH_REPORT.getName()
        val result: ReportActivity.NewInstanceResult = ReportActivity.newInstance(context, ReportInfo(userActionName, logTag, title, null, reportString.toString(), "\n\n" + TermuxUtils.getReportIssueMarkdownString(context), true, userActionName, "" + Environment.getExternalStorageDirectory() + "/" + FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)))
        if (result.contentIntent == null) {
            return
        }

        // Must ensure result code for PendingIntents and id for notification are unique otherwise will override previous
        val nextNotificationId: Int = TermuxNotificationUtils.getNextNotificationId(context)

        val contentIntent: PendingIntent = PendingIntent.getActivity(context, nextNotificationId, result.contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        var deleteIntent: PendingIntent? = null
        if (result.deleteIntent != null) {
            deleteIntent = PendingIntent.getBroadcast(context, nextNotificationId, result.deleteIntent!!, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        // Setup the notification channel if not already set up
        setupCrashReportsNotificationChannel(context)

        // Build the notification
        val builder: Notification.Builder? = getCrashReportsNotificationBuilder(context, title, null, null, contentIntent, deleteIntent, NotificationUtils.NOTIFICATION_MODE_VIBRATE)
        if (builder == null) {
            return
        }

        // Send the notification
        val notificationManager: NotificationManager? = NotificationUtils.getNotificationManager(context)
        if (notificationManager != null) {
            notificationManager.notify(nextNotificationId, builder.build())
        }
    }

    /**
     * Get [Notification.Builder] for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID]
     * and [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
     *
     * @param context The [Context] for operations.
     * @param title The title for the notification.
     * @param notificationText The second line text of the notification.
     * @param notificationBigText The full text of the notification that may optionally be styled.
     * @param contentIntent The [PendingIntent] which should be sent when notification is clicked.
     * @param deleteIntent The [PendingIntent] which should be sent when notification is deleted.
     * @param notificationMode The notification mode. It must be one of `NotificationUtils.NOTIFICATION_MODE_*`.
     * @return Returns the [Notification.Builder].
     **/
    @JvmStatic
    fun getCrashReportsNotificationBuilder(context: Context, title: CharSequence, notificationText: CharSequence?, notificationBigText: CharSequence?, contentIntent: PendingIntent, deleteIntent: PendingIntent?, notificationMode: Int): Notification.Builder? {
        val builder: Notification.Builder? = NotificationUtils.geNotificationBuilder(context, TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH, title, notificationText, notificationBigText, contentIntent, deleteIntent, notificationMode)

        if (builder == null) {
            return null
        }

        // Enable timestamp
        builder.setShowWhen(true)

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_error_notification)

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B.toInt())

        // Dismiss on click
        builder.setAutoCancel(true)

        return builder
    }

    /**
     * Setup the notification channel for [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID] and
     * [TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME].
     *
     * @param context The [Context] for operations.
     **/
    @JvmStatic
    fun setupCrashReportsNotificationChannel(context: Context) {
        NotificationUtils.setupNotificationChannel(context, TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID, TermuxConstants.TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
    }
}
