package com.termux.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import com.termux.app.settings.properties.TermuxAppSharedProperties
import com.termux.app.terminal.TermuxTerminalSessionClient
import com.termux.app.utils.PluginUtils
import com.termux.app.workspace.WorkspaceDiagnostics
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.models.errors.Errno
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.packages.PermissionUtils
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.shell.ShellUtils
import com.termux.shared.shell.TermuxSession
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.shell.TermuxTask
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalSession
import java.util.Locale

/**
 * A service holding a list of [TermuxSession] in [.mTermuxSessions] and background [TermuxTask]
 * in [.mTermuxTasks], showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through [TermuxActivity], but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart [TermuxActivity] later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, [Service.startForeground].
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * [.buildNotification].
 **/
class TermuxService : Service(), TermuxTask.TermuxTaskClient, TermuxSession.TermuxSessionClient {

    /** This service is only bound from inside the same process and never uses IPC.*/
    open inner class LocalBinder : Binder() {
        @JvmField val service: TermuxService = this@TermuxService
    }

    private val mBinder: IBinder = LocalBinder()

    private val mHandler: Handler = Handler()

    /**
     * The foreground TermuxSessions which this service manages.
     * Note that this list is observed by [TermuxActivity.mTermuxSessionListViewController],
     * so any changes must be made on the UI thread and followed by a call to
     * [ArrayAdapter.notifyDataSetChanged] }.
     **/
    @JvmField val mTermuxSessions: MutableList<TermuxSession> = ArrayList<TermuxSession>()

    /**
     * The background TermuxTasks which this service manages.
     **/
    @JvmField val mTermuxTasks: MutableList<TermuxTask> = ArrayList<TermuxTask>()

    /**
     * The pending plugin ExecutionCommands that have yet to be processed by this service.
     **/
    @JvmField val mPendingPluginExecutionCommands: MutableList<ExecutionCommand> = ArrayList<ExecutionCommand>()

    /** The full implementation of the [TerminalSessionClient] interface to be used by [TerminalSession]
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     **/
    @JvmField var mTermuxTerminalSessionClient: TermuxTerminalSessionClient? = null

    /** The basic implementation of the [TerminalSessionClient] interface to be used by [TerminalSession]
     * that does not hold activity references.
     **/
    @JvmField val mTermuxTerminalSessionClientBase: TermuxTerminalSessionClientBase = object : TermuxTerminalSessionClientBase() {
        override fun onTextChanged(session: TerminalSession?) {
            WorkspaceDiagnostics.onOutput(session)
            super.onTextChanged(session)
        }

        override fun onSessionFinished(session: TerminalSession?) {
            WorkspaceDiagnostics.finished(session)
            super.onSessionFinished(session)
        }
    }

    /** The wake lock and wifi lock are always acquired and released together.*/
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    /** If the user has executed the [TERMUX_SERVICE.ACTION_STOP_SERVICE] intent.*/
    @JvmField var mWantsToStop: Boolean = false

    @JvmField var mTerminalTranscriptRows: Int? = null

