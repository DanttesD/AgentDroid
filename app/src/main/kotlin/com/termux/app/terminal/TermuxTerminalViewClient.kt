package com.termux.app.terminal

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Environment
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ListView
import androidx.drawerlayout.widget.DrawerLayout
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.agent.ThemedDialog
import com.termux.app.models.UserAction
import com.termux.app.terminal.io.KeyboardShortcut
import com.termux.shared.activities.ReportActivity
import com.termux.shared.data.DataUtils
import com.termux.shared.data.UrlUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.settings.properties.TermuxPropertyConstants
import com.termux.shared.shell.ShellUtils
import com.termux.shared.terminal.TermuxTerminalViewClientBase
import com.termux.shared.terminal.io.extrakeys.SpecialButton
import com.termux.shared.termux.AndroidUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.view.KeyboardUtils
import com.termux.shared.view.ViewUtils
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import java.util.Arrays
import java.util.Collections

open class TermuxTerminalViewClient : TermuxTerminalViewClientBase {

    @JvmField val mActivity: TermuxActivity

    @JvmField val mTermuxTerminalSessionClient: TermuxTerminalSessionClient?

    /** Keeping track of the special keys acting as Ctrl and Fn for the soft keyboard and other hardware keys.*/
    @JvmField var mVirtualControlKeyDown: Boolean = false
    @JvmField var mVirtualFnKeyDown: Boolean = false

    private var mShowSoftKeyboardRunnable: Runnable? = null

    private var mShowSoftKeyboardIgnoreOnce: Boolean = false
    private var mShowSoftKeyboardWithDelayOnce: Boolean = false

    private var mTerminalCursorBlinkerStateAlreadySet: Boolean = false

    constructor(activity: TermuxActivity, termuxTerminalSessionClient: TermuxTerminalSessionClient?) : super() {
        this.mActivity = activity
        this.mTermuxTerminalSessionClient = termuxTerminalSessionClient
    }

    open fun getActivity(): TermuxActivity {
        return mActivity
    }

    /**
     * Should be called when mActivity.onCreate() is called
     **/
    open fun onCreate() {
        val migration: SharedPreferences = mActivity.getSharedPreferences("workspace-input", Context.MODE_PRIVATE)
        if (!migration.getBoolean("font-layout-reset-v17", false)) {
            mActivity.getPreferences()!!.setFontSize(TermuxAppSharedPreferences.getDefaultFontSizes(mActivity)[0])
            mActivity.getSharedPreferences("workspace-appearance", Context.MODE_PRIVATE).edit().remove("line-spacing-percent").apply()
            migration.edit().putBoolean("font-layout-reset-v17", true).apply()
        }
        mActivity.getTerminalView()!!.setTextSize(mActivity.getPreferences()!!.getFontSize())
        mActivity.getTerminalView()!!.setKeepScreenOn(mActivity.getPreferences()!!.shouldKeepScreenOn())
    }

    /**
     * Should be called when mActivity.onStart() is called
     **/
    open fun onStart() {
        // Set {@link TerminalView#TERMINAL_VIEW_KEY_LOGGING_ENABLED} value
        // Also required if user changed the preference from {@link TermuxSettings} activity and returns
        val isTerminalViewKeyLoggingEnabled: Boolean = mActivity.getPreferences()!!.isTerminalViewKeyLoggingEnabled()
        mActivity.getTerminalView()!!.setIsTerminalViewKeyLoggingEnabled(isTerminalViewKeyLoggingEnabled)

        // Piggyback on the terminal view key logging toggle for now, should add a separate toggle in future
        mActivity.getTermuxActivityRootView()!!.setIsRootViewLoggingEnabled(isTerminalViewKeyLoggingEnabled)
        ViewUtils.setIsViewUtilsLoggingEnabled(isTerminalViewKeyLoggingEnabled)
    }

    /**
     * Should be called when mActivity.onResume() is called
     **/
    open fun onResume() {
        // Show the soft keyboard if required
        setSoftKeyboardState(true, false)

        mTerminalCursorBlinkerStateAlreadySet = false

        if (mActivity.getTerminalView()!!.mEmulator != null) {
            // Start terminal cursor blinking if enabled
            // If emulator is already set, then start blinker now, otherwise wait for onEmulatorSet()
            // event to start it. This is needed since onEmulatorSet() may not be called after
            // TermuxActivity is started after device display timeout with double tap and not power button.
            setTerminalCursorBlinkerState(true)
            mTerminalCursorBlinkerStateAlreadySet = true
        }
    }

