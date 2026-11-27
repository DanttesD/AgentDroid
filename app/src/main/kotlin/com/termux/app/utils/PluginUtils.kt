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
import com.termux.shared.file.TermuxFileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.models.ReportInfo
import com.termux.shared.models.ResultConfig
import com.termux.shared.models.ResultData
import com.termux.shared.models.errors.Error
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.notification.TermuxNotificationUtils
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.settings.properties.SharedProperties
import com.termux.shared.settings.properties.TermuxPropertyConstants
import com.termux.shared.shell.ResultSender
import com.termux.shared.shell.ShellUtils
import com.termux.shared.termux.AndroidUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils

object PluginUtils {

    private const val LOG_TAG: String = "PluginUtils"

    /**
     * Process [ExecutionCommand] result.
     *
     * The ExecutionCommand currentState must be greater or equal to
     * [ExecutionCommand.ExecutionState.EXECUTED].
     * If the [ExecutionCommand.isPluginExecutionCommand] is `true` and
     * [ResultConfig.resultPendingIntent] or [ResultConfig.resultDirectoryPath]
     * is not `null`, then the result of commands are sent back to the command caller.
     *
     * @param context The [Context] that will be used to send result intent to the [PendingIntent] creator.
     * @param logTag The log tag to use for logging.
     * @param executionCommand The [ExecutionCommand] to process.
     **/
    @JvmStatic
    fun processPluginExecutionCommandResult(context: Context, logTag: String?, executionCommand: ExecutionCommand?) {
        var logTag = logTag
        if (executionCommand == null) {
            return
        }

        logTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)
        var error: Error? = null
        val resultData: ResultData = executionCommand.resultData

        if (!executionCommand.hasExecuted()) {
            Logger.logWarn(logTag, executionCommand.getCommandIdAndLabelLogString() + ": Ignoring call to processPluginExecutionCommandResult() since the execution command state is not higher than the ExecutionState.EXECUTED")
            return
        }

        val isPluginExecutionCommandWithPendingResult: Boolean = executionCommand.isPluginExecutionCommandWithPendingResult()
        val isExecutionCommandLoggingEnabled: Boolean = Logger.shouldEnableLoggingForCustomLogLevel(executionCommand.backgroundCustomLogLevel)

        // Log the output. ResultData should not be logged if pending result since ResultSender will do it
        // or if logging is disabled
        Logger.logDebugExtended(logTag, ExecutionCommand.getExecutionOutputLogString(executionCommand, true, !isPluginExecutionCommandWithPendingResult, isExecutionCommandLoggingEnabled))

        // If execution command was started by a plugin which expects the result back
        if (isPluginExecutionCommandWithPendingResult) {
            // Set variables which will be used by sendCommandResultData to send back the result
            if (executionCommand.resultConfig.resultPendingIntent != null) {
                setPluginResultPendingIntentVariables(executionCommand)
            }
            if (executionCommand.resultConfig.resultDirectoryPath != null) {
                setPluginResultDirectoryVariables(executionCommand)
            }

            // Send result to caller
            error = ResultSender.sendCommandResultData(context, logTag, executionCommand.getCommandIdAndLabelLogString(), executionCommand.resultConfig, executionCommand.resultData, isExecutionCommandLoggingEnabled)
            if (error != null) {
                // error will be added to existing Errors
                resultData.setStateFailed(error)
                Logger.logDebugExtended(logTag, ExecutionCommand.getExecutionOutputLogString(executionCommand, true, true, isExecutionCommandLoggingEnabled))

                // Flash and send notification for the error
                Logger.showToast(context, ResultData.getErrorsListMinimalString(resultData), true)
                sendPluginCommandErrorNotification(context, logTag, executionCommand, ResultData.getErrorsListMinimalString(resultData))
            }
        }