    override fun onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate")
        runStartForeground()
    }

    @SuppressLint("Wakelock")
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Logger.logDebug(LOG_TAG, "onStartCommand")

        // Run again in case service is already started and onCreate() is not called
        runStartForeground()

        val action: String? = intent.getAction()

        if (action != null) {
            when (action) {
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received")
                    actionStopService()
                }
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received")
                    actionAcquireWakeLock()
                }
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK -> {
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received")
                    actionReleaseWakeLock(true)
                }
                TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE -> {
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received")
                    actionServiceExecute(intent)
                }
                else -> {
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"")
                }
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy")

        TermuxShellUtils.clearTermuxTMPDIR(true)

        actionReleaseWakeLock(false)
        if (!mWantsToStop) {
            killAllTermuxExecutionCommands()
        }
        runStopForeground()
    }

    override fun onBind(intent: Intent): IBinder? {
        Logger.logVerbose(LOG_TAG, "onBind")
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Logger.logVerbose(LOG_TAG, "onUnbind")

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionClient != null) {
            unsetTermuxTerminalSessionClient()
        }
        return false
    }

    /** Make service run in foreground mode.*/
    private fun runStartForeground() {
        setupNotificationChannel()
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification())
    }

    /** Make service leave foreground mode.*/
    private fun runStopForeground() {
        stopForeground(true)
    }

    /** Request to stop service.*/
    private fun requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service")
        runStopForeground()
        stopSelf()
    }

    /** Process action to stop service.*/
    private fun actionStopService() {
        mWantsToStop = true
        killAllTermuxExecutionCommands()
        requestStopService()
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     **/
    @Synchronized
    private fun killAllTermuxExecutionCommands() {
        var processResult: Boolean

        Logger.logDebug(LOG_TAG, "Killing TermuxSessions=" + mTermuxSessions.size + ", TermuxTasks=" + mTermuxTasks.size + ", PendingPluginExecutionCommands=" + mPendingPluginExecutionCommands.size)

        val termuxSessions: MutableList<TermuxSession> = ArrayList<TermuxSession>(mTermuxSessions)
        var i = 0
        while (i < termuxSessions.size) {
            val executionCommand: ExecutionCommand = termuxSessions.get(i).getExecutionCommand()
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult()
            termuxSessions.get(i).killIfExecuting(this, processResult)
            i++
        }

        val termuxTasks: MutableList<TermuxTask> = ArrayList<TermuxTask>(mTermuxTasks)
        run {
            var i = 0
            while (i < termuxTasks.size) {
                val executionCommand: ExecutionCommand = termuxTasks.get(i).getExecutionCommand()
                if (executionCommand.isPluginExecutionCommandWithPendingResult()) {
                    termuxTasks.get(i).killIfExecuting(this, true)
                }
                i++
            }
        }

        val pendingPluginExecutionCommands: MutableList<ExecutionCommand> = ArrayList<ExecutionCommand>(mPendingPluginExecutionCommands)
        run {
            var i = 0
            while (i < pendingPluginExecutionCommands.size) {
                val executionCommand: ExecutionCommand = pendingPluginExecutionCommands.get(i)
                if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                    if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(R.string.error_execution_cancelled))) {
                        PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
                    }
                }
                i++
            }
        }
    }

    /** Process action to acquire Power and Wi-Fi WakeLocks.*/
    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held")
            return
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks")

        val pm: PowerManager? = getSystemService(Context.POWER_SERVICE) as PowerManager?
        mWakeLock = pm!!.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, (TermuxConstants.TERMUX_APP_NAME as java.lang.String).toLowerCase(Locale.ROOT) + ":service-wakelock")
        mWakeLock!!.acquire()

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        val wm: WifiManager? = getApplicationContext().getSystemService(Context.WIFI_SERVICE) as WifiManager?
        mWifiLock = wm!!.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, (TermuxConstants.TERMUX_APP_NAME as java.lang.String).toLowerCase(Locale.ROOT))
        mWifiLock!!.acquire()

        val packageName: String = getPackageName()
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val whitelist = Intent()
            whitelist.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            whitelist.setData(Uri.parse("package:" + packageName))
            whitelist.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                startActivity(whitelist)
            } catch (e: ActivityNotFoundException) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", e)
            }
        }

        updateNotification()

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully")
    }

    /** Process action to release Power and Wi-Fi WakeLocks.*/
    private fun actionReleaseWakeLock(updateNotification: Boolean) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held")
            return
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks")

        if (mWakeLock != null) {
            mWakeLock!!.release()
            mWakeLock = null
        }

        if (mWifiLock != null) {
            mWifiLock!!.release()
            mWifiLock = null
        }

        if (updateNotification) {
            updateNotification()
        }

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully")
    }

    /** Process [TERMUX_SERVICE.ACTION_SERVICE_EXECUTE] intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask.*/
    private fun actionServiceExecute(intent: Intent?) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute")
            return
        }

        val executionCommand = ExecutionCommand(getNextExecutionId())

        executionCommand.executableUri = intent.getData()
        executionCommand.inBackground = intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, false)

        if (executionCommand.executableUri != null) {
            executionCommand.executable = executionCommand.executableUri!!.getPath()
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, null) as Array<String?>?
            if (executionCommand.inBackground) {
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_STDIN, null)
            }
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null)
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, null)
        executionCommand.isFailsafe = intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
        executionCommand.sessionAction = intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION)
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command")
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null)
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_COMMAND_HELP, null)
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null)
        executionCommand.isPluginExecutionCommand = true
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_PENDING_INTENT)
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null)
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false)
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null)
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null)
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null)
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null)
        }

        // Add the execution command to pending plugin execution commands list
        mPendingPluginExecutionCommands.add(executionCommand)

        if (executionCommand.inBackground) {
            executeTermuxTaskCommand(executionCommand)
        } else {
            executeTermuxSessionCommand(executionCommand)
        }
    }

    /** Execute a shell command in background [TermuxTask].*/
    private fun executeTermuxTaskCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) {
            return
        }

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command")

        val newTermuxTask: TermuxTask? = createTermuxTask(executionCommand)
    }

    /** Create a [TermuxTask].*/
    fun createTermuxTask(executablePath: String, arguments: Array<String>, stdin: String, workingDirectory: String): TermuxTask? {
        return createTermuxTask(ExecutionCommand(getNextExecutionId(), executablePath, arguments as Array<String?>?, stdin, workingDirectory, true, false))
    }

    /** Create a [TermuxTask].*/
    @Synchronized
    fun createTermuxTask(executionCommand: ExecutionCommand?): TermuxTask? {
        if (executionCommand == null) {
            return null
        }

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask")

        if (!executionCommand.inBackground) {
            Logger.logDebug(LOG_TAG, "Ignoring a foreground execution command passed to createTermuxTask()")
            return null
        }

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) {
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())
        }

        val newTermuxTask: TermuxTask? = TermuxTask.execute(this, executionCommand, this, TermuxShellEnvironmentClient(), false)
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.getCommandIdAndLabelLogString())
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand) {
                PluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            } else {
                Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mTermuxTasks.add(newTermuxTask)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand) {
            mPendingPluginExecutionCommands.remove(executionCommand)
        }

        updateNotification()

        return newTermuxTask
    }

    /** Callback received when a [TermuxTask] finishes.*/
    override fun onTermuxTaskExited(termuxTask: TermuxTask) {
        mHandler.post(Runnable {
            if (termuxTask != null) {
                val executionCommand: ExecutionCommand? = termuxTask.getExecutionCommand()

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand!!.getCommandIdAndLabelLogString() + "\" TermuxTask command")

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand) {
                    PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
                }

                mTermuxTasks.remove(termuxTask)
            }

            updateNotification()
        })
    }

    /** Execute a shell command in a foreground [TermuxSession].*/
    private fun executeTermuxSessionCommand(executionCommand: ExecutionCommand?) {
        if (executionCommand == null) {
            return
        }

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command")

        var sessionName: String? = null

        // Transform executable path to session name, e.g. "/bin/do-something.sh" => "do something.sh".
        if (executionCommand.executable != null) {
            sessionName = (ShellUtils.getExecutableBasename(executionCommand.executable)!! as java.lang.String).replace('-', ' ')
        }

        val newTermuxSession: TermuxSession? = createTermuxSession(executionCommand, sessionName)
        if (newTermuxSession == null) {
            return
        }

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction, TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY), newTermuxSession.getTerminalSession())
    }

    /**
     * Create a [TermuxSession].
     * Currently called by [TermuxTerminalSessionClient.addNewSession] to add a new [TermuxSession].
     **/
    fun createTermuxSession(executablePath: String?, arguments: Array<String>?, stdin: String?, workingDirectory: String?, isFailSafe: Boolean, sessionName: String?): TermuxSession? {
        return createTermuxSession(ExecutionCommand(getNextExecutionId(), executablePath, arguments as Array<String?>?, stdin, workingDirectory, false, isFailSafe), sessionName)
    }

    /** Create a [TermuxSession].*/
    @Synchronized
    fun createTermuxSession(executionCommand: ExecutionCommand?, sessionName: String?): TermuxSession? {
        if (executionCommand == null) {
            return null
        }

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession")

        if (executionCommand.inBackground) {
            Logger.logDebug(LOG_TAG, "Ignoring a background execution command passed to createTermuxSession()")
            return null
        }

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE) {
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString())
        }

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        executionCommand.terminalTranscriptRows = getTerminalTranscriptRows()
        val newTermuxSession: TermuxSession? = TermuxSession.execute(this, executionCommand, getTermuxTerminalSessionClient()!!, this, TermuxShellEnvironmentClient(), sessionName, executionCommand.isPluginExecutionCommand)
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.getCommandIdAndLabelLogString())
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand) {
                PluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false)
            } else {
                Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            }
            return null
        }

        mTermuxSessions.add(newTermuxSession)

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand) {
            mPendingPluginExecutionCommands.remove(executionCommand)
        }

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mTermuxTerminalSessionClient != null) {
            mTermuxTerminalSessionClient!!.termuxSessionListNotifyUpdated()
        }

        updateNotification()
        TermuxActivity.updateTermuxActivityStyling(this)

        return newTermuxSession
    }

    /** Remove a TermuxSession.*/
    @Synchronized
    fun removeTermuxSession(sessionToRemove: TerminalSession?): Int {
        val index: Int = getIndexOfSession(sessionToRemove)

        if (index >= 0) {
            mTermuxSessions.get(index).finish()
        }

        return index
    }

    /** Callback received when a [TermuxSession] finishes.*/
    override fun onTermuxSessionExited(termuxSession: TermuxSession) {
        if (termuxSession != null) {
            val executionCommand: ExecutionCommand? = termuxSession.getExecutionCommand()

            Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand!!.getCommandIdAndLabelLogString() + "\" TermuxSession command")

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand) {
                PluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand)
            }

            mTermuxSessions.remove(termuxSession)

            // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mTermuxTerminalSessionClient != null) {
                mTermuxTerminalSessionClient!!.termuxSessionListNotifyUpdated()
            }
        }

        updateNotification()
    }

    /** Get the terminal transcript rows to be used for new [TermuxSession].*/
    fun getTerminalTranscriptRows(): Int? {
        if (mTerminalTranscriptRows == null) {
            setTerminalTranscriptRows()
        }
        return mTerminalTranscriptRows
    }

    fun setTerminalTranscriptRows() {
        // TermuxService only uses this termux property currently, so no need to load them all into
        // an internal values map like TermuxActivity does
        mTerminalTranscriptRows = TermuxAppSharedProperties.getTerminalTranscriptRows(this)
    }

    /** Process session action for new session.*/
    private fun handleSessionAction(sessionAction: Int, newTerminalSession: TerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"")

        when (sessionAction) {
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                if (mTermuxTerminalSessionClient != null) {
                    mTermuxTerminalSessionClient!!.setCurrentSession(newTerminalSession)
                }
                startTermuxActivity()
            }
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY -> {
                if (getTermuxSessionsSize() == 1) {
                    setCurrentStoredTerminalSession(newTerminalSession)
                }
                startTermuxActivity()
            }
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                setCurrentStoredTerminalSession(newTerminalSession)
                if (mTermuxTerminalSessionClient != null) {
                    mTermuxTerminalSessionClient!!.setCurrentSession(newTerminalSession)
                }
            }
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY -> {
                if (getTermuxSessionsSize() == 1) {
                    setCurrentStoredTerminalSession(newTerminalSession)
                }
            }
            else -> {
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.")
                handleSessionAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession)
            }
        }
    }

    /** Launch the {@link }TermuxActivity} to bring it to foreground.*/
    private fun startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this)
        } else {
            val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(this)
            if (preferences == null) {
                return
            }
            if (preferences.arePluginErrorNotificationsEnabled()) {
                Logger.showToast(this, this.getString(com.termux.R.string.error_display_over_other_apps_permission_not_granted), true)
            }
        }
    }

    /** If [TermuxActivity] has not bound to the [TermuxService] yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the [.mTermuxTerminalSessionClientBase]. Once [TermuxActivity] bind
     * callback is received, it should call [.setTermuxTerminalSessionClient] to set the
     * [TermuxService.mTermuxTerminalSessionClient] so that further terminal sessions are directly
     * passed the [TermuxTerminalSessionClient] object which fully implements the
     * [TerminalSessionClient] interface.
     *
     * @return Returns the [TermuxTerminalSessionClient] if [TermuxActivity] has bound with
     * [TermuxService], otherwise [TermuxTerminalSessionClientBase].
     **/
    @Synchronized
    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionClientBase? {
        if (mTermuxTerminalSessionClient != null) {
            return mTermuxTerminalSessionClient
        } else {
            return mTermuxTerminalSessionClientBase
        }
    }

    /** This should be called when [TermuxActivity.onServiceConnected] is called to set the
     * [TermuxService.mTermuxTerminalSessionClient] variable and update the [TerminalSession]
     * and [TerminalEmulator] clients in case they were passed [TermuxTerminalSessionClientBase]
     * earlier.
     *
     * @param termuxTerminalSessionClient The [TermuxTerminalSessionClient] object that fully
     * implements the [TerminalSessionClient] interface.
     **/
    @Synchronized
    fun setTermuxTerminalSessionClient(termuxTerminalSessionClient: TermuxTerminalSessionClient?) {
        mTermuxTerminalSessionClient = termuxTerminalSessionClient

        var i = 0
        while (i < mTermuxSessions.size) {
            mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionClient)
            i++
        }
    }

    /** This should be called when [TermuxActivity] has been destroyed and in [.onUnbind]
     * so that the [TermuxService] and [TerminalSession] and [TerminalEmulator]
     * clients do not hold an activity references.
     **/
    @Synchronized
    fun unsetTermuxTerminalSessionClient() {
        var i = 0
        while (i < mTermuxSessions.size) {
            mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionClientBase)
            i++
        }

        mTermuxTerminalSessionClient = null
    }

    private fun buildNotification(): Notification? {
        val res: Resources = getResources()

        // Set pending intent to be launched when notification is clicked
        val notificationIntent: Intent = TermuxActivity.newInstance(this)
        val contentIntent: PendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Set notification text
        val sessionCount: Int = getTermuxSessionsSize()
        val taskCount: Int = mTermuxTasks.size
        var notificationText: String = sessionCount.toString() + " session" + (if (sessionCount == 1) "" else "s")
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (if (taskCount == 1) "" else "s")
        }

        val wakeLockHeld: Boolean = mWakeLock != null
        if (wakeLockHeld) {
            notificationText += " (wake lock held)"
        }

        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        val priority: Int = if (wakeLockHeld) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW

        // Build the notification
        val builder: Notification.Builder? = NotificationUtils.geNotificationBuilder(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority, TermuxConstants.TERMUX_APP_NAME, notificationText, null, contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT)
        if (builder == null) {
            return null
        }

        // No need to show a timestamp:
        builder.setShowWhen(false)

        // Set notification icon
        builder.setSmallIcon(com.termux.R.drawable.ic_service_notification)

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B.toInt())

        // TermuxSessions are always ongoing
        builder.setOngoing(true)

        // Set Exit button action
        val exitIntent: Intent = Intent(this, TermuxService::class.java).setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE)
        builder.addAction(android.R.drawable.ic_delete, res.getString(com.termux.R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, PendingIntent.FLAG_IMMUTABLE))

        // Set Wakelock button actions
        val newWakeAction: String = if (wakeLockHeld) TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_UNLOCK else TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_WAKE_LOCK
        val toggleWakeLockIntent: Intent = Intent(this, TermuxService::class.java).setAction(newWakeAction)
        val actionTitle: String = res.getString(if (wakeLockHeld) com.termux.R.string.notification_action_wake_unlock else com.termux.R.string.notification_action_wake_lock)
        val actionIcon: Int = if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, PendingIntent.FLAG_IMMUTABLE))

        return builder.build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
    }

    /** Update the shown foreground service notification after making any changes that affect it.*/
    @Synchronized
    private fun updateNotification() {
        if (mWakeLock == null && mTermuxSessions.isEmpty() && mTermuxTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService()
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)!!.notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification())
        }
    }

    private fun setCurrentStoredTerminalSession(session: TerminalSession?) {
        if (session == null) {
            return
        }
        // Make the newly created session the current one to be displayed
        val preferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(this)
        if (preferences == null) {
            return
        }
        preferences.setCurrentSession(session.mHandle)
    }

    @Synchronized
    fun isTermuxSessionsEmpty(): Boolean {
        return mTermuxSessions.isEmpty()
    }

    @Synchronized
    fun getTermuxSessionsSize(): Int {
        return mTermuxSessions.size
    }

    @Synchronized
    fun getTermuxSessions(): MutableList<TermuxSession> {
        return mTermuxSessions
    }

    @Synchronized
    fun getTermuxSession(index: Int): TermuxSession? {
        if (index >= 0 && index < mTermuxSessions.size) {
            return mTermuxSessions.get(index)
        } else {
            return null
        }
    }

    @Synchronized
    fun getLastTermuxSession(): TermuxSession? {
        return if (mTermuxSessions.isEmpty()) null else mTermuxSessions.get(mTermuxSessions.size - 1)
    }

    @Synchronized
    fun getIndexOfSession(terminalSession: TerminalSession?): Int {
        var i = 0
        while (i < mTermuxSessions.size) {
            if (mTermuxSessions.get(i).getTerminalSession().equals(terminalSession)) {
                return i
            }
            i++
        }
        return -1
    }

    @Synchronized
    fun getTerminalSessionForHandle(sessionHandle: String): TerminalSession? {
        var terminalSession: TerminalSession
        var i = 0
        val len: Int = mTermuxSessions.size
        while (i < len) {
            terminalSession = mTermuxSessions.get(i).getTerminalSession()
            if (terminalSession.mHandle.equals(sessionHandle)) {
                return terminalSession
            }
            i++
        }
        return null
    }

    fun wantsToStop(): Boolean {
        return mWantsToStop
    }

    companion object {

        private var EXECUTION_ID: Int = 1000

        private const val LOG_TAG: String = "TermuxService"

        @Synchronized
        @JvmStatic
        fun getNextExecutionId(): Int {
            return EXECUTION_ID++
        }
    }
}
