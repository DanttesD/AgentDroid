package com.termux.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.ContextMenu
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager.widget.ViewPager
import com.termux.R
import com.termux.app.activities.HelpActivity
import com.termux.app.activities.SettingsActivity
import com.termux.app.agent.AppTheme
import com.termux.app.agent.ThemedDialog
import com.termux.app.settings.properties.TermuxAppSharedProperties
import com.termux.app.terminal.TermuxActivityRootView
import com.termux.app.terminal.TermuxSessionsListViewController
import com.termux.app.terminal.TermuxTerminalSessionClient
import com.termux.app.terminal.TermuxTerminalViewClient
import com.termux.app.terminal.io.TerminalToolbarViewPager
import com.termux.app.utils.CrashUtils
import com.termux.app.workspace.Pal
import com.termux.app.workspace.WorkspaceController
import com.termux.app.workspace.WorkspaceGalleryPicker
import com.termux.app.workspace.WorkspaceLanguage
import com.termux.app.workspace.WorkspaceStorageAccess
import com.termux.shared.activities.ReportActivity
import com.termux.shared.data.DataUtils
import com.termux.shared.interact.TextInputDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.packages.PermissionUtils
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.shell.TermuxSession
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.view.ViewUtils
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.util.function.Consumer

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 **/
class TermuxActivity : Activity(), ServiceConnection {

    /**
     * The connection to the [TermuxService]. Requested in [.onCreate] with a call to
     * [.bindService], and obtained and stored in
     * [.onServiceConnected].
     **/
    @JvmField var mTermuxService: TermuxService? = null

    private var mWorkspace: WorkspaceController? = null

    fun prepareWorkspaceKeyboardWindow(): Boolean {
        if (mWorkspace == null) {
            return false
        }
        mWorkspace!!.prepareKeyboardWindow()
        return true
    }

    fun toggleWorkspaceKeyboard() {
        if (mWorkspace != null) {
            mWorkspace!!.toggleKeyboard()
        }
    }

    /**
     * The [TerminalView] shown in  [TermuxActivity] that displays the terminal.
     **/
    @JvmField var mTerminalView: TerminalView? = null

    /**
     *  The [TerminalViewClient] interface implementation to allow for communication between
     *  [TerminalView] and [TermuxActivity].
     **/
    @JvmField var mTermuxTerminalViewClient: TermuxTerminalViewClient? = null

    /**
     *  The [TerminalSessionClient] interface implementation to allow for communication between
     *  [TerminalSession] and [TermuxActivity].
     **/
    @JvmField var mTermuxTerminalSessionClient: TermuxTerminalSessionClient? = null

    /**
     * Termux app shared preferences manager.
     **/
    private var mPreferences: TermuxAppSharedPreferences? = null

    /**
     * Termux app shared properties manager, loaded from termux.properties
     **/
    private var mProperties: TermuxAppSharedProperties? = null

    /**
     * The root view of the [TermuxActivity].
     **/
    @JvmField var mTermuxActivityRootView: TermuxActivityRootView? = null

    /**
     * The space at the bottom of [@mTermuxActivityRootView] of the [TermuxActivity].
     **/
    @JvmField var mTermuxActivityBottomSpaceView: View? = null

    /**
     * The terminal extra keys view.
     **/
    @JvmField var mExtraKeysView: ExtraKeysView? = null

    /**
     * The termux sessions list controller.
     **/
    @JvmField var mTermuxSessionListViewController: TermuxSessionsListViewController? = null

    /**
     * The [TermuxActivity] broadcast receiver for various things like terminal style configuration changes.
     **/
    private val mTermuxActivityBroadcastReceiver: BroadcastReceiver = TermuxActivityBroadcastReceiver()

    /**
     * The last toast shown, used cancel current toast before showing new in [.showToast].
     **/
    @JvmField var mLastToast: Toast? = null

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     **/
    private var mIsVisible: Boolean = false