    /**
     * Should be called when mActivity.onStop() is called
     **/
    open fun onStop() {
        // Stop terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(false)
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     **/
    open fun onReload() {
        // Show the soft keyboard if required
        setSoftKeyboardState(false, true)

        // Start terminal cursor blinking if enabled
        setTerminalCursorBlinkerState(true)
    }

    /**
     * Should be called when [com.termux.view.TerminalView.mEmulator] is set
     **/
    override fun onEmulatorSet() {
        if (!mTerminalCursorBlinkerStateAlreadySet) {
            // Start terminal cursor blinking if enabled
            // We need to wait for the first session to be attached that's set in
            // TermuxActivity.onServiceConnected() and then the multiple calls to TerminalView.updateSize()
            // where the final one eventually sets the mEmulator when width/height is not 0. Otherwise
            // blinker will not start again if TermuxActivity is started again after exiting it with
            // double back press. Check TerminalView.setTerminalCursorBlinkerState().
            setTerminalCursorBlinkerState(true)
            mTerminalCursorBlinkerStateAlreadySet = true
        }
    }

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val increase: Boolean = scale > 1.0f
            changeFontSize(increase)
            return 1.0f
        }
        return scale
    }

    override fun onTerminalScrolled() {
        mActivity.workspaceTerminalScrolled()
    }

    override fun onTerminalSizeChanged() {
        mActivity.workspaceTerminalSizeChanged()
    }

    override fun onTerminalViewportChanged() {
        mActivity.workspaceTerminalViewportChanged()
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val term: TerminalEmulator? = mActivity.getCurrentSession()!!.getEmulator()

        if (mActivity.getProperties()!!.shouldOpenTerminalTranscriptURLOnClick()) {
            val columnAndRow: IntArray = mActivity.getTerminalView()!!.getColumnAndRow(e, true)
            val wordAtTap: String = term!!.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1])
            val urlSet: LinkedHashSet<CharSequence> = UrlUtils.extractUrls(wordAtTap)

            if (!urlSet.isEmpty()) {
                val url: String = urlSet.iterator().next() as String
                ShareUtils.openURL(mActivity, url)
                return
            }
        }

        if (showDialogTerminalKeyboard(e)) {
            return
        }

        if (!term!!.isMouseTrackingActive() && !e!!.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (mActivity.getTerminalView()!!.isSoftKeyboardInputEnabled() && !KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView())
            } else {
                Logger.logVerbose(LOG_TAG, "Not showing soft keyboard onSingleTapUp since its disabled")
            }
        }
    }

    override fun onTerminalMouseTap(e: MotionEvent?) {
        showDialogTerminalKeyboard(e)
    }

    private fun showDialogTerminalKeyboard(e: MotionEvent?): Boolean {
        // The agent terminal is reparented into a dialog. Native TUIs enable mouse
        // tracking; taps must still be able to open Android's keyboard in this window.
        val terminalView: View? = mActivity.getTerminalView()
        val dialogTerminal: Boolean = terminalView!!.getRootView() !== mActivity.getWindow().getDecorView().getRootView()
        if (dialogTerminal && mActivity.getTerminalView()!!.isSoftKeyboardInputEnabled() && !e!!.isFromSource(InputDevice.SOURCE_MOUSE)) {
            terminalView!!.requestFocus()
            val keyboard: InputMethodManager? = mActivity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            terminalView.post(Runnable { keyboard!!.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT) })
            return true
        }

        return false
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return mActivity.getProperties()!!.isBackKeyTheEscapeKey()
    }

    override fun shouldEnforceCharBasedInput(): Boolean {
        return mActivity.getProperties()!!.isEnforcingCharBasedInput()
    }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return mActivity.getProperties()!!.isUsingCtrlSpaceWorkaround()
    }

    override fun isTerminalViewSelected(): Boolean {
        return mActivity.getTerminalToolbarViewPager() == null || mActivity.isTerminalViewSelected() || mActivity.getTerminalView()!!.hasFocus()
    }

    override fun copyModeChanged(copyMode: Boolean) {
        // Disable drawer while copying.
        mActivity.getDrawer()!!.setDrawerLockMode(if (copyMode) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    @SuppressLint("RtlHardcoded")
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, currentSession: TerminalSession?): Boolean {
        if (handleVirtualKeys(keyCode, e, true)) {
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER && !currentSession!!.isRunning()) {
            mTermuxTerminalSessionClient!!.removeFinishedSession(currentSession)
            return true
        } else if (!mActivity.getProperties()!!.areHardwareKeyboardShortcutsDisabled() && e!!.isCtrlPressed() && e.isAltPressed()) {
            // Get the unmodified code point:
            val unicodeChar: Int = e.getUnicodeChar(0)

            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || unicodeChar == 'n'.code /* next */) {
                mTermuxTerminalSessionClient!!.switchToSession(true)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || unicodeChar == 'p'.code /* previous */) {
                mTermuxTerminalSessionClient!!.switchToSession(false)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                mActivity.getDrawer()!!.openDrawer(Gravity.LEFT)
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                mActivity.getDrawer()!!.closeDrawers()
            } else if (unicodeChar == 'k'.code /* keyboard */) {
                onToggleSoftKeyboardRequest()
            } else if (unicodeChar == 'm'.code /* menu */) {
                mActivity.getTerminalView()!!.showContextMenu()
            } else if (unicodeChar == 'r'.code /* rename */) {
                mTermuxTerminalSessionClient!!.renameSession(currentSession)
            } else if (unicodeChar == 'c'.code /* create */) {
                mTermuxTerminalSessionClient!!.addNewSession(false, null)
            } else if (unicodeChar == 'u'.code /* urls */) {
                showUrlSelection()
            } else if (unicodeChar == 'v'.code) {
                doPaste()
            } else if (unicodeChar == '+'.code || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+'.code) {
                // We also check for the shifted char here since shift may be required to produce '+',
                // see https://github.com/termux/termux-api/issues/2
                changeFontSize(true)
            } else if (unicodeChar == '-'.code) {
                changeFontSize(false)
            } else if (unicodeChar >= '1'.code && unicodeChar <= '9'.code) {
                val index: Int = unicodeChar - '1'.code
                mTermuxTerminalSessionClient!!.switchToSession(index)
            }
            return true
        }

        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
        // If emulator is not set, like if bootstrap installation failed and user dismissed the error
        // dialog, then just exit the activity, otherwise they will be stuck in a broken state.
        if (keyCode == KeyEvent.KEYCODE_BACK && mActivity.getTerminalView()!!.mEmulator == null) {
            mActivity.finishActivityIfNotFinishing()
            return true
        }

        return handleVirtualKeys(keyCode, e, false)
    }

    /** Handle dedicated volume buttons as virtual keys if applicable.*/
    private fun handleVirtualKeys(keyCode: Int, event: KeyEvent?, down: Boolean): Boolean {
        val inputDevice: InputDevice? = event!!.getDevice()
        if (mActivity.getProperties()!!.areVirtualVolumeKeysDisabled()) {
            return false
        } else if (inputDevice != null && inputDevice.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            // Do not steal dedicated buttons from a full external keyboard.
            return false
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            mVirtualControlKeyDown = down
            return true
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mVirtualFnKeyDown = down
            return true
        }
        return false
    }

    override fun readControlKey(): Boolean {
        return readExtraKeysSpecialButton(SpecialButton.CTRL) || mVirtualControlKeyDown
    }

    override fun readAltKey(): Boolean {
        return readExtraKeysSpecialButton(SpecialButton.ALT)
    }

    override fun readShiftKey(): Boolean {
        return readExtraKeysSpecialButton(SpecialButton.SHIFT)
    }

    override fun readFnKey(): Boolean {
        return readExtraKeysSpecialButton(SpecialButton.FN)
    }

    open fun readExtraKeysSpecialButton(specialButton: SpecialButton): Boolean {
        if (mActivity.getExtraKeysView() == null) {
            return false
        }
        val state: Boolean? = mActivity.getExtraKeysView()!!.readSpecialButton(specialButton, true)
        if (state == null) {
            Logger.logError(LOG_TAG, "Failed to read an unregistered " + specialButton + " special button value from extra keys.")
            return false
        }
        return state
    }

    override fun onLongPress(event: MotionEvent?): Boolean {
        return false
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (mVirtualFnKeyDown) {
            var resultingKeyCode = -1
            var resultingCodePoint = -1
            var altDown = false
            val lowerCase: Int = Character.toLowerCase(codePoint)
            when (lowerCase) {
                // Arrow keys.
                'w'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_UP
                }
                'a'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_LEFT
                }
                's'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_DOWN
                }
                'd'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT
                }

                // Page up and down.
                'p'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_UP
                }
                'n'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_PAGE_DOWN
                }

                // Some special keys:
                't'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_TAB
                }
                'i'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_INSERT
                }
                'h'.code -> {
                    resultingCodePoint = '~'.code
                }

                // Special characters to input.
                'u'.code -> {
                    resultingCodePoint = '_'.code
                }
                'l'.code -> {
                    resultingCodePoint = '|'.code
                }

                // Function keys.
                '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, '7'.code, '8'.code, '9'.code -> {
                    resultingKeyCode = codePoint - '1'.code + KeyEvent.KEYCODE_F1
                }
                '0'.code -> {
                    resultingKeyCode = KeyEvent.KEYCODE_F10
                }

                // Other special keys.
                'e'.code -> {
                    resultingCodePoint = /*Escape*/ 27
                }
                '.'.code -> {
                    resultingCodePoint = /*^.*/ 28
                }
                'b'.code, 'f'.code, 'x'.code -> {
                    // alt+b, jumping backward in readline.
                    // alf+f, jumping forward in readline.
                    // alt+x, common in emacs.
                    resultingCodePoint = lowerCase
                    altDown = true
                }

                // Volume control.
                'v'.code -> {
                    resultingCodePoint = -1
                    val audio: AudioManager? = mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
                    audio!!.adjustSuggestedStreamVolume(AudioManager.ADJUST_SAME, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
                }

                // Writing mode:
                'q'.code, 'k'.code -> {
                    mActivity.toggleTerminalToolbar()
                    mVirtualFnKeyDown = false // force disable fn key down to restore keyboard input into terminal view, fixes termux/termux-app#1420
                }
                else -> {}
            }

            if (resultingKeyCode != -1) {
                val term: TerminalEmulator? = session!!.getEmulator()
                session.write(KeyHandler.getCode(resultingKeyCode, 0, term!!.isCursorKeysApplicationMode(), term.isKeypadApplicationMode()))
            } else if (resultingCodePoint != -1) {
                session!!.writeCodePoint(altDown, resultingCodePoint)
            }
            return true
        } else if (ctrlDown) {
            if (codePoint == 106 /* Ctrl+j or \n */ && !session!!.isRunning()) {
                mTermuxTerminalSessionClient!!.removeFinishedSession(session)
                return true
            }

            val shortcuts: MutableList<KeyboardShortcut>? = mActivity.getProperties()!!.getSessionShortcuts()
            if (shortcuts != null && !shortcuts.isEmpty()) {
                val codePointLowerCase: Int = Character.toLowerCase(codePoint)
                for (i in shortcuts.size - 1 downTo 0) {
                    val shortcut: KeyboardShortcut = shortcuts.get(i)
                    if (codePointLowerCase == shortcut.codePoint) {
                        when (shortcut.shortcutAction) {
                            TermuxPropertyConstants.ACTION_SHORTCUT_CREATE_SESSION -> {
                                mTermuxTerminalSessionClient!!.addNewSession(false, null)
                                return true
                            }
                            TermuxPropertyConstants.ACTION_SHORTCUT_NEXT_SESSION -> {
                                mTermuxTerminalSessionClient!!.switchToSession(true)
                                return true
                            }
                            TermuxPropertyConstants.ACTION_SHORTCUT_PREVIOUS_SESSION -> {
                                mTermuxTerminalSessionClient!!.switchToSession(false)
                                return true
                            }
                            TermuxPropertyConstants.ACTION_SHORTCUT_RENAME_SESSION -> {
                                mTermuxTerminalSessionClient!!.renameSession(mActivity.getCurrentSession())
                                return true
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        return false
    }

    open fun changeFontSize(increase: Boolean) {
        mActivity.getPreferences()!!.changeFontSize(increase)
        mActivity.getTerminalView()!!.setTextSize(mActivity.getPreferences()!!.getFontSize())
    }

    /**
     * Called when user requests the soft keyboard to be toggled via "KEYBOARD" toggle button in
     * drawer or extra keys, or with ctrl+alt+k hardware keyboard shortcut.
     **/
    open fun onToggleSoftKeyboardRequest() {
        if (mActivity.prepareWorkspaceKeyboardWindow()) {
            mActivity.toggleWorkspaceKeyboard()
            return
        }
        if (!mActivity.getTerminalView()!!.isSoftKeyboardInputEnabled()) {
            mActivity.toggleWorkspaceKeyboard()
            return
        }
        // If soft keyboard toggle behaviour is enable/disabled
        if (mActivity.getProperties()!!.shouldEnableDisableSoftKeyboardOnToggle()) {
            // If soft keyboard is visible
            if (!KeyboardUtils.areDisableSoftKeyboardFlagsSet(mActivity)) {
                Logger.logVerbose(LOG_TAG, "Disabling soft keyboard on toggle")
                mActivity.getPreferences()!!.setSoftKeyboardEnabled(false)
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
            } else {
                // Show with a delay, otherwise pressing keyboard toggle won't show the keyboard after
                // switching back from another app if keyboard was previously disabled by user.
                // Also request focus, since it wouldn't have been requested at startup by
                // setSoftKeyboardState if keyboard was disabled. #2112
                Logger.logVerbose(LOG_TAG, "Enabling soft keyboard on toggle")
                mActivity.getPreferences()!!.setSoftKeyboardEnabled(true)
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
                if (mShowSoftKeyboardWithDelayOnce) {
                    mShowSoftKeyboardWithDelayOnce = false
                    mActivity.getTerminalView()!!.postDelayed(getShowSoftKeyboardRunnable(), 500L)
                    mActivity.getTerminalView()!!.requestFocus()
                } else {
                    KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView())
                }
            }
        } else {
            // If soft keyboard toggle behaviour is show/hide
            // If soft keyboard is disabled by user for Termux
            if (!mActivity.getPreferences()!!.isSoftKeyboardEnabled()) {
                Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard on toggle")
                KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
            } else {
                Logger.logVerbose(LOG_TAG, "Showing/Hiding soft keyboard on toggle")
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)
                KeyboardUtils.toggleSoftKeyboard(mActivity)
            }
        }
    }

    open fun setSoftKeyboardState(isStartup: Boolean, isReloadTermuxProperties: Boolean) {
        // Workspace fields always need an IME-capable activity window. Terminal tapping
        // is controlled separately by the Interactive input setting.
        if (mActivity.prepareWorkspaceKeyboardWindow()) {
            return
        }
        if (!mActivity.getTerminalView()!!.isSoftKeyboardInputEnabled()) {
            return
        }
        var noShowKeyboard = false

        // Requesting terminal view focus is necessary regardless of if soft keyboard is to be
        // disabled or hidden at startup, otherwise if hardware keyboard is attached and user
        // starts typing on hardware keyboard without tapping on the terminal first, then a colour
        // tint will be added to the terminal as highlight for the focussed view. Test with a light
        // theme. For android 8.+, the "defaultFocusHighlightEnabled" attribute is also set to false
        // in TerminalView layout to fix the issue.

        // If soft keyboard is disabled by user for Termux (check function docs for Termux behaviour info)
        if (KeyboardUtils.shouldSoftKeyboardBeDisabled(mActivity, mActivity.getPreferences()!!.isSoftKeyboardEnabled(), mActivity.getPreferences()!!.isSoftKeyboardEnabledOnlyIfNoHardware())) {
            Logger.logVerbose(LOG_TAG, "Maintaining disabled soft keyboard")
            KeyboardUtils.disableSoftKeyboard(mActivity, mActivity.getTerminalView())
            mActivity.getTerminalView()!!.requestFocus()
            noShowKeyboard = true
            // Delay is only required if onCreate() is called like when Termux app is exited with
            // double back press, not when Termux app is switched back from another app and keyboard
            // toggle is pressed to enable keyboard
            if (isStartup && mActivity.isOnResumeAfterOnCreate()) {
                mShowSoftKeyboardWithDelayOnce = true
            }
        } else {
            // Set flag to automatically push up TerminalView when keyboard is opened instead of showing over it
            KeyboardUtils.setSoftInputModeAdjustResize(mActivity)

            // Clear any previous flags to disable soft keyboard in case setting updated
            KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity)

            // If soft keyboard is to be hidden on startup
            if (isStartup && mActivity.getProperties()!!.shouldSoftKeyboardBeHiddenOnStartup()) {
                Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on startup")
                // Required to keep keyboard hidden when Termux app is switched back from another app
                KeyboardUtils.setSoftKeyboardAlwaysHiddenFlags(mActivity)

                KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView())
                mActivity.getTerminalView()!!.requestFocus()
                noShowKeyboard = true
                // Required to keep keyboard hidden on app startup
                mShowSoftKeyboardIgnoreOnce = true
            }
        }

        mActivity.getTerminalView()!!.setOnFocusChangeListener(object : View.OnFocusChangeListener {
            override fun onFocusChange(view: View, hasFocus: Boolean) {
                if (!mActivity.getTerminalView()!!.isSoftKeyboardInputEnabled()) {
                    return
                }
                // Force show soft keyboard if TerminalView or toolbar text input view has
                // focus and close it if they don't
                var textInputViewHasFocus = false
                val textInputView: EditText? = mActivity.findViewById(R.id.terminal_toolbar_text_input)
                if (textInputView != null) {
                    textInputViewHasFocus = textInputView.hasFocus()
                }

                if (hasFocus || textInputViewHasFocus) {
                    if (mShowSoftKeyboardIgnoreOnce) {
                        mShowSoftKeyboardIgnoreOnce = false
                        return
                    }
                    Logger.logVerbose(LOG_TAG, "Showing soft keyboard on focus change")
                } else {
                    Logger.logVerbose(LOG_TAG, "Hiding soft keyboard on focus change")
                }

                KeyboardUtils.setSoftKeyboardVisibility(getShowSoftKeyboardRunnable()!!, mActivity, mActivity.getTerminalView()!!, hasFocus || textInputViewHasFocus)
            }
        })

        // Do not force show soft keyboard if termux-reload-settings command was run with hardware keyboard
        // or soft keyboard is to be hidden or is disabled
        if (!isReloadTermuxProperties && !noShowKeyboard) {
            // Request focus for TerminalView
            // Also show the keyboard, since onFocusChange will not be called if TerminalView already
            // had focus on startup to show the keyboard, like when opening url with context menu
            // "Select URL" long press and returning to Termux app with back button. This
            // will also show keyboard even if it was closed before opening url. #2111
            Logger.logVerbose(LOG_TAG, "Requesting TerminalView focus and showing soft keyboard")
            mActivity.getTerminalView()!!.requestFocus()
            mActivity.getTerminalView()!!.postDelayed(getShowSoftKeyboardRunnable(), 300L)
        }
    }

    private fun getShowSoftKeyboardRunnable(): Runnable? {
        if (mShowSoftKeyboardRunnable == null) {
            mShowSoftKeyboardRunnable = Runnable {
                if (!mActivity.getTerminalView()!!.isSoftKeyboardInputEnabled()) {
                    return@Runnable
                }
                KeyboardUtils.showSoftKeyboard(mActivity, mActivity.getTerminalView())
            }
        }
        return mShowSoftKeyboardRunnable
    }

    open fun setTerminalCursorBlinkerState(start: Boolean) {
        if (start) {
            // If set/update the cursor blinking rate is successful, then enable cursor blinker
            if (mActivity.getTerminalView()!!.setTerminalCursorBlinkerRate(mActivity.getProperties()!!.getTerminalCursorBlinkRate())) {
                mActivity.getTerminalView()!!.setTerminalCursorBlinkerState(true, true)
            } else {
                Logger.logError(LOG_TAG, "Failed to start cursor blinker")
            }
        } else {
            // Disable cursor blinker
            mActivity.getTerminalView()!!.setTerminalCursorBlinkerState(false, true)
        }
    }

    open fun shareSessionTranscript() {
        val session: TerminalSession? = mActivity.getCurrentSession()
        if (session == null) {
            return
        }

        var transcriptText: String? = ShellUtils.getTerminalSessionTranscriptText(session, false, true)
        if (transcriptText == null) {
            return
        }

        // See https://github.com/termux/termux-app/issues/1166.
        transcriptText = (DataUtils.getTruncatedCommandOutput(transcriptText, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, false, true, false)!! as java.lang.String).trim()
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_transcript), transcriptText, mActivity.getString(R.string.title_share_transcript_with))
    }

    open fun shareSelectedText() {
        val selectedText: String? = mActivity.getTerminalView()!!.getStoredSelectedText()
        if (DataUtils.isNullOrEmpty(selectedText)) {
            return
        }
        ShareUtils.shareText(mActivity, mActivity.getString(R.string.title_share_selected_text), selectedText, mActivity.getString(R.string.title_share_selected_text_with))
    }

    open fun showUrlSelection() {
        val session: TerminalSession? = mActivity.getCurrentSession()
        if (session == null) {
            return
        }

        val text: String? = ShellUtils.getTerminalSessionTranscriptText(session, true, true)

        val urlSet: LinkedHashSet<CharSequence> = UrlUtils.extractUrls(text!!)
        if (urlSet.isEmpty()) {
            ThemedDialog(mActivity).setMessage(R.string.title_select_url_none_found).show()
            return
        }

        val urls: Array<CharSequence> = urlSet.toTypedArray()
        Collections.reverse(Arrays.asList(*urls)) // Latest first.

        // Click to copy url to clipboard:
        val dialog: AlertDialog = ThemedDialog(mActivity).setItems(urls, DialogInterface.OnClickListener { di, which ->
            val url: String = urls[which] as String
            ShareUtils.copyTextToClipboard(mActivity, url, mActivity.getString(R.string.msg_select_url_copied_to_clipboard))
        }).setTitle(R.string.title_select_url_dialog).create()

        // Long press to open URL:
        dialog.setOnShowListener(DialogInterface.OnShowListener { di ->
            val lv: ListView = dialog.getListView() // this is a ListView with your "buds" in it
            lv.setOnItemLongClickListener(AdapterView.OnItemLongClickListener { parent, view, position, id ->
                dialog.dismiss()
                val url: String = urls[position] as String
                ShareUtils.openURL(mActivity, url)
                return@OnItemLongClickListener true
            })
        })

        dialog.show()
    }

    open fun reportIssueFromTranscript() {
        val session: TerminalSession? = mActivity.getCurrentSession()
        if (session == null) {
            return
        }

        val transcriptText: String? = ShellUtils.getTerminalSessionTranscriptText(session, false, true)
        if (transcriptText == null) {
            return
        }

        MessageDialogUtils.showMessage(mActivity, TermuxConstants.TERMUX_APP_NAME + " Report Issue", mActivity.getString(R.string.msg_add_termux_debug_info), mActivity.getString(R.string.action_yes), DialogInterface.OnClickListener { dialog, which -> reportIssueFromTranscript(transcriptText, true) }, mActivity.getString(R.string.action_no), DialogInterface.OnClickListener { dialog, which -> reportIssueFromTranscript(transcriptText, false) }, null)
    }

    private fun reportIssueFromTranscript(transcriptText: String, addTermuxDebugInfo: Boolean) {
        Logger.showToast(mActivity, mActivity.getString(R.string.msg_generating_report), true)

        object : Thread() {
            override fun run() {
                val reportString = StringBuilder()

                val title: String = TermuxConstants.TERMUX_APP_NAME + " Report Issue"

                reportString.append("## Transcript\n")
                reportString.append("\n").append(MarkdownUtils.getMarkdownCodeForString(transcriptText, true))
                reportString.append("\n##\n")

                reportString.append("\n\n").append(TermuxUtils.getAppInfoMarkdownString(mActivity, true))
                reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(mActivity))

                val termuxAptInfo: String? = TermuxUtils.geAPTInfoMarkdownString(mActivity)
                if (termuxAptInfo != null) {
                    reportString.append("\n\n").append(termuxAptInfo)
                }

                if (addTermuxDebugInfo) {
                    val termuxDebugInfo: String? = TermuxUtils.getTermuxDebugMarkdownString(mActivity)
                    if (termuxDebugInfo != null) {
                        reportString.append("\n\n").append(termuxDebugInfo)
                    }
                }

                val userActionName: String = UserAction.REPORT_ISSUE_FROM_TRANSCRIPT.getName()
                ReportActivity.startReportActivity(mActivity, ReportInfo(userActionName, TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY_NAME, title, null, reportString.toString(), "\n\n" + TermuxUtils.getReportIssueMarkdownString(mActivity), false, userActionName, "" + Environment.getExternalStorageDirectory() + "/" + FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true)))
            }
        }.start()
    }

    open fun doPaste() {
        val session: TerminalSession? = mActivity.getCurrentSession()
        if (session == null) {
            return
        }
        if (!session.isRunning()) {
            return
        }

        val text: String? = ShareUtils.getTextStringFromClipboardIfSet(mActivity, true)
        if (text != null) {
            session.getEmulator()!!.paste(text)
        }
    }

    companion object {

        private const val LOG_TAG: String = "TermuxTerminalViewClient"
    }
}