        if (!executionCommand.isStateFailed() && error == null) {
            executionCommand.setState(ExecutionCommand.ExecutionState.SUCCESS)
        }
    }

    /**
     * Process [ExecutionCommand] error.
     *
     * The ExecutionCommand currentState must be equal to [ExecutionCommand.ExecutionState.FAILED].
     * The [ResultData.getErrCode] must have been set to a value greater than
     * [Errno.ERRNO_SUCCESS].
     * The [ResultData.errorsList] must also be set with appropriate error info.
     *
     * If the [ExecutionCommand.isPluginExecutionCommand] is `true` and
     * [ResultConfig.resultPendingIntent] or [ResultConfig.resultDirectoryPath]
     * is not `null`, then the errors of commands are sent back to the command caller.
     *
     * Otherwise if the [TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED] is
     * enabled, then a flash and a notification will be shown for the error as well
     * on the [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME] channel instead of just logging
     * the error.
     *
     * @param context The [Context] for operations.
     * @param logTag The log tag to use for logging.
     * @param executionCommand The [ExecutionCommand] that failed.
     * @param forceNotification If set to `true`, then a flash and notification will be shown
     *                          regardless of if pending intent is `null` or
     *                          [TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED]
     *                          is `false`.
     **/
    @JvmStatic
    fun processPluginExecutionCommandError(context: Context?, logTag: String?, executionCommand: ExecutionCommand?, forceNotification: Boolean) {
        var logTag = logTag
        var forceNotification = forceNotification
        if (context == null || executionCommand == null) {
            return
        }

        logTag = DataUtils.getDefaultIfNull(logTag, LOG_TAG)
        var error: Error?
        val resultData: ResultData = executionCommand.resultData

        if (!executionCommand.isStateFailed()) {
            Logger.logWarn(logTag, executionCommand.getCommandIdAndLabelLogString() + ": Ignoring call to processPluginExecutionCommandError() since the execution command is not in ExecutionState.FAILED")
            return
        }

        val isPluginExecutionCommandWithPendingResult: Boolean = executionCommand.isPluginExecutionCommandWithPendingResult()
        val isExecutionCommandLoggingEnabled: Boolean = Logger.shouldEnableLoggingForCustomLogLevel(executionCommand.backgroundCustomLogLevel)

        // Log the error and any exception. ResultData should not be logged if pending result since ResultSender will do it
        Logger.logErrorExtended(logTag, ExecutionCommand.getExecutionOutputLogString(executionCommand, true, !isPluginExecutionCommandWithPendingResult, isExecutionCommandLoggingEnabled))

        // If execution command was started by a plugin which expects the result back
        if (isPluginExecutionCommandWithPendingResult) {
            // Set variables which will be used by sendCommandResultData to send back the result
            if (executionCommand.resultConfig.resultPendingIntent != null) {
                setPluginResultPendingIntentVariables(executionCommand)
            }
            if (executionCommand.resultConfig.resultDirectoryPath != null) {
                setPluginResultDirectoryVariables(executionCommand)
            }

            // Send result to caller
            error = ResultSender.sendCommandResultData(context, logTag, executionCommand.getCommandIdAndLabelLogString(), executionCommand.resultConfig, executionCommand.resultData, isExecutionCommandLoggingEnabled)
            if (error != null) {
                // error will be added to existing Errors
                resultData.setStateFailed(error)
                Logger.logErrorExtended(logTag, ExecutionCommand.getExecutionOutputLogString(executionCommand, true, true, isExecutionCommandLoggingEnabled))
                forceNotification = true
            }

            // No need to show notifications if a pending intent was sent, let the caller handle the result himself
            if (!forceNotification) {
                return
            }
        }

        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context)
        if (preferences == null) {
            return
        }

        // If user has disabled notifications for plugin commands, then just return
        if (!preferences.arePluginErrorNotificationsEnabled() && !forceNotification) {
            return
        }

        // Flash and send notification for the error
        Logger.showToast(context, ResultData.getErrorsListMinimalString(resultData), true)
        sendPluginCommandErrorNotification(context, logTag, executionCommand, ResultData.getErrorsListMinimalString(resultData))
    }

    /** Set variables which will be used by [ResultSender.sendCommandResultData]
     * to send back the result via [ResultConfig.resultPendingIntent].*/
    @JvmStatic
    fun setPluginResultPendingIntentVariables(executionCommand: ExecutionCommand) {
        val resultConfig: ResultConfig = executionCommand.resultConfig

        resultConfig.resultBundleKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE
        resultConfig.resultStdoutKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT
        resultConfig.resultStdoutOriginalLengthKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH
        resultConfig.resultStderrKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR
        resultConfig.resultStderrOriginalLengthKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH
        resultConfig.resultExitCodeKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE
        resultConfig.resultErrCodeKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERR
        resultConfig.resultErrmsgKey = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG
    }

    /** Set variables which will be used by [ResultSender.sendCommandResultData]
     * to send back the result by writing it to files in [ResultConfig.resultDirectoryPath].*/
    @JvmStatic
    fun setPluginResultDirectoryVariables(executionCommand: ExecutionCommand) {
        val resultConfig: ResultConfig = executionCommand.resultConfig

        resultConfig.resultDirectoryPath = TermuxFileUtils.getCanonicalPath(resultConfig.resultDirectoryPath, null, true)
        resultConfig.resultDirectoryAllowedParentPath = TermuxFileUtils.getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(resultConfig.resultDirectoryPath)

        // Set default resultFileBasename if resultSingleFile is true to `<executable_basename>-<timestamp>.log`
        if (resultConfig.resultSingleFile && resultConfig.resultFileBasename == null) {
            resultConfig.resultFileBasename = ShellUtils.getExecutableBasename(executionCommand.executable) + "-" + AndroidUtils.getCurrentMilliSecondLocalTimeStamp() + ".log"
        }
    }

    /**
     * Send an error notification for [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID]
     * and [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME].
     *
     * @param context The [Context] for operations.
     * @param executionCommand The [ExecutionCommand] that failed.
     * @param notificationTextString The text of the notification.
     **/
    @JvmStatic
    fun sendPluginCommandErrorNotification(context: Context, logTag: String?, executionCommand: ExecutionCommand, notificationTextString: String) {
        // Send a notification to show the error which when clicked will open the ReportActivity
        // to show the details of the error
        val title: String = TermuxConstants.TERMUX_APP_NAME + " Plugin Execution Command Error"

        val reportString = StringBuilder()

        reportString.append(ExecutionCommand.getExecutionCommandMarkdownString(executionCommand))
        reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(context, true))
        reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context))

        val userActionName: String = UserAction.PLUGIN_EXECUTION_COMMAND.getName()
        val result: ReportActivity.NewInstanceResult = ReportActivity.newInstance(context, ReportInfo(userActionName, logTag, title, null, reportString.toString(), null, true, userActionName, "" + Environment.getExternalStorageDirectory() + "/" + FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)))
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
        setupPluginCommandErrorsNotificationChannel(context)

        // Use markdown in notification
        val notificationTextCharSequence: CharSequence? = MarkdownUtils.getSpannedMarkdownText(context, notificationTextString)
        //CharSequence notificationTextCharSequence = notificationTextString;

        // Build the notification
        val builder: Notification.Builder? = getPluginCommandErrorsNotificationBuilder(context, title, notificationTextCharSequence, notificationTextCharSequence, contentIntent, deleteIntent, NotificationUtils.NOTIFICATION_MODE_VIBRATE)
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
     * Get [Notification.Builder] for [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID]
     * and [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME].
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
    fun getPluginCommandErrorsNotificationBuilder(context: Context, title: CharSequence, notificationText: CharSequence?, notificationBigText: CharSequence?, contentIntent: PendingIntent, deleteIntent: PendingIntent?, notificationMode: Int): Notification.Builder? {
        val builder: Notification.Builder? = NotificationUtils.geNotificationBuilder(context, TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH, title, notificationText, notificationBigText, contentIntent, deleteIntent, notificationMode)

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
     * Setup the notification channel for [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID] and
     * [TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME].
     *
     * @param context The [Context] for operations.
     **/
    @JvmStatic
    fun setupPluginCommandErrorsNotificationChannel(context: Context) {
        NotificationUtils.setupNotificationChannel(context, TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID, TermuxConstants.TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
    }

    /**
     * Check if [TermuxConstants.PROP_ALLOW_EXTERNAL_APPS] property is not set to "true".
     *
     * @param context The [Context] to get error string.
     * @return Returns the `error` if policy is violated, otherwise `null`.
     **/
    @JvmStatic
    fun checkIfAllowExternalAppsPolicyIsViolated(context: Context?, apiName: String): String? {
        var errmsg: String? = null
        if (!SharedProperties.isPropertyValueTrue(context, TermuxPropertyConstants.getTermuxPropertiesFile(), TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, true)) {
            errmsg = context!!.getString(R.string.error_allow_external_apps_ungranted, apiName, TermuxFileUtils.getUnExpandedTermuxPath(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH))
        }

        return errmsg
    }
}
