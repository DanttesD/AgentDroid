package com.termux.app.terminal

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.text.TextUtils
import android.widget.ListView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.app.agent.ThemedDialog
import com.termux.app.workspace.WorkspaceDiagnostics
import com.termux.shared.interact.ShareUtils
import com.termux.shared.interact.TextInputDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.settings.properties.TermuxPropertyConstants
import com.termux.shared.shell.TermuxSession
import com.termux.shared.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.terminal.io.BellHandler
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import java.io.File
import java.io.FileInputStream
import java.util.Properties

open class TermuxTerminalSessionClient : TermuxTerminalSessionClientBase {

    private val mActivity: TermuxActivity

    private var mBellSoundPool: SoundPool? = null

    private var mBellSoundId: Int = 0

    constructor(activity: TermuxActivity) : super() {
        this.mActivity = activity
    }

    /**
     * Should be called when mActivity.onCreate() is called
     **/
    open fun onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    /**
     * Should be called when mActivity.onStart() is called
     **/
    open fun onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast())
            termuxSessionListNotifyUpdated()
        }

        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.getTerminalView()!!.onScreenUpdated()
    }

    /**
     * Should be called when mActivity.onResume() is called
     **/
    open fun onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        getBellSoundPool()
    }

    /**
     * Should be called when mActivity.onStop() is called
     **/
    open fun onStop() {
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession()

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool()
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     **/
    open fun onReload() {
        // Set terminal fonts and colors
        checkForFontAndColors()
    }

    override fun onTextChanged(changedSession: TerminalSession?) {
        WorkspaceDiagnostics.onOutput(changedSession)
        mActivity.workspaceSessionOutput(changedSession)
        if (!mActivity.isVisible()) {
            return
        }

        if (mActivity.getCurrentSession() === changedSession) {
            mActivity.getTerminalView()!!.onScreenUpdated()
        }
    }

    override fun onTitleChanged(updatedSession: TerminalSession?) {
        if (!mActivity.isVisible()) {
            return
        }

        if (updatedSession !== mActivity.getCurrentSession()) {
            mActivity.showToast(toToastTitle(updatedSession), true)
        }

        mActivity.termuxSessionTitleNotifyUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        if (mActivity.onWorkspaceSessionFinished(finishedSession)) {
            return
        }
        mActivity.refreshWorkspace()
        val service: TermuxService? = mActivity.getTermuxService()

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing()
            return
        }

        val index: Int = service.getIndexOfSession(finishedSession)

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        var isPluginExecutionCommandWithPendingResult = false
        val termuxSession: TermuxSession? = service.getTermuxSession(index)
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult()
            if (isPluginExecutionCommandWithPendingResult) {
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession!!.mSessionName + "\" session will be force finished automatically since result in pending.")
            }
        }

        if (mActivity.isVisible() && finishedSession !== mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0) {
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true)
            }
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            if (finishedSession!!.getExitStatus() == 0 || finishedSession.getExitStatus() == 130 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession)
            }
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        if (!mActivity.isVisible()) {
            return
        }

        ShareUtils.copyTextToClipboard(mActivity, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (!mActivity.isVisible()) {
            return
        }

        val text: String? = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null) {
            mActivity.getTerminalView()!!.mEmulator!!.paste(text)
        }
    }

    override fun onBell(session: TerminalSession?) {
        if (!mActivity.isVisible()) {
            return
        }

        when (mActivity.getProperties()!!.getBellBehaviour()) {
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE -> {
                BellHandler.getInstance(mActivity)!!.doBell()
            }
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP -> {
                getBellSoundPool()!!.play(mBellSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            }
            TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE -> {
                // Ignore the bell character.
            }
            else -> {}
        }
    }

    override fun onColorsChanged(changedSession: TerminalSession?) {
        if (mActivity.getCurrentSession() === changedSession) {
            updateBackgroundColor()
        }
    }

    override fun onTerminalCursorStateChange(enabled: Boolean) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible")
            return
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        mActivity.getTerminalView()!!.setTerminalCursorBlinkerState(enabled, false)
    }

    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     **/
    open fun onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        mActivity.getTerminalView()!!.setTerminalCursorBlinkerState(true, true)
    }

    override fun getTerminalCursorStyle(): Int? {
        return mActivity.getProperties()!!.getTerminalCursorStyle()
    }

    /** Initialize and get mBellSoundPool*/
    @Synchronized
    private fun getBellSoundPool(): SoundPool? {
        if (mBellSoundPool == null) {
            mBellSoundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()

            mBellSoundId = mBellSoundPool!!.load(mActivity, R.raw.bell, 1)
        }

        return mBellSoundPool
    }

    /** Release mBellSoundPool resources*/
    @Synchronized
    private fun releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool!!.release()
            mBellSoundPool = null
        }
    }

    /** Try switching to session.*/
    open fun setCurrentSession(session: TerminalSession?) {
        if (session == null) {
            return
        }

        if (mActivity.getTerminalView()!!.attachSession(session)) {
            // notify about switched session if not already displaying the session
            notifyOfSessionChange()
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session)
        updateBackgroundColor()
        mActivity.refreshWorkspace()
    }

    open fun notifyOfSessionChange() {
        if (!mActivity.isVisible()) {
            return
        }

        if (!mActivity.getProperties()!!.areTerminalSessionChangeToastsDisabled()) {
            val session: TerminalSession? = mActivity.getCurrentSession()
            mActivity.showToast(toToastTitle(session), false)
        }
    }

    open fun switchToSession(forward: Boolean) {
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return
        }

        val currentTerminalSession: TerminalSession? = mActivity.getCurrentSession()
        var index: Int = service.getIndexOfSession(currentTerminalSession)
        val size: Int = service.getTermuxSessionsSize()
        if (forward) {
            if (++index >= size) {
                index = 0
            }
        } else {
            if (--index < 0) {
                index = size - 1
            }
        }

        val termuxSession: TermuxSession? = service.getTermuxSession(index)
        if (termuxSession != null) {
            setCurrentSession(termuxSession.getTerminalSession())
        }
    }

    open fun switchToSession(index: Int) {
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return
        }

        val termuxSession: TermuxSession? = service.getTermuxSession(index)
        if (termuxSession != null) {
            setCurrentSession(termuxSession.getTerminalSession())
        }
    }

    @SuppressLint("InflateParams")
    open fun renameSession(sessionToRename: TerminalSession?) {
        if (sessionToRename == null) {
            return
        }

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, TextInputDialogUtils.TextSetListener { text ->
            sessionToRename.mSessionName = text
            termuxSessionListNotifyUpdated()
        }, -1, null, -1, null, null)
    }

    open fun addNewSession(isFailSafe: Boolean, sessionName: String?) {
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return
        }

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            ThemedDialog(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached).setPositiveButton(android.R.string.ok, null).show()
        } else {
            val currentSession: TerminalSession? = mActivity.getCurrentSession()

            var workingDirectory: String?
            if (currentSession == null) {
                workingDirectory = mActivity.getProperties()!!.getDefaultWorkingDirectory()
            } else {
                workingDirectory = currentSession.getCwd()
            }

            val newTermuxSession: TermuxSession? = if (isFailSafe) service.createTermuxSession(null, null, null, workingDirectory, true, sessionName) else mActivity.createWorkspaceSession(sessionName, workingDirectory)
            if (newTermuxSession == null) {
                return
            }

            val newTerminalSession: TerminalSession = newTermuxSession.getTerminalSession()
            setCurrentSession(newTerminalSession)

            mActivity.getDrawer()!!.closeDrawers()
        }
    }

    open fun setCurrentStoredSession() {
        val currentSession: TerminalSession? = mActivity.getCurrentSession()
        if (currentSession != null) {
            mActivity.getPreferences()!!.setCurrentSession(currentSession.mHandle)
        } else {
            mActivity.getPreferences()!!.setCurrentSession(null)
        }
    }

    /** The current session as stored or the last one if that does not exist.*/
    open fun getCurrentStoredSessionOrLast(): TerminalSession? {
        val stored: TerminalSession? = getCurrentStoredSession()

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored
        } else {
            // Else return the last session currently running
            val service: TermuxService? = mActivity.getTermuxService()
            if (service == null) {
                return null
            }

            val termuxSession: TermuxSession? = service.getLastTermuxSession()
            if (termuxSession != null) {
                return termuxSession.getTerminalSession()
            } else {
                return null
            }
        }
    }

    private fun getCurrentStoredSession(): TerminalSession? {
        val sessionHandle: String? = mActivity.getPreferences()!!.getCurrentSession()

        // If no session is stored in shared preferences
        if (sessionHandle == null) {
            return null
        }

        // Check if the session handle found matches one of the currently running sessions
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return null
        }

        return service.getTerminalSessionForHandle(sessionHandle)
    }

    open fun removeFinishedSession(finishedSession: TerminalSession?) {
        if (mActivity.isWorkspaceBaseTerminal(finishedSession)) {
            return
        }
        // Return pressed with finished session - remove it.
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return
        }

        var index: Int = service.removeTermuxSession(finishedSession)

        val size: Int = service.getTermuxSessionsSize()
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing()
        } else {
            if (index >= size) {
                index = size - 1
            }
            val termuxSession: TermuxSession? = service.getTermuxSession(index)
            if (termuxSession != null) {
                setCurrentSession(termuxSession.getTerminalSession())
            }
        }
    }

    open fun termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated()
    }

    open fun checkAndScrollToSession(session: TerminalSession) {
        if (!mActivity.isVisible()) {
            return
        }
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return
        }

        val indexOfSession: Int = service.getIndexOfSession(session)
        if (indexOfSession < 0) {
            return
        }
        val termuxSessionsListView: ListView? = mActivity.findViewById(R.id.terminal_sessions_list)
        if (termuxSessionsListView == null) {
            return
        }

        termuxSessionsListView.setItemChecked(indexOfSession, true)
        // Delay is necessary otherwise sometimes scroll to newly added session does not happen
        termuxSessionsListView.postDelayed(Runnable { termuxSessionsListView.smoothScrollToPosition(indexOfSession) }, 1000L)
    }

    open fun toToastTitle(session: TerminalSession?): String? {
        val service: TermuxService? = mActivity.getTermuxService()
        if (service == null) {
            return null
        }

        val indexOfSession: Int = service.getIndexOfSession(session)
        if (indexOfSession < 0) {
            return null
        }
        val toastTitle = StringBuilder("[" + (indexOfSession + 1) + "]")
        if (!TextUtils.isEmpty(session!!.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName)
        }
        val title: String? = session.getTitle()
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(if (session.mSessionName == null) " " else "\n")
            toastTitle.append(title)
        }
        return toastTitle.toString()
    }

    open fun checkForFontAndColors() {
        try {
            val colorsFile: File = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
            val fontFile: File = TermuxConstants.TERMUX_FONT_FILE

            val props = Properties()
            if (colorsFile.isFile()) {
                (FileInputStream(colorsFile)).use { `in` ->
                    props.load(`in`)
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props)
            val session: TerminalSession? = mActivity.getCurrentSession()
            if (session != null && session.getEmulator() != null) {
                session.getEmulator()!!.mColors.reset()
            }
            updateBackgroundColor()

            val newTypeface: Typeface = if (fontFile.exists() && fontFile.length() > 0) Typeface.createFromFile(fontFile) else Typeface.MONOSPACE
            mActivity.getTerminalView()!!.setTypeface(newTypeface)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e)
        }
    }

    open fun updateBackgroundColor() {
        if (!mActivity.isVisible()) {
            return
        }
        val session: TerminalSession? = mActivity.getCurrentSession()
        if (session != null && session.getEmulator() != null) {
            mActivity.getWindow().getDecorView().setBackgroundColor(session.getEmulator()!!.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        }
    }

    companion object {

        private const val MAX_SESSIONS: Int = 8

        private const val LOG_TAG: String = "TermuxTerminalSessionClient"
    }
}