    /**
     * If onResume() was called after onCreate().
     **/
    private var isOnResumeAfterOnCreate: Boolean = false

    /**
     * The [TermuxActivity] is in an invalid state and must not be run.
     **/
    private var mIsInvalidState: Boolean = false

    private var mNavBarHeight: Int = 0

    private var mTerminalToolbarDefaultHeight: Int = 0

    protected override fun attachBaseContext(base: Context) {
        super.attachBaseContext(WorkspaceLanguage.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Locale wrapping must not leave orientation/window bounds at launch values.
        // Keep the Activity and its existing sessions; no workspace/composer rebuild.
        WorkspaceLanguage.configurationChanged(this, newConfig)
        AppTheme.applyTextScale(this)
    }

    private var themeSig: String = ""

    /** Handles the "agentdroid_screen" deep link; one-shot actions are consumed so a recreate does not repeat them.*/
    private fun openWorkspaceScreen(intent: Intent) {
        mWorkspace!!.backLabel(intent.getStringExtra("agentdroid_from"))
        val screen: String? = intent.getStringExtra("agentdroid_screen")
        if (screen != null && screen.startsWith("settings:")) {
            intent.removeExtra("agentdroid_screen")
        }
        mWorkspace!!.openScreen(screen)
    }

    protected override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (mWorkspace != null) {
            openWorkspaceScreen(intent)
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        Logger.logDebug(LOG_TAG, "onCreate")
        isOnResumeAfterOnCreate = true

        // Check if a crash happened on last run of the app and show a
        // notification with the crash details if it did
        CrashUtils.notifyAppCrashOnLastRun(this, LOG_TAG)

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false)

        // Load termux shared properties
        mProperties = TermuxAppSharedProperties(this)

        setActivityTheme()

        WorkspaceLanguage.initialize(this)
        AppTheme.applyTextScale(this)
        Pal.init(this)
        themeSig = AppTheme.signature(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_termux)

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true)
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true
            return
        }

        setMargins()

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view)
        mTermuxActivityRootView!!.setActivity(this)
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view)
        mTermuxActivityRootView!!.setOnApplyWindowInsetsListener(TermuxActivityRootView.WindowInsetsListener())

        val content: View? = findViewById(android.R.id.content)
        content!!.setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener { v, insets ->
            mNavBarHeight = insets.getSystemWindowInsetBottom()
            return@OnApplyWindowInsetsListener insets
        })

        if (mProperties!!.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setDrawerTheme()

        setTermuxTerminalViewAndClients()

        setTerminalToolbarView(savedInstanceState)

        setSettingsButtonView()

        setNewSessionButtonView()

        setToggleKeyboardView()

        mWorkspace = WorkspaceController(this)
        openWorkspaceScreen(getIntent())

        registerForContextMenu(mTerminalView)

        // Start the {@link TermuxService} and make it run regardless of who is bound to it
        val serviceIntent = Intent(this, TermuxService::class.java)
        startService(serviceIntent)

        // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
        // callback if it succeeds.
        if (!bindService(serviceIntent, this, 0)) {
            throw RuntimeException("bindService() failed")
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this)
    }

    public override fun onStart() {
        super.onStart()

        Logger.logDebug(LOG_TAG, "onStart")

        if (mIsInvalidState) {
            return
        }

        mIsVisible = true

        if (mTermuxTerminalSessionClient != null) {
            mTermuxTerminalSessionClient!!.onStart()
        }

        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient!!.onStart()
        }

        if (mPreferences!!.isTerminalMarginAdjustmentEnabled()) {
            addTermuxActivityRootViewGlobalLayoutListener()
        }

        registerTermuxActivityBroadcastReceiver()
    }

    public override fun onResume() {
        super.onResume()

        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }

        Logger.logVerbose(LOG_TAG, "onResume")

        if (mIsInvalidState) {
            return
        }

        if (mTermuxTerminalSessionClient != null) {
            mTermuxTerminalSessionClient!!.onResume()
        }

        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient!!.onResume()
        }

        if (mWorkspace != null) {
            mWorkspace!!.onResume()
        }
        isOnResumeAfterOnCreate = false
    }

    protected override fun onStop() {
        super.onStop()

        Logger.logDebug(LOG_TAG, "onStop")

        if (mIsInvalidState) {
            return
        }

        if (mWorkspace != null) {
            mWorkspace!!.onStop()
        }
        mIsVisible = false

        if (mTermuxTerminalSessionClient != null) {
            mTermuxTerminalSessionClient!!.onStop()
        }

        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient!!.onStop()
        }

        removeTermuxActivityRootViewGlobalLayoutListener()

        unregisterTermuxActivityBroadcastReceiever()
        getDrawer()!!.closeDrawers()
    }

    public override fun onDestroy() {
        super.onDestroy()

        Logger.logDebug(LOG_TAG, "onDestroy")
        if (mWorkspaceStorageAccess != null) {
            mWorkspaceStorageAccess!!.close()
        }
        if (mWorkspaceGalleryPicker != null) {
            mWorkspaceGalleryPicker!!.close()
        }
        if (mWorkspace != null) {
            mWorkspace!!.close()
        }

        if (mIsInvalidState) {
            return
        }

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService!!.unsetTermuxTerminalSessionClient()
            mTermuxService = null
        }

        try {
            unbindService(this)
        } catch (e: Exception) {
            // ignore.
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        saveTerminalToolbarTextInput(savedInstanceState)
    }

    /**
     * Part of the [ServiceConnection] interface. The service is bound with
     * [.bindService] in [.onCreate] which will cause a call to this
     * callback method.
     **/
    override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
        Logger.logDebug(LOG_TAG, "onServiceConnected")

        mTermuxService = (service as TermuxService.LocalBinder).service

        setTermuxSessionsListView()

        if (mTermuxService!!.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(this@TermuxActivity, Runnable {
                    if (mTermuxService == null) {
                        return@Runnable
                    } // Activity might have been destroyed.
                    try {
                        val bundle: Bundle? = getIntent().getExtras()
                        var launchFailsafe = false
                        if (bundle != null) {
                            launchFailsafe = bundle.getBoolean(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                        }
                        mTermuxTerminalSessionClient!!.addNewSession(launchFailsafe, null)
                    } catch (e: WindowManager.BadTokenException) {
                        // Activity finished - ignore.
                    }
                })
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing()
            }
        } else {
            val i: Intent? = getIntent()
            if (i != null && Intent.ACTION_RUN.equals(i.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                val isFailSafe: Boolean = i.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false)
                mTermuxTerminalSessionClient!!.addNewSession(isFailSafe, null)
            } else {
                mTermuxTerminalSessionClient!!.setCurrentSession(mTermuxTerminalSessionClient!!.getCurrentStoredSessionOrLast())
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService!!.setTermuxTerminalSessionClient(mTermuxTerminalSessionClient)
        refreshWorkspace()
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected")

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing()
    }

    private fun setActivityTheme() {
        this.setTheme(R.style.Theme_Termux_Black)
    }

    private fun setDrawerTheme() {
        findViewById<View>(R.id.left_drawer)!!.setBackgroundColor(Color.rgb(11, 19, 25))
        (findViewById(R.id.settings_button) as ImageButton?)!!.setColorFilter(Color.rgb(121, 223, 205))
    }

    private fun setMargins() {
        val relativeLayout: RelativeLayout? = findViewById(R.id.activity_termux_root_relative_layout)
        val marginHorizontal: Int = mProperties!!.getTerminalMarginHorizontal()
        val marginVertical: Int = mProperties!!.getTerminalMarginVertical()
        ViewUtils.setLayoutMarginsInDp(relativeLayout!!, marginHorizontal, marginVertical, marginHorizontal, marginVertical)
    }

    fun addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView()!!.getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView())
    }

    fun removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null) {
            getTermuxActivityRootView()!!.getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView())
        }
    }

    private fun setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionClient = TermuxTerminalSessionClient(this)
        mTermuxTerminalViewClient = TermuxTerminalViewClient(this, mTermuxTerminalSessionClient)

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view)
        mTerminalView!!.setTerminalViewClient(mTermuxTerminalViewClient)

        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient!!.onCreate()
        }

        if (mTermuxTerminalSessionClient != null) {
            mTermuxTerminalSessionClient!!.onCreate()
        }
    }

    private fun setTermuxSessionsListView() {
        val termuxSessionsListView: ListView? = findViewById(R.id.terminal_sessions_list)
        mTermuxSessionListViewController = TermuxSessionsListViewController(this, mTermuxService!!.getTermuxSessions())
        termuxSessionsListView!!.setAdapter(mTermuxSessionListViewController)
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController)
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController)
    }

    private fun setTerminalToolbarView(savedInstanceState: Bundle?) {
        val terminalToolbarViewPager: ViewPager? = getTerminalToolbarViewPager()
        if (mPreferences!!.shouldShowTerminalToolbar()) {
            terminalToolbarViewPager!!.setVisibility(View.VISIBLE)
        }

        val layoutParams: ViewGroup.LayoutParams = terminalToolbarViewPager!!.getLayoutParams()
        mTerminalToolbarDefaultHeight = layoutParams.height

        setTerminalToolbarHeight()

        var savedTextInput: String? = null
        if (savedInstanceState != null) {
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT)
        }

        terminalToolbarViewPager.setAdapter(TerminalToolbarViewPager.PageAdapter(this, savedTextInput))
        terminalToolbarViewPager.addOnPageChangeListener(TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager))
    }

    private fun setTerminalToolbarHeight() {
        val terminalToolbarViewPager: ViewPager? = getTerminalToolbarViewPager()
        if (terminalToolbarViewPager == null) {
            return
        }

        val layoutParams: ViewGroup.LayoutParams = terminalToolbarViewPager.getLayoutParams()
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight * (if (mProperties!!.getExtraKeysInfo() == null) 0 else mProperties!!.getExtraKeysInfo()!!.getMatrix().size) * mProperties!!.getTerminalToolbarHeightScaleFactor())
        terminalToolbarViewPager.setLayoutParams(layoutParams)
    }

    fun toggleTerminalToolbar() {
        val terminalToolbarViewPager: ViewPager? = getTerminalToolbarViewPager()
        if (terminalToolbarViewPager == null) {
            return
        }

        val showNow: Boolean = mPreferences!!.toogleShowTerminalToolbar()
        Logger.showToast(this, if (showNow) getString(R.string.msg_enabling_terminal_toolbar) else getString(R.string.msg_disabling_terminal_toolbar), true)
        terminalToolbarViewPager.setVisibility(if (showNow) View.VISIBLE else View.GONE)
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById<View>(R.id.terminal_toolbar_text_input)!!.requestFocus()
        }
    }

    private fun saveTerminalToolbarTextInput(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            return
        }

        val textInputView: EditText? = findViewById(R.id.terminal_toolbar_text_input)
        if (textInputView != null) {
            val textInput: String = textInputView.getText().toString()
            if (!textInput.isEmpty()) {
                savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput)
            }
        }
    }

    private fun setSettingsButtonView() {
        val settingsButton: ImageButton? = findViewById(R.id.settings_button)
        settingsButton!!.setOnClickListener(View.OnClickListener { v -> startActivity(Intent(this, SettingsActivity::class.java)) })
    }

    private fun setNewSessionButtonView() {
        val newSessionButton: View? = findViewById(R.id.new_session_button)
        newSessionButton!!.setOnClickListener(View.OnClickListener { v -> mTermuxTerminalSessionClient!!.addNewSession(false, null) })
        newSessionButton.setOnLongClickListener(View.OnLongClickListener { v ->
            TextInputDialogUtils.textInput(this@TermuxActivity, R.string.title_create_named_session, null, R.string.action_create_named_session_confirm, TextInputDialogUtils.TextSetListener { text -> mTermuxTerminalSessionClient!!.addNewSession(false, text) }, R.string.action_new_session_failsafe, TextInputDialogUtils.TextSetListener { text -> mTermuxTerminalSessionClient!!.addNewSession(true, text) }, -1, null, null)
            return@OnLongClickListener true
        })
    }

    private fun setToggleKeyboardView() {
        findViewById<View>(R.id.toggle_keyboard_button)!!.setOnClickListener(View.OnClickListener { v ->
            mTermuxTerminalViewClient!!.onToggleSoftKeyboardRequest()
            getDrawer()!!.closeDrawers()
        })

        findViewById<View>(R.id.toggle_keyboard_button)!!.setOnLongClickListener(View.OnLongClickListener { v ->
            toggleTerminalToolbar()
            return@OnLongClickListener true
        })
    }

    @SuppressLint("RtlHardcoded")
    override fun onBackPressed() {
        if (getDrawer()!!.isDrawerOpen(Gravity.LEFT)) {
            getDrawer()!!.closeDrawers()
        } else if (mWorkspace == null || !mWorkspace!!.onBack()) {
            finishActivityIfNotFinishing()
        }
    }

    fun finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!this@TermuxActivity.isFinishing()) {
            finish()
        }
    }

    /** Show a toast and dismiss the last one if still visible.*/
    fun showToast(text: String?, longDuration: Boolean) {
        if (text == null || text.isEmpty()) {
            return
        }
        if (mLastToast != null) {
            mLastToast!!.cancel()
        }
        mLastToast = Toast.makeText(this@TermuxActivity, text, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
        mLastToast!!.setGravity(Gravity.TOP, 0, 0)
        mLastToast!!.show()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo) {
        val currentSession: TerminalSession? = getCurrentSession()
        if (currentSession == null) {
            return
        }

        val autoFillEnabled: Boolean = mTerminalView!!.isAutoFillEnabled()

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url)
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript)
        if (!DataUtils.isNullOrEmpty(mTerminalView!!.getStoredSelectedText())) {
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username)
        }
        if (autoFillEnabled) {
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password)
        }
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession()!!.getPid())).setEnabled(currentSession.isRunning())
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal)
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences!!.shouldKeepScreenOn())
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help)
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings)
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue)
    }

    /** Hook system menu to show context menu instead.*/
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mTerminalView!!.showContextMenu()
        return false
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val session: TerminalSession? = getCurrentSession()

        when (item.getItemId()) {
            CONTEXT_MENU_SELECT_URL_ID -> {
                mTermuxTerminalViewClient!!.showUrlSelection()
                return true
            }
            CONTEXT_MENU_SHARE_TRANSCRIPT_ID -> {
                mTermuxTerminalViewClient!!.shareSessionTranscript()
                return true
            }
            CONTEXT_MENU_SHARE_SELECTED_TEXT -> {
                mTermuxTerminalViewClient!!.shareSelectedText()
                return true
            }
            CONTEXT_MENU_AUTOFILL_USERNAME -> {
                mTerminalView!!.requestAutoFillUsername()
                return true
            }
            CONTEXT_MENU_AUTOFILL_PASSWORD -> {
                mTerminalView!!.requestAutoFillPassword()
                return true
            }
            CONTEXT_MENU_RESET_TERMINAL_ID -> {
                onResetTerminalSession(session)
                return true
            }
            CONTEXT_MENU_KILL_PROCESS_ID -> {
                showKillSessionDialog(session)
                return true
            }
            CONTEXT_MENU_STYLING_ID -> {
                showStylingDialog()
                return true
            }
            CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON -> {
                toggleKeepScreenOn()
                return true
            }
            CONTEXT_MENU_HELP_ID -> {
                startActivity(Intent(this, HelpActivity::class.java))
                return true
            }
            CONTEXT_MENU_SETTINGS_ID -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            CONTEXT_MENU_REPORT_ID -> {
                mTermuxTerminalViewClient!!.reportIssueFromTranscript()
                return true
            }
            else -> {
                return super.onContextItemSelected(item)
            }
        }
    }

    override fun onContextMenuClosed(menu: Menu) {
        super.onContextMenuClosed(menu)
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView!!.onContextMenuClosed(menu)
    }

    private fun showKillSessionDialog(session: TerminalSession?) {
        if (session == null) {
            return
        }

        val b: AlertDialog.Builder = ThemedDialog(this)
        b.setIcon(android.R.drawable.ic_dialog_alert)
        b.setMessage(R.string.title_confirm_kill_process)
        b.setPositiveButton(android.R.string.yes, DialogInterface.OnClickListener { dialog, id ->
            dialog.dismiss()
            session.finishIfRunning()
        })
        b.setNegativeButton(android.R.string.no, null)
        b.show()
    }

    private fun onResetTerminalSession(session: TerminalSession?) {
        if (session != null) {
            session.reset()
            showToast(getResources().getString(R.string.msg_terminal_reset), true)

            if (mTermuxTerminalSessionClient != null) {
                mTermuxTerminalSessionClient!!.onResetTerminalSession()
            }
        }
    }

    private fun showStylingDialog() {
        val stylingIntent = Intent()
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING.TERMUX_STYLING_ACTIVITY_NAME)
        try {
            startActivity(stylingIntent)
        } catch (e: ActivityNotFoundException) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            ThemedDialog(this).setMessage(getString(R.string.error_styling_not_installed)).setPositiveButton(R.string.action_styling_install, DialogInterface.OnClickListener { dialog, which ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)))
            }).setNegativeButton(android.R.string.cancel, null).show()
        } catch (e: IllegalArgumentException) {
            ThemedDialog(this).setMessage(getString(R.string.error_styling_not_installed)).setPositiveButton(R.string.action_styling_install, DialogInterface.OnClickListener { dialog, which ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL)))
            }).setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun toggleKeepScreenOn() {
        if (mTerminalView!!.getKeepScreenOn()) {
            mTerminalView!!.setKeepScreenOn(false)
            mPreferences!!.setKeepScreenOn(false)
        } else {
            mTerminalView!!.setKeepScreenOn(true)
            mPreferences!!.setKeepScreenOn(true)
        }
    }

    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     **/
    private var mWorkspaceStorageAccess: WorkspaceStorageAccess? = null
    private var mWorkspaceGalleryPicker: WorkspaceGalleryPicker? = null

    fun requestWorkspaceGallery(limit: Int, selected: Consumer<MutableList<Uri?>>) {
        if (mWorkspaceGalleryPicker == null) {
            mWorkspaceGalleryPicker = WorkspaceGalleryPicker(this)
        }
        mWorkspaceGalleryPicker!!.pick(limit, selected)
    }

    fun requestWorkspaceStorageAccess(ready: Runnable) {
        requestWorkspaceStorageAccess(ready, null)
    }

    fun requestWorkspaceStorageAccess(ready: Runnable, cancelled: Runnable?) {
        if (mWorkspaceStorageAccess == null) {
            mWorkspaceStorageAccess = WorkspaceStorageAccess(this)
        }
        mWorkspaceStorageAccess!!.request(ready, cancelled)
    }

    fun ensureStoragePermissionGranted(): Boolean {
        if (PermissionUtils.checkPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission not granted, requesting permission.")
            PermissionUtils.requestPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION)
            return false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (mWorkspaceStorageAccess != null && mWorkspaceStorageAccess!!.permissionResult(requestCode)) {
            return
        }
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Logger.logInfo(LOG_TAG, "Storage permission granted by user on request.")
            TermuxInstaller.setupStorageSymlinks(this)
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission denied by user on request.")
        }
    }

    fun getNavBarHeight(): Int {
        return mNavBarHeight
    }

    fun getTermuxActivityRootView(): TermuxActivityRootView? {
        return mTermuxActivityRootView
    }

    fun getTermuxActivityBottomSpaceView(): View? {
        return mTermuxActivityBottomSpaceView
    }

    fun getExtraKeysView(): ExtraKeysView? {
        return mExtraKeysView
    }

    fun setExtraKeysView(extraKeysView: ExtraKeysView?) {
        mExtraKeysView = extraKeysView
    }

    fun getDrawer(): DrawerLayout? {
        return findViewById(R.id.drawer_layout) as DrawerLayout?
    }

    fun getTerminalToolbarViewPager(): ViewPager? {
        return findViewById(R.id.terminal_toolbar_view_pager) as ViewPager?
    }

    fun isTerminalViewSelected(): Boolean {
        return getTerminalToolbarViewPager()!!.getCurrentItem() == 0
    }

    fun isTerminalToolbarTextInputViewSelected(): Boolean {
        return getTerminalToolbarViewPager()!!.getCurrentItem() == 1
    }

    fun termuxSessionListNotifyUpdated() {
        termuxSessionTitleNotifyUpdated()
        refreshWorkspace()
    }

    /** OSC titles do not change workspace names or session membership. Keep touch targets stable.*/
    fun termuxSessionTitleNotifyUpdated() {
        if (mTermuxSessionListViewController != null) {
            mTermuxSessionListViewController!!.notifyDataSetChanged()
        }
    }

    fun prepareWorkspaceBootstrap(ready: Runnable) {
        TermuxInstaller.setupBootstrapIfNeeded(this, ready)
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)
        if (mWorkspaceStorageAccess != null && mWorkspaceStorageAccess!!.activityResult(requestCode)) {
            return
        }
        if (mWorkspaceGalleryPicker != null && mWorkspaceGalleryPicker!!.result(requestCode, resultCode, data)) {
            return
        }
    }

    fun workspaceTerminalScrolled() {
        if (mWorkspace != null) {
            mWorkspace!!.onTerminalRedrawInteraction()
        }
    }

    fun workspaceTerminalSizeChanged() {
        if (mWorkspace != null) {
            mWorkspace!!.onTerminalRedrawInteraction()
        }
    }

    fun workspaceTerminalViewportChanged() {
        if (mWorkspace != null) {
            mWorkspace!!.onTerminalViewportChanged()
        }
    }

    fun workspaceSessionOutput(session: TerminalSession?) {
        if (mWorkspace != null) {
            mWorkspace!!.onSessionOutput(session)
        }
    }

    fun refreshWorkspace() {
        if (mWorkspace != null) {
            mWorkspace!!.refresh()
            if (mIsVisible) {
                mWorkspace!!.ensureBaseTerminal()
            }
        }
    }

    fun isWorkspaceBaseTerminal(session: TerminalSession?): Boolean {
        return mWorkspace != null && mWorkspace!!.isBaseTerminal(session)
    }

    fun onWorkspaceSessionFinished(session: TerminalSession?): Boolean {
        return mWorkspace != null && mWorkspace!!.onFinished(session)
    }

    fun createWorkspaceSession(name: String?, directory: String?): TermuxSession? {
        return if (mWorkspace == null) null else mWorkspace!!.createDefault(name, directory)
    }

    fun isVisible(): Boolean {
        return mIsVisible
    }

    fun isOnResumeAfterOnCreate(): Boolean {
        return isOnResumeAfterOnCreate
    }

    fun getTermuxService(): TermuxService? {
        return mTermuxService
    }

    fun getTerminalView(): TerminalView? {
        return mTerminalView
    }

    fun getTermuxTerminalViewClient(): TermuxTerminalViewClient? {
        return mTermuxTerminalViewClient
    }

    fun getTermuxTerminalSessionClient(): TermuxTerminalSessionClient? {
        return mTermuxTerminalSessionClient
    }

    fun getCurrentSession(): TerminalSession? {
        if (mTerminalView != null) {
            return mTerminalView!!.getCurrentSession()
        } else {
            return null
        }
    }

    fun getPreferences(): TermuxAppSharedPreferences? {
        return mPreferences
    }

    fun getProperties(): TermuxAppSharedProperties? {
        return mProperties
    }

    private fun registerTermuxActivityBroadcastReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        intentFilter.addAction(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter)
    }

    private fun unregisterTermuxActivityBroadcastReceiever() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver)
    }

    private fun fixTermuxActivityBroadcastReceieverIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        val extraReloadStyle: String? = intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE)
            intent.setAction(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS)
        }
    }

    open inner class TermuxActivityBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) {
                return
            }

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceieverIntent(intent)

                when (intent.getAction()!!) {
                    TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS -> {
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions")
                        if (ensureStoragePermissionGranted()) {
                            TermuxInstaller.setupStorageSymlinks(this@TermuxActivity)
                        }
                        return
                    }
                    TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE -> {
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling")
                        reloadActivityStyling()
                        return
                    }
                    else -> {
                    }
                }
            }
        }
    }

    private fun reloadActivityStyling() {
        if (mProperties != null) {
            mProperties!!.loadTermuxPropertiesFromDisk()

            if (mExtraKeysView != null) {
                mExtraKeysView!!.setButtonTextAllCaps(mProperties!!.shouldExtraKeysTextBeAllCaps())
                mExtraKeysView!!.reload(mProperties!!.getExtraKeysInfo())
            }
        }

        setMargins()
        setTerminalToolbarHeight()

        if (mTermuxTerminalSessionClient != null) {
            mTermuxTerminalSessionClient!!.onReload()
        }

        if (mTermuxTerminalViewClient != null) {
            mTermuxTerminalViewClient!!.onReload()
        }

        if (mTermuxService != null) {
            mTermuxService!!.setTerminalTranscriptRows()
        }

        // To change the activity and drawer theme, activity needs to be recreated.
        // But this will destroy the activity, and will call the onCreate() again.
        // We need to investigate if enabling this is wise, since all stored variables and
        // views will be destroyed and bindService() will be called again. Extra keys input
        // text will we restored since that has already been implemented. Terminal sessions
        // and transcripts are also already preserved. Theme does change properly too.
        // TermuxActivity.this.recreate();
    }

    companion object {

        private const val CONTEXT_MENU_SELECT_URL_ID: Int = 0
        private const val CONTEXT_MENU_SHARE_TRANSCRIPT_ID: Int = 1
        private const val CONTEXT_MENU_SHARE_SELECTED_TEXT: Int = 10
        private const val CONTEXT_MENU_AUTOFILL_USERNAME: Int = 11
        private const val CONTEXT_MENU_AUTOFILL_PASSWORD: Int = 2
        private const val CONTEXT_MENU_RESET_TERMINAL_ID: Int = 3
        private const val CONTEXT_MENU_KILL_PROCESS_ID: Int = 4
        private const val CONTEXT_MENU_STYLING_ID: Int = 5
        private const val CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON: Int = 6
        private const val CONTEXT_MENU_HELP_ID: Int = 7
        private const val CONTEXT_MENU_SETTINGS_ID: Int = 8
        private const val CONTEXT_MENU_REPORT_ID: Int = 9

        private const val ARG_TERMINAL_TOOLBAR_TEXT_INPUT: String = "terminal_toolbar_text_input"

        private const val LOG_TAG: String = "TermuxActivity"

        @JvmStatic
        fun updateTermuxActivityStyling(context: Context) {
            // Make sure that terminal styling is always applied.
            val stylingIntent = Intent(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            context.sendBroadcast(stylingIntent)
        }

        @JvmStatic
        fun startTermuxActivity(context: Context) {
            context.startActivity(newInstance(context))
        }

        @JvmStatic
        fun newInstance(context: Context): Intent {
            val intent = Intent(context, TermuxActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent
        }
    }
}
