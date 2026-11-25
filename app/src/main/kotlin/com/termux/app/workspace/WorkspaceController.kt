package com.termux.app.workspace

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.TermuxService
import com.termux.app.agent.AppSettingsActivity
import com.termux.shared.shell.TermuxSession
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.function.Supplier
import org.json.JSONArray
import org.json.JSONObject

/** Native workspace shell around the original TerminalView and foreground service.*/
class WorkspaceController {
    enum class Screen {
        HOME,
        SESSIONS,
        COMMANDS,
        TERMINAL,
        SERVERS
    }

    private lateinit var activity: TermuxActivity
    private lateinit var store: WorkspaceStore
    private val servers: WorkspaceServers
    private val header: LinearLayout?
    private val navigation: LinearLayout?
    private val tabs: LinearLayout?
    private val inputRow: LinearLayout?
    private val codexStatus: LinearLayout?
    private val terminalInput: EditText
    private var commandResults: LinearLayout? = null
    private var noteResults: LinearLayout? = null
    private var terminalInputHandle: String? = null
    private val terminalDrafts: MutableMap<String, InputDraft> = HashMap<String, InputDraft>()

    private class InputDraft {
        @JvmField val text: String
        @JvmField val start: Int
        @JvmField val end: Int

        constructor(input: EditText) {
            text = input.getText().toString()
            start = input.getSelectionStart()
            end = input.getSelectionEnd()
        }
    }

    private val content: FrameLayout?
    private val packageBrowsers: MutableList<WorkspacePackages> = ArrayList<WorkspacePackages>()
    private val fileBrowsers: MutableList<WorkspaceFileBrowser> = ArrayList<WorkspaceFileBrowser>()
    private val reader: ExecutorService = Executors.newSingleThreadExecutor()
    private val codexHandler: Handler = Handler(Looper.getMainLooper())
    private val codexReader: ExecutorService = Executors.newSingleThreadExecutor()
    private var activityLine: WorkspaceActivityLine? = null
    private var agentStop: AgentStopButton? = null
    private var transferBorder: WorkspaceTransferBorder? = null
    private var agentTerminalBorder: WorkspaceTransferBorder? = null
    private var wasAgentChat: Boolean = false
    private var activityStopped: Boolean = false

    private fun updateTransferBorder() {
        if (transferBorder != null) {
            transferBorder!!.active(!closed && !activityStopped && screen === Screen.TERMINAL && activity.isVisible() && servers.transfersActive())
        }
        if (agentTerminalBorder != null) {
            agentTerminalBorder!!.active(agentChatUi != null && agentChatUi!!.requiresTerminal())
        }
    }

    private val outputSignal: SessionOutputActivity = SessionOutputActivity()

    fun onTerminalRedrawInteraction() {
        val session: TerminalSession? = activity.getCurrentSession()
        if (session != null) {
            outputSignal.resize(session.mHandle, SystemClock.uptimeMillis())
        }
        updateActivityLine()
    }

    fun onTerminalViewportChanged() {
        outputSignal.viewport(SystemClock.uptimeMillis())
        updateActivityLine()
    }

    private var activityProbePending: Boolean = false
    private var foregroundCommand: Boolean = false
    private var activityProbeHandle: String = ""
    private val activityPoll: Runnable = object : Runnable {
        override fun run() {
            if (closed || activityStopped) {
                return
            }
            updateActivityLine()
            updateDesktopProgramsBorder()
            val session: TerminalSession? = activity.getCurrentSession()
            val profile: WorkspaceStore.Session? = if (session == null) null else store.findByHandle(session.mHandle)
            val shell: Boolean = profile == null || !profile.environment.equals("ssh") && !profile.environment.equals("codex") && !profile.claudeAgent && !profile.geminiAgent && profile.aiAgent.isEmpty()
            if (screen === Screen.TERMINAL && activity.isVisible() && session != null && session.isRunning() && shell && !activityProbePending) {
                activityProbePending = true
                val handle: String = session.mHandle
                val pid: Int = session.getPid()
                reader.execute(Runnable {
                    val busy: Boolean = SessionActivitySignal.busy(pid)
                    activity.runOnUiThread(Runnable {
                        activityProbePending = false
                        if (closed) {
                            return@Runnable
                        }
                        val current: TerminalSession? = activity.getCurrentSession()
                        if (current === session && current.getPid() == pid) {
                            activityProbeHandle = handle
                            foregroundCommand = busy
                            updateActivityLine()
                        }
                    })
                })
            }
            codexHandler.postDelayed(this, 700L)
        }
    }

    fun onSessionOutput(session: TerminalSession?) {
        if (closed || session == null) {
            return
        }
        outputSignal.output(session.mHandle, SystemClock.uptimeMillis())
        updateActivityLine()
    }

    private fun updateActivityLine() {
        if (activityLine == null) {
            return
        }
        val session: TerminalSession? = activity.getCurrentSession()
        var active = false
        if (!activityStopped && screen === Screen.TERMINAL && activity.isVisible() && session != null && session.isRunning()) {
            active = outputSignal.active(session.mHandle, SystemClock.uptimeMillis())
            if (agentChatUi != null && agentChatUi!!.working()) {
                active = true
            }
            val profile: WorkspaceStore.Session? = store.findByHandle(session.mHandle)
            val shell: Boolean = profile == null || !profile.environment.equals("ssh") && !profile.environment.equals("codex") && !profile.claudeAgent && !profile.geminiAgent && profile.aiAgent.isEmpty()
            if (shell && activityProbeHandle.equals(session.mHandle)) {
                active = active or foregroundCommand
            }
            val status: CodexStatus? = if (profile == null) null else codexStatuses.get(profile.id)
            if (status != null && status.error!!.isEmpty() && System.currentTimeMillis() - status.updated < 2500 && status.state.equals("active")) {
                active = true
            }
        }
        if (agentStop != null && agentStop!!.getVisibility() == View.VISIBLE && agentChatUi != null && agentChatUi!!.structured()) {
            active = agentChatUi!!.canInterrupt()
        }
        if (agentStop != null) {
            val enabled: Boolean = agentChatUi != null && agentChatUi!!.canInterrupt()
            if (agentStop!!.isEnabled() != enabled) {
                agentStop!!.setEnabled(enabled)
            }
            agentStop!!.setContentDescription(if (enabled) tr("Остановить текущую работу агента", "Stop current agent work") else tr("Агент не выполняет запрос", "Agent is idle"))
        }
        activityLine!!.working(active)
    }

    private var geminiLiveUi: GeminiLiveUi? = null
    private var claudeLiveUi: ClaudeLiveUi? = null
    private var agentReceiptsUi: AgentReceiptsUi? = null
    private var agentChatUi: AgentChatUi? = null
    private var desktopUi: WorkspaceDesktops? = null
    private var desktopSessionBar: DesktopSessionBar? = null

    private fun updateDesktopProgramsBorder() {
        if (desktopSessionBar != null) {
            desktopSessionBar!!.working(!closed && !activityStopped && screen === Screen.TERMINAL && activity.isVisible() && desktopUi != null && desktopUi!!.programsInstalling())
        }
    }

    private var environmentSetup: EnvironmentSetup
    private var agentEnvironments: AgentEnvironmentManager
    private val geminiInputDispatch: GeminiInputDispatch = GeminiInputDispatch()
    private var codexLoading: Boolean = false
    private var codexApplyGeneration: Int = 0
    private val codexConfirmedChoices: MutableMap<String, Array<String?>> = HashMap<String, Array<String?>>()
    private var codexApplyDialog: AlertDialog? = null
    private val codexStatuses: MutableMap<String, CodexStatus> = HashMap<String, CodexStatus>()
    private val codexPoll: Runnable = object : Runnable {
        override fun run() {
            if (closed) {
                return
            }
            val profile: WorkspaceStore.Session? = currentCodex()
            if (screen === Screen.TERMINAL && profile != null) {
                loadCodexStatus(profile, null)
            }
            codexHandler.postDelayed(this, 30000L)
        }
    }
    private var screen: Screen = Screen.HOME
    private var query: String = ""
    private var journalQuery: String = ""
    private var commandCategory: String? = ""
    private var noteCategory: String? = ""
    private var noteFavorites: Boolean = false
    private var favorites: Boolean = false
    private var savedOnly: Boolean = false
    private var closed: Boolean = false
    private var creating: Boolean = false
    private var baseCreating: Boolean = false
    private var aliasesSelected: Boolean = false
    private var aliasFavorites: Boolean = false
    private var aliasCategory: String? = ""
    private var lastBaseRestart: Long = 0L
    private var baseStartedAt: Long = 0L
    private var baseRestartFailures: Int = 0
    private var toolbarVisibility: Int = View.GONE
    private var interactiveInput: Boolean = false
    private var desktopInputButton: TextView? = null
    private var desktopKeysButton: TextView? = null
    private var swipeStart: Float = 0f
    private val pendingCloses: MutableSet<String> = HashSet<String>()
    private val unstartedOnLoad: MutableSet<String> = HashSet<String>()
    private val pendingRestarts: MutableMap<String, WorkspaceStore.Session?> = HashMap<String, WorkspaceStore.Session?>()

    private val pendingSshPasswords: MutableMap<String, CharArray> = HashMap<String, CharArray>()

    constructor(activity: TermuxActivity) {
        this.activity = activity
        environmentSetup = EnvironmentSetup(activity)
        prepareKeyboardWindow()
        store = WorkspaceStore(activity, WorkspaceStore.ErrorListener { message -> activity.showToast(message, true) })
        for (p in store.sessions) {
            if (!WorkspaceStore.isBase(p) && !p!!.saved && p.handle.isEmpty()) {
                unstartedOnLoad.add(p.id)
            }
        }
        agentEnvironments = AgentEnvironmentManager(activity, store, Predicate<String> { alias ->
            for (p in store.sessions) {
                val t: TerminalSession? = liveFor(p)
                if (alias.equals(AgentEnvironments.scope(p)) && t != null && t.isRunning()) {
                    return@Predicate true
                }
            }
            return@Predicate false
        })
        environmentSetup.removal = BiConsumer<WorkspaceEnvironments.Choice?, Runnable> { choice, refresh -> agentEnvironments.remove(choice, refresh) }
        agentEnvironments.freshInstall = Consumer<Consumer<Boolean?>>(environmentSetup::installAgentUbuntu)
        agentEnvironments.prepareTools = Consumer<Consumer<Boolean?>>(environmentSetup::prepareMigrationTools)
        claudeLiveUi = ClaudeLiveUi(activity, Supplier<WorkspaceStore.Session?> {
            val t: TerminalSession? = activity.getCurrentSession()
            val p: WorkspaceStore.Session? = if (t == null) null else store.findByHandle(t.mHandle)
            return@Supplier if (t != null && t.isRunning() && p != null && p.claudeAgent) p else null
        }, BooleanSupplier { !closed && !activityStopped && screen === Screen.TERMINAL && activity.isVisible() }, ClaudeLiveUi.Applied { p, run, model, mode ->
            if (store.sessions.contains(p) && run.equals(p.claudeSocketId)) {
                p.claudeModel = model
                p.claudeMode = mode
                store.save()
            }
        })
        geminiLiveUi = GeminiLiveUi(activity, Supplier<WorkspaceStore.Session?> {
            val t: TerminalSession? = activity.getCurrentSession()
            val p: WorkspaceStore.Session? = if (t == null) null else store.findByHandle(t.mHandle)
            return@Supplier if (t != null && t.isRunning() && p != null && p.geminiAgent) p else null
        }, BooleanSupplier { !closed && !activityStopped && screen === Screen.TERMINAL && activity.isVisible() }, GeminiLiveUi.ModeApplied { profile, run, before, mode -> store.applyGeminiMode(profile, run, before, mode) })
        claudeLiveUi!!.effortApplied(BiConsumer<WorkspaceStore.Session, String> { p, value ->
            if (store.sessions.contains(p)) {
                p.claudeEffort = value
                store.save()
            }
        })
        geminiLiveUi!!.thinkingApplied(BiConsumer<WorkspaceStore.Session, String> { p, value ->
            if (store.sessions.contains(p)) {
                p.geminiThinking = value
                store.save()
            }
        })
        agentReceiptsUi = AgentReceiptsUi(activity, Supplier<WorkspaceStore.Session?> {
            val t: TerminalSession? = activity.getCurrentSession()
            return@Supplier if (t != null && t.isRunning()) store.findByHandle(t.mHandle) else null
        }, BooleanSupplier {
            !closed && !activityStopped && screen === Screen.TERMINAL && activity.isVisible() && (agentChatUi == null || !agentChatUi!!.showing())
        })
        activity.getTerminalView()!!.setTranscriptTapListener(Predicate<String> { line -> agentReceiptsUi!!.tap(line) })
        servers = WorkspaceServers(activity, WorkspaceServers.Terminal { name, command ->
            val s = WorkspaceStore.Session()
            s.name = name + " · SSH"
            s.environment = "termux"
            s.directory = TermuxConstants.TERMUX_HOME_DIR_PATH
            s.startup = command
            launchNew(s, true)
        })
        interactiveInput = activity.getSharedPreferences("workspace-input", Context.MODE_PRIVATE).getBoolean("interactive-input", false)
        activity.getTerminalView()!!.setSoftKeyboardInputEnabled(interactiveInput)
        servers.transferListener(Runnable(this::updateTransferBorder))
        header = activity.findViewById(R.id.workspace_header)
        navigation = activity.findViewById(R.id.workspace_navigation)
        tabs = activity.findViewById(R.id.workspace_tabs)
        codexStatus = activity.findViewById(R.id.workspace_codex_status)
        activityLine = activity.findViewById(R.id.workspace_session_activity)
        content = activity.findViewById(R.id.workspace_content)
        inputRow = activity.findViewById(R.id.workspace_input)
        inputRow!!.setPadding(dp(12.0f), dp(6.0f), dp(12.0f), dp(6.0f))
        terminalInput = field(WorkspaceText.tr("Введите команду…"), "", true)
        terminalInput.setTypeface(Typeface.MONOSPACE)
        terminalInput.setTextSize(12.0f)
        terminalInput.setMinLines(1)
        terminalInput.setMinHeight(dp(48.0f))
        terminalInput.setHorizontallyScrolling(false)
        terminalInput.setImeOptions(EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_EXTRACT_UI)
        inputRow.addView(terminalInput, LinearLayout.LayoutParams(0, -2, 1.0f))
        val commands: TextView = button("★", false, Runnable(this::chooseInputCommand))
        commands.setTextColor(Pal.ACCENT)
        commands.setTextSize(22.0f)
        commands.setContentDescription(WorkspaceText.tr("Вставить сохранённую команду"))
        val commandsParams = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        commandsParams.setMarginEnd(dp(8.0f))
        inputRow.addView(commands, 0, commandsParams)
        val send: TextView = button("▷", true, Runnable(this::sendInput))
        send.setContentDescription(WorkspaceText.tr("Отправить в активный терминал"))
        val sendParams = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        sendParams.setMarginStart(dp(8.0f))
        inputRow.addView(send, sendParams)
        activity.getWindow().setStatusBarColor(Pal.BG)
        activity.getWindow().setNavigationBarColor(Pal.BG)
        tabs!!.setOnTouchListener(View.OnTouchListener { v, event ->
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                swipeStart = event.getX()
            }
            if (event.getAction() == MotionEvent.ACTION_UP && Math.abs(event.getX() - swipeStart) > dp(64.0f)) {
                activity.getTermuxTerminalSessionClient()!!.switchToSession(event.getX() < swipeStart)
                v.performClick()
                return@OnTouchListener true
            }
            return@OnTouchListener true
        })
        toolbarVisibility = activity.getTerminalToolbarViewPager()!!.getVisibility()
        agentChatUi = AgentChatUi(activity, Supplier<WorkspaceStore.Session?> {
            val t: TerminalSession? = activity.getCurrentSession()
            return@Supplier if (t != null && t.isRunning()) store.findByHandle(t.mHandle) else null
        }, BooleanSupplier { !closed && !activityStopped && screen === Screen.TERMINAL && activity.isVisible() })
        geminiLiveUi!!.chatPresentation(Runnable(this::previewChatScale), Runnable(store::save))
        claudeLiveUi!!.chatPresentation(Runnable(this::previewChatScale), Runnable(store::save))
        geminiLiveUi!!.permissionVisibility(BooleanSupplier { !agentChatUi!!.showing() })
        agentChatUi!!.onShellCommand(Consumer<String>(store::recordCommand))
        agentChatUi!!.composer(terminalInput, inputRow)
        agentChatUi!!.onVisibilityChanged(Runnable {
            agentChatUi!!.shellMode(interactiveInput)
            val chat: Boolean = agentChatUi!!.showing()
            val focus: Boolean = chat && !wasAgentChat
            wasAgentChat = chat
            terminalInput.setHint(if (chat) tr("Сообщение ", "Message ") + agentChatUi!!.name() + "…" else WorkspaceText.tr("Введите команду…"))
            activity.getTerminalToolbarViewPager()!!.setVisibility(if (screen === Screen.TERMINAL && !activityStopped && !chat) toolbarVisibility else View.GONE)
            if (focus) {
                leaveAgentInputIdle()
            }
            renderHeader()
            renderTabs()
        })
        desktopUi = WorkspaceDesktops(activity, Supplier<WorkspaceStore.Session?> {
            val t: TerminalSession? = activity.getCurrentSession()
            return@Supplier if (t != null && t.isRunning()) store.findByHandle(t.mHandle) else null
        }, BooleanSupplier { !closed && !activityStopped && screen === Screen.TERMINAL }, inputRow, Runnable(this::renderCodexStatus), Runnable {
            val terminal: Boolean = screen === Screen.TERMINAL && !activityStopped
            activity.getTerminalView()!!.setVisibility(if (terminal) View.VISIBLE else View.INVISIBLE)
            activity.getTerminalView()!!.setWorkspaceTerminalVisible(terminal)
            inputRow.setVisibility(if (terminal) View.VISIBLE else View.GONE)
            activity.getTerminalToolbarViewPager()!!.setVisibility(if (terminal) toolbarVisibility else View.GONE)
            if (agentChatUi != null) {
                agentChatUi!!.sync()
            }
        })
        desktopUi!!.onKeyboardChanged(Runnable(this::updateDesktopInputButton))
        agentChatUi!!.externalDisplay(BooleanSupplier { desktopUi != null && desktopUi!!.hasSurface() })
        show(Screen.HOME, false)
        reader.execute(Runnable {
            try {
                RetiredModelData.clean(activity.getApplicationContext())
            } catch (failure: IOException) {
                activity.runOnUiThread(Runnable {
                    if (!closed) {
                        activity.showToast(tr("Не удалось полностью удалить данные локальных ИИ. Повторите запуск после освобождения файлов.", "Could not fully remove retired local-model data. Reopen after releasing the files."), true)
                    }
                })
            } catch (failure: SecurityException) {
                activity.runOnUiThread(Runnable {
                    if (!closed) {
                        activity.showToast(tr("Не удалось полностью удалить данные локальных ИИ. Повторите запуск после освобождения файлов.", "Could not fully remove retired local-model data. Reopen after releasing the files."), true)
                    }
                })
            }
        })
        codexHandler.postDelayed(codexPoll, 1000L)
    }

    fun onStop() {
        geminiInputDispatch.cancel()
        activityStopped = true
        activity.getTerminalView()!!.setWorkspaceTerminalVisible(false)
        geminiLiveUi!!.syncPermissionVisibility()
        agentReceiptsUi!!.syncVisibility()
        if (transferBorder != null) {
            transferBorder!!.active(false)
        }
        if (desktopSessionBar != null) {
            desktopSessionBar!!.working(false)
        }
        codexHandler.removeCallbacks(activityPoll)
        if (activityLine != null) {
            activityLine!!.working(false)
        }
        WorkspaceUpdates.background(activity)
        servers.pause()
        if (agentChatUi != null) {
            agentChatUi!!.sync()
        }
        if (desktopUi != null) {
            desktopUi!!.sync()
        }
    }

    fun onResume() {
        syncLanguage()
        activityStopped = false
        updateDesktopProgramsBorder()
        activity.getTerminalView()!!.setWorkspaceTerminalVisible(screen === Screen.TERMINAL)
        updateTransferBorder()
        codexHandler.removeCallbacks(activityPoll)
        codexHandler.post(activityPoll)
        servers.resume()
        if (agentChatUi != null) {
            agentChatUi!!.sync()
        }
        if (desktopUi != null) {
            desktopUi!!.sync()
        }
        WorkspaceUpdates.automatic(activity, Runnable {
            if (!closed) {
                renderHeader()
            }
        })
    }

    private var appliedLanguage: String? = null
    private var backLabel: String? = null

    /** The screen that opened the workspace; the arrow in the header names it ("‹ Агенты"). Without a name it is the Environment tab.*/
    fun backLabel(from: String?) {
        val label: String? = if (from == null || from.isEmpty()) null else from
        if (if (label == null) backLabel == null else label.equals(backLabel)) {
            return
        }
        backLabel = label
        renderHeader()
    }

    /** The language is chosen in Settings; when it changed while this screen was in the background, redraw the texts.*/
    private fun syncLanguage() {
        val code: String? = WorkspaceLanguage.code(activity)
        if (appliedLanguage != null && !appliedLanguage!!.equals(code)) {
            WorkspaceLanguage.apply(activity)
            refreshLanguage()
        }
        appliedLanguage = code
    }

    fun refreshLanguage() {
        terminalInput.setHint(WorkspaceText.tr("Введите команду…"))
        inputRow!!.getChildAt(0).setContentDescription(WorkspaceText.tr("Вставить сохранённую команду"))
        inputRow.getChildAt(inputRow.getChildCount() - 1).setContentDescription(WorkspaceText.tr("Отправить в активный терминал"))
        renderHeader()
        renderNavigation()
        renderTabs()
        renderContent()
        renderCodexStatus()
    }

    private fun dp(value: Float): Int {
        return Math.round(value * activity.getResources().getDisplayMetrics().density)
    }

    private fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    private fun row(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.HORIZONTAL)
        v.setGravity(Gravity.CENTER_VERTICAL)
        return v
    }

    private fun text(value: String?, size: Int, color: Int): TextView {
        val t = TextView(activity)
        t.setText(value)
        t.setTextSize(size.toFloat())
        t.setTextColor(color)
        t.setFontFeatureSettings("kern")
        return t
    }

    private fun shape(color: Int, stroke: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(dp(radius.toFloat()).toFloat())
        d.setStroke(dp(1.0f), stroke)
        return d
    }

    private fun button(label: String?, active: Boolean, click: Runnable): TextView {
        val b: TextView = WorkspaceIconButton(activity)
        b.setText(label)
        b.setTextSize(13.0f)
        b.setTextColor(if (active) Pal.ONACCENT else Pal.TEXT)
        b.setGravity(Gravity.CENTER)
        b.setPadding(dp(13.0f), dp(8.0f), dp(13.0f), dp(8.0f))
        b.setMinHeight(dp(48.0f))
        b.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (active) Pal.ACCENT else Pal.PANEL, if (active) Pal.ACCENT else Pal.BORDER, 12), null))
        val icon: Int = iconFor((label!! as java.lang.String).split("\n", 2)[0])
        if (icon != 0) {
            val drawable: Drawable = activity.getDrawable(icon)!!.mutate()
            drawable.setTint(if (active) Pal.ONACCENT else Pal.TEXT)
            drawable.setBounds(0, 0, dp(22.0f), dp(22.0f))
            if (label.contains("\n")) {
                b.setText(label.substring(label.indexOf('\n') + 1))
                b.setCompoundDrawables(null, drawable, null, null)
                b.setCompoundDrawablePadding(dp(4.0f))
            } else {
                (b as WorkspaceIconButton).setIcon(drawable, dp(22.0f))
                b.setContentDescription(label)
                b.setMinWidth(dp(48.0f))
            }
        }
        b.setOnClickListener(View.OnClickListener { v -> click.run() })
        return b
    }

    private fun iconFor(label: String): Int {
        when (label) {
            "⌂" -> {
                return R.drawable.workspace_home
            }
            "▤" -> {
                return R.drawable.workspace_sessions
            }
            ">_" -> {
                return R.drawable.workspace_commands
            }
            "▣" -> {
                return R.drawable.workspace_terminal
            }
            "⌕" -> {
                return R.drawable.workspace_search
            }
            "+" -> {
                return R.drawable.workspace_plus
            }
            "★" -> {
                return R.drawable.workspace_star
            }
            "▱" -> {
                return R.drawable.workspace_folder
            }
            "\ud83d\udce6" -> {
                return R.drawable.workspace_packages
            }
            "▷" -> {
                return R.drawable.workspace_play
            }
            "⋮" -> {
                return R.drawable.workspace_more
            }
            "⚙" -> {
                return R.drawable.workspace_settings
            }
            "◎" -> {
                return R.drawable.workspace_language
            }
            "⌖" -> {
                return R.drawable.workspace_interactive_input
            }
            "⌨" -> {
                return R.drawable.workspace_extra_keys
            }
            "›" -> {
                return R.drawable.workspace_next
            }
            else -> {
                return 0
            }
        }
    }

    private fun tintButton(button: TextView, color: Int) {
        button.setTextColor(color)
        for (d in button.getCompoundDrawables()) {
            if (d != null) {
                d.setTint(color)
            }
        }
    }

    private fun section(parent: LinearLayout, title: String?, action: String?, click: Runnable) {
        val r: LinearLayout = row()
        r.setPadding(0, dp(22.0f), 0, dp(10.0f))
        r.setMinimumHeight(dp(80.0f))
        val t: TextView = text(title, 17, Pal.TEXT)
        t.setTypeface(null, Typeface.BOLD)
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1.0f))
        if (action != null) {
            val a: TextView = button(action, false, click)
            a.setTextColor(Pal.ACCENT)
            r.addView(a)
        }
        parent.addView(r)
    }

    /** Opens one of the workspace screens by name (home, sessions, commands, terminal, servers); used by the app navigation.*/
    fun openScreen(name: String?) {
        if (name == null) {
            return
        }
        if (name.startsWith("settings:")) {
            val what: String = name.substring(9)
            activity.getWindow().getDecorView().post(Runnable { runSetting(what) })
            return
        }
        try {
            show(Screen.valueOf((name as java.lang.String).toUpperCase(Locale.ROOT)), false)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    private fun show(target: Screen, animate: Boolean) {
        if (target !== Screen.TERMINAL) {
            activity.getTerminalView()!!.setWorkspaceTerminalVisible(false)
        }
        if (screen !== target) {
            onTerminalViewportChanged()
        }
        if (screen === Screen.TERMINAL && target !== Screen.TERMINAL && (desktopUi == null || !desktopUi!!.showing()) && (agentChatUi == null || !agentChatUi!!.showing())) {
            toolbarVisibility = activity.getTerminalToolbarViewPager()!!.getVisibility()
        }
        if (screen !== target) {
            val focused: View? = activity.getCurrentFocus()
            if (focused != null) {
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(focused.getWindowToken(), 0)
            }
            header!!.setFocusableInTouchMode(true)
            header.requestFocus()
        }
        screen = target
        servers.visibility(screen === Screen.SERVERS)
        val contentParams: RelativeLayout.LayoutParams? = content!!.getLayoutParams() as RelativeLayout.LayoutParams?
        contentParams!!.addRule(RelativeLayout.BELOW, R.id.workspace_header)
        content.setLayoutParams(contentParams)
        val terminal: Boolean = screen === Screen.TERMINAL
        activity.getDrawer()!!.setVisibility(if (terminal) View.VISIBLE else View.INVISIBLE)
        activity.getTerminalToolbarViewPager()!!.setVisibility(if (terminal) toolbarVisibility else View.GONE)
        activity.findViewById<View>(R.id.workspace_tab_scroll)!!.setVisibility(if (terminal) View.VISIBLE else View.GONE)
        content.setVisibility(if (terminal) View.GONE else View.VISIBLE)
        inputRow!!.setVisibility(if (terminal) View.VISIBLE else View.GONE)
        terminalInput.setHint(WorkspaceText.tr("Введите команду…"))
        renderHeader()
        renderNavigation()
        renderTabs()
        renderContent()
        renderCodexStatus()
        if (animate && !terminal) {
            content.setAlpha(0.0f)
            content.animate().alpha(1.0f).setDuration(180L).start()
        }
        activity.getTerminalView()!!.setWorkspaceTerminalVisible(terminal && !activityStopped)
        if (terminal) {
            activity.getTerminalView()!!.requestFocus()
        }
        if (agentChatUi != null) {
            agentChatUi!!.sync()
        }
        if (desktopUi != null) {
            desktopUi!!.sync()
        }
        if (terminal && agentChatUi != null && agentChatUi!!.showing()) {
            leaveAgentInputIdle()
        }
    }

    private fun updateDesktopInputButton() {
        if (screen !== Screen.TERMINAL || desktopInputButton == null) {
            return
        }
        val active: Boolean = if (desktopUi != null && desktopUi!!.hasSurface()) desktopUi!!.keyboardEnabled() else interactiveInput
        if (desktopKeysButton != null && desktopUi != null && desktopUi!!.showing()) {
            val keys: Boolean = desktopUi!!.extraKeysVisible()
            if (desktopKeysButton!!.isSelected() != keys) {
                desktopKeysButton!!.setSelected(keys)
                desktopKeysButton!!.setTextColor(if (keys) Pal.ONACCENT else Pal.TEXT)
                desktopKeysButton!!.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (keys) Pal.ACCENT else Pal.PANEL, if (keys) Pal.ACCENT else Pal.BORDER, 12), null))
            }
        }
        if (desktopInputButton!!.isSelected() == active) {
            return
        }
        desktopInputButton!!.setSelected(active)
        desktopInputButton!!.setTextColor(if (active) Pal.ONACCENT else Pal.TEXT)
        desktopInputButton!!.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (active) Pal.ACCENT else Pal.PANEL, if (active) Pal.ACCENT else Pal.BORDER, 12), null))
    }

    private fun renderHeader() {
        desktopInputButton = null
        desktopKeysButton = null
        // Keep the navigation container stable while rebuilding its surrounding header.
        if (navigation!!.getParent() != null) {
            (navigation.getParent() as ViewGroup).removeView(navigation)
        }
        header!!.removeAllViews()
        header.setPadding(dp(8.0f), dp(4.0f), dp(8.0f), dp(4.0f))
        val top: LinearLayout = row()
        val name: TextView = text("‹  " + (if (backLabel == null) activity.getString(R.string.nav_env) else backLabel), 17, Pal.TEXT)
        name.setTypeface(null, Typeface.BOLD)
        name.setMinHeight(dp(48.0f))
        name.setGravity(Gravity.CENTER_VERTICAL)
        name.setContentDescription(WorkspaceText.tr("Назад"))
        name.setOnClickListener(View.OnClickListener { v -> activity.finish() })
        name.setSingleLine()
        name.setEllipsize(TextUtils.TruncateAt.END)
        val brand: LinearLayout = row()
        brand.addView(name, LinearLayout.LayoutParams(0, -2, 1.0f))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1.0f))
        if (screen === Screen.TERMINAL) {
            val directActive: Boolean = if (desktopUi != null && desktopUi!!.hasSurface()) desktopUi!!.keyboardEnabled() else interactiveInput
            val direct: TextView = button("⌖", directActive, Runnable {
                if (desktopUi != null && desktopUi!!.showing()) {
                    desktopUi!!.keyboard()
                    return@Runnable
                }
                setInteractiveInput(!interactiveInput)
                renderHeader()
            })
            direct.setContentDescription(WorkspaceText.tr("Интерактивный ввод"))
            direct.setSelected(directActive)
            desktopInputButton = direct
            val directParams = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
            directParams.setMarginEnd(dp(8.0f))
            top.addView(direct, directParams)
            val desktop: Boolean = desktopUi != null && desktopUi!!.showing()
            val keysBlocked: Boolean = !desktop && agentChatUi != null && agentChatUi!!.handlesComposer()
            val keysVisible: Boolean = if (desktop) desktopUi!!.extraKeysVisible() else !keysBlocked && toolbarVisibility == View.VISIBLE
            val keys: TextView = button("⌨", keysVisible, Runnable {
                if (desktopUi != null && desktopUi!!.showing()) {
                    desktopUi!!.extraKeys()
                    return@Runnable
                }
                if (agentChatUi != null && agentChatUi!!.handlesComposer()) {
                    return@Runnable
                }
                activity.toggleTerminalToolbar()
                toolbarVisibility = activity.getTerminalToolbarViewPager()!!.getVisibility()
                renderHeader()
            })
            desktopKeysButton = keys
            keys.setContentDescription(WorkspaceText.tr("Дополнительные клавиши"))
            keys.setSelected(keysVisible)
            if (keysBlocked) {
                keys.setEnabled(false)
                keys.setAlpha(0.4f)
                keys.setSelected(false)
            }
            val keyParams = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
            keyParams.setMarginEnd(dp(8.0f))
            top.addView(keys, keyParams)
        }
        val settings: TextView = button("⚙", false, Runnable(this::globalSettings))
        settings.setContentDescription(WorkspaceText.tr("Настройки приложения"))
        if (settings is WorkspaceIconButton) {
            (settings as WorkspaceIconButton).setBadge(WorkspaceUpdates.available(activity))
        }
        top.addView(settings, LinearLayout.LayoutParams(dp(48.0f), dp(48.0f)))
        header.addView(top, LinearLayout.LayoutParams(-1, dp(48.0f)))
        val navigationParams = LinearLayout.LayoutParams(-1, -2)
        navigationParams.topMargin = dp(4.0f)
        header.addView(navigation, navigationParams)
        if (screen === Screen.COMMANDS || screen === Screen.SESSIONS) {
            val tools: LinearLayout = row()
            tools.setPadding(dp(8.0f), dp(26.0f), dp(8.0f), dp(6.0f))
            if (screen === Screen.SESSIONS) {
                val title: TextView = text(WorkspaceText.tr("Сессии"), 17, Pal.TEXT)
                title.setTypeface(null, Typeface.BOLD)
                tools.addView(title, LinearLayout.LayoutParams(0, -2, 1.0f))
            }
            if (screen === Screen.COMMANDS) {
                val search: EditText = field(tr("Поиск команд/alias и заметок", "Search commands/aliases and notes"), query, false)
                search.setContentDescription(tr("Поиск команд/alias и заметок", "Search commands/aliases and notes"))
                search.setTextSize(13.0f)
                search.setImeOptions(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI)
                val icon: Drawable = activity.getDrawable(R.drawable.workspace_search)!!.mutate()
                icon.setTint(Pal.MUTED)
                icon.setBounds(0, 0, dp(18.0f), dp(18.0f))
                search.setCompoundDrawables(icon, null, null, null)
                search.setCompoundDrawablePadding(dp(6.0f))
                val searchParams = LinearLayout.LayoutParams(0, -2, 1.0f)
                search.setMinHeight(dp(48.0f))
                tools.addView(search, searchParams)
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                    }

                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                        query = s.toString()
                        renderLibraryResults()
                    }

                    override fun afterTextChanged(s: Editable) {
                    }
                })
                search.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, event ->
                    if (actionId != EditorInfo.IME_ACTION_SEARCH) {
                        return@OnEditorActionListener false
                    }
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.getWindowToken(), 0)
                    return@OnEditorActionListener true
                })
            }
            val add: TextView = button("+", true, Runnable {
                if (screen === Screen.COMMANDS) {
                    newLibraryEntry()
                } else {
                    editSession(null)
                }
            })
            add.setContentDescription(if (screen === Screen.COMMANDS) WorkspaceText.tr("Новая команда") else WorkspaceText.tr("Новая сессия"))
            if (screen === Screen.SESSIONS) {
                tools.addView(add, LinearLayout.LayoutParams(dp(48.0f), dp(48.0f)))
            }
            header.addView(tools)
        }
    }

    private fun currentDirectory(): String? {
        val s: TerminalSession? = activity.getCurrentSession()
        return if (s == null) WorkspaceText.tr("Нет активной сессии") else compact(s.getCwd())
    }

    private fun compact(path: String?): String {
        if (path == null || path.isEmpty()) {
            return "~"
        }
        val home: String = TermuxConstants.TERMUX_HOME_DIR_PATH
        return if (path.equals(home) || path.startsWith(home + "/")) "~" + path.substring(home.length) else path
    }

    private fun renderNavigation() {
        navigation!!.removeAllViews()
        navigation.setPadding(dp(8.0f), 0, dp(8.0f), 0)
        val labels: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Главная"), WorkspaceText.tr("Команды/Заметки"), WorkspaceText.tr("Терминал"))
        val targets: Array<Screen> = arrayOf<Screen>(Screen.HOME, Screen.COMMANDS, Screen.TERMINAL)
        for (i in 0 until (targets.size)) {
            val target: Screen = targets[i]
            val nav: TextView = button("", false, Runnable { show(target, true) })
            nav.setTextSize(11.0f)
            nav.setPadding(0, dp(4.0f), 0, dp(4.0f))
            nav.setMinWidth(0)
            nav.setMinHeight(dp(56.0f))
            nav.setMaxLines(2)
            val resource: Int = if (target === Screen.HOME) R.drawable.workspace_home else if (target === Screen.COMMANDS) R.drawable.workspace_library else R.drawable.workspace_commands
            val icon: Drawable = activity.getDrawable(resource)!!.mutate()
            (nav as WorkspaceIconButton).setIcon(icon, dp(28.0f))
            nav.setContentDescription(labels[i])
            tintButton(nav, if (target === screen) Pal.ACCENT else Pal.MUTED)
            nav.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (target === screen) Pal.CARD2 else Pal.PANEL, if (target === screen) Pal.ACCENT else Pal.BORDER, 12), null))
            val tileParams = LinearLayout.LayoutParams(0, dp(56.0f), 1.0f)
            if (i < targets.size - 1) {
                tileParams.setMarginEnd(dp(8.0f))
            }
            navigation.addView(nav, tileParams)
        }
    }

    private fun live(): MutableList<TermuxSession> {
        val service: TermuxService? = activity.getTermuxService()
        return if (service == null) Collections.emptyList() else ArrayList<TermuxSession>(service.getTermuxSessions())
    }

    private fun liveFor(profile: WorkspaceStore.Session?): TerminalSession? {
        for (t in live()) {
            if (t.getTerminalSession().mHandle.equals(profile!!.handle)) {
                return t.getTerminalSession()
            }
        }
        return null
    }

    private fun leaveAgentInputIdle() {
        // Navigation must not activate either input. Explicit taps and the
        // interactive-input button still request focus normally.
        terminalInput.clearFocus()
        header!!.setFocusableInTouchMode(true)
        header.requestFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(terminalInput.getWindowToken(), 0)
    }

    private fun syncTerminalInput() {
        val current: TerminalSession? = activity.getCurrentSession()
        val handle: String? = if (current == null) null else current.mHandle
        if (!Objects.equals(handle, terminalInputHandle)) {
            geminiInputDispatch.cancel()
            if (terminalInputHandle != null) {
                terminalDrafts.put(terminalInputHandle!!, InputDraft(terminalInput))
            }
            terminalInputHandle = handle
            val selected: WorkspaceStore.Session? = if (handle == null) null else store.findByHandle(handle)
            if (selected != null && (selected.environment.equals("codex") || selected.claudeAgent || selected.geminiAgent || !selected.aiAgent.isEmpty())) {
                leaveAgentInputIdle()
            }
            val draft: InputDraft? = terminalDrafts.get(handle)
            terminalInput.clearComposingText()
            terminalInput.setText(if (draft == null) "" else draft.text)
            if (draft != null) {
                terminalInput.setSelection(Math.max(0, Math.min(draft.start, terminalInput.length())), Math.max(0, Math.min(draft.end, terminalInput.length())))
            }
        } else {
            return
        }
        if (activity.getTermuxService() != null) {
            val handles: MutableSet<String> = HashSet<String>()
            for (session in live()) {
                handles.add(session.getTerminalSession().mHandle)
            }
            terminalDrafts.keys.retainAll(handles)
        }
        if (terminalInput.hasFocus()) {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(terminalInput)
        }
    }

    private fun renderTabs() {
        syncTerminalInput()
        if (agentChatUi != null) {
            agentChatUi!!.sync()
        }
        if (desktopUi != null) {
            desktopUi!!.sync()
        }
        updateActivityLine()
        renderCodexStatus()
        tabs!!.removeAllViews()
        tabs.setPadding(dp(10.0f), dp(4.0f), dp(10.0f), dp(8.0f))
        tabs.setGravity(Gravity.TOP)
        val current: TerminalSession? = activity.getCurrentSession()
        val name: String? = if (current == null) WorkspaceText.tr("Сессии") else if (current.mSessionName == null) "Bash" else current.mSessionName
        val profile: WorkspaceStore.Session? = if (current == null) null else store.findByHandle(current.mHandle)
        val subtitle: String = if (profile == null) "" else ProjectFiles.environmentLabel(profile) + " · " + compact(profile.directory)
        val selector: TextView = button(name + "  ▾" + (if (subtitle.isEmpty()) "" else "\n" + subtitle), false, Runnable {  })
        selector.setTextSize(13.0f)
        selector.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        selector.setMaxLines(2)
        selector.setEllipsize(TextUtils.TruncateAt.END)
        selector.setContentDescription(name)
        selector.setOnClickListener(View.OnClickListener { v -> showSessionStack(selector) })
        if (current != null) {
            val panel = FrameLayout(activity)
            panel.addView(selector, FrameLayout.LayoutParams(-1, -1))
            val cancellable: Boolean = SessionShell.hasCancellableStartup(profile)
            selector.setPadding(selector.getPaddingLeft(), selector.getPaddingTop(), dp((if (cancellable) 96 else 52).toFloat()), selector.getPaddingBottom())
            val settings: TextView = sessionSettingsButton(name, false, Runnable {
                if (profile != null) {
                    sessionMenu(profile, true)
                }
            })
            settings.setTag("session-current-settings")
            settings.setEnabled(profile != null)
            panel.addView(settings, FrameLayout.LayoutParams(dp(40.0f), dp(40.0f), Gravity.END or Gravity.CENTER_VERTICAL))
            if (cancellable) {
                val cancel: TextView = button("", false, Runnable { confirmCancelStartup(profile) })
                cancel.setTag("cancel-startup")
                cancel.setContentDescription(WorkspaceText.tr("Отменить команды запуска"))
                val icon: Drawable = activity.getDrawable(R.drawable.workspace_cancel_startup)!!.mutate()
                icon.setTint(Pal.MUTED)
                (cancel as WorkspaceIconButton).setIcon(icon, dp(22.0f))
                cancel.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(Color.TRANSPARENT, Color.TRANSPARENT, 12), null))
                val cancelParams = FrameLayout.LayoutParams(dp(48.0f), dp(48.0f), Gravity.END or Gravity.CENTER_VERTICAL)
                cancelParams.setMarginEnd(dp(40.0f))
                panel.addView(cancel, cancelParams)
            }
            tabs.addView(panel, LinearLayout.LayoutParams(0, dp(48.0f), 1.0f))
        } else {
            tabs.addView(selector, LinearLayout.LayoutParams(0, dp(48.0f), 1.0f))
        }
        val serverTools: LinearLayout = row()
        val toolIcons: LinearLayout = serverTools
        val chat: Boolean = agentChatUi != null && agentChatUi!!.terminalAvailable()
        if (agentTerminalBorder != null) {
            agentTerminalBorder!!.active(false)
        }
        agentTerminalBorder = null
        val packages: TextView = button(if (chat) "" else "\ud83d\udce6", false, if (chat) Runnable { agentChatUi!!.terminal() } else Runnable(this::showCurrentPackages))
        packages.setContentDescription(if (chat) tr("Терминал ", "Terminal ") + agentChatUi!!.name() else WorkspaceText.tr("Пакеты"))
        if (chat) {
            (packages as WorkspaceIconButton).setIcon(activity.getDrawable(R.drawable.workspace_ai_agent)!!.mutate(), dp(22.0f))
            agentTerminalBorder = WorkspaceTransferBorder(activity.getResources().getDisplayMetrics().density)
            packages.setBackground(LayerDrawable(arrayOf<Drawable?>(packages.getBackground(), agentTerminalBorder)))
            agentTerminalBorder!!.active(agentChatUi!!.requiresTerminal())
        }
        toolIcons.addView(packages, LinearLayout.LayoutParams(dp(48.0f), dp(48.0f)))
        val files: TextView = button("▱", false, Runnable(this::showProjectFiles))
        files.setContentDescription(WorkspaceText.tr("Файлы проекта"))
        val fileParams = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        fileParams.setMarginStart(dp(10.0f))
        toolIcons.addView(files, fileParams)
        val serverLabel: String = if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Сервер + Agent" else "Server + Agent"
        val statusRow: FrameLayout? = activity.findViewById(R.id.workspace_terminal_tools)
        statusRow!!.setVisibility(if (screen === Screen.TERMINAL) View.VISIBLE else View.GONE)
        val oldServer: View? = statusRow.findViewWithTag("server-entry")
        if (oldServer != null) {
            statusRow.removeView(oldServer)
        }
        val server: TextView = AgentStatusUi.chip(activity, serverLabel, serverLabel, Runnable { show(Screen.SERVERS, true) })
        server.setTextColor(Pal.TEXT)
        server.setPadding(dp(4.0f), dp(4.0f), dp(4.0f), dp(4.0f))
        server.setTag("server-entry")
        if (transferBorder != null) {
            transferBorder!!.active(false)
        }
        transferBorder = WorkspaceTransferBorder(activity.getResources().getDisplayMetrics().density)
        server.setBackground(LayerDrawable(arrayOf<Drawable?>(server.getBackground(), transferBorder)))
        updateTransferBorder()
        val serverParams = FrameLayout.LayoutParams(dp(106.0f), -2, Gravity.END or Gravity.CENTER_VERTICAL)
        statusRow.addView(server, serverParams)
        if (agentStop == null) {
            agentStop = AgentStopButton(activity)
            agentStop!!.setTag("agent-stop")
            agentStop!!.setOnClickListener(View.OnClickListener { v ->
                if (agentChatUi != null) {
                    agentChatUi!!.stopCurrent()
                }
                updateActivityLine()
            })
            val stopParams = FrameLayout.LayoutParams(dp(44.0f), dp(44.0f), Gravity.END or Gravity.CENTER_VERTICAL)
            stopParams.setMarginEnd(dp(112.0f))
            statusRow.addView(agentStop, stopParams)
        }
        agentStop!!.setVisibility(if (chat) View.VISIBLE else View.GONE)
        val statusParams: FrameLayout.LayoutParams? = codexStatus!!.getLayoutParams() as FrameLayout.LayoutParams?
        val statusMargin: Int = dp((if (chat) 162 else 112).toFloat())
        if (statusParams!!.getMarginEnd() != statusMargin) {
            statusParams.setMarginEnd(statusMargin)
            codexStatus.setLayoutParams(statusParams)
        }
        activityLine!!.stopTarget(if (chat) agentStop else null)
        updateActivityLine()

        val toolsParams = LinearLayout.LayoutParams(dp(106.0f), -2)
        toolsParams.setMarginStart(dp(6.0f))
        tabs.addView(serverTools, toolsParams)
    }

    private fun sessionStackItem(label: String?, active: Boolean, icon: Int, click: Runnable): TextView {
        val item = TextView(activity)
        item.setText(label)
        item.setTextSize(14.0f)
        item.setTextColor(if (active) Pal.ONACCENT else Pal.TEXT)
        item.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
        item.setPadding(dp(13.0f), dp(8.0f), dp(13.0f), dp(8.0f))
        item.setMinHeight(dp(48.0f))
        item.setMaxLines(3)
        item.setEllipsize(TextUtils.TruncateAt.END)
        item.setSelected(active)
        item.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (active) Pal.ACCENT else Pal.PANEL, if (active) Pal.ACCENT else Pal.BORDER, 12), null))
        val image: Drawable = activity.getDrawable(icon)!!.mutate()
        image.setTint(if (active) Pal.ONACCENT else Pal.TEXT)
        image.setBounds(0, 0, dp(22.0f), dp(22.0f))
        item.setCompoundDrawables(image, null, null, null)
        item.setCompoundDrawablePadding(dp(8.0f))
        item.setOnClickListener(View.OnClickListener { v -> click.run() })
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(6.0f)
        item.setLayoutParams(lp)
        return item
    }

    private fun sessionSettingsButton(title: String?, active: Boolean, click: Runnable): TextView {
        val settings: TextView = button("", false, click)
        settings.setMinHeight(0)
        settings.setMinWidth(0)
        settings.setPadding(dp(8.0f), dp(8.0f), dp(8.0f), dp(8.0f))
        val icon: Drawable = activity.getDrawable(R.drawable.workspace_settings)!!.mutate()
        icon.setTint(if (active) Pal.ONACCENT else Pal.TEXT)
        (settings as WorkspaceIconButton).setIcon(icon, dp(20.0f))
        settings.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(Color.TRANSPARENT, Color.TRANSPARENT, 12), null))
        settings.setContentDescription(tr("Меню сессии: ", "Session menu: ") + title)
        return settings
    }

    private fun showSessionStack(anchor: View): PopupWindow {
        val body: LinearLayout = column()
        body.setPadding(dp(8.0f), dp(8.0f), dp(8.0f), dp(8.0f))
        body.setBackground(shape(Pal.PANEL, Pal.BORDER, 14))
        val heading: TextView = text(WorkspaceText.tr("Открытые сессии"), 14, Pal.TEXT)
        heading.setPadding(dp(6.0f), dp(4.0f), dp(6.0f), dp(10.0f))
        val scroll = ScrollView(activity)
        val list: LinearLayout = column()
        scroll.addView(list)
        val popup = PopupWindow(body, Math.min(dp(340.0f), activity.getResources().getDisplayMetrics().widthPixels - dp(24.0f)), -2, true)
        popup.setBackgroundDrawable(shape(Pal.PANEL, Pal.BORDER, 14))
        popup.setOutsideTouchable(true)
        popup.setElevation(dp(8.0f).toFloat())
        val create: TextView = sessionStackItem(WorkspaceText.tr("Создать новую сессию"), false, R.drawable.workspace_plus, Runnable {
            popup.dismiss()
            editSession(null)
        })
        create.setContentDescription(WorkspaceText.tr("Создать новую сессию"))
        create.setTag("session-stack-create")
        list.addView(create)
        list.addView(heading)
        for (ts in live()) {
            val terminal: TerminalSession = ts.getTerminalSession()
            val profile: WorkspaceStore.Session? = store.findByHandle(terminal.mHandle)
            val title: String? = if (terminal.mSessionName == null) "Bash" else terminal.mSessionName
            val info: String = if (profile == null) "" else ProjectFiles.environmentLabel(profile) + " · " + compact(profile.directory)
            val active: Boolean = activity.getCurrentSession() === terminal
            val item: TextView = sessionStackItem(title + (if (active) "  ✓" else "") + "\n" + info, active, sessionIcon(profile), Runnable {
                popup.dismiss()
                activity.getTermuxTerminalSessionClient()!!.setCurrentSession(terminal)
                show(Screen.TERMINAL, false)
            })
            item.setTag("session-stack-" + terminal.mHandle)
            item.setContentDescription(WorkspaceText.tr("Сессия ") + title + " · " + info)
            val frame = FrameLayout(activity)
            frame.setLayoutParams(item.getLayoutParams())
            item.setPadding(item.getPaddingLeft(), item.getPaddingTop(), dp(52.0f), item.getPaddingBottom())
            frame.addView(item, FrameLayout.LayoutParams(-1, -2))
            val settings: TextView = sessionSettingsButton(title, active, Runnable {
                popup.dismiss()
                if (profile != null) {
                    sessionMenu(profile, true)
                }
            })
            settings.setTag("session-stack-settings-" + terminal.mHandle)
            settings.setEnabled(profile != null)
            frame.addView(settings, FrameLayout.LayoutParams(dp(40.0f), dp(40.0f), Gravity.END or Gravity.CENTER_VERTICAL))
            list.addView(frame)
        }

        if (live().isEmpty()) {
            list.addView(text(WorkspaceText.tr("Нет открытых сессий. Создайте новую сессию."), 13, Pal.MUTED))
        }
        body.addView(scroll, LinearLayout.LayoutParams(-1, Math.min(dp(360.0f), Math.max(dp(140.0f), dp(86.0f) + live().size * dp(76.0f)))))
        body.addView(text(tr("Шестерёнка — меню сессии", "Gear — session menu"), 12, Pal.MUTED))
        val cancel: View? = tabs!!.findViewWithTag("cancel-startup")
        if (cancel != null) {
            cancel.setVisibility(View.INVISIBLE)
            popup.setOnDismissListener(PopupWindow.OnDismissListener { cancel.setVisibility(View.VISIBLE) })
        }
        popup.showAsDropDown(anchor, 0, dp(4.0f))
        return popup
    }

    private fun showCurrentPackages() {
        val terminal: TerminalSession? = activity.getCurrentSession()
        val profile: WorkspaceStore.Session? = if (terminal == null) null else store.findByHandle(terminal.mHandle)
        if (profile == null) {
            activity.showToast(WorkspaceText.tr("Сначала откройте сессию"), false)
            return
        }
        showPackages(profile)
    }

    private fun showProjectFiles() {
        val terminal: TerminalSession? = activity.getCurrentSession()
        val profile: WorkspaceStore.Session? = if (terminal == null) null else store.findByHandle(terminal.mHandle)
        if (profile == null) {
            activity.showToast(WorkspaceText.tr("Сначала откройте сессию"), false)
            return
        }
        showFileBrowser(profile, profile.directory, null)
    }

    private fun showFileBrowser(profile: WorkspaceStore.Session, path: String?, choice: WorkspaceFileBrowser.DirectoryChoice?) {
        val browser = WorkspaceFileBrowser(activity, profile, path, choice)
        browser.setOnClosed(Runnable { fileBrowsers.remove(browser) })
        fileBrowsers.add(browser)
        browser.show()
    }

    private fun renderContent() {
        if (screen === Screen.TERMINAL) {
            content!!.setVisibility(View.GONE)
            activity.getDrawer()!!.setVisibility(View.VISIBLE)
            return
        }
        commandResults = null
        noteResults = null
        content!!.removeAllViews()
        val body: LinearLayout = column()
        body.setPadding(dp(16.0f), 0, dp(16.0f), dp(12.0f))
        if (screen === Screen.SERVERS) {
            content.addView(body, FrameLayout.LayoutParams(-1, -1))
            servers.attach(body)
            return
        }
        if (screen === Screen.HOME || screen === Screen.COMMANDS) {
            val viewport = AdaptiveWorkspaceLayout(activity)
            viewport.setContentDescription(WorkspaceText.tr("Адаптивная область списков"))
            viewport.addView(body)
            content.addView(viewport, FrameLayout.LayoutParams(-1, -1))
            if (screen === Screen.HOME) {
                home(body)
            } else {
                commands(body)
            }
        } else {
            val scroll = ScrollView(activity)
            scroll.setFillViewport(true)
            scroll.addView(body)
            content.addView(scroll)
            if (screen === Screen.COMMANDS) {
                commands(body)
            } else {
                sessions(body)
            }
        }
    }

    private fun home(body: LinearLayout) {
        section(body, WorkspaceText.tr("Недавние команды"), if (store.recentCommands.isEmpty()) null else WorkspaceText.tr("Очистить"), Runnable {
            if (!store.isWritable()) {
                activity.showToast(WorkspaceText.tr("Журнал недоступен для записи"), true)
                return@Runnable
            }
            confirm(WorkspaceText.tr("Очистить весь журнал недавних команд?"), Runnable {
                store.clearRecentCommands()
                renderContent()
            })
        })
        val search: EditText = field(WorkspaceText.tr("Поиск по журналу"), journalQuery, false)
        search.setContentDescription(WorkspaceText.tr("Поиск по журналу"))
        val searchParams = LinearLayout.LayoutParams(-1, -2)
        search.setMinHeight(dp(48.0f))
        searchParams.setMargins(0, 0, 0, dp(12.0f))
        body.addView(search, searchParams)
        val journal: ScrollView = WorkspacePanelScrollView(activity)
        journal.setBackground(shape(Pal.PANEL, Pal.BORDER, 16))
        journal.setClipToOutline(true)
        journal.setContentDescription(WorkspaceText.tr("Журнал последних 50 команд"))
        val entries: LinearLayout = column()
        entries.setPadding(dp(12.0f), dp(12.0f), dp(12.0f), dp(12.0f))
        journal.addView(entries)
        body.addView(journal, LinearLayout.LayoutParams(-1, 0, 1.0f))
        val count: TextView = text("", 11, Pal.MUTED)
        count.setPadding(0, dp(8.0f), 0, 0)
        body.addView(count)
        renderJournal(entries, count)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, after: Int) {
                journalQuery = s.toString()
                renderJournal(entries, count)
                journal.scrollTo(0, 0)
            }

            override fun afterTextChanged(s: Editable) {
            }
        })
        section(body, WorkspaceText.tr("Пресеты запуска"), "+", Runnable { editSession(null, false) })
        val saved: ScrollView = WorkspacePanelScrollView(activity)
        saved.setBackground(shape(Pal.PANEL, Pal.BORDER, 16))
        saved.setClipToOutline(true)
        saved.setContentDescription(WorkspaceText.tr("Список пресетов запуска"))
        val profiles: LinearLayout = column()
        profiles.setPadding(dp(12.0f), dp(12.0f), dp(12.0f), dp(12.0f))
        saved.addView(profiles)
        body.addView(saved, LinearLayout.LayoutParams(-1, 0, 1.0f))
        var savedCount = 0
        for (profile in sortedSessions()) {
            if (profile!!.saved) {
                sessionCard(profiles, profile)
                savedCount++
            }
        }
        if (savedCount == 0) {
            empty(profiles, WorkspaceText.tr("Пресетов запуска нет"), WorkspaceText.tr("Нажмите +, чтобы подготовить команду запуска."))
        }
    }

    private fun renderJournal(entries: LinearLayout, count: TextView) {
        entries.removeAllViews()
        var shown = 0
        if (store.recentCommands.isEmpty()) {
            entries.addView(text(WorkspaceText.tr("Команд пока нет"), 16, Pal.TEXT))
            val hint: TextView = text(WorkspaceText.tr("Здесь появятся команды, отправленные из поля ввода терминала."), 13, Pal.MUTED)
            hint.setPadding(0, dp(8.0f), 0, 0)
            entries.addView(hint)
        }
        for (recent in store.recentCommands) {
            if (!(recent.command as java.lang.String).toLowerCase(Locale.ROOT).contains((journalQuery as java.lang.String).toLowerCase(Locale.ROOT))) {
                continue
            }
            shown++
            val card: LinearLayout = card(entries)
            card.setBackground(shape(Pal.BG, Pal.BORDER, 12))
            val labels: LinearLayout = column()
            val entry: TextView = text(recent.command, 13, Pal.TEXT)
            entry.setTypeface(Typeface.MONOSPACE)
            labels.addView(entry)
            val time: String? = if (recent.sentAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(recent.sentAt)) else WorkspaceText.tr("Ранее")
            val timestamp: TextView = text(time, 11, Pal.MUTED)
            timestamp.setPadding(0, dp(6.0f), 0, 0)
            labels.addView(timestamp)
            val insert = Runnable { recentMenu(recent) }
            entry.setOnClickListener(View.OnClickListener { v -> insert.run() })
            labels.setOnClickListener(View.OnClickListener { v -> insert.run() })
            card.setOnClickListener(View.OnClickListener { v -> insert.run() })
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1.0f))
        }
        if (shown == 0 && !store.recentCommands.isEmpty()) {
            entries.addView(text(WorkspaceText.tr("Ничего не найдено"), 14, Pal.MUTED))
        }
        count.setText(store.recentCommands.size.toString() + " / " + WorkspaceStore.MAX_RECENT_COMMANDS + (if (journalQuery.isEmpty()) "" else WorkspaceText.tr(" · найдено: ") + shown))
    }

    private fun recentMenu(recent: WorkspaceStore.RecentCommand) {
        hideDialogKeyboard()
        val body: LinearLayout = previewBody(recent.command, true)
        val time: TextView = text(if (recent.sentAt > 0) SimpleDateFormat("dd.MM · HH:mm", Locale.getDefault()).format(Date(recent.sentAt)) else WorkspaceText.tr("Ранее"), 12, Pal.MUTED)
        time.setPadding(0, 0, 0, dp(10.0f))
        body.addView(time, 0)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Недавняя команда")).setView(formScroll(body)).setPositiveButton(WorkspaceText.tr("Закрыть"), null).create()
        previewAction(body, WorkspaceText.tr("Вставить в терминал"), R.drawable.workspace_commands, Runnable {
            dialog.dismiss()
            val command = WorkspaceStore.Command()
            command.command = recent.command
            insertCommand(command)
        })
        previewAction(body, WorkspaceText.tr("Сохранить в команды"), R.drawable.workspace_save, Runnable {
            dialog.dismiss()
            editCommand(null, recent.command)
        })
        previewAction(body, WorkspaceText.tr("Скопировать"), R.drawable.workspace_copy, Runnable {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(WorkspaceText.tr("Команда"), recent.command))
            dialog.dismiss()
            activity.showToast(WorkspaceText.tr("Команда скопирована"), false)
        })
        dialog.show()
    }

    private fun hideDialogKeyboard() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(content!!.getWindowToken(), 0)
    }

    private fun previewBody(value: String?, code: Boolean): LinearLayout {
        val body: LinearLayout = column()
        val pane = ScrollView(activity)
        pane.setFillViewport(true)
        pane.setContentDescription(if (code) WorkspaceText.tr("Полный текст команды") else WorkspaceText.tr("Полный текст заметки"))
        pane.setBackground(shape(Pal.PANEL, Pal.BORDER, 12))
        pane.setClipToOutline(true)
        val full: TextView = text(value, 14, Pal.TEXT)
        full.setPadding(dp(14.0f), dp(14.0f), dp(14.0f), dp(14.0f))
        full.setTextIsSelectable(true)
        if (code) {
            full.setTypeface(Typeface.MONOSPACE)
        }
        pane.addView(full)
        pane.setOnTouchListener(View.OnTouchListener { v, event ->
            v.getParent().requestDisallowInterceptTouchEvent(event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL)
            return@OnTouchListener false
        })
        val available: Int = activity.getResources().getDisplayMetrics().heightPixels
        val height: Int = Math.min(dp(240.0f), Math.max(dp(96.0f), available / 3))
        body.addView(pane, LinearLayout.LayoutParams(-1, height))
        return body
    }

    private fun previewAction(body: LinearLayout, label: String?, icon: Int, click: Runnable) {
        val action: TextView = WorkspaceDialogs.action(activity, label, icon)
        action.setOnClickListener(View.OnClickListener { v -> click.run() })
        val params = LinearLayout.LayoutParams(-1, -2)
        params.topMargin = dp(10.0f)
        body.addView(action, params)
    }

    private fun previewMetadata(body: LinearLayout, category: String?, favorite: Boolean) {
        val label: TextView = text(category + (if (favorite) "   ★" else ""), 12, if (favorite) Pal.ACCENT else Pal.MUTED)
        label.setPadding(0, 0, 0, dp(12.0f))
        body.addView(label, 0)
    }

    private fun openCommand(command: WorkspaceStore.Command) {
        hideDialogKeyboard()
        val body: LinearLayout = previewBody(command.command, true)
        previewMetadata(body, command.category, command.favorite)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(command.name).setView(formScroll(body)).setPositiveButton(WorkspaceText.tr("Закрыть"), null).create()
        previewAction(body, WorkspaceText.tr("Вставить в терминал"), R.drawable.workspace_commands, Runnable {
            dialog.dismiss()
            insertCommand(command)
        })
        previewAction(body, if (command.favorite) WorkspaceText.tr("Убрать из избранного") else WorkspaceText.tr("В избранное"), R.drawable.workspace_star, Runnable {
            if (!store.isWritable()) {
                activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
                return@Runnable
            }
            command.favorite = !command.favorite
            store.save()
            dialog.dismiss()
            renderContent()
        })
        previewAction(body, WorkspaceText.tr("Редактировать"), R.drawable.workspace_edit, Runnable {
            dialog.dismiss()
            editCommand(command)
        })
        previewAction(body, WorkspaceText.tr("Удалить"), R.drawable.workspace_delete, Runnable {
            if (!store.isWritable()) {
                activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
                return@Runnable
            }
            dialog.dismiss()
            confirm(WorkspaceText.tr("Удалить команду «") + command.name + "»?", Runnable {
                store.commands.remove(command)
                store.save()
                renderContent()
            })
        })
        dialog.show()
    }

    private fun chips(body: LinearLayout, options: Array<String?>, selected: String?, click: Consumer<String?>) {
        val scroller = HorizontalScrollView(activity)
        scroller.setHorizontalScrollBarEnabled(false)
        val items: LinearLayout = row()
        items.setPadding(0, dp(10.0f), 0, dp(10.0f))
        for (option in options) {
            val chip: TextView = button(option, option!!.equals(selected), Runnable { click.accept(option) })
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMarginEnd(dp(8.0f))
            items.addView(chip, lp)
        }
        scroller.addView(items)
        body.addView(scroller)
    }

    private fun searchSummary(body: LinearLayout) {
        if (!query.isEmpty()) {
            body.addView(button(WorkspaceText.tr("Поиск: ") + query + "  ×", false, Runnable {
                query = ""
                renderContent()
            }))
        }
    }

    private fun commands(body: LinearLayout) {
        if (!commandCategory!!.isEmpty() && !commandCategories().contains(commandCategory)) {
            commandCategory = ""
        }
        if (!noteCategory!!.isEmpty() && !noteCategories().contains(noteCategory)) {
            noteCategory = ""
        }
        if (!aliasCategory!!.isEmpty() && !aliasCategories().contains(aliasCategory)) {
            aliasCategory = ""
        }
        libraryFilters(body, false)
        commandResults = libraryPanel(body, WorkspaceText.tr("Список сохранённых команд"))
        libraryFilters(body, true)
        noteResults = libraryPanel(body, WorkspaceText.tr("Список сохранённых заметок"))
        renderLibraryResults()
    }

    private fun renderLibraryResults() {
        if (screen !== Screen.COMMANDS || commandResults == null || noteResults == null) {
            return
        }
        commandResults!!.removeAllViews()
        noteResults!!.removeAllViews()
        var count = 0
        if (aliasesSelected) {
            for (a in store.aliases) {
                if ((!aliasFavorites || a.favorite) && (aliasCategory!!.isEmpty() || aliasCategory!!.equals(a.category)) && matches(a.name + " " + a.command + " " + a.category + " " + WorkspaceAliases.label(a.environment))) {
                    aliasCard(commandResults, a)
                    count++
                }
            }
            if (count == 0) {
                empty(commandResults, tr("Aliases пока нет", "No aliases yet"), tr("Нажмите +, чтобы создать alias, или измените фильтры.", "Tap + to create an alias, or change the filters."))
            }
        } else {
            for (c in store.commands) {
                if ((!favorites || c.favorite) && (commandCategory!!.isEmpty() || commandCategory!!.equals(c.category)) && matches(c.name + " " + c.command + " " + c.category)) {
                    commandCard(commandResults, c)
                    count++
                }
            }
            if (count == 0) {
                empty(commandResults, WorkspaceText.tr("Команд пока нет"), WorkspaceText.tr("Нажмите +, чтобы сохранить команду, или измените фильтры."))
            }
        }
        count = 0
        for (n in store.notes) {
            if ((!noteFavorites || n.favorite) && (noteCategory!!.isEmpty() || noteCategory!!.equals(n.category)) && matches(n.name + " " + n.text + " " + n.category)) {
                noteCard(noteResults, n)
                count++
            }
        }
        if (count == 0) {
            empty(noteResults, WorkspaceText.tr("Заметок пока нет"), WorkspaceText.tr("Нажмите +, чтобы сохранить заметку, или измените фильтры."))
        }
    }

    private fun libraryFilters(body: LinearLayout, notes: Boolean) {
        if (!notes) {
            aliasLibraryFilters(body)
            return
        }
        val line: LinearLayout = row()
        line.setPadding(0, dp(10.0f), 0, dp(10.0f))
        line.setContentDescription(if (notes) WorkspaceText.tr("Фильтры заметок") else WorkspaceText.tr("Фильтры команд"))
        val items: LinearLayout = row()
        val all: String? = if (notes) WorkspaceText.tr("Все заметки") else WorkspaceText.tr("Все команды")
        for (option in arrayOf<String?>(all, WorkspaceText.tr("Избранное"))) {
            val active: Boolean = (if (notes) noteCategory else commandCategory)!!.isEmpty() && (if (notes) noteFavorites else favorites) == option!!.equals(WorkspaceText.tr("Избранное"))
            val chip: TextView = button(option, active, Runnable {
                if (notes) {
                    noteFavorites = option!!.equals(WorkspaceText.tr("Избранное"))
                    noteCategory = ""
                } else {
                    favorites = option!!.equals(WorkspaceText.tr("Избранное"))
                    commandCategory = ""
                }
                renderContent()
            })
            chip.setSelected(active)
            compactFilter(chip)
            val params = LinearLayout.LayoutParams(0, -2, 1.0f)
            params.setMarginEnd(dp(4.0f))
            items.addView(chip, params)
        }
        val selected: String? = if (notes) noteCategory else commandCategory
        val category: TextView = button((if (selected!!.isEmpty()) WorkspaceText.tr("Категории") else selected) + " ▾", !selected.isEmpty(), Runnable {
            if (notes) {
                chooseNoteCategory()
            } else {
                chooseCommandCategory()
            }
        })
        category.setContentDescription(if (notes) WorkspaceText.tr("Фильтр категорий заметок") else WorkspaceText.tr("Фильтр категорий команд"))
        category.setSelected(!selected.isEmpty())
        compactFilter(category)
        items.addView(category, LinearLayout.LayoutParams(0, -2, 1.0f))
        line.addView(items, LinearLayout.LayoutParams(0, -2, 1.0f))
        val add: TextView = button("+", true, Runnable {
            if (notes) {
                editNote(null)
            } else {
                editCommand(null)
            }
        })
        add.setContentDescription(if (notes) WorkspaceText.tr("Новая заметка") else WorkspaceText.tr("Новая команда"))
        val params = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        params.setMarginStart(dp(8.0f))
        line.addView(add, params)
        body.addView(line)
    }

    private fun newLibraryEntry() {
        val body: LinearLayout = column()
        body.setPadding(dp(16.0f), dp(8.0f), dp(16.0f), dp(12.0f))
        val choices: LinearLayout = row()
        body.addView(choices)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setView(body).create()
        val command: TextView = button(WorkspaceText.tr("Новая команда"), false, Runnable {
            dialog.dismiss()
            editCommand(null)
        })
        val alias: TextView = button(tr("Новый alias", "New alias"), false, Runnable {
            dialog.dismiss()
            editAlias(null)
        })
        val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
        lp.setMarginEnd(dp(8.0f))
        choices.addView(command, lp)
        choices.addView(alias, LinearLayout.LayoutParams(0, -2, 1.0f))
        dialog.show()
    }

    private fun aliasLibraryFilters(body: LinearLayout) {
        val line: LinearLayout = row()
        line.setPadding(0, dp(10.0f), 0, dp(10.0f))
        line.setContentDescription(WorkspaceText.tr("Фильтры команд"))
        val items: LinearLayout = row()
        line.addView(items, LinearLayout.LayoutParams(0, -2, 1.0f))
        val selected: String? = if (aliasesSelected) aliasCategory else commandCategory
        val onlyFavorites: Boolean = if (aliasesSelected) aliasFavorites else favorites
        val commands: TextView = button(WorkspaceText.tr("Все команды"), !aliasesSelected && selected!!.isEmpty() && !onlyFavorites, Runnable {
            aliasesSelected = false
            favorites = false
            commandCategory = ""
            renderContent()
        })
        val aliases: TextView = button(tr("Все Alias", "All aliases"), aliasesSelected && selected!!.isEmpty() && !onlyFavorites, Runnable {
            aliasesSelected = true
            aliasFavorites = false
            aliasCategory = ""
            renderContent()
        })
        val categories: TextView = button((if (onlyFavorites) WorkspaceText.tr("Избранное") else if (selected!!.isEmpty()) WorkspaceText.tr("Категории") else selected) + " ▾", !selected!!.isEmpty() || onlyFavorites, Runnable(this::chooseLibraryCategory))
        categories.setContentDescription(WorkspaceText.tr("Фильтр категорий команд"))
        for (chip in arrayOf<TextView>(commands, aliases, categories)) {
            compactFilter(chip)
            val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
            lp.setMarginEnd(dp(4.0f))
            items.addView(chip, lp)
        }
        commands.setSelected(!aliasesSelected && selected.isEmpty() && !onlyFavorites)
        aliases.setSelected(aliasesSelected && selected.isEmpty() && !onlyFavorites)
        categories.setSelected(!selected.isEmpty() || onlyFavorites)
        val add: TextView = button("+", true, Runnable(this::newLibraryEntry))
        add.setContentDescription(tr("Новая команда или alias", "New command or alias"))
        val lp = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        lp.setMarginStart(dp(4.0f))
        line.addView(add, lp)
        body.addView(line)
    }

    private fun aliasCategories(): MutableList<String?> {
        val names: MutableSet<String?> = LinkedHashSet<String?>()
        for (a in store.aliases) {
            if (!a.category!!.isEmpty()) {
                names.add(a.category)
            }
        }
        return ArrayList<String?>(names)
    }

    private fun chooseLibraryCategory() {
        val body: LinearLayout = column()
        body.setPadding(dp(16.0f), dp(8.0f), dp(16.0f), dp(8.0f))
        val choices: LinearLayout = row()
        body.addView(choices)
        val entries: LinearLayout = column()
        val scroll = ScrollView(activity)
        scroll.addView(entries)
        body.addView(scroll, LinearLayout.LayoutParams(-1, dp(280.0f)))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Категории")).setView(body).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        val alias: BooleanArray = booleanArrayOf(aliasesSelected)
        val commands: TextView = button(WorkspaceText.tr("Категории команд"), false, Runnable {  })
        val aliases: TextView = button(tr("Категории Alias", "Alias categories"), false, Runnable {  })
        val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
        lp.setMarginEnd(dp(8.0f))
        choices.addView(commands, lp)
        choices.addView(aliases, LinearLayout.LayoutParams(0, -2, 1.0f))
        val render = Runnable {
            entries.removeAllViews()
            for (tab in arrayOf<TextView>(commands, aliases)) {
                val active: Boolean = (tab === aliases) == alias[0]
                tab.setSelected(active)
                tab.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (active) Pal.CARD2 else Pal.PANEL, if (active) Pal.ACCENT else Pal.BORDER, 12), null))
            }
            entries.addView(button(WorkspaceText.tr("Избранное"), if (alias[0]) aliasFavorites else favorites, Runnable {
                aliasesSelected = alias[0]
                if (alias[0]) {
                    aliasFavorites = true
                    aliasCategory = ""
                } else {
                    favorites = true
                    commandCategory = ""
                }
                dialog.dismiss()
                renderContent()
            }))
            val categories: MutableList<String?> = if (alias[0]) aliasCategories() else commandCategories()
            val labels: MutableList<String?> = ArrayList<String?>()
            labels.add(WorkspaceText.tr("Все категории"))
            labels.addAll(categories)
            var i = 0
            while (i < labels.size) {
                val value: String? = if (i == 0) "" else labels.get(i)
                entries.addView(button(labels.get(i), value!!.equals(if (alias[0]) aliasCategory else commandCategory) && !(if (alias[0]) aliasFavorites else favorites), Runnable {
                    aliasesSelected = alias[0]
                    if (alias[0]) {
                        aliasCategory = value
                        aliasFavorites = false
                    } else {
                        commandCategory = value
                        favorites = false
                    }
                    dialog.dismiss()
                    renderContent()
                }))
                i++
            }
        }
        commands.setOnClickListener(View.OnClickListener { v ->
            alias[0] = false
            render.run()
        })
        aliases.setOnClickListener(View.OnClickListener { v ->
            alias[0] = true
            render.run()
        })
        render.run()
        dialog.show()
    }

    private fun aliasCard(parent: LinearLayout?, a: WorkspaceStore.Alias) {
        val card: LinearLayout = card(parent)
        card.setBackground(shape(Pal.BG, Pal.BORDER, 12))
        cardLabels(card, a.name, a.command, WorkspaceAliases.label(a.environment) + " · " + a.category + (if (a.favorite) "  ★" else ""), false)
        (card.getChildAt(0) as TextView).setText("≋")
        card.setContentDescription("Alias " + a.name)
        card.setOnClickListener(View.OnClickListener { v -> openAlias(a, null) })
    }

    private fun openAlias(a: WorkspaceStore.Alias, back: Runnable?) {
        hideDialogKeyboard()
        val body: LinearLayout = previewBody(a.command, true)
        previewMetadata(body, WorkspaceAliases.label(a.environment) + " · " + a.category, a.favorite)
        body.addView(text(tr("Будет автоматически доступен в новых терминальных сессиях выбранного окружения.", "Automatically available in new terminal sessions of the selected environment."), 12, Pal.MUTED))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(a.name).setView(formScroll(body)).setPositiveButton(WorkspaceText.tr(if (back == null) "Закрыть" else "Назад"), DialogInterface.OnClickListener { d, w ->
            if (back != null) {
                back.run()
            }
        }).create()
        dialog.setOnCancelListener(DialogInterface.OnCancelListener { d ->
            if (back != null) {
                back.run()
            }
        })
        previewAction(body, WorkspaceText.tr("Вставить в сессию"), R.drawable.workspace_commands, Runnable {
            dialog.dismiss()
            insertAlias(a)
        })
        previewAction(body, if (a.favorite) WorkspaceText.tr("Убрать из избранного") else WorkspaceText.tr("В избранное"), R.drawable.workspace_star, Runnable {
            if (!store.isWritable() || ALIAS_BUSY.get()) {
                return@Runnable
            }
            a.favorite = !a.favorite
            store.save()
            dialog.dismiss()
            renderContent()
        })
        previewAction(body, WorkspaceText.tr("Редактировать"), R.drawable.workspace_edit, Runnable {
            dialog.dismiss()
            editAlias(a)
        })
        previewAction(body, WorkspaceText.tr("Удалить"), R.drawable.workspace_delete, Runnable {
            dialog.dismiss()
            confirm(tr("Удалить alias «", "Delete alias “") + a.name + "»?\n\n" + (if (a.environment.equals("termux")) tr("Перезапустите вкладку после удаления Alias", "Restart the tab after deleting the alias") else tr("Alias будет удалён из окружения. Чтобы он перестал работать в уже открытом терминале, закройте эту сессию и откройте её заново.", "The alias will be removed from the environment. To stop using the already loaded alias in an open terminal, close that session and open it again.")), Runnable { changeAlias(a, null, null, null) })
        })
        dialog.show()
    }

    private fun insertAlias(a: WorkspaceStore.Alias) {
        val current: TerminalSession? = activity.getCurrentSession()
        val profile: WorkspaceStore.Session? = if (current == null) null else store.findByHandle(current.mHandle)
        val env: String = if (profile == null) "termux" else if (!WorkspaceEnvironments.alias(profile).isEmpty()) WorkspaceEnvironments.alias(profile) else if (profile.environment.equals("legacy")) "termux" else profile.environment
        if (profile != null && !AgentPreparation.agent(profile).isEmpty() || !a.environment.equals(env) || current == null || !current.isRunning()) {
            activity.showToast(tr("Выберите работающую терминальную сессию окружения ", "Select a running terminal session in ") + WorkspaceAliases.label(a.environment), true)
            return
        }
        val value = WorkspaceStore.Command()
        value.command = a.name
        insertIntoCurrent(value)
    }

    private fun editAlias(existing: WorkspaceStore.Alias?) {
        if (!store.isWritable() || ALIAS_BUSY.get()) {
            activity.showToast(tr("Сохранение aliases пока недоступно", "Alias changes are currently unavailable"), true)
            return
        }
        val draft: WorkspaceStore.Alias = if (existing == null) WorkspaceStore.Alias() else existing.copy()
        if (existing == null) {
            draft.category = WorkspaceText.tr("Общее")
        }
        val choices: MutableList<WorkspaceEnvironments.Choice> = WorkspaceAliases.installed(WorkspaceEnvironments.choices())
        val form: LinearLayout = column()
        val name: EditText = labeled(form, tr("Имя", "Name"), draft.name, false)
        name.setContentDescription(tr("Имя alias", "Alias name"))
        val command: EditText = labeled(form, WorkspaceText.tr("Команда"), draft.command, true)
        form.addView(text(tr("Окружение", "Environment"), 12, Pal.MUTED))
        val environment: TextView = button(WorkspaceAliases.label(draft.environment) + " ▾", false, Runnable {  })
        form.addView(environment)
        val termuxHint: TextView = text(tr("Перезапустите вкладку для добавления Alias", "Restart the tab to load the alias"), 12, Pal.MUTED)
        form.addView(termuxHint)
        termuxHint.setVisibility(if (draft.environment.equals("termux")) View.VISIBLE else View.GONE)
        environment.setOnClickListener(View.OnClickListener { v ->
            val labels = arrayOfNulls<String>(choices.size)
            var selected = -1
            for (i in 0 until (labels.size)) {
                val c: WorkspaceEnvironments.Choice = choices.get(i)
                labels[i] = c.label
                if ((if (c.alias.isEmpty()) "termux" else c.alias).equals(draft.environment)) {
                    selected = i
                }
            }
            WorkspaceDialogs.builder(activity).setTitle(tr("Окружение", "Environment")).setSingleChoiceItems(labels, selected, DialogInterface.OnClickListener { d, w ->
                val c: WorkspaceEnvironments.Choice = choices.get(w)
                draft.environment = if (c.alias.isEmpty()) "termux" else c.alias
                environment.setText(c.label + " ▾")
                termuxHint.setVisibility(if (draft.environment.equals("termux")) View.VISIBLE else View.GONE)
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        val category: EditText = categoryField(form, draft.category, aliasCategories())
        val favorite = CheckBox(activity)
        favorite.setText(WorkspaceText.tr("Добавить в избранное"))
        favorite.setTextColor(Pal.TEXT)
        favorite.setChecked(draft.favorite)
        form.addView(favorite)
        form.addView(text(tr("Будет автоматически доступен в новых терминальных сессиях выбранного окружения.", "Automatically available in new terminal sessions of the selected environment."), 12, Pal.MUTED))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (existing == null) tr("Новый alias", "New alias") else tr("Редактировать alias", "Edit alias")).setView(formScroll(form)).setPositiveButton(WorkspaceText.tr("Сохранить"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                draft.favorite = favorite.isChecked()
                draft.name = (name.getText().toString() as java.lang.String).trim()
                draft.command = command.getText().toString()
                draft.category = (category.getText().toString() as java.lang.String).trim()
                if (draft.category!!.isEmpty()) {
                    draft.category = WorkspaceText.tr("Общее")
                }
                if (!WorkspaceAliases.validName(draft.name)) {
                    name.setError(tr("Имя: латинская буква или _, затем буквы, цифры, _ или -. До 64 символов.", "Start with a letter or _, then letters, digits, _ or -. Up to 64 characters."))
                    return@OnClickListener
                }
                if (!WorkspaceAliases.valid(draft)) {
                    command.setError(tr("Введите команду до 16 384 символов без NUL и CR.", "Enter a command up to 16,384 characters without NUL or CR."))
                    return@OnClickListener
                }
                var collision: WorkspaceStore.Alias? = null
                for (a in store.aliases) {
                    if (a !== existing && a.environment.equals(draft.environment) && a.name.equals(draft.name)) {
                        collision = a
                    }
                }
                val replace: WorkspaceStore.Alias? = collision
                val save = Runnable { changeAlias(existing, draft.copy(), replace, dialog) }
                if (replace != null) {
                    confirm(tr("Заменить существующий alias «", "Replace existing alias “") + draft.name + "»?", save)
                } else {
                    save.run()
                }
            })
        })
        dialog.show()
    }

    private fun changeAlias(before: WorkspaceStore.Alias?, after: WorkspaceStore.Alias?, collision: WorkspaceStore.Alias?, editor: AlertDialog?) {
        if (!store.isWritable() || !ALIAS_BUSY.compareAndSet(false, true)) {
            return
        }
        val progress: AlertDialog = WorkspaceDialogs.builder(activity).setMessage(if (after == null) tr("Удаление alias из окружения…", "Removing alias from the environment…") else tr("Сохранение alias в окружении…", "Saving alias in the environment…")).create()
        progress.setCancelable(false)
        progress.setCanceledOnTouchOutside(false)
        progress.show()
        reader.execute(Runnable {
            var failure: Exception? = null
            try {
                WorkspaceAliases.change(before, after, collision, WorkspaceAliases.Installer { value, delete -> WorkspaceAliases.apply(activity, value, delete, "") })
            } catch (error: Exception) {
                failure = error
            }
            val result: Exception? = failure
            activity.runOnUiThread(Runnable {
                ALIAS_BUSY.set(false)
                if (progress.isShowing()) {
                    progress.dismiss()
                }
                if (result != null) {
                    if (!closed) {
                        WorkspaceDialogs.builder(activity).setTitle(if (after == null) tr("Alias не удалён", "Alias was not removed") else tr("Alias не сохранён", "Alias was not saved")).setMessage(tr("Проверьте наличие окружения, свободное место и права на файлы оболочки. Форма сохранена. Код: ", "Check the environment, free storage and shell-file permissions. Your draft is retained. Code: ") + result.message).setPositiveButton("OK", null).show()
                    }
                    return@Runnable
                }
                val target: WorkspaceStore = if (closed) WorkspaceStore(activity, WorkspaceStore.ErrorListener { message ->  }) else store
                if (before != null) {
                    target.aliases.removeIf(Predicate<WorkspaceStore.Alias> { a -> a.id!!.equals(before.id) })
                }
                if (collision != null) {
                    target.aliases.removeIf(Predicate<WorkspaceStore.Alias> { a -> a.id!!.equals(collision.id) })
                }
                if (after != null) {
                    target.aliases.add(after)
                }
                target.save()
                if (closed) {
                    target.close()
                }
                if (!closed) {
                    if (editor != null) {
                        editor.dismiss()
                    }
                    aliasesSelected = true
                    renderContent()
                    val termux: Boolean = (if (after == null) before else after)!!.environment.equals("termux")
                    activity.showToast(if (after == null) if (termux) tr("Перезапустите вкладку после удаления Alias", "Restart the tab after deleting the alias") else tr("Alias удалён. Открытую сессию нужно закрыть и открыть заново.", "Alias removed. Close and reopen any session that already loaded it.") else if (termux) tr("Перезапустите вкладку для добавления Alias", "Restart the tab to load the alias") else tr("Сохранено. Изменения доступны в новых сессиях окружения.", "Saved. Changes are available in new environment sessions."), true)
                }
            })
        })
    }

    private fun compactFilter(filter: TextView) {
        filter.setPadding(dp(4.0f), dp(8.0f), dp(4.0f), dp(8.0f))
        filter.setMinWidth(0)
        filter.setMaxLines(2)
        filter.setEllipsize(TextUtils.TruncateAt.END)
    }

    private fun libraryPanel(body: LinearLayout, description: String?): LinearLayout {
        val panel: ScrollView = WorkspacePanelScrollView(activity)
        panel.setBackground(shape(Pal.PANEL, Pal.BORDER, 16))
        panel.setClipToOutline(true)
        panel.setContentDescription(description)
        val entries: LinearLayout = column()
        entries.setPadding(dp(12.0f), dp(12.0f), dp(12.0f), dp(12.0f))
        panel.addView(entries)
        body.addView(panel, LinearLayout.LayoutParams(-1, 0, 1.0f))
        return entries
    }

    private fun noteCategories(): MutableList<String?> {
        val names: MutableSet<String?> = LinkedHashSet<String?>()
        for (note in store.notes) {
            if (!note.category!!.isEmpty()) {
                names.add(note.category)
            }
        }
        return ArrayList<String?>(names)
    }

    private fun chooseNoteCategory() {
        val categories: MutableList<String?> = noteCategories()
        val labels: MutableList<String?> = ArrayList<String?>()
        labels.add(WorkspaceText.tr("Все категории"))
        labels.addAll(categories)
        val selected: Int = if (noteCategory!!.isEmpty()) 0 else categories.indexOf(noteCategory) + 1
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Категории заметок")).setSingleChoiceItems(labels.toTypedArray(), Math.max(0, selected), DialogInterface.OnClickListener { dialog, which ->
            noteCategory = if (which == 0) "" else categories.get(which - 1)
            noteFavorites = false
            dialog.dismiss()
            renderContent()
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun noteCard(parent: LinearLayout?, note: WorkspaceStore.Note) {
        val card: LinearLayout = card(parent)
        card.setBackground(shape(Pal.BG, Pal.BORDER, 12))
        cardLabels(card, note.name, note.text, note.category + (if (note.favorite) "  ★" else ""), false)
        (card.getChildAt(0) as TextView).setText("≡")
        card.setContentDescription(WorkspaceText.tr("Заметка ") + note.name)
        card.setOnClickListener(View.OnClickListener { v -> openNote(note) })
    }

    private fun openNote(note: WorkspaceStore.Note) {
        hideDialogKeyboard()
        val body: LinearLayout = previewBody(note.text, false)
        previewMetadata(body, note.category, note.favorite)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(note.name).setView(formScroll(body)).setPositiveButton(WorkspaceText.tr("Закрыть"), null).create()
        previewAction(body, if (note.favorite) WorkspaceText.tr("Убрать из избранного") else WorkspaceText.tr("В избранное"), R.drawable.workspace_star, Runnable {
            if (!store.isWritable()) {
                activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
                return@Runnable
            }
            note.favorite = !note.favorite
            store.save()
            dialog.dismiss()
            renderContent()
        })
        previewAction(body, WorkspaceText.tr("Редактировать"), R.drawable.workspace_edit, Runnable {
            dialog.dismiss()
            editNote(note)
        })
        previewAction(body, WorkspaceText.tr("Удалить"), R.drawable.workspace_delete, Runnable {
            if (!store.isWritable()) {
                activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
                return@Runnable
            }
            dialog.dismiss()
            confirm(WorkspaceText.tr("Удалить заметку «") + note.name + "»?", Runnable {
                store.notes.remove(note)
                store.save()
                renderContent()
            })
        })
        dialog.show()
    }

    private fun editNote(existing: WorkspaceStore.Note?) {
        if (!store.isWritable()) {
            activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
            return
        }
        val draft: WorkspaceStore.Note = if (existing == null) WorkspaceStore.Note() else existing
        val form: LinearLayout = column()
        val name: EditText = labeled(form, WorkspaceText.tr("Название"), draft.name, false)
        val text: EditText = labeled(form, WorkspaceText.tr("Текст заметки"), draft.text, true)
        val category: EditText = categoryField(form, draft.category, noteCategories())
        val favorite = CheckBox(activity)
        favorite.setText(WorkspaceText.tr("Добавить в избранное"))
        favorite.setTextColor(Pal.TEXT)
        favorite.setChecked(draft.favorite)
        form.addView(favorite)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (existing == null) WorkspaceText.tr("Новая заметка") else WorkspaceText.tr("Редактировать заметку")).setView(formScroll(form)).setPositiveButton(WorkspaceText.tr("Сохранить"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if ((name.getText().toString() as java.lang.String).trim().isEmpty()) {
                    name.setError(WorkspaceText.tr("Укажите название"))
                    return@OnClickListener
                }
                if ((text.getText().toString() as java.lang.String).trim().isEmpty()) {
                    text.setError(WorkspaceText.tr("Укажите текст"))
                    return@OnClickListener
                }
                draft.name = (name.getText().toString() as java.lang.String).trim()
                draft.text = text.getText().toString()
                draft.category = (category.getText().toString() as java.lang.String).trim()
                if (draft.category!!.isEmpty()) {
                    draft.category = WorkspaceText.tr("Общее")
                }
                draft.favorite = favorite.isChecked()
                if (existing == null) {
                    store.notes.add(draft)
                }
                store.save()
                dialog.dismiss()
                renderContent()
            })
        })
        dialog.show()
    }

    private fun commandCategories(): MutableList<String?> {
        val names: MutableSet<String?> = LinkedHashSet<String?>()
        for (command in store.commands) {
            if (!command.category!!.isEmpty()) {
                names.add(command.category)
            }
        }
        return ArrayList<String?>(names)
    }

    private fun chooseCommandCategory() {
        val categories: MutableList<String?> = commandCategories()
        val labels: MutableList<String?> = ArrayList<String?>()
        labels.add(WorkspaceText.tr("Все категории"))
        labels.addAll(categories)
        val selected: Int = if (commandCategory!!.isEmpty()) 0 else categories.indexOf(commandCategory) + 1
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Категории команд")).setSingleChoiceItems(labels.toTypedArray(), Math.max(0, selected), DialogInterface.OnClickListener { dialog, which ->
            commandCategory = if (which == 0) "" else categories.get(which - 1)
            favorites = false
            dialog.dismiss()
            renderContent()
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun matches(value: String): Boolean {
        return (value as java.lang.String).toLowerCase(Locale.ROOT).contains((query as java.lang.String).toLowerCase(Locale.ROOT))
    }

    private fun sortedSessions(): MutableList<WorkspaceStore.Session?> {
        val result: MutableList<WorkspaceStore.Session?> = ArrayList<WorkspaceStore.Session?>(store.sessions)
        result.sortWith(Comparator<WorkspaceStore.Session?> { a, b -> java.lang.Long.compare(b!!.updated, a!!.updated) })
        return result
    }

    private fun sessions(body: LinearLayout) {
        chips(body, arrayOf<String?>(WorkspaceText.tr("Активные"), WorkspaceText.tr("Пресеты")), if (savedOnly) WorkspaceText.tr("Пресеты") else WorkspaceText.tr("Активные"), Consumer<String?> { value ->
            savedOnly = value!!.equals(WorkspaceText.tr("Пресеты"))
            renderContent()
        })
        searchSummary(body)
        var count = 0
        for (s in sortedSessions()) {
            if ((if (savedOnly) s!!.saved else liveFor(s) != null) && matches(s!!.name + " " + s.directory + " " + TextUtils.join(" ", s.startupCommands()))) {
                sessionCard(body, s)
                count++
            }
        }
        if (count == 0) {
            empty(body, if (savedOnly) WorkspaceText.tr("Пресетов запуска нет") else WorkspaceText.tr("Нет активных сессий"), WorkspaceText.tr("Создайте сессию или запустите сохранённую."))
        }
        body.addView(button(WorkspaceText.tr("+  Новая сессия"), true, Runnable { editSession(null) }))
    }

    private fun empty(parent: LinearLayout?, title: String?, subtitle: String?) {
        val card: LinearLayout = column()
        card.setPadding(dp(18.0f), dp(24.0f), dp(18.0f), dp(24.0f))
        card.setBackground(shape(Pal.PANEL, Pal.BORDER, 16))
        card.addView(text(title, 16, Pal.TEXT))
        val sub: TextView = text(subtitle, 13, Pal.MUTED)
        sub.setPadding(0, dp(8.0f), 0, 0)
        card.addView(sub)
        parent!!.addView(card)
    }

    private fun card(parent: LinearLayout?): LinearLayout {
        val card: LinearLayout = row()
        card.setPadding(dp(12.0f), dp(12.0f), dp(8.0f), dp(12.0f))
        card.setBackground(shape(Pal.PANEL, Pal.BORDER, 16))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 0, 0, dp(8.0f))
        parent!!.addView(card, lp)
        return card
    }

    private fun cardLabels(card: LinearLayout, title: String?, subtitle: String?, status: String?, terminal: Boolean) {
        val icon: TextView = text(if (terminal) ">_" else "\$", 18, Pal.ACCENT)
        icon.setGravity(Gravity.CENTER)
        icon.setTypeface(Typeface.MONOSPACE)
        icon.setBackground(shape(Pal.CARD2, Pal.TINT_BORDER, 12))
        val ilp = LinearLayout.LayoutParams(dp(42.0f), dp(48.0f))
        ilp.setMarginEnd(dp(12.0f))
        card.addView(icon, ilp)
        val labels: LinearLayout = column()
        val name: TextView = text(title, 15, Pal.TEXT)
        name.setTypeface(null, Typeface.BOLD)
        name.setSingleLine()
        name.setEllipsize(TextUtils.TruncateAt.END)
        labels.addView(name)
        val sub: TextView = text(subtitle, 11, Pal.MUTED)
        sub.setSingleLine()
        sub.setEllipsize(TextUtils.TruncateAt.MIDDLE)
        sub.setPadding(0, dp(4.0f), 0, dp(4.0f))
        labels.addView(sub)
        val state: TextView = text(status, 10, if (terminal) Pal.ACCENT else Pal.MUTED)
        labels.addView(state)
        card.addView(labels, LinearLayout.LayoutParams(0, -2, 1.0f))
    }

    private fun commandCard(parent: LinearLayout?, c: WorkspaceStore.Command) {
        val card: LinearLayout = card(parent)
        card.setBackground(shape(Pal.BG, Pal.BORDER, 12))
        cardLabels(card, c.name, c.command, c.category + (if (c.favorite) "  ★" else ""), false)
        card.setOnClickListener(View.OnClickListener { v -> openCommand(c) })
        card.setContentDescription(WorkspaceText.tr("Команда ") + c.name)
    }

    private fun sessionCard(parent: LinearLayout, s: WorkspaceStore.Session?) {
        val running: TerminalSession? = liveFor(s)
        val active: Boolean = running != null && running.isRunning()
        val card: LinearLayout = card(parent)
        cardLabels(card, s!!.name, if (s.startupCommands().isEmpty()) compact(s.directory) else TextUtils.join(" → ", s.startupCommands()), if (active) WorkspaceText.tr("● Активна") else if (running != null) WorkspaceText.tr("Завершена") else WorkspaceText.tr("Сохранена"), true)
        if (screen === Screen.HOME) {
            card.setBackground(shape(Pal.BG, Pal.BORDER, 12))
        }
        card.setOnClickListener(View.OnClickListener { v -> openSavedSession(s) })
        val run: TextView = button(if (active) "›" else "▷", false, Runnable { openSavedSession(s) })
        run.setContentDescription(if (active) WorkspaceText.tr("Открыть ") + s.name else WorkspaceText.tr("Запустить ") + s.name)
        card.addView(run, LinearLayout.LayoutParams(dp(48.0f), dp(48.0f)))
        val more: TextView = button("⋮", false, Runnable { sessionMenu(s) })
        more.setContentDescription(WorkspaceText.tr("Действия с сессией ") + s.name)
        val moreParams = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        moreParams.setMarginStart(dp(8.0f))
        card.addView(more, moreParams)
    }

    private fun openSavedSession(profile: WorkspaceStore.Session?) {
        if (!profile!!.environment.equals("codex") || liveFor(profile) != null && liveFor(profile)!!.isRunning()) {
            launch(profile)
            return
        }
        val body: LinearLayout = column()
        body.addView(text(WorkspaceText.tr("Папка: ") + profile.directory, 14, Pal.TEXT))
        body.addView(text(WorkspaceText.tr("Среда: ") + ProjectFiles.environmentLabel(profile), 13, Pal.MUTED))
        body.addView(text(WorkspaceText.tr("Чат: ") + (if (profile.codexThreadName!!.isEmpty()) WorkspaceText.tr("Новый чат") else profile.codexThreadName), 14, Pal.TEXT))
        body.addView(text(WorkspaceText.tr("Новый чат не заменяет прежнюю историю. Сохранение — по вашему выбору."), 12, Pal.MUTED))
        WorkspaceDialogs.builder(activity).setTitle(profile.name).setView(body).setPositiveButton(if (profile.codexChat.equals("id")) WorkspaceText.tr("Продолжить чат") else WorkspaceText.tr("Начать чат"), DialogInterface.OnClickListener { d, w -> launch(profile) }).setNeutralButton(WorkspaceText.tr("Новый чат"), DialogInterface.OnClickListener { d, w ->
            val next: WorkspaceStore.Session = profile.copy()
            next.name = profile.name
            next.codexChat = "new"
            next.codexThread = ""
            next.codexThreadName = ""
            next.codexSourceDirectory = ""
            next.saved = false
            launchNew(next, true)
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun search() {
        val input: EditText = field(WorkspaceText.tr("Название, команда или каталог"), query, false)
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Поиск")).setView(input).setPositiveButton(WorkspaceText.tr("Найти"), DialogInterface.OnClickListener { d, which ->
            query = (input.getText().toString() as java.lang.String).trim()
            renderContent()
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun field(hint: String?, value: String?, multiline: Boolean): EditText {
        val e = EditText(activity)
        e.setHint(hint)
        e.setText(value)
        e.setTextColor(Pal.TEXT)
        e.setHintTextColor(Pal.MUTED)
        e.setTextSize(14.0f)
        e.setPadding(dp(12.0f), dp(10.0f), dp(12.0f), dp(10.0f))
        e.setBackground(shape(Pal.PANEL, Pal.BORDER, 10))
        e.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0))
        e.setShowSoftInputOnFocus(true)
        e.setOnClickListener(View.OnClickListener { v ->
            prepareKeyboardWindow()
            e.requestFocus()
            e.post(Runnable { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, 0) })
        })
        e.setOnFocusChangeListener(View.OnFocusChangeListener { v, focused ->
            if (focused) {
                prepareKeyboardWindow()
            }
        })
        e.setSingleLine(!multiline)
        if (multiline) {
            e.setMinLines(2)
            e.setMaxLines(6)
            e.setGravity(Gravity.TOP)
        }
        return e
    }

    private fun labeled(form: LinearLayout, label: String?, value: String?, multiline: Boolean): EditText {
        val title: TextView = text(label, 12, Pal.MUTED)
        title.setPadding(0, dp(12.0f), 0, dp(6.0f))
        form.addView(title)
        val e: EditText = field(label, value, multiline)
        form.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun categoryField(form: LinearLayout, value: String?, categories: MutableList<String?>): EditText {
        val title: TextView = text(WorkspaceText.tr("Категория"), 12, Pal.MUTED)
        title.setPadding(0, dp(12.0f), 0, dp(6.0f))
        form.addView(title)
        val input = AutoCompleteTextView(activity)
        input.setTextColor(Pal.TEXT)
        input.setHintTextColor(Pal.MUTED)
        input.setTextSize(14.0f)
        input.setSingleLine(true)
        input.setThreshold(1)
        input.setPadding(dp(12.0f), dp(10.0f), dp(12.0f), dp(10.0f))
        input.setBackground(shape(Pal.PANEL, Pal.BORDER, 10))
        input.setDropDownBackgroundDrawable(shape(Pal.PANEL, Pal.BORDER, 10))
        input.setAdapter(object : ArrayAdapter<String>(activity, android.R.layout.simple_dropdown_item_1line, categories) {
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val row: TextView = super.getView(position, recycled, parent) as TextView
                row.setTextColor(Pal.TEXT)
                return row
            }
        })
        input.setText(value, false)
        input.setHint(WorkspaceText.tr("Категория"))
        input.setOnClickListener(View.OnClickListener { v ->
            if (!categories.isEmpty()) {
                input.showDropDown()
            }
        })
        val line: LinearLayout = row()
        line.addView(input, LinearLayout.LayoutParams(0, -2, 1.0f))
        val choose: TextView = button("▾", false, Runnable {
            input.dismissDropDown()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.getWindowToken(), 0)
            val picker: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Выбрать категорию"))
            if (categories.isEmpty()) {
                picker.setMessage(WorkspaceText.tr("Категорий пока нет. Введите новую в поле."))
            } else {
                picker.setItems(categories.toTypedArray(), DialogInterface.OnClickListener { dialog, index ->
                    input.setText(categories.get(index), false)
                    input.setSelection(input.length())
                })
            }
            picker.setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        choose.setContentDescription(WorkspaceText.tr("Выбрать категорию"))
        val params = LinearLayout.LayoutParams(dp(48.0f), dp(48.0f))
        params.setMarginStart(dp(8.0f))
        line.addView(choose, params)
        form.addView(line, LinearLayout.LayoutParams(-1, -2))
        return input
    }

    private fun formScroll(form: LinearLayout): ScrollView {
        form.setPadding(dp(20.0f), dp(4.0f), dp(20.0f), dp(18.0f))
        val scroll = ScrollView(activity)
        scroll.addView(form)
        return scroll
    }

    private fun editCommand(existing: WorkspaceStore.Command?) {
        editCommand(existing, "")
    }

    private fun editCommand(existing: WorkspaceStore.Command?, initialCommand: String) {
        if (!store.isWritable()) {
            activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
            return
        }
        val draft: WorkspaceStore.Command = if (existing == null) WorkspaceStore.Command() else existing
        if (existing == null) {
            draft.command = initialCommand
        }
        val form: LinearLayout = column()
        val name: EditText = labeled(form, WorkspaceText.tr("Название"), draft.name, false)
        val command: EditText = labeled(form, WorkspaceText.tr("Команда"), draft.command, true)
        val categoryInput: EditText = categoryField(form, draft.category, commandCategories())
        val favorite = CheckBox(activity)
        favorite.setText(WorkspaceText.tr("Добавить в избранное"))
        favorite.setTextColor(Pal.TEXT)
        favorite.setChecked(draft.favorite)
        form.addView(favorite)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (existing == null) WorkspaceText.tr("Новая команда") else WorkspaceText.tr("Редактировать команду")).setView(formScroll(form)).setPositiveButton(WorkspaceText.tr("Сохранить"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if ((name.getText().toString() as java.lang.String).trim().isEmpty()) {
                    name.setError(WorkspaceText.tr("Укажите название"))
                    return@OnClickListener
                }
                if ((command.getText().toString() as java.lang.String).trim().isEmpty()) {
                    command.setError(WorkspaceText.tr("Укажите команду"))
                    return@OnClickListener
                }
                draft.name = (name.getText().toString() as java.lang.String).trim()
                draft.command = command.getText().toString()
                draft.category = (categoryInput.getText().toString() as java.lang.String).trim()
                if (draft.category!!.isEmpty()) {
                    draft.category = WorkspaceText.tr("Общее")
                }
                draft.favorite = favorite.isChecked()
                if (existing == null) {
                    store.commands.add(draft)
                }
                store.save()
                dialog.dismiss()
                renderContent()
            })
        })
        dialog.show()
    }

    private fun validDirectoryInput(field: EditText): Boolean {
        val value: String = (field.getText().toString() as java.lang.String).trim()
        if (value.indexOf('\u0000') >= 0 || value.contains("\n")) {
            field.setError(WorkspaceText.tr("Некорректный каталог"))
            return false
        }
        if (!value.isEmpty() && !value.startsWith("/") && !value.equals("~") && !value.startsWith("~/")) {
            field.setError(WorkspaceText.tr("Используйте абсолютный путь или ~/каталог"))
            return false
        }
        return true
    }

    private fun unavailableAgentScope(form: LinearLayout) {
        val scope = CheckBox(activity)
        scope.setText(tr("Работать только в пределах проекта · недоступно", "Work only within the project · unavailable"))
        scope.setTextColor(Pal.MUTED)
        scope.setEnabled(false)
        scope.setChecked(false)
        form.addView(scope)
        form.addView(text(tr("Для этого агента ограничение папкой не реализовано в нашей среде Android/proot. Рабочий каталог не является защитной границей; действуют штатные права агента.", "Project-folder enforcement is not implemented for this agent in our Android/proot environment. The working directory is not a security boundary; native agent permissions apply."), 12, Pal.MUTED))
    }

    private fun editSession(existing: WorkspaceStore.Session?) {
        editSession(existing, true)
    }

    private fun editSession(existing: WorkspaceStore.Session?, launchAfterSave: Boolean) {
        if (!store.isWritable()) {
            activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
            return
        }
        val draft: WorkspaceStore.Session = if (existing == null) WorkspaceStore.Session() else existing
        if (existing != null && draft.environment.equals("codex") && draft.codexEnvironment.equals("termux")) {
            activity.showToast(tr("Старый профиль Codex сохраняет Termux, авторизацию и историю. Для Ubuntu создайте новую сессию.", "This legacy Codex profile keeps Termux, authentication and history. Create a new session for Ubuntu."), true)
        }
        val form: LinearLayout = column()
        if (draft.restoreReview) {
            form.addView(text(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Импортированный пресет: проверьте среду, команды и пути. Отдельное восстановленное окружение доступно своим пресетом запуска. Сохраните настройки перед запуском." else "Imported preset: review environment, commands and paths. A separate restored environment has its own launch preset. Save settings before launching.", 14, Pal.MUTED))
        }
        val name: EditText = labeled(form, WorkspaceText.tr("Название сессии"), if (existing == null) "" else draft.name, false)
        val directory: EditText = labeled(form, WorkspaceText.tr("Рабочий каталог"), if (existing == null) TermuxConstants.TERMUX_HOME_DIR_PATH else draft.directory, false)
        val chosenEnvironment: Array<String> = arrayOf<String>(if (existing == null) "termux" else if (draft.aiAgent.equals("opencode") || draft.claudeAgent || draft.geminiAgent) "codex" else if (draft.environment.equals("legacy")) "termux" else if (!draft.prootAlias.isEmpty()) "proot:" + draft.prootAlias else draft.environment)
        val codexEnvironment: Array<String> = arrayOf<String>(AgentEnvironments.editorUbuntu(existing))
        val chosenAgent: Array<String> = arrayOf<String>(if (draft.geminiAgent) "gemini" else if (draft.claudeAgent) "claude" else if (draft.aiAgent.equals("opencode")) "opencode" else "codex")
        val chooseDirectory: TextView = button("▱", false, Runnable {  })
        chooseDirectory.setContentDescription(WorkspaceText.tr("Выбрать рабочий каталог"))
        form.removeView(directory)
        val directoryRow: LinearLayout = row()
        directoryRow.addView(directory, LinearLayout.LayoutParams(0, -2, 1.0f))
        directoryRow.addView(chooseDirectory, LinearLayout.LayoutParams(dp(48.0f), dp(48.0f)))
        form.addView(directoryRow)

        val editorOpen: BooleanArray = booleanArrayOf(true)
        val environments: Array<String> = arrayOf<String>("local", "ssh", "codex")
        val environmentNames: Array<String?> = arrayOf<String?>(tr("Окружения Termux", "Termux environments"), WorkspaceText.tr("SSH · сервер"), "AI Agents")
        val localFields: LinearLayout = column()
        val localEnvironment: TextView = button("", false, Runnable {  })
        localEnvironment.setContentDescription(tr("Окружение", "Environment"))
        localFields.addView(localEnvironment)
        val environmentLogin: TextView = text("", 13, Pal.MUTED)
        environmentLogin.setContentDescription("Environment login command")
        environmentLogin.setTextIsSelectable(true)
        localFields.addView(environmentLogin)
        val environment: TextView = button("", false, Runnable {  })
        environment.setContentDescription(WorkspaceText.tr("Среда запуска"))
        val environmentCheck: TextView = button(tr("Проверка среды запуска", "Launch environment check"), false, Runnable {  })
        environmentCheck.setTag("agent-environment-check")
        val resetEnvironmentCheck: Array<Runnable> = arrayOf<Runnable>(Runnable { environmentCheck.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0) })
        val sshFields: LinearLayout = column()
        val sshHost: EditText = labeled(sshFields, WorkspaceText.tr("Адрес сервера"), draft.sshHost, false)
        val sshUser: EditText = labeled(sshFields, WorkspaceText.tr("Пользователь"), draft.sshUser, false)
        val sshPort: EditText = labeled(sshFields, WorkspaceText.tr("Порт"), Integer.toString(draft.sshPort), false)
        sshPort.setInputType(InputType.TYPE_CLASS_NUMBER)
        val authModes: Array<String> = arrayOf<String>("auto", "key", "password")
        val authNames: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Автоматически"), WorkspaceText.tr("Файл SSH-ключа"), WorkspaceText.tr("По паролю"))
        val chosenAuth: Array<String> = arrayOf<String>(draft.sshAuth)
        val auth: TextView = button("", false, Runnable {  })
        auth.setContentDescription(WorkspaceText.tr("Авторизация SSH"))
        sshFields.addView(auth)
        val keyFields: LinearLayout = column()
        sshFields.addView(keyFields)
        val identity: EditText = labeled(keyFields, WorkspaceText.tr("Файл SSH-ключа"), draft.sshIdentity, false)
        identity.setContentDescription(WorkspaceText.tr("Файл SSH-ключа"))
        keyFields.addView(button(WorkspaceText.tr("Выбрать ключ"), false, Runnable { chooseSshKey(identity) }))
        val passwordFields: LinearLayout = column()
        sshFields.addView(passwordFields)
        val password: EditText = labeled(passwordFields, WorkspaceText.tr("Пароль"), "", false)
        password.setContentDescription(WorkspaceText.tr("Пароль SSH"))
        password.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.setSaveEnabled(false)
        password.setLongClickable(false)
        if (!draft.sshEncryptedPassword.isEmpty()) {
            password.setHint(WorkspaceText.tr("Сохранён; оставьте пустым, чтобы не менять"))
        }
        val showPassword = CheckBox(activity)
        showPassword.setText(WorkspaceText.tr("Показать пароль"))
        showPassword.setTextColor(Pal.TEXT)
        showPassword.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b, checked ->
            password.setTransformationMethod(if (checked) null else PasswordTransformationMethod.getInstance())
            password.setSelection(password.length())
        })
        passwordFields.addView(showPassword)
        val rememberPassword = CheckBox(activity)
        rememberPassword.setText(WorkspaceText.tr("Запомнить пароль"))
        rememberPassword.setTextColor(Pal.TEXT)
        rememberPassword.setChecked(!draft.sshEncryptedPassword.isEmpty())
        passwordFields.addView(rememberPassword)
        passwordFields.addView(text(WorkspaceText.tr("Сохранение через Android Keystore. После удаления приложения пароль потребуется ввести заново."), 12, Pal.MUTED))
        val updateAuth = Runnable {
            auth.setText(WorkspaceText.tr("Авторизация: ") + authNames[Math.max(0, Arrays.asList(*authModes).indexOf(chosenAuth[0]))])
            keyFields.setVisibility(if (chosenAuth[0].equals("key")) View.VISIBLE else View.GONE)
            passwordFields.setVisibility(if (chosenAuth[0].equals("password")) View.VISIBLE else View.GONE)
        }
        updateAuth.run()
        auth.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Авторизация SSH")).setSingleChoiceItems(authNames, Arrays.asList(*authModes).indexOf(chosenAuth[0]), DialogInterface.OnClickListener { d, which ->
                chosenAuth[0] = authModes[which]
                updateAuth.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        val sshKeepOpen = CheckBox(activity)
        sshKeepOpen.setText(WorkspaceText.tr("Оставить подключение открытым"))
        sshKeepOpen.setTextColor(Pal.TEXT)
        sshKeepOpen.setChecked(draft.sshKeepOpen)
        sshFields.addView(sshKeepOpen)
        val agentFields: LinearLayout = column()
        agentFields.addView(text(tr("Права применяются при следующем запуске. Сохранение настроек не меняет работающий агент.", "Permissions apply on next launch. Saving settings does not change a running agent."), 12, Pal.MUTED))
        val agentButton: TextView = button("", false, Runnable {  })
        agentFields.addView(agentButton)
        val geminiFields: LinearLayout = column()
        agentFields.addView(geminiFields)
        val geminiModel: Array<String> = arrayOf<String>(draft.geminiModel)
        val geminiMode: Array<String> = arrayOf<String>(draft.geminiMode)
        val geminiChat: Array<String> = arrayOf<String>(draft.geminiChat)
        val geminiThread: Array<String> = arrayOf<String>(draft.geminiThread)
        val geminiAuth: Array<String> = arrayOf<String>(draft.geminiAuth)
        val geminiAuthButton: TextView = button("", false, Runnable {  })
        geminiFields.addView(geminiAuthButton)
        val geminiKeyFields: LinearLayout = column()
        geminiKeyFields.setTag("gemini-api-key-fields")
        geminiFields.addView(geminiKeyFields)
        val geminiStoredKey: Array<String> = arrayOf<String>(draft.geminiEncryptedKey)
        val geminiApiKey: EditText = field(if (draft.geminiEncryptedKey.isEmpty()) tr("Gemini API-ключ (необязательно)", "Gemini API key (optional)") else tr("Ключ сохранён · введите новый для замены", "Key saved · enter a replacement"), "", false)
        geminiApiKey.setTag("gemini-api-key")
        geminiApiKey.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        geminiApiKey.setSaveEnabled(false)
        geminiApiKey.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        geminiKeyFields.addView(geminiApiKey)
        geminiKeyFields.addView(text(tr("Если ключ не указан и ранее не сохранён, при запуске Gemini предложит ввести его в терминальном окне — откройте его кнопкой робота. Пустое поле сохраняет уже заданный ключ, новый ключ заменяет его. Ключ защищён Android Keystore и применяется при следующем запуске.", "If no key is entered or saved, Gemini will offer key entry in the terminal at launch — open it with the robot button. Empty keeps an existing key; a new key replaces it. Android Keystore protects the key; changes apply at the next launch."), 12, Pal.MUTED))
        val clearGeminiKey: TextView = button(tr("Удалить сохранённый ключ", "Remove saved key"), false, Runnable {  })
        geminiKeyFields.addView(clearGeminiKey)
        clearGeminiKey.setVisibility(if (geminiStoredKey[0].isEmpty()) View.GONE else View.VISIBLE)
        clearGeminiKey.setOnClickListener(View.OnClickListener { v ->
            geminiStoredKey[0] = ""
            geminiApiKey.setText("")
            geminiApiKey.setHint(tr("Gemini API-ключ (необязательно)", "Gemini API key (optional)"))
            clearGeminiKey.setVisibility(View.GONE)
        })
        val geminiAuthDescription: TextView = text("", 12, Pal.MUTED)
        geminiFields.addView(geminiAuthDescription)
        val geminiCloudFields: LinearLayout = column()
        geminiFields.addView(geminiCloudFields)
        geminiCloudFields.addView(text(tr("Проект Google Cloud — облачный ID, не рабочая папка", "Google Cloud project — cloud ID, not a working folder"), 12, Pal.MUTED))
        VertexConnection.applyDefaults(activity, draft)
        val geminiCloudProject: EditText = field(tr("ID проекта Google Cloud (если используется)", "Google Cloud project ID (if used)"), draft.geminiCloudProject, false)
        geminiCloudProject.setTag("gemini-cloud-project")
        geminiCloudFields.addView(geminiCloudProject)
        geminiCloudFields.addView(text(tr("Регион Vertex AI", "Vertex AI location"), 12, Pal.MUTED))
        val geminiCloudLocation: EditText = field(tr("Например: global или us-central1", "For example: global or us-central1"), draft.geminiCloudLocation, false)
        geminiCloudLocation.setTag("gemini-cloud-location")
        geminiCloudLocation.setFocusable(false)
        geminiCloudLocation.setCursorVisible(false)
        geminiCloudLocation.setOnClickListener(View.OnClickListener { v ->
            GeminiAuthUi.location(activity, geminiCloudLocation.getText().toString(), Consumer<String> { value -> geminiCloudLocation.setText(value) })
        })
        geminiCloudFields.addView(geminiCloudLocation)
        geminiCloudFields.addView(text(tr("Обычно укажите ID облачного проекта и регион; в Ubuntu должны быть настроены Google Cloud ADC или сервисный аккаунт. Для Vertex Express используется отдельный GOOGLE_API_KEY. Пустые поля сохраняют параметры окружения; ключи здесь не вводятся. Подключение само не создаёт облачный проект и не настраивает оплату.", "Normally enter your cloud project ID and location; Google Cloud ADC or a service account must be configured in Ubuntu. Vertex Express uses a separate GOOGLE_API_KEY. Empty fields preserve environment settings; do not enter keys here. Connecting does not create a cloud project or configure billing."), 12, Pal.MUTED))
        val geminiModelButton: TextView = button("", false, Runnable {  })
        geminiFields.addView(geminiModelButton)
        val geminiModeButton: TextView = button("", false, Runnable {  })
        geminiFields.addView(geminiModeButton)
        val geminiChatButton: TextView = button("", false, Runnable {  })
        geminiFields.addView(geminiChatButton)
        val geminiModes: Array<String> = GeminiRightsUi.MODES
        val geminiModeNames: Array<String?> = arrayOf<String?>(GeminiRightsUi.label("default"), GeminiRightsUi.label("auto_edit"), GeminiRightsUi.label("plan"))
        val geminiModeDescriptions: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Gemini запрашивает разрешения на действия в терминале."), WorkspaceText.tr("Gemini автоматически разрешает инструменты редактирования; остальные действия требуют подтверждения."), WorkspaceText.tr("Анализ и план без выполнения изменений. Это режим Gemini, а не системная песочница."))
        val geminiAccessDetails: TextView = text("", 12, Pal.MUTED)
        geminiFields.addView(geminiAccessDetails)
        unavailableAgentScope(geminiFields)
        val updateGemini = Runnable {
            resetEnvironmentCheck[0].run()
            geminiKeyFields.setVisibility(if (geminiAuth[0].equals("api")) View.VISIBLE else View.GONE)
            geminiAuthButton.setText(WorkspaceText.tr("Вход Gemini: ") + GeminiAuthUi.label(activity, geminiAuth[0]))
            geminiAuthDescription.setText(GeminiAuthUi.description(activity, geminiAuth[0]))
            geminiCloudFields.setVisibility(if (geminiAuth[0].equals("vertex")) View.VISIBLE else View.GONE)
            if (geminiAuth[0].equals("vertex") && geminiCloudProject.getText().toString().isEmpty()) {
                val defaults = WorkspaceStore.Session()
                defaults.geminiAgent = true
                defaults.geminiAuth = "vertex"
                VertexConnection.applyDefaults(activity, defaults)
                if (!defaults.geminiCloudProject!!.isEmpty()) {
                    geminiCloudProject.setText(defaults.geminiCloudProject)
                    geminiCloudLocation.setText(defaults.geminiCloudLocation)
                }
            }
            geminiModelButton.setText(WorkspaceText.tr("Модель: ") + (if (geminiModel[0].isEmpty()) WorkspaceText.tr("По умолчанию") else geminiModel[0]))
            geminiModeButton.setText(tr("Режим прав: ", "Permission mode: ") + geminiModeNames[Arrays.asList(*geminiModes).indexOf(geminiMode[0])])
            geminiAccessDetails.setText(geminiModeDescriptions[Arrays.asList(*geminiModes).indexOf(geminiMode[0])])
            geminiChatButton.setText(WorkspaceText.tr("Чат: ") + (if (geminiChat[0].equals("new")) WorkspaceText.tr("Новый чат") else if (geminiChat[0].equals("choose")) WorkspaceText.tr("Выбрать чат") else geminiThread[0]))
        }
        updateGemini.run()
        geminiAuthButton.setOnClickListener(View.OnClickListener { v ->
            GeminiAuthUi.choose(activity, geminiAuth[0], Consumer<String> { value ->
                geminiAuth[0] = value
                updateGemini.run()
            })
        })
        geminiModelButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Модель Gemini CLI")).setSingleChoiceItems(arrayOf<String?>(WorkspaceText.tr("По умолчанию"), "Auto", "Flash", "Pro"), Arrays.asList("", "auto", "flash", "pro").indexOf(geminiModel[0]), DialogInterface.OnClickListener { d, w ->
                geminiModel[0] = arrayOf<String>("", "auto", "flash", "pro")[w]
                updateGemini.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        geminiModeButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Доступ Gemini CLI")).setSingleChoiceItems(arrayOf<String>(geminiModeNames[0] + "\n" + geminiModeDescriptions[0], geminiModeNames[1] + "\n" + geminiModeDescriptions[1], geminiModeNames[2] + "\n" + geminiModeDescriptions[2]), Arrays.asList(*geminiModes).indexOf(geminiMode[0]), DialogInterface.OnClickListener { d, w ->
                geminiMode[0] = geminiModes[w]
                updateGemini.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        geminiChatButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Чат Gemini CLI")).setItems(arrayOf<String?>(WorkspaceText.tr("Новый чат"), WorkspaceText.tr("Выбрать чат"), WorkspaceText.tr("Ввести ID чата")), DialogInterface.OnClickListener { d, w ->
                if (w < 2) {
                    geminiChat[0] = if (w == 0) "new" else "choose"
                    updateGemini.run()
                    return@OnClickListener
                }
                val id: EditText = field(WorkspaceText.tr("ID чата Gemini CLI"), if (geminiChat[0].equals("id")) geminiThread[0] else "", false)
                val picker: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("ID чата Gemini CLI")).setView(id).setPositiveButton(WorkspaceText.tr("Выбрать"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
                picker.setOnShowListener(DialogInterface.OnShowListener { x ->
                    picker.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { y ->
                        val value: String = (id.getText().toString() as java.lang.String).trim()
                        if (!GeminiCli.validThread(value)) {
                            id.setError(WorkspaceText.tr("Некорректный ID"))
                            return@OnClickListener
                        }
                        geminiThread[0] = value
                        geminiChat[0] = "id"
                        updateGemini.run()
                        picker.dismiss()
                    })
                })
                picker.show()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        geminiFields.addView(text(tr("Gemini CLI запускается в Ubuntu. При запуске проверяем компоненты и предлагаем установить недостающее. Выберите Gemini API-ключ или Vertex AI с отдельной авторизацией Google Cloud. История привязана к каталогу проекта; для продолжения выберите прежний каталог.", "Gemini CLI runs in Ubuntu. Launch checks components and offers to install missing items. Choose a Gemini API key or Vertex AI with separate Google Cloud authentication. History is tied to the project directory; choose the previous directory to resume."), 12, Pal.MUTED))
        val claudeFields: LinearLayout = column()
        agentFields.addView(claudeFields)
        val claudeModel: Array<String?> = arrayOf<String?>(draft.claudeModel)
        val claudeMode: Array<String?> = arrayOf<String?>(draft.claudeMode)
        val claudeChat: Array<String> = arrayOf<String>(draft.claudeChat)
        val claudeThread: Array<String> = arrayOf<String>(draft.claudeThread)
        val claudeModelButton: TextView = button("", false, Runnable {  })
        claudeFields.addView(claudeModelButton)
        val claudeModeButton: TextView = button("", false, Runnable {  })
        claudeFields.addView(claudeModeButton)
        val claudeChatButton: TextView = button("", false, Runnable {  })
        claudeFields.addView(claudeChatButton)
        val claudeModes: Array<String> = arrayOf<String>("manual", "acceptEdits", "plan")
        val claudeModeNames: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Подтверждать действия"), WorkspaceText.tr("Разрешать редактирование"), WorkspaceText.tr("Планирование"))
        val claudeModeDescriptions: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Claude запрашивает разрешения на действия в терминале."), WorkspaceText.tr("Правки файлов и обычные файловые команды разрешаются автоматически; остальные действия требуют разрешения."), WorkspaceText.tr("Анализ и план без выполнения изменений. Это режим Claude, а не системная песочница."))
        val claudeAccessDetails: TextView = text("", 12, Pal.MUTED)
        claudeFields.addView(claudeAccessDetails)
        unavailableAgentScope(claudeFields)
        val updateClaude = Runnable {
            claudeModelButton.setText(WorkspaceText.tr("Модель: ") + (if (claudeModel[0]!!.isEmpty()) WorkspaceText.tr("По умолчанию") else claudeModel[0]))
            claudeModeButton.setText(tr("Права: ", "Permissions: ") + claudeModeNames[Arrays.asList(*claudeModes).indexOf(claudeMode[0])])
            claudeAccessDetails.setText(claudeModeDescriptions[Arrays.asList(*claudeModes).indexOf(claudeMode[0])])
            claudeChatButton.setText(WorkspaceText.tr("Чат: ") + (if (claudeChat[0].equals("new")) WorkspaceText.tr("Новый чат") else if (claudeChat[0].equals("choose")) WorkspaceText.tr("Выбрать чат") else claudeThread[0]))
        }
        updateClaude.run()
        claudeModelButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Модель Claude Code")).setSingleChoiceItems(arrayOf<String?>(WorkspaceText.tr("По умолчанию"), "Sonnet", "Opus", "Haiku"), Arrays.asList("", "sonnet", "opus", "haiku").indexOf(claudeModel[0]), DialogInterface.OnClickListener { d, w ->
                claudeModel[0] = arrayOf<String>("", "sonnet", "opus", "haiku")[w]
                updateClaude.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        claudeModeButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Доступ Claude Code")).setSingleChoiceItems(arrayOf<String>(claudeModeNames[0] + "\n" + claudeModeDescriptions[0], claudeModeNames[1] + "\n" + claudeModeDescriptions[1], claudeModeNames[2] + "\n" + claudeModeDescriptions[2]), Arrays.asList(*claudeModes).indexOf(claudeMode[0]), DialogInterface.OnClickListener { d, w ->
                claudeMode[0] = claudeModes[w]
                updateClaude.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        claudeChatButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Чат Claude Code")).setItems(arrayOf<String?>(WorkspaceText.tr("Новый чат"), WorkspaceText.tr("Выбрать чат"), WorkspaceText.tr("Ввести ID чата")), DialogInterface.OnClickListener { d, w ->
                if (w < 2) {
                    claudeChat[0] = if (w == 0) "new" else "choose"
                    updateClaude.run()
                    return@OnClickListener
                }
                val id: EditText = field(WorkspaceText.tr("ID чата Claude Code"), if (claudeChat[0].equals("id")) claudeThread[0] else "", false)
                val picker: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("ID чата Claude Code")).setView(id).setPositiveButton(WorkspaceText.tr("Выбрать"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
                picker.setOnShowListener(DialogInterface.OnShowListener { x ->
                    picker.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { y ->
                        val value: String = (id.getText().toString() as java.lang.String).trim()
                        if (!ClaudeCode.validThread(value)) {
                            id.setError(WorkspaceText.tr("Некорректный ID"))
                            return@OnClickListener
                        }
                        claudeThread[0] = value
                        claudeChat[0] = "id"
                        updateClaude.run()
                        picker.dismiss()
                    })
                })
                picker.show()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        claudeFields.addView(text(WorkspaceText.tr("Claude Code запускается в Ubuntu. Компоненты устанавливаются при первом запуске в отдельный каталог. Нужна подписка Claude или оплачиваемый API; ChatGPT Plus не подходит. Авторизация и история отдельные. «Выбрать чат» открывает список Claude в терминале. Доступность моделей зависит от аккаунта."), 12, Pal.MUTED))
        val openCodeFields: LinearLayout = column()
        agentFields.addView(openCodeFields)
        val aiProjectScope = CheckBox(activity)
        aiProjectScope.setText(tr("Ограничивать внешние пути · OpenCode", "Restrict external paths · OpenCode"))
        aiProjectScope.setTextColor(Pal.TEXT)
        aiProjectScope.setChecked(draft.aiProjectOnly)
        aiProjectScope.setContentDescription("OpenCode project scope")
        val aiConnection: Array<String> = arrayOf<String>(draft.aiConnection)
        val aiModel: Array<String> = arrayOf<String>(draft.aiModel)
        val aiVariant: Array<String?> = arrayOf<String?>(draft.aiVariant)
        val aiMode: Array<String> = arrayOf<String>(draft.aiMode)
        val aiAccess: Array<String> = arrayOf<String>(draft.aiAccess)
        val aiChat: Array<String> = arrayOf<String>(draft.aiChat)
        val aiThread: Array<String> = arrayOf<String>(draft.aiThread)
        val connectionButton: TextView = button("", false, Runnable {  })
        openCodeFields.addView(connectionButton)
        val modelButton: TextView = button("", false, Runnable {  })
        openCodeFields.addView(modelButton)
        val modeButton: TextView = button("", false, Runnable {  })
        openCodeFields.addView(modeButton)
        val modeDescription: TextView = text("", 12, Pal.MUTED)
        openCodeFields.addView(modeDescription)
        val aiAccessButton: TextView = button("", false, Runnable {  })
        openCodeFields.addView(aiAccessButton)
        val aiAccessDescription: TextView = text("", 12, Pal.MUTED)
        openCodeFields.addView(aiAccessDescription)
        openCodeFields.addView(aiProjectScope)
        openCodeFields.addView(text(tr("Включено: правила OpenCode запрещают внешние каталоги, shell-команды, субагентов, MCP и неизвестные инструменты. Доступны файловые инструменты проекта/рабочего дерева OpenCode в рамках выбранных прав. Это правила инструментов: они не гарантируют защиту от символьных ссылок и не создают системную песочницу. Выключено: внешние пути регулируются штатными правами OpenCode.", "On: OpenCode rules deny external directories, shell, subagents, MCP and unknown tools. File tools operate in the OpenCode project/worktree according to selected permissions. This tool policy does not guarantee protection against symlink escapes and is not an OS sandbox. Off: native OpenCode permissions govern external paths."), 12, Pal.MUTED))
        val chatButton: TextView = button("", false, Runnable {  })
        openCodeFields.addView(chatButton)
        openCodeFields.addView(text(WorkspaceText.tr("OpenCode запускается в Ubuntu. При «Создать и запустить» компоненты проверяются и при необходимости устанавливаются в этой же вкладке. Node.js устанавливается отдельно от системного. Подключения и история отдельные от Codex; разрешения запрашиваются в терминале."), 12, Pal.MUTED))
        val updateOpenCode = Runnable {
            connectionButton.setText(WorkspaceText.tr("Подключение: ") + AiAgentsUi.title(activity, aiConnection[0]))
            modelButton.setText(WorkspaceText.tr("Модель: ") + (if (aiModel[0].isEmpty()) WorkspaceText.tr("По умолчанию") else aiModel[0]))
            modeButton.setText(WorkspaceText.tr("Режим: ") + (if (aiMode[0].equals("build")) "Build" else "Plan"))
            modeDescription.setText(AiAgentsUi.modeDescription(aiMode[0]))
            aiAccessButton.setText(tr("Права: ", "Permissions: ") + AiAgentsUi.accessLabel(aiAccess[0]))
            aiAccessDescription.setText(AiAgentsUi.accessDescription(aiAccess[0]))
            chatButton.setText(WorkspaceText.tr("Чат: ") + (if (aiChat[0].equals("new")) WorkspaceText.tr("Новый чат") else aiThread[0]))
        }
        updateOpenCode.run()
        connectionButton.setOnClickListener(View.OnClickListener { v ->
            AiAgentsUi.chooseConnection(activity, aiConnection[0], AiAgentsUi.Choice { id, title ->
                aiConnection[0] = id
                aiModel[0] = ""
                aiVariant[0] = ""
                aiChat[0] = "new"
                aiThread[0] = "ses" + (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
                updateOpenCode.run()
            })
        })
        modelButton.setOnClickListener(View.OnClickListener { v ->
            AiAgentsUi.chooseModel(activity, aiConnection[0], agentQueryProfile(codexEnvironment[0]), AiAgentsUi.ModelChoice { provider, model, variant ->
                aiModel[0] = provider + "/" + model
                aiVariant[0] = variant
                updateOpenCode.run()
            })
        })
        modeButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Режим OpenCode")).setSingleChoiceItems(arrayOf<String>("Build\n" + AiAgentsUi.modeDescription("build"), "Plan\n" + AiAgentsUi.modeDescription("plan")), if (aiMode[0].equals("plan")) 1 else 0, DialogInterface.OnClickListener { d, w ->
                aiMode[0] = if (w == 0) "build" else "plan"
                updateOpenCode.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        aiAccessButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Доступ OpenCode")).setSingleChoiceItems(arrayOf<String>(AiAgentsUi.accessLabel("full") + "\n" + AiAgentsUi.accessDescription("full"), AiAgentsUi.accessLabel("ask") + "\n" + AiAgentsUi.accessDescription("ask"), AiAgentsUi.accessLabel("read") + "\n" + AiAgentsUi.accessDescription("read")), Arrays.asList("full", "ask", "read").indexOf(aiAccess[0]), DialogInterface.OnClickListener { d, w ->
                val selected: String = arrayOf<String>("full", "ask", "read")[w]
                d.dismiss()
                if (selected.equals("full")) {
                    WorkspaceDialogs.builder(activity).setTitle(AiAgentsUi.accessLabel("full")).setMessage(AiAgentsUi.accessDescription("full")).setPositiveButton(WorkspaceText.tr("Выбрать"), DialogInterface.OnClickListener { confirm, index ->
                        aiAccess[0] = selected
                        updateOpenCode.run()
                    }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
                } else {
                    aiAccess[0] = selected
                    updateOpenCode.run()
                }
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        chatButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Чаты OpenCode")).setItems(arrayOf<String?>(WorkspaceText.tr("Новый чат"), WorkspaceText.tr("Выбрать чат"), WorkspaceText.tr("Ввести ID чата")), DialogInterface.OnClickListener { d, w ->
                if (w == 0) {
                    aiChat[0] = "new"
                    aiThread[0] = "ses" + (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
                    updateOpenCode.run()
                } else if (w == 1) {
                    AiAgentsUi.chooseChat(activity, aiConnection[0], agentQueryProfile(codexEnvironment[0]), AiAgentsUi.Choice { id, title ->
                        aiThread[0] = id
                        aiChat[0] = "resume"
                        updateOpenCode.run()
                    })
                } else {
                    val id: EditText = field("ID OpenCode", aiThread[0], false)
                    val picker: AlertDialog = WorkspaceDialogs.builder(activity).setTitle("ID OpenCode").setView(id).setPositiveButton(WorkspaceText.tr("Выбрать"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
                    picker.setOnShowListener(DialogInterface.OnShowListener { x ->
                        picker.getButton(-1).setOnClickListener(View.OnClickListener { y ->
                            if (!AiAgents.validThread((id.getText().toString() as java.lang.String).trim())) {
                                id.setError(WorkspaceText.tr("Некорректный ID"))
                                return@OnClickListener
                            }
                            aiThread[0] = (id.getText().toString() as java.lang.String).trim()
                            aiChat[0] = "resume"
                            updateOpenCode.run()
                            picker.dismiss()
                        })
                    })
                    picker.show()
                }
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        val codexFields: LinearLayout = column()
        val codexAccess: Array<String> = arrayOf<String>(draft.codexAccess)
        val codexChat: Array<String> = arrayOf<String>(draft.codexChat)
        val codexThread: Array<String> = arrayOf<String>(draft.codexThread)
        val codexThreadName: Array<String?> = arrayOf<String?>(draft.codexThreadName)
        val codexSourceDirectory: Array<String?> = arrayOf<String?>(draft.codexSourceDirectory)
        val codexConfirmedDirectory: Array<String?> = arrayOf<String?>(if (existing != null && !draft.codexSourceDirectory!!.isEmpty()) draft.directory else "")
        val codexAccessButton: TextView = button("", false, Runnable {  })
        codexFields.addView(codexAccessButton)
        val codexChatButton: TextView = button("", false, Runnable {  })
        codexFields.addView(codexChatButton)
        codexAccessButton.setContentDescription(WorkspaceText.tr("Доступ Codex"))
        codexChatButton.setContentDescription(WorkspaceText.tr("Чат Codex"))
        val accessModes: Array<String> = arrayOf<String>("full", "project", "read")
        val accessNames: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Полный доступ · без подтверждений"), WorkspaceText.tr("В пределах проекта · с подтверждениями"), WorkspaceText.tr("Только чтение · с подтверждениями"))
        val codexAccessDetails: Array<String> = arrayOf<String>(tr("Без файловой песочницы Codex: файлы и команды доступны в пределах прав среды. Подтверждения настраиваются отдельно. Это не root Android.", "No Codex filesystem sandbox: files and commands are available within environment permissions. Approvals are configured separately. This is not Android root."), tr("Штатный workspace-write: запись в проект и разрешённые служебные каталоги. Чтение вне проекта не обязательно запрещено. Подтверждения регулируются отдельно; поддержка песочницы зависит от Android/proot.", "Native workspace-write: writes to the project and runtime-managed writable directories. Reads outside the project are not necessarily blocked. Approvals are separate; sandbox support depends on Android/proot."), tr("Штатный read-only Codex запрещает запись внутри песочницы; команды чтения могут быть доступны. Это не режим «только чат». Выход за ограничения зависит от политики подтверждений и поддержки Android/proot.", "Native Codex read-only blocks sandbox writes; read commands may be available. This is not chat-only mode. Escalation depends on approval policy and Android/proot support."))
        val codexAccessDescription: TextView = text("", 12, Pal.MUTED)
        codexFields.addView(codexAccessDescription)
        val codexWriteScope: BooleanArray = booleanArrayOf(draft.codexAccess.equals("project"))
        val codexScope = CheckBox(activity)
        codexScope.setText(tr("Ограничивать запись проектом · Codex", "Restrict writes to the project · Codex"))
        codexScope.setTextColor(Pal.TEXT)
        codexScope.setChecked(codexWriteScope[0])
        codexScope.setContentDescription("Codex project write scope")
        codexFields.addView(codexScope)
        codexFields.addView(text(tr("Штатный workspace-write ограничивает запись, но не всё чтение вне проекта. Возможны служебные разрешённые каталоги. Поддержка песочницы зависит от Android/proot; при ошибке запуска приложение не переключается на полный доступ. При включённых подтверждениях Codex может запросить выход за ограничения.", "Native workspace-write restricts writes, not all reads outside the project; runtime-managed writable directories may exist. Sandbox support depends on Android/proot; startup errors never trigger a full-access fallback. With confirmations enabled, Codex may request approval to exceed restrictions."), 12, Pal.MUTED))
        val codexConfirm = CheckBox(activity)
        codexConfirm.setText(tr("Подтверждать действия · Codex", "Confirm actions · Codex"))
        codexConfirm.setTextColor(Pal.TEXT)
        codexConfirm.setChecked(CodexSessionShell.approval(draft).equals("on-request"))
        codexConfirm.setContentDescription("Codex action confirmations")
        codexFields.addView(codexConfirm)
        codexFields.addView(text(tr("on-request: подтверждения запрашивает сам Codex по своей политике, не перед каждым действием. Выключено: never, запросы повышения прав не разрешаются; выбранная песочница сохраняется.", "on-request: Codex requests approvals under its own policy, not before every action. Off: never, no escalation approvals; the selected sandbox remains in force."), 12, Pal.MUTED))

        val codexChatEdited: BooleanArray = booleanArrayOf(false)
        val updateCodex = Runnable {
            codexAccessButton.setText(tr("Права: ", "Permissions: ") + (if (codexAccess[0].equals("read")) tr("Чтение · read-only", "Read · read-only") else tr("Чтение и запись", "Read and write")))
            codexScope.setEnabled(!codexAccess[0].equals("read"))
            codexAccessDescription.setText(codexAccessDetails[Math.max(0, Arrays.asList(*accessModes).indexOf(codexAccess[0]))])
            codexChatButton.setText(WorkspaceText.tr("Чат: ") + (if (codexChat[0].equals("new")) WorkspaceText.tr("Новый") else if (codexChat[0].equals("last")) WorkspaceText.tr("Выберите конкретный чат") else if (codexChat[0].equals("id")) codexThreadName[0] else WorkspaceText.tr("Выбрать при запуске")))
        }
        updateCodex.run()
        codexAccessButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(tr("Права Codex", "Codex permissions")).setSingleChoiceItems(arrayOf<String>(tr("Чтение · read-only", "Read · read-only"), tr("Чтение и запись", "Read and write")), if (codexAccess[0].equals("read")) 0 else 1, DialogInterface.OnClickListener { d, w ->
                codexAccess[0] = if (w == 0) "read" else if (codexWriteScope[0]) "project" else "full"
                updateCodex.run()
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        codexScope.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b, on ->
            codexWriteScope[0] = on
            if (!codexAccess[0].equals("read")) {
                codexAccess[0] = if (on) "project" else "full"
            }
            updateCodex.run()
        })
        codexChatButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Чат Codex")).setItems(arrayOf<String?>(WorkspaceText.tr("Новый чат"), WorkspaceText.tr("Выбрать чат"), WorkspaceText.tr("Ввести ID чата")), DialogInterface.OnClickListener { d, which ->
                if (which == 0) {
                    codexChatEdited[0] = true
                    codexChat[0] = "new"
                    codexThread[0] = ""
                    codexThreadName[0] = ""
                    codexSourceDirectory[0] = ""
                    codexConfirmedDirectory[0] = ""
                    updateCodex.run()
                } else if (which == 1) {
                    chooseCodexChat(codexEnvironment[0], (directory.getText().toString() as java.lang.String).trim(), ChatChoice { id, title, source ->
                        codexChatEdited[0] = true
                        codexChat[0] = if (id.isEmpty()) "new" else "id"
                        codexThread[0] = id
                        codexThreadName[0] = title
                        codexSourceDirectory[0] = source
                        codexConfirmedDirectory[0] = (directory.getText().toString() as java.lang.String).trim()
                        updateCodex.run()
                    })
                } else {
                    val id: EditText = field(WorkspaceText.tr("ID чата Codex"), codexThread[0], false)
                    val picker: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("ID чата Codex")).setView(id).setPositiveButton(WorkspaceText.tr("Выбрать"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
                    picker.setOnShowListener(DialogInterface.OnShowListener { x ->
                        picker.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { y ->
                            val value: String = (id.getText().toString() as java.lang.String).trim()
                            if (!CodexSessionShell.validThread(value)) {
                                id.setError(WorkspaceText.tr("Некорректный ID"))
                                return@OnClickListener
                            }
                            val env: String = codexEnvironment[0]
                            val cwd: String = (directory.getText().toString() as java.lang.String).trim()
                            verifyCodexChat(env, cwd, value, BiConsumer<String, String?> { title, source ->
                                if (!picker.isShowing() || !env.equals(codexEnvironment[0]) || !cwd.equals((directory.getText().toString() as java.lang.String).trim())) {
                                    return@BiConsumer
                                }
                                codexChatEdited[0] = true
                                codexChat[0] = "id"
                                codexThread[0] = value
                                codexThreadName[0] = title
                                codexSourceDirectory[0] = source
                                codexConfirmedDirectory[0] = cwd
                                updateCodex.run()
                                picker.dismiss()
                            })
                        })
                    })
                    picker.show()
                }
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        chooseDirectory.setOnClickListener(View.OnClickListener { v ->
            val location = WorkspaceStore.Session()
            location.id = draft.id
            location.environment = chosenEnvironment[0]
            location.codexEnvironment = codexEnvironment[0]
            location.prootIsolated = draft.prootIsolated && chosenEnvironment[0].equals("proot:" + draft.prootAlias)
            location.sshHost = (sshHost.getText().toString() as java.lang.String).trim()
            location.sshUser = (sshUser.getText().toString() as java.lang.String).trim()
            location.sshAuth = chosenAuth[0]
            location.sshIdentity = (identity.getText().toString() as java.lang.String).trim()
            location.sshEncryptedPassword = draft.sshEncryptedPassword
            try {
                location.sshPort = Integer.parseInt(sshPort.getText().toString())
            } catch (e: NumberFormatException) {
                sshPort.setError(WorkspaceText.tr("Порт от 1 до 65535"))
                return@OnClickListener
            }
            showFileBrowser(location, (directory.getText().toString() as java.lang.String).trim(), WorkspaceFileBrowser.DirectoryChoice { path ->
                directory.setText(path)
                if ((name.getText().toString() as java.lang.String).trim().isEmpty()) {
                    name.setText(File(path).getName())
                }
            })
        })
        directory.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(t: CharSequence, start: Int, before: Int, count: Int) {
                if (codexChat[0].equals("id")) {
                    codexConfirmedDirectory[0] = ""
                    updateCodex.run()
                }
            }

            override fun afterTextChanged(t: Editable) {
            }
        })
        codexFields.addView(text(WorkspaceText.tr("Авторизация и история берутся из выбранной среды. Если вход не выполнен, Codex предложит авторизоваться. Для карточки управления нужна версия Codex с app-server и --remote. Полный доступ действует в пределах доступных среде файлов. На Android ограничения sandbox зависят от поддержки Codex; при ошибке запуск остановится, права не повышаются автоматически."), 12, Pal.MUTED))
        val agentUbuntu: TextView = button("", false, Runnable {  })
        val migrateAgent: TextView = button(tr("Миграция среды агента", "Agent environment migration"), false, Runnable {  })
        val codexLoginHint: TextView = text(tr("После запуска будет предложено войти в Codex, если вход ещё не выполнен.", "After launch, you will be prompted to sign in to Codex if you are not already signed in."), 12, Pal.MUTED)
        codexLoginHint.setTag("codex-login-hint")
        form.addView(codexLoginHint, 0)
        val updateEnvironment = Runnable {
            codexLoginHint.setVisibility(if (chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("codex")) View.VISIBLE else View.GONE)
            resetEnvironmentCheck[0].run()
            environmentCheck.setVisibility(View.VISIBLE)
            agentUbuntu.setText(tr("Окружение агента: ", "Agent environment: ") + AgentEnvironments.label(codexEnvironment[0]))
            agentUbuntu.setVisibility(if (chosenEnvironment[0].equals("codex")) View.VISIBLE else View.GONE)
            migrateAgent.setVisibility(if (chosenEnvironment[0].equals("codex")) View.VISIBLE else View.GONE)
            AgentStatusUi.available(migrateAgent, !codexEnvironment[0].equals("ubuntu-ai"))
            localFields.setVisibility(if (!chosenEnvironment[0].equals("ssh") && !chosenEnvironment[0].equals("codex")) View.VISIBLE else View.GONE)
            if (chosenEnvironment[0].equals("ssh")) {
                environmentCheck.setVisibility(View.GONE)
            }
            localEnvironment.setText(tr("Окружение: ", "Environment: ") + (if (chosenEnvironment[0].startsWith("proot:")) WorkspaceEnvironments.label(chosenEnvironment[0].substring(6)) else if (chosenEnvironment[0].equals("ubuntu")) "Ubuntu" else "Termux"))
            val loginAlias: String = if (chosenEnvironment[0].startsWith("proot:")) chosenEnvironment[0].substring(6) else if (chosenEnvironment[0].equals("ubuntu")) "ubuntu" else ""
            environmentLogin.setText(if (loginAlias.isEmpty()) tr("Вход: локальная оболочка Termux.", "Entry: local Termux shell.") else tr("Вход из Termux: ", "Enter from Termux: ") + WorkspaceEnvironments.entryCommand(loginAlias, draft.prootIsolated && loginAlias.equals(draft.prootAlias)) + "\n" + (if (loginAlias.equals("ubuntu")) tr("Обычная Ubuntu. Для AI-агентов по умолчанию используется отдельная Ubuntu AI.", "Ordinary Ubuntu. New AI agents use the separate Ubuntu AI by default.") else tr("Команда открывает именно этот экземпляр. Обычная команда входа в Ubuntu откроет другое окружение.", "This command opens this exact instance. The ordinary Ubuntu login command opens a different environment.")))
            sshFields.setVisibility(if (chosenEnvironment[0].equals("ssh")) View.VISIBLE else View.GONE)
            agentFields.setVisibility(if (chosenEnvironment[0].equals("codex")) View.VISIBLE else View.GONE)
            codexFields.setVisibility(if (chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("codex")) View.VISIBLE else View.GONE)
            openCodeFields.setVisibility(if (chosenAgent[0].equals("opencode")) View.VISIBLE else View.GONE)
            geminiFields.setVisibility(if (chosenAgent[0].equals("gemini")) View.VISIBLE else View.GONE)
            claudeFields.setVisibility(if (chosenAgent[0].equals("claude")) View.VISIBLE else View.GONE)
            agentButton.setText(WorkspaceText.tr("Агент: ") + (if (chosenAgent[0].equals("gemini")) "Gemini CLI" else if (chosenAgent[0].equals("claude")) "Claude Code" else if (chosenAgent[0].equals("codex")) "Codex" else "OpenCode"))
            val index: Int = Arrays.asList(*environments).indexOf(if (chosenEnvironment[0].equals("ssh") || chosenEnvironment[0].equals("codex")) chosenEnvironment[0] else "local")
            environment.setText(WorkspaceText.tr("Среда запуска: ") + environmentNames[Math.max(0, index)])
        }
        val checkProfile = Supplier<WorkspaceStore.Session> {
            val p: WorkspaceStore.Session = draft.copy()
            p.environment = if (chosenEnvironment[0].equals("codex")) if (chosenAgent[0].equals("codex")) "codex" else "ubuntu" else chosenEnvironment[0]
            p.codexEnvironment = codexEnvironment[0]
            p.geminiAgent = chosenAgent[0].equals("gemini") && chosenEnvironment[0].equals("codex")
            p.claudeAgent = chosenAgent[0].equals("claude") && chosenEnvironment[0].equals("codex")
            p.aiAgent = if (chosenAgent[0].equals("opencode") && chosenEnvironment[0].equals("codex")) "opencode" else ""
            p.geminiAuth = geminiAuth[0]
            p.geminiCloudProject = (geminiCloudProject.getText().toString() as java.lang.String).trim()
            p.geminiCloudLocation = (geminiCloudLocation.getText().toString() as java.lang.String).trim()
            return@Supplier p
        }
        environmentCheck.setOnClickListener(View.OnClickListener { v ->
            if (!chosenEnvironment[0].equals("codex")) {
                val captured: String = chosenEnvironment[0]
                environmentSetup.check(captured, Consumer<EnvironmentSetup.Result> { result ->
                    if (!editorOpen[0] || closed || !captured.equals(chosenEnvironment[0])) {
                        return@Consumer
                    }
                    environmentCheck.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (result.ready()) R.drawable.workspace_environment_ready else 0, 0)
                    environmentCheck.setCompoundDrawablePadding(dp(10.0f))
                })
                return@OnClickListener
            }
            val p: WorkspaceStore.Session = checkProfile.get()
            val captured: String = AgentPreparation.key(p)
            AgentPreparation.check(activity, p, Consumer<AgentPreparation.Plan> { plan ->
                if (!captured.equals(AgentPreparation.key(checkProfile.get())) || environmentCheck.getWindowToken() == null) {
                    return@Consumer
                }
                environmentCheck.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (plan.ready()) R.drawable.workspace_environment_ready else 0, 0)
                environmentCheck.setCompoundDrawablePadding(dp(10.0f))
                WorkspaceDialogs.builder(activity).setTitle(tr("Проверка среды запуска", "Launch environment check")).setMessage(AgentPreparation.summary(activity, plan)).setPositiveButton(tr("Назад", "Back"), null).show()
            })
        })
        val cloudChanged: TextWatcher = object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(t: CharSequence, start: Int, before: Int, count: Int) {
                resetEnvironmentCheck[0].run()
            }

            override fun afterTextChanged(t: Editable) {
            }
        }
        geminiCloudProject.addTextChangedListener(cloudChanged)
        geminiCloudLocation.addTextChangedListener(cloudChanged)
        val switchUbuntu = Consumer<String> { alias ->
            if (!editorOpen[0] || closed) {
                return@Consumer
            }
            codexEnvironment[0] = alias
            codexConfirmedDirectory[0] = ""
            updateEnvironment.run()
        }
        agentUbuntu.setOnClickListener(View.OnClickListener { v -> agentEnvironments.choose(codexEnvironment[0], switchUbuntu) })
        migrateAgent.setOnClickListener(View.OnClickListener { v ->
            if (!codexEnvironment[0].equals("ubuntu-ai")) {
                agentEnvironments.migrate(switchUbuntu)
            }
        })
        updateEnvironment.run()
        form.addView(environment)
        form.addView(localFields)
        form.addView(agentUbuntu)
        form.addView(environmentCheck)
        form.addView(migrateAgent)
        form.addView(sshFields)
        form.addView(agentFields)
        form.addView(codexFields)
        agentButton.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Выбрать агента")).setItems(arrayOf<String>("Codex", "OpenCode", "Claude Code", "Gemini CLI"), DialogInterface.OnClickListener { d, w ->
                chosenAgent[0] = arrayOf<String>("codex", "opencode", "claude", "gemini")[w]
                if (w >= 0) {
                    if (Arrays.asList("", "Codex", "OpenCode", "Claude Code", "Gemini CLI").contains((name.getText().toString() as java.lang.String).trim())) {
                        name.setText(if (w == 0) "Codex" else if (w == 1) "OpenCode" else if (w == 2) "Claude Code" else "Gemini CLI")
                    }
                    if (!(w == 0 && existing != null && existing.environment.equals("codex") && existing.codexEnvironment.equals("termux")) && !AgentEnvironments.ubuntu(codexEnvironment[0])) {
                        codexEnvironment[0] = "ubuntu-ai"
                    }
                    if (AgentEnvironments.ubuntu(codexEnvironment[0]) && directory.getText().toString().equals(TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                        directory.setText("/root")
                    }
                }
                updateEnvironment.run()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        localEnvironment.setOnClickListener(View.OnClickListener { v ->
            environmentSetup.choose(Consumer<WorkspaceEnvironments.Choice> { choice ->
                if (!editorOpen[0] || closed) {
                    return@Consumer
                }
                val old: String = chosenEnvironment[0]
                chosenEnvironment[0] = choice.environment
                if (!old.equals(choice.environment)) {
                    directory.setText(choice.home)
                }
                updateEnvironment.run()
            })
        })
        environment.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Среда запуска")).setItems(environmentNames, DialogInterface.OnClickListener { d, which ->
                val before: String = chosenEnvironment[0]
                if (which == 0) {
                    if (before.equals("codex") || before.equals("ssh")) {
                        chosenEnvironment[0] = "termux"
                        directory.setText(TermuxConstants.TERMUX_HOME_DIR_PATH)
                    }
                } else if (which == 1) {
                    chosenEnvironment[0] = "ssh"
                    if (directory.getText().toString().equals(TermuxConstants.TERMUX_HOME_DIR_PATH) || directory.getText().toString().equals("/root")) {
                        directory.setText("~")
                    }
                } else {
                    chosenEnvironment[0] = "codex"
                    if (directory.getText().toString().equals(TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                        directory.setText("/root")
                    }
                    if ((name.getText().toString() as java.lang.String).trim().isEmpty()) {
                        name.setText("Codex")
                    }
                }
                updateEnvironment.run()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        val commandFields: LinearLayout = column()
        form.addView(commandFields)
        val startupFields: MutableList<EditText> = ArrayList<EditText>()
        startupFields.add(labeled(commandFields, WorkspaceText.tr("Команда 1 (необязательно)"), draft.startup, true))
        if (!draft.startup2.isEmpty() || !draft.startup3.isEmpty()) {
            startupFields.add(labeled(commandFields, WorkspaceText.tr("Команда 2"), draft.startup2, true))
        }
        if (!draft.startup3.isEmpty()) {
            startupFields.add(labeled(commandFields, WorkspaceText.tr("Команда 3"), draft.startup3, true))
        }
        val addCommand: TextView = button(WorkspaceText.tr("+ Команда"), false, Runnable {  })
        addCommand.setContentDescription(WorkspaceText.tr("Добавить команду запуска"))
        addCommand.setOnClickListener(View.OnClickListener { v ->
            if (startupFields.size >= 3) {
                return@OnClickListener
            }
            startupFields.add(labeled(commandFields, WorkspaceText.tr("Команда ") + (startupFields.size + 1), "", true))
            if (startupFields.size == 3) {
                addCommand.setVisibility(View.GONE)
            }
        })
        if (startupFields.size == 3) {
            addCommand.setVisibility(View.GONE)
        }
        form.addView(addCommand)
        val toggleCommands = Runnable {
            commandFields.setVisibility(if (chosenEnvironment[0].equals("codex")) View.GONE else View.VISIBLE)
            addCommand.setVisibility(if (chosenEnvironment[0].equals("codex") || startupFields.size >= 3) View.GONE else View.VISIBLE)
        }
        toggleCommands.run()
        environment.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(x: CharSequence, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(x: CharSequence, a: Int, b: Int, c: Int) {
                toggleCommands.run()
            }

            override fun afterTextChanged(x: Editable) {
            }
        })
        val hint: TextView = text(tr("Окружения Termux: вход выполняется автоматически в выбранный экземпляр. Команда ручного входа показана выше; добавлять её в команды запуска не нужно. До трёх шагов, при ошибке остановка. Для Ubuntu используется Bash, для остальных систем — Bash или sh. SSH: каталог и команды выполняются на сервере с Bash; пароль и подтверждение ключа — в терминале.", "Termux environments: automatically enter the selected instance. The manual entry command is shown above; do not add it to startup commands. Up to three steps, stopping after a failure. Ubuntu uses Bash; other systems use Bash or sh. SSH: directories and commands run on the server with Bash; password and host-key confirmation are handled in the terminal."), 12, Pal.MUTED)
        hint.setPadding(0, dp(16.0f), 0, 0)
        form.addView(hint)
        val connectionFields: LinearLayout = column()
        connectionFields.setTag("agent-connection-fields")
        form.addView(connectionFields)
        val connectionCommand: EditText = labeled(connectionFields, tr("Команда подключения (необязательно)", "Connection command (optional)"), draft.agentConnectionCommand, false)
        connectionCommand.setTag("agent-connection-command")
        connectionCommand.setContentDescription(tr("Команда подключения", "Connection command"))
        connectionCommand.setHint("/absolute/path/to/launcher")
        connectionFields.addView(text(tr("Путь к исполняемому запускателю в выбранном окружении, без аргументов. Ему передаются штатная команда агента и её параметры. Пусто — обычное подключение. Для работающего чата после сохранения перезапустите эту сессию, продолжив тот же чат. Другие сессии не меняются.", "Executable launcher path in the selected environment, without arguments. It receives the normal agent command and arguments. Empty means normal connection. After saving, restart this session and resume the same chat. Other sessions are unchanged."), 12, Pal.MUTED))
        val toggleConnection = Runnable {
            connectionFields.setVisibility(if (chosenEnvironment[0].equals("codex") && AgentEnvironments.ubuntu(codexEnvironment[0])) View.VISIBLE else View.GONE)
        }
        toggleConnection.run()
        environment.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
            }

            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
                toggleConnection.run()
            }

            override fun afterTextChanged(s: Editable) {
            }
        })
        val extendedDebug = CheckBox(activity)
        extendedDebug.setTag("agent-extended-debug")
        extendedDebug.setText(WorkspaceText.tr("Расширенный дебаг-журнал"))
        extendedDebug.setTextColor(Pal.TEXT)
        extendedDebug.setChecked(draft.extendedDebug)
        form.addView(extendedDebug)
        form.addView(text(WorkspaceText.tr("Базовый журнал ведётся автоматически. Расширенный: события и коды ошибок, для Gemini также штатный debug с запуска. Без записи ввода и диалога. Изменение действует при следующем запуске."), 12, Pal.MUTED))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (existing == null) WorkspaceText.tr(if (launchAfterSave) "Создать сессию" else "Создать пресет") else WorkspaceText.tr("Настройки сессии")).setView(formScroll(form)).setPositiveButton(if (existing == null && launchAfterSave) WorkspaceText.tr("Создать и запустить") else WorkspaceText.tr("Сохранить"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        if (existing == null && launchAfterSave) {
            dialog.setButton(DialogInterface.BUTTON_NEUTRAL, WorkspaceText.tr("Пресеты"), DialogInterface.OnClickListener { d, w -> chooseSavedSession() })
        }
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if ((name.getText().toString() as java.lang.String).trim().isEmpty()) {
                    name.setError(WorkspaceText.tr("Укажите название"))
                    return@OnClickListener
                }
                val agentConnection: String = (connectionCommand.getText().toString() as java.lang.String).trim()
                if (chosenEnvironment[0].equals("codex") && !AgentConnection.valid(agentConnection)) {
                    connectionCommand.setError(tr("Укажите абсолютный путь к запускателю без управляющих символов", "Enter an absolute launcher path without control characters"))
                    return@OnClickListener
                }
                val connectionEdited: Boolean = !draft.agentConnectionCommand.equals(agentConnection)

                if (!validDirectoryInput(directory)) {
                    return@OnClickListener
                }
                val nextAlias: String = if (chosenEnvironment[0].startsWith("proot:")) chosenEnvironment[0].substring(6) else ""
                if (existing != null && (!existing.prootAlias.isEmpty() || !nextAlias.isEmpty()) && liveFor(existing) != null && liveFor(existing)!!.isRunning() && (!existing.prootAlias.equals(nextAlias) || !existing.environment.equals(if (nextAlias.isEmpty()) chosenEnvironment[0] else "termux"))) {
                    activity.showToast(tr("Сначала закройте сессию, чтобы сменить окружение.", "Close the session before changing its environment."), true)
                    return@OnClickListener
                }
                val selectedGemini: Boolean = chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("gemini")
                if (selectedGemini && geminiAuth[0].equals("vertex")) {
                    if (!GeminiCli.validCloudProject((geminiCloudProject.getText().toString() as java.lang.String).trim())) {
                        geminiCloudProject.setError(tr("Укажите ID облачного проекта, например my-project-123, а не путь к папке", "Enter a cloud project ID, for example my-project-123, not a folder path"))
                        return@OnClickListener
                    }
                    if (!GeminiCli.validCloudLocation((geminiCloudLocation.getText().toString() as java.lang.String).trim())) {
                        geminiCloudLocation.setError(tr("Укажите global или регион, например us-central1", "Enter global or a location such as us-central1"))
                        return@OnClickListener
                    }
                }
                if (existing != null && liveFor(existing) != null && liveFor(existing)!!.isRunning() && (existing.geminiAgent || selectedGemini) && (existing.geminiAgent != selectedGemini || !existing.geminiAuth.equals(geminiAuth[0]) || geminiAuth[0].equals("vertex") && (!existing.geminiCloudProject!!.equals((geminiCloudProject.getText().toString() as java.lang.String).trim()) || !existing.geminiCloudLocation!!.equals((geminiCloudLocation.getText().toString() as java.lang.String).trim())) || !existing.geminiModel.equals(geminiModel[0]) || !existing.geminiMode.equals(geminiMode[0]) || !existing.geminiChat.equals(geminiChat[0]) || !existing.geminiThread.equals(geminiThread[0]))) {
                    activity.showToast(WorkspaceText.tr("Сначала закройте сессию, чтобы сменить агента, подключение или уровень доступа."), true)
                    return@OnClickListener
                }
                val selectedClaude: Boolean = chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("claude")
                if (existing != null && liveFor(existing) != null && liveFor(existing)!!.isRunning() && (existing.claudeAgent || selectedClaude) && (existing.claudeAgent != selectedClaude || !existing.claudeModel!!.equals(claudeModel[0]) || !existing.claudeMode!!.equals(claudeMode[0]) || !existing.claudeChat.equals(claudeChat[0]) || !existing.claudeThread.equals(claudeThread[0]))) {
                    activity.showToast(WorkspaceText.tr("Сначала закройте сессию, чтобы сменить агента, подключение или уровень доступа."), true)
                    return@OnClickListener
                }
                if (existing != null && liveFor(existing) != null && liveFor(existing)!!.isRunning() && !existing.codexEnvironment.equals(codexEnvironment[0])) {
                    activity.showToast(tr("Сначала закройте сессию, чтобы сменить Ubuntu", "Close the session before changing Ubuntu"), true)
                    return@OnClickListener
                }
                if (existing != null && liveFor(existing) != null && liveFor(existing)!!.isRunning() && (!existing.aiAgent.isEmpty() || chosenAgent[0].equals("opencode")) && (!existing.aiAgent.equals(if (chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("opencode")) "opencode" else "") || !existing.aiConnection.equals(aiConnection[0]) || !existing.aiAccess.equals(aiAccess[0]) || !existing.environment.equals(if (chosenAgent[0].equals("opencode") && chosenEnvironment[0].equals("codex")) "ubuntu" else chosenEnvironment[0]))) {
                    activity.showToast(WorkspaceText.tr("Сначала закройте сессию, чтобы сменить агента, подключение или уровень доступа."), true)
                    return@OnClickListener
                }
                if (chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("codex") && codexChat[0].equals("id") && !codexConfirmedDirectory[0]!!.equals((directory.getText().toString() as java.lang.String).trim())) {
                    val env: String = codexEnvironment[0]
                    val cwd: String = (directory.getText().toString() as java.lang.String).trim()
                    val thread: String = codexThread[0]
                    verifyCodexChat(env, cwd, thread, BiConsumer<String, String?> { title, source ->
                        if (!dialog.isShowing() || !env.equals(codexEnvironment[0]) || !cwd.equals((directory.getText().toString() as java.lang.String).trim()) || !thread.equals(codexThread[0])) {
                            return@BiConsumer
                        }
                        codexSourceDirectory[0] = source
                        codexConfirmedDirectory[0] = cwd
                        codexThreadName[0] = title
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    })
                    return@OnClickListener
                }

                var port = 22
                if (chosenEnvironment[0].equals("ssh")) {
                    if (!SshSessionShell.validHost((sshHost.getText().toString() as java.lang.String).trim())) {
                        sshHost.setError(WorkspaceText.tr("Укажите адрес без пробелов, пользователя и порта"))
                        return@OnClickListener
                    }
                    if (!SshSessionShell.validUser((sshUser.getText().toString() as java.lang.String).trim())) {
                        sshUser.setError(WorkspaceText.tr("Укажите пользователя SSH"))
                        return@OnClickListener
                    }
                    try {
                        port = Integer.parseInt(sshPort.getText().toString())
                    } catch (e: NumberFormatException) {
                        port = 0
                    }
                    if (!SshSessionShell.validPort(port)) {
                        sshPort.setError(WorkspaceText.tr("Порт от 1 до 65535"))
                        return@OnClickListener
                    }
                }
                val sshPasswordMode: Boolean = chosenEnvironment[0].equals("ssh") && chosenAuth[0].equals("password")
                if (chosenEnvironment[0].equals("ssh") && chosenAuth[0].equals("key")) {
                    val path: String = (identity.getText().toString() as java.lang.String).trim()
                    if (!SshSessionShell.validIdentity(path) || !File(resolve(path)).isFile() || !File(resolve(path)).canRead()) {
                        identity.setError(WorkspaceText.tr("Укажите доступный файл ключа: /путь или ~/путь"))
                        return@OnClickListener
                    }
                }
                val sameConnection: Boolean = draft.sshHost.equals((sshHost.getText().toString() as java.lang.String).trim()) && draft.sshUser.equals((sshUser.getText().toString() as java.lang.String).trim()) && draft.sshPort == port
                var encrypted: String = if (sshPasswordMode && rememberPassword.isChecked() && sameConnection) draft.sshEncryptedPassword else ""
                val secret = CharArray(password.length())
                TextUtils.getChars(password.getText(), 0, secret.size, secret, 0)
                if (sshPasswordMode && (password.getText().toString().contains("\n") || password.getText().toString().contains("\r") || password.getText().toString().indexOf('\u0000') >= 0 || secret.size > 256)) {
                    Arrays.fill(secret, '\u0000')
                    password.setError(WorkspaceText.tr("Пароль до 256 символов, без переносов строки"))
                    return@OnClickListener
                }
                if (sshPasswordMode && rememberPassword.isChecked() && encrypted.isEmpty() && secret.size == 0) {
                    password.setError(WorkspaceText.tr("Введите пароль для сохранения"))
                    return@OnClickListener
                }
                if (sshPasswordMode && rememberPassword.isChecked() && secret.size > 0) {
                    val binding = WorkspaceStore.Session()
                    binding.id = draft.id
                    binding.sshHost = (sshHost.getText().toString() as java.lang.String).trim()
                    binding.sshUser = (sshUser.getText().toString() as java.lang.String).trim()
                    binding.sshPort = port
                    try {
                        encrypted = SshPasswordVault.encrypt(binding, secret)
                    } catch (e: GeneralSecurityException) {
                        Arrays.fill(secret, '\u0000')
                        password.setError(WorkspaceText.tr("Keystore недоступен. Отключите запоминание или повторите."))
                        return@OnClickListener
                    } catch (e: RuntimeException) {
                        Arrays.fill(secret, '\u0000')
                        password.setError(WorkspaceText.tr("Keystore недоступен. Отключите запоминание или повторите."))
                        return@OnClickListener
                    }
                }
                var nextGeminiKey: String = geminiStoredKey[0]
                if (selectedGemini && geminiAuth[0].equals("api") && geminiApiKey.length() > 0) {
                    val apiSecret = CharArray(geminiApiKey.length())
                    TextUtils.getChars(geminiApiKey.getText(), 0, apiSecret.size, apiSecret, 0)
                    try {
                        if (!GeminiApiKeyVault.valid(apiSecret)) {
                            geminiApiKey.setError(tr("Ключ до 4096 символов, без пробелов и переносов строки", "Key up to 4096 characters, without spaces or line breaks"))
                            return@OnClickListener
                        }
                        nextGeminiKey = GeminiApiKeyVault.encrypt(draft, apiSecret)
                    } catch (e: GeneralSecurityException) {
                        geminiApiKey.setError(tr("Не удалось защитить ключ через Android Keystore. Повторите или используйте вход CLI.", "Could not protect the key with Android Keystore. Retry or use CLI authentication."))
                        return@OnClickListener
                    } catch (e: RuntimeException) {
                        geminiApiKey.setError(tr("Не удалось защитить ключ через Android Keystore. Повторите или используйте вход CLI.", "Could not protect the key with Android Keystore. Retry or use CLI authentication."))
                        return@OnClickListener
                    } finally {
                        Arrays.fill(apiSecret, '\u0000')
                        Arrays.fill(secret, '\u0000')
                    }
                }
                draft.geminiEncryptedKey = nextGeminiKey
                geminiApiKey.setText("")
                val oldSecret: CharArray? = pendingSshPasswords.remove(draft.id)
                if (oldSecret != null) {
                    Arrays.fill(oldSecret, '\u0000')
                }
                if (sshPasswordMode && secret.size > 0 && !rememberPassword.isChecked()) {
                    pendingSshPasswords.put(draft.id, secret.clone())
                }
                Arrays.fill(secret, '\u0000')
                password.setText("")
                draft.extendedDebug = extendedDebug.isChecked()
                draft.agentConnectionCommand = if (chosenEnvironment[0].equals("codex")) agentConnection else ""
                draft.sshAuth = chosenAuth[0]
                draft.sshIdentity = (identity.getText().toString() as java.lang.String).trim()
                draft.sshEncryptedPassword = encrypted
                draft.sshHost = (sshHost.getText().toString() as java.lang.String).trim()
                draft.sshUser = (sshUser.getText().toString() as java.lang.String).trim()
                draft.sshPort = port
                draft.sshKeepOpen = sshKeepOpen.isChecked()
                if (selectedGemini && geminiAuth[0].equals("vertex")) {
                    draft.geminiCloudProject = (geminiCloudProject.getText().toString() as java.lang.String).trim()
                    draft.geminiCloudLocation = (geminiCloudLocation.getText().toString() as java.lang.String).trim()
                }
                draft.geminiAgent = selectedGemini
                draft.geminiAuth = geminiAuth[0]
                draft.geminiModel = geminiModel[0]
                draft.geminiMode = geminiMode[0]
                draft.geminiChat = geminiChat[0]
                draft.geminiThread = geminiThread[0]
                if (VertexConnection.selected(draft) && !draft.geminiCloudProject!!.isEmpty()) {
                    VertexConnection.remember(activity, draft.geminiCloudProject, draft.geminiCloudLocation)
                }
                draft.claudeAgent = selectedClaude
                draft.claudeModel = claudeModel[0]
                draft.claudeMode = claudeMode[0]
                draft.claudeChat = claudeChat[0]
                draft.claudeThread = claudeThread[0]
                draft.aiAgent = if (chosenEnvironment[0].equals("codex") && chosenAgent[0].equals("opencode")) "opencode" else ""
                if (draft.aiAgent.equals("opencode")) {
                    draft.environment = "ubuntu"
                }
                if (!draft.aiConnection.equals(aiConnection[0]) || aiChat[0].equals("new") || !draft.aiSavedThread.equals(aiThread[0])) {
                    draft.aiSavedThread = ""
                }
                draft.aiConnection = aiConnection[0]
                draft.aiModel = aiModel[0]
                draft.aiVariant = aiVariant[0]
                draft.aiMode = aiMode[0]
                draft.aiAccess = aiAccess[0]
                draft.aiChat = aiChat[0]
                draft.aiThread = aiThread[0]
                if (existing != null && codexChatEdited[0]) {
                    draft.codexBindingBlockedRun = draft.codexSocketId
                }
                draft.codexConfirmActions = codexConfirm.isChecked()
                draft.aiProjectOnly = aiProjectScope.isChecked()
                draft.codexEnvironment = codexEnvironment[0]
                draft.codexAccess = codexAccess[0]
                draft.codexChat = codexChat[0]
                draft.codexThread = codexThread[0]
                draft.codexThreadName = codexThreadName[0]
                draft.codexSourceDirectory = codexSourceDirectory[0]
                draft.prootIsolated = draft.prootIsolated && draft.prootAlias.equals(nextAlias)
                draft.prootAlias = if (chosenEnvironment[0].startsWith("proot:")) chosenEnvironment[0].substring(6) else ""
                draft.environment = if (draft.aiAgent.equals("opencode") || draft.claudeAgent || draft.geminiAgent) "ubuntu" else if (chosenEnvironment[0].startsWith("proot:")) "termux" else chosenEnvironment[0]
                draft.name = (name.getText().toString() as java.lang.String).trim()
                draft.directory = (directory.getText().toString() as java.lang.String).trim()
                draft.startup = startupFields.get(0).getText().toString()
                draft.startup2 = if (startupFields.size > 1) startupFields.get(1).getText().toString() else ""
                draft.startup3 = if (startupFields.size > 2) startupFields.get(2).getText().toString() else ""
                if (draft.aiAgent.equals("opencode") || draft.claudeAgent || draft.geminiAgent) {
                    draft.startup = ""
                    draft.startup2 = ""
                    draft.startup3 = ""
                }
                if (connectionEdited && existing != null && !codexChatEdited[0] && liveFor(draft) != null && liveFor(draft)!!.isRunning()) {
                    val active: CodexStatus? = codexStatuses.get(draft.id)
                    if (active != null) {
                        CodexProjects.bind(draft, active)
                    }
                }
                draft.updated = System.currentTimeMillis()
                draft.restoreReview = false
                if (existing == null) {
                    draft.saved = !launchAfterSave
                    if (draft.saved) {
                        store.sessions.add(draft)
                    }
                }
                val live: TerminalSession? = liveFor(draft)
                if (live != null) {
                    live.mSessionName = draft.name
                }
                store.save()
                dialog.dismiss()
                refresh()
                if (existing == null && launchAfterSave) {
                    launchNew(draft, true)
                } else if (live != null) {
                    activity.showToast(if (connectionEdited) tr("Команда подключения сохранена. Применится после перезапуска этой сессии. Текущий процесс не изменён.", "Connection command saved. Restart this session to apply it. The current process is unchanged.") else tr("Имя обновлено. Каталог, команды и права применятся при следующем запуске; текущая сессия не изменена.", "Name updated. Directory, commands and permissions apply on next launch; the current session is unchanged."), true)
                }
            })
        })
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            editorOpen[0] = false
            password.setText("")
            geminiApiKey.setText("")
        })
        dialog.show()
    }

    private fun interface ChatChoice {
        fun chosen(id: String, name: String?, sourceDirectory: String?)
    }

    private fun chooseCodexChat(environment: String, directory: String, choice: ChatChoice) {
        val cwd: String = CodexProjects.directory(environment, directory)
        if (cwd.isEmpty()) {
            activity.showToast(WorkspaceText.tr("Укажите абсолютный рабочий каталог"), true)
            return
        }
        val body: LinearLayout = column()
        body.setPadding(dp(12.0f), dp(4.0f), dp(12.0f), dp(8.0f))
        val loading: TextView = text(WorkspaceText.tr("Загрузка истории…"), 13, Pal.MUTED)
        body.addView(loading)
        val search: EditText = field(WorkspaceText.tr("Найти чат или каталог"), "", false)
        body.addView(search)
        val filters: LinearLayout = row()
        body.addView(filters)
        val all: TextView = button(WorkspaceText.tr("Все чаты"), false, Runnable {  })
        val project: TextView = button(WorkspaceText.tr("Чаты этой папки"), false, Runnable {  })
        all.setTextSize(12.0f)
        project.setTextSize(12.0f)
        all.setTextColor(Pal.ACCENT)
        filters.addView(all, LinearLayout.LayoutParams(0, -2, 1.0f))
        val projectParams = LinearLayout.LayoutParams(0, -2, 1.0f)
        projectParams.setMarginStart(dp(6.0f))
        filters.addView(project, projectParams)
        val scroll = ScrollView(activity)
        val rows: LinearLayout = column()
        scroll.addView(rows)
        body.addView(scroll, LinearLayout.LayoutParams(-1, dp(280.0f)))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Выбрать чат Codex")).setView(body).setPositiveButton(WorkspaceText.tr("Продолжить выбранный чат"), null).setNeutralButton(WorkspaceText.tr("Новый чат в этой папке"), DialogInterface.OnClickListener { d, w -> choice.chosen("", WorkspaceText.tr("Новый чат"), "") }).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog.setOnShowListener(DialogInterface.OnShowListener { d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false) })
        dialog.show()
        codexReader.execute(Runnable {
            val chats: MutableList<CodexChatCatalog.Entry> = ArrayList<CodexChatCatalog.Entry>()
            var failure: String? = null
            val profile = WorkspaceStore.Session()
            profile.codexEnvironment = environment
            try {
                (CodexRpc.history(activity, profile)).use { rpc ->
                    var cursor: String? = null
                    for (page in 0 until (10)) {
                        val data: JSONObject = rpc.call("thread/list", CodexChatCatalog.params(cursor))
                        val entries: JSONArray = data.getJSONArray("data")
                        var i = 0
                        while (i < entries.length()) {
                            val entry: CodexChatCatalog.Entry? = CodexChatCatalog.parse(entries.getJSONObject(i))
                            if (entry != null) {
                                chats.add(entry)
                            }
                            i++
                        }
                        cursor = if (data.isNull("nextCursor")) null else data.optString("nextCursor", null)
                        if (cursor == null || cursor.isEmpty()) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                failure = WorkspaceText.tr("Не удалось прочитать всю историю. Проверьте Codex и авторизацию в выбранной среде.")
            }
            val error: String? = failure
            activity.runOnUiThread(Runnable {
                if (closed || !dialog.isShowing()) {
                    return@Runnable
                }
                loading.setText(if (error != null) error else AgentEnvironments.label(environment) + WorkspaceText.tr(" · новый каталог: ") + cwd + (if (chats.isEmpty()) WorkspaceText.tr("\nСохранённых чатов пока нет.") else ""))
                val selected: IntArray = intArrayOf(-1)
                val projectOnly: BooleanArray = booleanArrayOf(false)
                val render = arrayOfNulls<Runnable>(1)
                render[0] = Runnable {
                    rows.removeAllViews()
                    all.setTextColor(if (projectOnly[0]) Pal.MUTED else Pal.ACCENT)
                    project.setTextColor(if (projectOnly[0]) Pal.ACCENT else Pal.MUTED)
                    var shown = 0
                    var i = 0
                    while (i < chats.size) {
                        val entry: CodexChatCatalog.Entry = chats.get(i)
                        if (!CodexChatCatalog.visible(entry, environment, cwd, projectOnly[0], search.getText().toString())) {
                            i++
                            continue
                        }
                        val index: Int = i
                        var subtitle: String? = if (entry.cwd.isEmpty()) WorkspaceText.tr("Каталог неизвестен") else entry.cwd
                        if (entry.updated > 0) {
                            subtitle += " · " + DateFormat.format("dd.MM HH:mm", entry.updated * 1000)
                        }
                        val item: TextView = button(entry.name + (if (selected[0] == i) "  ✓" else "") + "\n" + subtitle, false, Runnable {
                            selected[0] = index
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true)
                            render[0]!!.run()
                        })
                        item.setTextSize(13.0f)
                        item.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
                        item.setTextColor(if (selected[0] == i) Pal.ACCENT else Pal.TEXT)
                        item.setMaxLines(3)
                        item.setEllipsize(TextUtils.TruncateAt.END)
                        val lp = LinearLayout.LayoutParams(-1, -2)
                        lp.bottomMargin = dp(6.0f)
                        rows.addView(item, lp)
                        shown++
                        i++
                    }
                    if (shown == 0) {
                        rows.addView(text(if (projectOnly[0]) WorkspaceText.tr("Чатов этой папки нет. Выберите «Все чаты».") else WorkspaceText.tr("Нет подходящих чатов"), 13, Pal.MUTED))
                    }
                    if (selected[0] >= 0 && !CodexChatCatalog.visible(chats.get(selected[0]), environment, cwd, projectOnly[0], search.getText().toString())) {
                        selected[0] = -1
                    }
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(selected[0] >= 0)
                }
                all.setOnClickListener(View.OnClickListener { v ->
                    projectOnly[0] = false
                    render[0]!!.run()
                })
                project.setOnClickListener(View.OnClickListener { v ->
                    projectOnly[0] = true
                    render[0]!!.run()
                })
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(t: CharSequence, a: Int, c: Int, f: Int) {
                    }

                    override fun onTextChanged(t: CharSequence, a: Int, b: Int, c: Int) {
                        render[0]!!.run()
                    }

                    override fun afterTextChanged(t: Editable) {
                    }
                })
                render[0]!!.run()
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                    if (selected[0] < 0) {
                        return@OnClickListener
                    }
                    val entry: CodexChatCatalog.Entry = chats.get(selected[0])
                    verifyCodexChat(environment, cwd, entry.id, BiConsumer<String, String?> { title, source ->
                        if (dialog.isShowing()) {
                            choice.chosen(entry.id, title, source)
                            dialog.dismiss()
                        }
                    })
                })
            })
        })
    }

    private fun confirmCodexDirectory(environment: String, target: String, source: String?, accepted: Runnable) {
        if (CodexProjects.matches(environment, target, source)) {
            accepted.run()
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Продолжить чат в другой папке?")).setMessage(WorkspaceText.tr("Исходный каталог:\n") + source + WorkspaceText.tr("\n\nНовый рабочий каталог:\n") + CodexProjects.directory(environment, target) + WorkspaceText.tr("\n\nИстория останется прежней. В ней могут быть старые задачи и пути. Файлы не переносятся; права остаются выбранными в настройках сессии.")).setPositiveButton(WorkspaceText.tr("Продолжить в новой папке"), DialogInterface.OnClickListener { d, w -> accepted.run() }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun verifyCodexChat(environment: String, cwd: String, id: String, choice: BiConsumer<String, String?>) {
        val waiting: TextView = text(WorkspaceText.tr("Проверка истории и каталога…"), 14, Pal.MUTED)
        waiting.setPadding(dp(16.0f), dp(16.0f), dp(16.0f), dp(16.0f))
        val progress: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Проверка чата")).setView(waiting).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        codexReader.execute(Runnable {
            val profile = WorkspaceStore.Session()
            profile.codexEnvironment = environment
            var title: String? = null
            var source: String? = null
            try {
                val rpc = CodexRpc.history(activity, profile)
                var primaryException: Throwable? = null
                try {
                    val chat: JSONObject = rpc.call("thread/read", JSONObject().put("threadId", id).put("includeTurns", false)).getJSONObject("thread")
                    val original: String = chat.optString("cwd", "")
                    if (original.startsWith("/") && !CodexProjects.directory(environment, original).isEmpty()) {
                        source = original
                        title = chat.optString("name", id)
                        if (title!!.isEmpty() || title.equals("null")) {
                            title = id
                        }
                    }
                } catch (t: Throwable) {
                    primaryException = t
                    throw t
                } finally {
                    if (primaryException != null) {
                        try {
                            rpc.close()
                        } catch (suppressed: Throwable) {
                            primaryException.addSuppressed(suppressed)
                        }
                    } else {
                        rpc.close()
                    }
                }
            } catch (ignored: Exception) {
            }
            val result: String? = title
            val origin: String? = source
            activity.runOnUiThread(Runnable {
                if (closed || !progress.isShowing()) {
                    return@Runnable
                }
                progress.dismiss()
                if (result == null) {
                    activity.showToast(WorkspaceText.tr("Чат недоступен или исходный каталог неизвестен. Проверьте ID и выбранную среду."), true)
                } else {
                    confirmCodexDirectory(environment, cwd, origin, Runnable {
                        if (!closed) {
                            choice.accept(result, origin)
                        }
                    })
                }
            })
        })
    }

    private fun currentCodex(): WorkspaceStore.Session? {
        val live: TerminalSession? = activity.getCurrentSession()
        if (live == null || !live.isRunning()) {
            return null
        }
        val profile: WorkspaceStore.Session? = store.findByHandle(live.mHandle)
        return if (profile != null && profile.environment.equals("codex")) profile else null
    }

    private fun renderCodexStatus() {
        geminiLiveUi!!.syncPermissionVisibility()
        agentReceiptsUi!!.syncVisibility()
        if (codexStatus == null) {
            return
        }
        if (desktopSessionBar != null) {
            desktopSessionBar!!.working(false)
        }
        desktopSessionBar = null
        codexStatus.removeAllViews()
        val aiLive: TerminalSession? = activity.getCurrentSession()
        val ai: WorkspaceStore.Session? = if (aiLive == null) null else store.findByHandle(aiLive.mHandle)
        if (ai != null && ai.geminiAgent && aiLive!!.isRunning()) {
            codexStatus.setVisibility(if (screen === Screen.TERMINAL) View.VISIBLE else View.GONE)
            geminiLiveUi!!.render(codexStatus, ai)
            return
        }
        if (ai != null && ai.claudeAgent && aiLive!!.isRunning()) {
            codexStatus.setVisibility(if (screen === Screen.TERMINAL) View.VISIBLE else View.GONE)
            claudeLiveUi!!.render(codexStatus, ai)
            return
        }
        if (ai != null && ai.aiAgent.equals("opencode") && aiLive!!.isRunning()) {
            codexStatus.setVisibility(if (screen === Screen.TERMINAL) View.VISIBLE else View.GONE)
            val chip: TextView = AgentStatusUi.chip(activity, "OpenCode · " + agentChatUi!!.modelLabel(ai) + "\n" + ai.aiMode + " · " + AiAgentsUi.accessLabel(ai.aiAccess) + " ›", tr("Статус и модель OpenCode", "OpenCode status and model"), Runnable {
                AiAgentsUi.active(activity, ai, Runnable {
                    store.save()
                    agentChatUi!!.modelChanged(ai)
                    renderCodexStatus()
                }, Runnable(this::previewChatScale), Runnable(store::save))
            })
            AgentStatusUi.attach(codexStatus, chip)
            return
        }
        if (aiLive != null && aiLive.isRunning() && WorkspaceDesktop.eligible(ai)) {
            codexStatus.setVisibility(if (screen === Screen.TERMINAL) View.VISIBLE else View.GONE)
            desktopSessionBar = DesktopSessionBar(activity, if (desktopUi == null) tr("Терминал", "Terminal") else desktopUi!!.label(), Runnable {
                if (desktopUi != null) {
                    desktopUi!!.menu()
                }
            }, Runnable {
                if (desktopUi != null) {
                    desktopUi!!.openPrograms()
                }
            })
            val desktopRow = LinearLayout.LayoutParams(-1, -2)
            desktopRow.bottomMargin = dp(6.0f)
            desktopRow.topMargin = desktopRow.bottomMargin
            codexStatus.addView(desktopSessionBar, desktopRow)
            updateDesktopProgramsBorder()
            return
        }
        val profile: WorkspaceStore.Session? = currentCodex()
        val prior: CodexStatus? = if (profile == null) null else codexStatuses.get(profile.id)
        val ended: Boolean = profile != null && prior != null && prior.updated > 0 && liveFor(profile) == null
        codexStatus.setVisibility(if (screen === Screen.TERMINAL && profile != null && !ended) View.VISIBLE else View.GONE)
        if (profile == null) {
            return
        }
        val status: CodexStatus? = codexStatuses.get(profile.id)
        var label: String? = if (status == null) WorkspaceText.tr("Codex · подключение…") else if (!status.error!!.isEmpty()) WorkspaceText.tr("Codex · нет данных ›") else (if (status.model!!.isEmpty()) "Codex" else status.model) + (if (status.effort!!.isEmpty()) "" else " · " + effortName(status.effort)) + "  " + status.summary() + " ›"
        if (status != null && status.error!!.isEmpty()) {
            label = "Codex · " + (if (status.model!!.isEmpty()) tr("Модель не указана", "Model unavailable") else status.model) + (if (status.effort!!.isEmpty()) "" else " · " + effortName(status.effort)) + (if (status.modelPending) tr(" · следующий ответ", " · next response") else "") + "\n" + status.summary() + " ›"
        }
        val chip: TextView = AgentStatusUi.chip(activity, label, WorkspaceText.tr("Статус и модель Codex"), Runnable { showCodexStatus(profile) })
        AgentStatusUi.attach(codexStatus, chip)
    }

    private fun loadCodexStatus(profile: WorkspaceStore.Session?, done: Runnable?) {
        if (closed || codexLoading) {
            return
        }
        codexLoading = true
        val socketAtRequest: String = profile!!.codexSocketId
        val handleAtRequest: String = profile.handle
        codexReader.execute(Runnable {
            var result: CodexStatus
            try {
                result = CodexStatus.local(activity.getFilesDir(), profile)
            } catch (e: Exception) {
                result = CodexStatus()
                result.error = CodexRpc.diagnostic(e)
            }
            val status: CodexStatus = result
            activity.runOnUiThread(Runnable {
                codexLoading = false
                if (closed || !socketAtRequest.equals(profile.codexSocketId) || !handleAtRequest.equals(profile.handle)) {
                    return@Runnable
                }
                val terminal: TerminalSession? = liveFor(profile)
                if (terminal != null && status.effort!!.isEmpty() && !status.model!!.isEmpty()) {
                    val changed: String? = CodexModelSelection.acknowledgement(codexScreen(terminal))
                    val prefix: String = "Model changed to " + status.model + " "
                    if (changed!!.startsWith(prefix)) {
                        status.effort = changed.substring(prefix.length)
                    }
                }
                val choice: Array<String?>? = codexConfirmedChoices.get(profile.codexSocketId)
                if (choice != null && choice[0]!!.equals(status.thread) && status.error!!.isEmpty()) {
                    val changed: String? = if (terminal == null) "" else CodexModelSelection.acknowledgement(codexScreen(terminal))
                    if (!changed!!.isEmpty() && !changed.equals(choice[3])) {
                        val parts: Array<String> = (changed.substring("Model changed to ".length) as java.lang.String).split(" ", 2)
                        if (parts.size == 2) {
                            choice[1] = parts[0]
                            choice[2] = parts[1]
                            choice[3] = changed
                        }
                    }
                    status.model = choice[1]
                    status.effort = choice[2]
                } else if (choice != null) {
                    codexConfirmedChoices.remove(profile.codexSocketId)
                }
                if (CodexProjects.bind(profile, status)) {
                    store.save()
                    renderContent()
                }
                codexStatuses.put(profile.id, status)
                renderCodexStatus()
                if (done != null) {
                    done.run()
                }
            })
        })
    }

    private fun codexResetTime(status: CodexStatus, window: String): String? {
        val limit: JSONObject? = status.limits!!.optJSONObject(window)
        val reset: Long = if (limit == null) 0L else limit.optLong("resetsAt")
        return if (reset > 0) WorkspaceText.tr("Обновится ") + DateFormat.format("dd.MM HH:mm", reset * 1000) else WorkspaceText.tr("Время обновления недоступно")
    }

    private fun previewChatScale() {
        if (!closed && agentChatUi != null) {
            agentChatUi!!.applyPresentationScale()
        }
    }

    private fun showCodexStatus(profile: WorkspaceStore.Session) {
        val status: CodexStatus? = codexStatuses.get(profile.id)
        val shown: Array<AlertDialog?> = arrayOf<AlertDialog?>(null)
        val form: LinearLayout = AgentStatusUi.body(activity)
        if (status == null || !status.error!!.isEmpty()) {
            form.addView(text(if (status == null) WorkspaceText.tr("Получение статуса Codex…") else WorkspaceText.statusError(status.error), 14, Pal.MUTED))
        } else {
            AgentStatusUi.row(form, WorkspaceText.tr("Модель: ") + (if (status.model!!.isEmpty()) WorkspaceText.tr("нет данных") else status.model) + " ›", "model", Runnable {
                if (shown[0] != null) {
                    shown[0]!!.dismiss()
                }
                chooseCodexModel(profile, status)
            })
            AgentStatusUi.row(form, WorkspaceText.tr("Рассуждение: ") + (if (status.effort!!.isEmpty()) WorkspaceText.tr("нет данных") else effortName(status.effort)) + " ›", "reasoning", Runnable {
                if (shown[0] != null) {
                    shown[0]!!.dismiss()
                }
                chooseCodexEffort(profile, status)
            })
            if (status.modelPending) {
                form.addView(text(tr("Выбранные модель и рассуждение будут переданы Codex со следующим сообщением.", "The selected model and reasoning will be sent to Codex with your next message."), 12, Pal.MUTED))
            }
            form.addView(text("", 12, Pal.MUTED))
            form.addView(text(status.planLabel(), 14, Pal.TEXT))
            if (status.windows().isEmpty()) {
                form.addView(text(tr("Сервер не передал окна лимитов.", "The server did not provide rate-limit windows."), 14, Pal.MUTED))
            }
            for (window in status.windows()) {
                val title: String? = CodexStatus.windowLabel(status.limits, window, false)
                form.addView(text(CodexStatus.limit(status.limits, window, title) + (if (CodexStatus.remaining(status.limits!!.optJSONObject(window)) == null) "" else tr(" осталось", " remaining")), 14, Pal.TEXT))
                val limit: JSONObject = status.limits!!.optJSONObject(window)
                val remaining: Int? = CodexStatus.remaining(limit)
                if (remaining != null) {
                    val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
                    bar.setMax(100)
                    bar.setProgress(remaining)
                    bar.setProgressTintList(ColorStateList.valueOf(Pal.ACCENT))
                    form.addView(bar, LinearLayout.LayoutParams(-1, dp(18.0f)))
                }
                val time: TextView = text(codexResetTime(status, window), 12, Pal.MUTED)
                time.setPadding(0, dp(4.0f), 0, dp(14.0f))
                form.addView(time)
            }
            form.addView(text(WorkspaceText.tr("Общий лимит аккаунта. Доступ: ") + (if (profile.codexAccess.equals("full")) WorkspaceText.tr("полный") else if (profile.codexAccess.equals("read")) WorkspaceText.tr("только чтение") else WorkspaceText.tr("в пределах проекта")) + (if (CodexChatControl.enabled(activity.getFilesDir(), profile)) tr("\nСтатус обновляется раз в 30 секунд. Выбранные модель и рассуждение передаются со следующим сообщением этой сессии. До отправки они помечены как следующий ответ. Поле Shell выполняет вашу команду напрямую с правами Ubuntu, без обращения к модели.", "\nStatus refreshes every 30 seconds. Selected model and reasoning are sent with this session’s next message and marked as next response until then. The Shell field runs your command directly with Ubuntu privileges without model inference.") else WorkspaceText.tr("\nСтатус обновляется раз в 30 секунд. Выбор модели и рассуждения применяется через штатное меню Codex только к этой сессии. Если Codex отвечает, приложение дождётся завершения. Поле ввода сохраняется.")), 12, Pal.MUTED))
        }
        AgentChatScale.controls(form, profile, BooleanSupplier { !closed && currentCodex() === profile }, Runnable(this::previewChatScale), Runnable(store::save))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Статус Codex")).setView(formScroll(form)).setPositiveButton(WorkspaceText.tr("Обновить"), null).setNegativeButton(WorkspaceText.tr("Закрыть"), null).create()
        shown[0] = dialog
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if (codexLoading) {
                    return@OnClickListener
                }
                loadingCodexDialog(profile, dialog)
            })
        })
        dialog.show()
        if (status == null && !codexLoading) {
            loadingCodexDialog(profile, dialog)
        }
    }

    private fun loadingCodexDialog(profile: WorkspaceStore.Session, dialog: AlertDialog) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false)
        loadCodexStatus(profile, Runnable {
            if (dialog.isShowing()) {
                dialog.dismiss()
                showCodexStatus(profile)
            }
        })
    }

    private fun chooseCodexModel(profile: WorkspaceStore.Session, status: CodexStatus) {
        if (status.models.length() == 0) {
            activity.showToast(WorkspaceText.tr("Каталог моделей недоступен. После этого обновления запустите новый терминал Codex с прежним ID чата."), true)
            return
        }
        val labels = arrayOfNulls<String>(status.models.length())
        val selected: IntArray = intArrayOf(0)
        for (i in 0 until (labels.size)) {
            val model: JSONObject? = status.models.optJSONObject(i)
            labels[i] = if (model == null) "—" else model.optString("displayName", model.optString("model"))
            if (model != null && model.optString("model").equals(status.model)) {
                selected[0] = i
            }
        }
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Модель Codex")).setSingleChoiceItems(labels, selected[0], DialogInterface.OnClickListener { d, w ->
            d.dismiss()
            chooseCodexEffortFor(profile, status, status.models.optJSONObject(w))
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun chooseCodexEffort(profile: WorkspaceStore.Session, status: CodexStatus) {
        var i = 0
        while (i < status.models.length()) {
            val model: JSONObject? = status.models.optJSONObject(i)
            if (model != null && model.optString("model").equals(status.model)) {
                chooseCodexEffortFor(profile, status, model)
                return
            }
            i++
        }
        chooseCodexModel(profile, status)
    }

    private fun effortName(effort: String?): String? {
        when (effort!!) {
            "none" -> {
                return WorkspaceText.tr("Без рассуждения")
            }
            "minimal" -> {
                return WorkspaceText.tr("Минимальный")
            }
            "low" -> {
                return WorkspaceText.tr("Низкий")
            }
            "medium" -> {
                return WorkspaceText.tr("Средний")
            }
            "high" -> {
                return WorkspaceText.tr("Высокий")
            }
            "xhigh" -> {
                return WorkspaceText.tr("Очень высокий")
            }
            "max" -> {
                return WorkspaceText.tr("Максимальный")
            }
            "ultra" -> {
                return WorkspaceText.tr("Ультра")
            }
            else -> {
                return effort
            }
        }
    }

    private fun chooseCodexEffortFor(profile: WorkspaceStore.Session, status: CodexStatus, model: JSONObject?) {
        if (model == null) {
            return
        }
        val options: JSONArray? = model.optJSONArray("supportedReasoningEfforts")
        val values: MutableList<String> = ArrayList<String>()
        val labels: MutableList<String?> = ArrayList<String?>()
        if (options != null) {
            var i = 0
            while (i < options.length()) {
                val option: JSONObject? = options.optJSONObject(i)
                val effort: String = if (option == null) "" else option.optString("reasoningEffort")
                if (!CodexModelSelection.effortLabel(effort).isEmpty()) {
                    values.add(effort)
                    labels.add(effortName(effort))
                }
                i++
            }
        }
        if (values.isEmpty()) {
            val effort: String = model.optString("defaultReasoningEffort")
            if (!CodexModelSelection.effortLabel(effort).isEmpty()) {
                values.add(effort)
                labels.add(effortName(effort))
            }
        }
        if (values.isEmpty()) {
            activity.showToast(WorkspaceText.tr("Codex не сообщил доступные уровни для этой модели"), true)
            return
        }
        val current: String? = if (model.optString("model").equals(status.model)) status.effort else model.optString("defaultReasoningEffort")
        val selected: IntArray = intArrayOf(Math.max(0, values.indexOf(current)))
        WorkspaceDialogs.builder(activity).setTitle(model.optString("displayName", model.optString("model")) + WorkspaceText.tr(" · поведение")).setSingleChoiceItems(labels.toTypedArray(), selected[0], DialogInterface.OnClickListener { d, w ->
            d.dismiss()
            confirmCodexModel(profile, model, values.get(w), values.size == 1)
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun confirmCodexModel(profile: WorkspaceStore.Session, model: JSONObject, effort: String, singleEffort: Boolean) {
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Применить параметры Codex?")).setMessage(WorkspaceText.tr("Модель: ") + model.optString("displayName", model.optString("model")) + WorkspaceText.tr("\nПоведение: ") + effortName(effort) + WorkspaceText.tr("\n\nТолько для текущей сессии.")).setPositiveButton(WorkspaceText.tr("Применить"), DialogInterface.OnClickListener { d, w -> applyCodexModel(profile, model, effort, singleEffort) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun codexScreen(terminal: TerminalSession?): String {
        val emulator: TerminalEmulator? = terminal!!.getEmulator()
        return if (emulator == null) "" else emulator.getScreen().getSelectedText(0, 0, emulator.mColumns, emulator.mRows - 1)
    }

    private fun applyCodexModel(profile: WorkspaceStore.Session, model: JSONObject, effort: String, singleEffort: Boolean) {
        if (CodexChatControl.enabled(activity.getFilesDir(), profile)) {
            val wait: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Параметры Codex")).setMessage(tr("Применяем модель и рассуждение…", "Applying model and reasoning…")).create()
            wait.show()
            val run: String = profile.codexSocketId
            Thread(Runnable {
                var ok = false
                try {
                    ok = CodexChatControl.request(activity.getFilesDir(), profile, JSONObject().put("op", "model").put("model", model.optString("model")).put("effort", effort)).optBoolean("ok")
                } catch (ignored: Exception) {
                }
                val applied: Boolean = ok
                activity.runOnUiThread(Runnable {
                    wait.dismiss()
                    if (closed || !run.equals(profile.codexSocketId)) {
                        return@Runnable
                    }
                    activity.showToast(if (applied) tr("Выбрано для следующего сообщения", "Selected for the next message") else tr("Codex не подтвердил смену. Дождитесь завершения ответа или закройте его штатный терминал и повторите.", "Codex did not confirm the change. Wait for the response or close its native terminal and retry."), !applied)
                    if (applied) {
                        codexStatuses.remove(profile.id)
                        loadCodexStatus(profile, null)
                    }
                })
            }, "codex-chat-model").start()
            return
        }
        if (codexApplyDialog != null && codexApplyDialog!!.isShowing()) {
            return
        }
        val terminal: TerminalSession? = liveFor(profile)
        if (terminal == null || !terminal.isRunning()) {
            activity.showToast(WorkspaceText.tr("Сессия уже завершена"), false)
            return
        }
        val id: String = model.optString("model")
        val wanted: String = "Model changed to " + id + " " + effort
        val cached: CodexStatus? = codexStatuses.get(profile.id)
        if (cached != null && cached.model!!.equals(id) && (cached.effort!!.equals(effort) || CodexModelSelection.acknowledgement(codexScreen(terminal))!!.equals(wanted))) {
            activity.showToast(WorkspaceText.tr("Эти параметры уже выбраны в Codex"), false)
            return
        }
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(terminalInput.getWindowToken(), 0)
        val generation: Int = ++codexApplyGeneration
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Параметры Codex")).setMessage(WorkspaceText.tr("Проверяем состояние сессии…")).setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> codexApplyGeneration++ }).create()
        codexApplyDialog = dialog
        dialog.setOnCancelListener(DialogInterface.OnCancelListener { d -> codexApplyGeneration++ })
        dialog.show()
        val until: Long = System.currentTimeMillis() + 300000
        val waiting: Runnable = object : Runnable {
            override fun run() {
                if (closed || generation != codexApplyGeneration || !dialog.isShowing()) {
                    return
                }
                if (!terminal.isRunning() || System.currentTimeMillis() > until) {
                    dialog.dismiss()
                    activity.showToast(WorkspaceText.tr("Не удалось дождаться свободного Codex"), true)
                    return
                }
                if (codexLoading) {
                    codexHandler.postDelayed(this, 500L)
                    return
                }
                loadCodexStatus(profile, Runnable {
                    if (closed || generation != codexApplyGeneration || !dialog.isShowing()) {
                        return@Runnable
                    }
                    val status: CodexStatus? = codexStatuses.get(profile.id)
                    if (status == null || !status.error!!.isEmpty()) {
                        dialog.dismiss()
                        activity.showToast(if (status == null) WorkspaceText.tr("Статус Codex недоступен") else status.error, true)
                        return@Runnable
                    }
                    if (!status.state.equals("idle")) {
                        dialog.setMessage(WorkspaceText.tr("Ожидаем завершения ответа Codex…"))
                        codexHandler.postDelayed(this, 3000L)
                        return@Runnable
                    }
                    dialog.setMessage(WorkspaceText.tr("Применяем через штатное меню Codex…"))
                    runCodexModelSelection(terminal, profile, model, effort, singleEffort, generation, dialog)
                })
            }
        }
        waiting.run()
    }

    private fun runCodexModelSelection(terminal: TerminalSession?, profile: WorkspaceStore.Session, model: JSONObject, effort: String, singleEffort: Boolean, generation: Int, dialog: AlertDialog) {
        val selection = CodexModelSelection(model.optString("model"), model.optString("displayName", model.optString("model")), effort)
        selection.singleEffort(singleEffort)
        val adapter: CodexModelSelection.Terminal = object : CodexModelSelection.Terminal {
            override fun screen(): String {
                return codexScreen(terminal)
            }

            override fun menuOpen(): Boolean {
                return terminal!!.getEmulator() != null && !terminal.getEmulator()!!.isCursorEnabled()
            }

            override fun write(keys: String) {
                if (keys.equals("/model\r") && terminal!!.getEmulator() != null) {
                    terminal.getEmulator()!!.paste("/model")
                    terminal.write("\r")
                } else {
                    terminal!!.write(keys)
                }
            }

            override fun emptyPrompt(): Boolean {
                val emulator: TerminalEmulator? = terminal!!.getEmulator()
                if (emulator == null || !emulator.isCursorEnabled() || emulator.getCursorCol() > 2) {
                    return false
                }
                val line: String = (emulator.getScreen().getSelectedText(0, emulator.getCursorRow(), emulator.mColumns, emulator.getCursorRow()) as java.lang.String).trim()
                if (!line.startsWith("›")) {
                    return false
                }
                val content: String = (line.substring(1) as java.lang.String).trim()
                return content.isEmpty() || content.startsWith("Ask Codex")
            }
        }
        val tick: Runnable = object : Runnable {
            override fun run() {
                if (closed || generation != codexApplyGeneration || !dialog.isShowing()) {
                    return
                }
                if (!terminal!!.isRunning()) {
                    dialog.dismiss()
                    activity.showToast(WorkspaceText.tr("Сессия завершена"), false)
                    return
                }
                selection.step(adapter, System.currentTimeMillis())
                if (!selection.finished) {
                    codexHandler.postDelayed(this, 200L)
                    return
                }
                dialog.dismiss()
                if (selection.confirmed) {
                    val status: CodexStatus? = codexStatuses.get(profile.id)
                    if (status != null) {
                        status.model = model.optString("model")
                        status.effort = effort
                        codexConfirmedChoices.put(profile.codexSocketId, arrayOf<String?>(status.thread, status.model, effort, "Model changed to " + status.model + " " + effort))
                        renderCodexStatus()
                    }
                    activity.showToast(WorkspaceText.tr("Codex подтвердил: ") + model.optString("displayName", model.optString("model")) + " · " + effortName(effort), true)
                } else {
                    activity.showToast(selection.error, true)
                }
            }
        }
        tick.run()
    }

    private fun finishWorkspaceSession(terminal: TerminalSession) {
        val profile: WorkspaceStore.Session? = store.findByHandle(terminal.mHandle)
        if (profile != null && profile.geminiAgent) {
            val runId: String = profile.geminiSocketId
            val environment: String = profile.codexEnvironment
            codexReader.execute(Runnable {
                try {
                    GeminiCli.stop(activity, runId, environment)
                } catch (ignored: Exception) {
                }
                activity.runOnUiThread(Runnable {
                    if (terminal.isRunning()) {
                        terminal.finishIfRunning()
                    }
                })
            })
            return
        }
        if (profile != null && profile.claudeAgent) {
            val runId: String = profile.claudeSocketId
            val environment: String = profile.codexEnvironment
            codexReader.execute(Runnable {
                try {
                    ClaudeCode.stop(activity, runId, environment)
                } catch (ignored: Exception) {
                }
                activity.runOnUiThread(Runnable {
                    if (terminal.isRunning()) {
                        terminal.finishIfRunning()
                    }
                })
            })
            return
        }
        if (profile != null && profile.aiAgent.equals("opencode")) {
            val target: WorkspaceStore.Session = AiAgents.snapshot(profile)
            codexReader.execute(Runnable {
                try {
                    AiAgents.call(activity, target.aiConnection, target, AiAgentsUi.op("stop"))
                } catch (ignored: Exception) {
                }
                activity.runOnUiThread(Runnable {
                    if (terminal.isRunning()) {
                        terminal.finishIfRunning()
                    }
                })
            })
            return
        }
        if (profile == null || !profile.environment.equals("codex")) {
            terminal.finishIfRunning()
            return
        }
        val stop: Runnable = CodexRpc.ownedStop(activity.getFilesDir(), profile)
        codexReader.execute(Runnable {
            stop.run()
            activity.runOnUiThread(Runnable {
                if (terminal.isRunning()) {
                    terminal.finishIfRunning()
                }
            })
        })
    }

    private fun chooseSshKey(identity: EditText) {
        val files: Array<File>? = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh").listFiles()
        val keys: MutableList<File> = ArrayList<File>()
        if (files != null) {
            for (file in files) {
                val n: String = file.getName()
                if (file.isFile() && file.canRead() && !n.endsWith(".pub") && !n.equals("config") && !n.startsWith("known_hosts") && !n.startsWith("authorized_keys")) {
                    keys.add(file)
                }
            }
        }
        Collections.sort(keys, Comparator<File> { a, b -> a.getName().compareTo(b.getName()) })
        val picker: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Файл SSH-ключа"))
        if (keys.isEmpty()) {
            picker.setMessage(WorkspaceText.tr("Ключей в ~/.ssh нет. Укажите путь к своему файлу в поле."))
        } else {
            val names = arrayOfNulls<String>(keys.size)
            for (i in 0 until (names.size)) {
                names[i] = keys.get(i).getName()
            }
            picker.setItems(names, DialogInterface.OnClickListener { d, which -> identity.setText(keys.get(which).getAbsolutePath()) })
        }
        picker.setNegativeButton(WorkspaceText.tr("Закрыть"), null).show()
    }

    private fun requestSshPassword(session: WorkspaceStore.Session?, add: Boolean) {
        val form: LinearLayout = column()
        val password: EditText = labeled(form, WorkspaceText.tr("Пароль SSH"), "", false)
        password.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.setSaveEnabled(false)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(session!!.sshUser + "@" + session.sshHost).setView(formScroll(form)).setPositiveButton(WorkspaceText.tr("Подключиться"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if (password.length() == 0) {
                    password.setError(WorkspaceText.tr("Введите пароль"))
                    return@OnClickListener
                }
                val secret = CharArray(password.length())
                TextUtils.getChars(password.getText(), 0, secret.size, secret, 0)
                val old: CharArray? = pendingSshPasswords.put(session.id, secret)
                if (old != null) {
                    Arrays.fill(old, '\u0000')
                }
                password.setText("")
                dialog.dismiss()
                launchNew(session, add)
            })
        })
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> password.setText("") })
        dialog.show()
    }

    private fun showPackages(profile: WorkspaceStore.Session?) {
        val environment: String = ProjectFiles.environment(profile)
        if (!environment.equals("termux") && !environment.equals("ubuntu") && !environment.startsWith("proot:")) {
            activity.showToast(WorkspaceText.tr("Пакеты пока доступны только для Termux и Ubuntu."), true)
            return
        }
        val heldHandle: String = profile!!.handle
        val browser = WorkspacePackages(activity, profile, WorkspacePackages.Launch { operation, selectedSession ->
            if (closed) {
                return@Launch
            }
            if (!selectedSession) {
                operation.packageRuntimeStartup = packageJournalCommand(profile, operation, operation.id)
                launchNew(operation, true)
                return@Launch
            }
            val target: TerminalSession? = liveFor(profile)
            if (target == null || !target.isRunning() || !PackageCatalog.matchesTarget(profile, heldHandle, target.mHandle, ProjectFiles.environment(operation))) {
                activity.showToast(WorkspaceText.tr("Выбранная сессия закрыта или изменилась. Откройте меню пакетов заново либо выберите новую сессию."), true)
                return@Launch
            }
            activity.getTermuxTerminalSessionClient()!!.setCurrentSession(target)
            show(Screen.TERMINAL, true)
            val command: String = packageJournalCommand(profile, operation, profile.id)
            if (target.getEmulator() != null) {
                target.getEmulator()!!.paste(command)
                target.write("\r")
            } else {
                target.write(command + "\r")
            }
        })
        browser.setOnClosed(Runnable { packageBrowsers.remove(browser) })
        packageBrowsers.add(browser)
        browser.show()
    }

    private fun packageJournalCommand(source: WorkspaceStore.Session?, operation: WorkspaceStore.Session, sessionId: String): String {
        if (operation.packageOperation == null) {
            return operation.startup
        }
        try {
            return PackageOperationJournal.prepare(activity.getFilesDir(), WorkspacePackageHistory.key(source), sessionId, !ProjectFiles.environment(source).equals("termux"), operation.packageOperation, operation.startup)
        } catch (error: Exception) {
            activity.showToast(tr("Журнал результата недоступен; команда выполнится без записи результата.", "Result journal unavailable; the command will run without a result record."), true)
            return operation.startup
        }
    }

    private fun sessionMenu(s: WorkspaceStore.Session?) {
        sessionMenu(s, false)
    }

    private fun sessionMenu(s: WorkspaceStore.Session?, terminalTab: Boolean) {
        if (WorkspaceStore.isBase(s)) {
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Terminal · базовая сессия")).setPositiveButton(WorkspaceText.tr("Перезапустить"), DialogInterface.OnClickListener { d, w ->
                confirm(WorkspaceText.tr("Перезапустить базовую оболочку?"), Runnable {
                    val current: TerminalSession? = liveFor(s)
                    if (current != null && current.isRunning()) {
                        current.finishIfRunning()
                    } else {
                        launch(s)
                    }
                })
            }).setItems(arrayOf<String?>(WorkspaceText.tr("Диагностика сессии")), DialogInterface.OnClickListener { d, w -> showDiagnostics(s) }).setNegativeButton(WorkspaceText.tr("Закрыть меню"), null).show()
            return
        }
        val actions: MutableList<String?> = ArrayList<String?>()
        if (terminalTab && !s!!.saved) {
            actions.add(WorkspaceText.tr("Добавить в пресеты запуска"))
        }
        if (terminalTab) {
            Collections.addAll(actions, WorkspaceText.tr("Перезапустить"), WorkspaceText.tr("Настройки"), WorkspaceText.tr("Закрыть сессию"))
        } else {
            Collections.addAll(actions, WorkspaceText.tr("Закрыть сессию"), WorkspaceText.tr("Настройки"), WorkspaceText.tr("Удалить сессию"))
        }
        if (terminalTab) {
            actions.add(WorkspaceText.tr("Диагностика сессии"))
        }
        if (terminalTab && (s!!.environment.equals("codex") || s.aiAgent.equals("opencode") || s.geminiAgent || s.claudeAgent)) {
            actions.add(tr("Установка пакетов", "Install packages"))
        }
        WorkspaceDialogs.builder(activity).setTitle(s!!.name).setItems(actions.toTypedArray(), DialogInterface.OnClickListener { d, which ->
            val action: String? = actions.get(which)
            if (action!!.equals(tr("Установка пакетов", "Install packages"))) {
                showPackages(s)
                return@OnClickListener
            }
            if (action.equals(WorkspaceText.tr("Диагностика сессии"))) {
                showDiagnostics(s)
                return@OnClickListener
            }
            if (action.equals(WorkspaceText.tr("Добавить в пресеты запуска"))) {
                if (!store.isWritable()) {
                    activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
                    return@OnClickListener
                }
                saveSessionProfile(s)
                return@OnClickListener
            }
            if (action.equals(WorkspaceText.tr("Настройки"))) {
                editSession(s)
            } else if (action.equals(WorkspaceText.tr("Перезапустить"))) {
                confirm(WorkspaceText.tr("Остановить текущий процесс и повторно выполнить стартовую команду?"), Runnable {
                    val live: TerminalSession? = liveFor(s)
                    if (live != null && live.isRunning()) {
                        if (live.getPid() <= 0) {
                            activity.showToast(WorkspaceText.tr("Откройте терминал и дождитесь запуска"), false)
                            return@Runnable
                        }
                        pendingRestarts.put(live.mHandle, s)
                        store.save()
                        finishWorkspaceSession(live)
                    } else {
                        store.save()
                        launch(s)
                    }
                })
            } else if (action.equals(WorkspaceText.tr("Закрыть сессию"))) {
                closeSession(s)
            } else {
                confirm(WorkspaceText.tr("Удалить настройки и историю сессии «") + s.name + WorkspaceText.tr("»? Активный терминал сначала нужно остановить."), Runnable {
                    val live: TerminalSession? = liveFor(s)
                    if (live != null && live.isRunning()) {
                        activity.showToast(WorkspaceText.tr("Сначала остановите сессию"), true)
                        return@Runnable
                    }
                    if (live != null) {
                        activity.getTermuxTerminalSessionClient()!!.removeFinishedSession(live)
                    }
                    store.sessions.remove(s)
                    store.save()
                    eraseSessionFiles(s)
                    renderContent()
                })
            }
        }).show()
    }

    private fun chooseSavedSession() {
        val saved: MutableList<WorkspaceStore.Session?> = ArrayList<WorkspaceStore.Session?>()
        for (session in sortedSessions()) {
            if (session!!.saved) {
                saved.add(session)
            }
        }
        val picker: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Пресеты запуска"))
        if (saved.isEmpty()) {
            picker.setMessage(WorkspaceText.tr("Пресетов запуска нет. Удерживайте вкладку терминала и выберите «Добавить в пресеты запуска»."))
        } else {
            val names = arrayOfNulls<String>(saved.size)
            for (i in 0 until (names.size)) {
                names[i] = saved.get(i)!!.name
            }
            picker.setItems(names, DialogInterface.OnClickListener { d, which -> launch(saved.get(which)) })
        }
        picker.setNegativeButton(WorkspaceText.tr("Закрыть"), null).show()
    }

    private fun saveSessionProfile(profile: WorkspaceStore.Session?) {
        if (profile!!.environment.equals("codex") && profile.codexChat.equals("new") && liveFor(profile) != null && liveFor(profile)!!.isRunning()) {
            if (codexLoading) {
                activity.showToast(WorkspaceText.tr("Дождитесь обновления статуса и повторите сохранение"), false)
                return
            }
            loadCodexStatus(profile, Runnable {
                if (!store.sessions.contains(profile)) {
                    return@Runnable
                }
                if (profile.codexChat.equals("id")) {
                    saveSessionProfile(profile)
                } else {
                    activity.showToast(WorkspaceText.tr("ID чата ещё не получен. Дождитесь запуска Codex и повторите сохранение; нужен помощник новой версии."), true)
                }
            })
            return
        }
        commitSavedProfile(profile)
    }

    private fun commitSavedProfile(profile: WorkspaceStore.Session?) {
        profile!!.saved = true
        profile.updated = System.currentTimeMillis()
        store.save()
        activity.showToast(WorkspaceText.tr("Пресет запуска добавлен"), false)
        renderContent()
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun closeSession(profile: WorkspaceStore.Session?) {
        if (!store.isWritable()) {
            activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
            return
        }

        if (WorkspaceStore.isBase(profile)) {
            return
        }
        val target: TerminalSession? = liveFor(profile)
        if (target == null) {
            activity.showToast(WorkspaceText.tr("Сессия уже закрыта"), false)
            return
        }
        if (!store.isWritable()) {
            activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
            return
        }
        val dialog: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Закрыть сессию «") + profile!!.name + "»?")
        val desktopWarning: String = if (desktopUi != null && desktopUi!!.owns(profile)) tr("\nГрафический рабочий стол и его программы тоже будут закрыты. Сохраните изменения в них заранее.", "\nThe graphical desktop and its programs will also close. Save their work first.") else ""
        if (profile.saved) {
            dialog.setMessage(WorkspaceText.tr("Процесс будет остановлен, вкладка терминала закрыта. Сохранённые настройки и история останутся.") + desktopWarning).setPositiveButton(WorkspaceText.tr("Закрыть сессию"), DialogInterface.OnClickListener { d, which -> finishClose(profile, target, true) })
        } else {
            val body: LinearLayout = column()
            val keep = CheckBox(activity)
            keep.setText(tr("Сохранить историю", "Keep history"))
            keep.setTextColor(Pal.TEXT)
            keep.setChecked(true)
            keep.setTag("close-keep-history")
            body.addView(keep)
            body.addView(text(tr("Галочка сохраняет историю оболочки на стороне приложения. Собственные чаты Codex и других CLI-агентов не удаляются; для удаления используйте «Истории чатов». Пресет сохраняет настройки запуска и историю.", "The checkbox keeps app-side shell history. Native Codex/CLI conversations are not deleted; use Chat histories for deletion. A preset keeps launch settings and history."), 13, Pal.MUTED))
            if (!desktopWarning.isEmpty()) {
                body.addView(text(desktopWarning, 13, Pal.MUTED))
            }
            dialog.setView(body).setPositiveButton(tr("Добавить в пресеты и закрыть", "Add preset and close"), DialogInterface.OnClickListener { d, which ->
                profile.keepHistory = true
                finishClose(profile, target, true)
            }).setNegativeButton(tr("Закрыть", "Close"), DialogInterface.OnClickListener { d, which ->
                profile.keepHistory = keep.isChecked()
                finishClose(profile, target, false)
            })
        }
        dialog.show()
    }

    private fun finishClose(profile: WorkspaceStore.Session?, target: TerminalSession, save: Boolean) {
        finishClose(profile, target, save, false)
    }

    private fun finishClose(profile: WorkspaceStore.Session?, target: TerminalSession, save: Boolean, aiChecked: Boolean) {
        if (WorkspaceStore.isBase(profile)) {
            return
        }
        if (liveFor(profile) !== target) {
            return
        }
        if (save && !profile!!.saved && profile.environment.equals("codex") && profile.codexChat.equals("new") && target.isRunning()) {
            if (codexLoading) {
                activity.showToast(WorkspaceText.tr("Дождитесь обновления статуса и повторите закрытие"), false)
                return
            }
            loadCodexStatus(profile, Runnable {
                if (profile.codexChat.equals("id")) {
                    finishClose(profile, target, true)
                } else {
                    activity.showToast(WorkspaceText.tr("ID чата ещё не получен. Дождитесь запуска либо выберите закрытие без сохранения."), true)
                }
            })
            return
        }
        if (target.isRunning() && target.getPid() <= 0) {
            activity.showToast(WorkspaceText.tr("Дождитесь запуска сессии"), false)
            return
        }
        if (save) {
            profile!!.saved = true
            profile.updated = System.currentTimeMillis()
        }
        if (desktopUi != null) {
            desktopUi!!.sessionClosed(profile)
        }
        store.save()
        pendingRestarts.remove(target.mHandle)
        if (target.isRunning()) {
            pendingCloses.add(target.mHandle)
            finishWorkspaceSession(target)
        } else {
            retireProfile(profile)
            activity.getTermuxTerminalSessionClient()!!.removeFinishedSession(target)
            refresh()
        }
    }

    /** Only after the process exits: it must not recreate the history while we remove it.*/
    private fun retireProfile(profile: WorkspaceStore.Session?) {
        if (WorkspaceStore.isBase(profile)) {
            return
        }
        profile!!.handle = ""
        if (!profile.saved) {
            store.sessions.remove(profile)
            if (!profile.keepHistory) {
                eraseSessionFiles(profile)
            }
        }
        store.save()
    }

    private fun eraseSessionFiles(profile: WorkspaceStore.Session?) {
        val secret: CharArray? = pendingSshPasswords.remove(profile!!.id)
        if (secret != null) {
            Arrays.fill(secret, '\u0000')
        }
        val dir: File = SessionShell.directory(activity.getFilesDir(), profile.id)
        reader.execute(Runnable {
            try {
                PackageOperationJournal.collect(activity.getFilesDir(), profile.id)
            } catch (ignored: Exception) {
            }
            val files: Array<File>? = dir.listFiles()
            var removed = true
            if (files != null) {
                for (f in files) {
                    if (!f.delete()) {
                        removed = false
                    }
                }
            }
            if (dir.exists() && !dir.delete()) {
                removed = false
            }
            if (!removed) {
                activity.runOnUiThread(Runnable { activity.showToast(WorkspaceText.tr("Не удалось полностью удалить историю сессии"), true) })
            }
        })
    }

    private fun confirm(message: String?, action: Runnable) {
        WorkspaceDialogs.builder(activity).setMessage(message).setPositiveButton(WorkspaceText.tr("Продолжить"), DialogInterface.OnClickListener { d, w -> action.run() }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun sendInput() {
        val current: TerminalSession? = activity.getCurrentSession()
        if (current == null || !current.isRunning() || current.getPid() <= 0) {
            activity.showToast(WorkspaceText.tr("Нет работающего терминала"), false)
            return
        }
        val command: String = terminalInput.getText().toString()
        if ((command as java.lang.String).trim().isEmpty() && !(agentChatUi != null && agentChatUi!!.handlesComposer() && agentChatUi!!.hasAttachments())) {
            return
        }
        val profile: WorkspaceStore.Session? = store.findByHandle(current.mHandle)
        if (profile != null && (profile.aiAgent.equals("opencode") || profile.geminiAgent || profile.claudeAgent || CodexChatControl.enabled(activity.getFilesDir(), profile)) && agentChatUi!!.handlesComposer()) {
            agentChatUi!!.send(command, Runnable {
                store.recordCommand(command)
                if (activity.getCurrentSession() === current && terminalInput.getText().toString().equals(command)) {
                    terminalInput.setText("")
                    terminalDrafts.remove(current.mHandle)
                    if (agentChatUi!!.showing() && screen === Screen.TERMINAL && !activityStopped) {
                        leaveAgentInputIdle()
                    }
                }
            })
            return
        }
        if (profile != null && profile.geminiAgent && current.getEmulator() != null) {
            val run: String = profile.geminiSocketId
            val mode: Boolean = interactiveInput
            geminiInputDispatch.send(command, object : GeminiInputDispatch.Target {
                override fun current(): Boolean {
                    return !closed && !activityStopped && activity.getCurrentSession() === current && current.isRunning() && run.equals(profile.geminiSocketId) && interactiveInput == mode && terminalInput.getText().toString().equals(command)
                }

                override fun paste(value: String) {
                    current.getEmulator()!!.paste(value)
                }

                override fun enter() {
                    current.write("\r")
                    store.recordCommand(command)
                    terminalInput.setText("")
                    terminalDrafts.remove(current.mHandle)
                    if (interactiveInput) {
                        activity.getTerminalView()!!.requestFocus()
                    } else {
                        terminalInput.requestFocus()
                    }
                }

                override fun cancelled(value: String) {
                    activity.showToast(tr("Отправка отменена: текст остался во вводе Gemini. Его можно отправить в интерактивном режиме.", "Submission cancelled: the text remains in Gemini's composer. Submit it in interactive mode."), true)
                }
            }, GeminiInputDispatch.Scheduler { task, delay -> codexHandler.postDelayed(task, delay) })
            return
        }
        if (current.getEmulator() != null) {
            current.getEmulator()!!.paste(command)
            current.write("\r")
        } else {
            current.write(command + "\r")
        }
        store.recordCommand(command)
        terminalInput.setText("")
        terminalDrafts.remove(current.mHandle)
        if (interactiveInput) {
            activity.getTerminalView()!!.requestFocus()
        } else {
            terminalInput.requestFocus()
        }
    }

    private fun openInputEntry(item: Any, back: Runnable) {
        if (item is WorkspaceStore.Alias) {
            openAlias(item as WorkspaceStore.Alias, back)
            return
        }
        val isNote: Boolean = item is WorkspaceStore.Note
        val note: WorkspaceStore.Note? = if (isNote) item as WorkspaceStore.Note else null
        val command: WorkspaceStore.Command? = if (isNote) null else item as WorkspaceStore.Command
        val name: String = if (isNote) note!!.name else command!!.name
        val body: LinearLayout = previewBody(if (isNote) note!!.text else command!!.command, !isNote)
        previewMetadata(body, if (isNote) note!!.category else command!!.category, if (isNote) note!!.favorite else command!!.favorite)
        val preview: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(name).setView(formScroll(body)).setPositiveButton(WorkspaceText.tr("Назад"), DialogInterface.OnClickListener { dialog, which -> back.run() }).create()
        preview.setOnCancelListener(DialogInterface.OnCancelListener { dialog -> back.run() })
        previewAction(body, WorkspaceText.tr("Вставить в сессию"), R.drawable.workspace_commands, Runnable {
            preview.dismiss()
            val value = WorkspaceStore.Command()
            value.command = if (isNote) note!!.text else command!!.command
            insertIntoCurrent(value)
        })
        previewAction(body, WorkspaceText.tr("Редактировать"), R.drawable.workspace_edit, Runnable {
            preview.dismiss()
            if (isNote) {
                editNote(note)
            } else {
                editCommand(command)
            }
        })
        previewAction(body, WorkspaceText.tr("Удалить"), R.drawable.workspace_delete, Runnable {
            if (!store.isWritable()) {
                activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
                return@Runnable
            }
            preview.dismiss()
            confirm((if (isNote) WorkspaceText.tr("Удалить заметку «") else WorkspaceText.tr("Удалить команду «")) + name + "»?", Runnable {
                if (isNote) {
                    store.notes.remove(note)
                } else {
                    store.commands.remove(command)
                }
                store.save()
                renderContent()
            })
        })
        preview.show()
    }

    private fun chooseInputCommand() {
        hideDialogKeyboard()
        val commands: MutableList<WorkspaceStore.Command> = ArrayList<WorkspaceStore.Command>(store.commands)
        Collections.sort(commands, Comparator<WorkspaceStore.Command> { a, b -> java.lang.Boolean.compare(b.favorite, a.favorite) })
        val notes: MutableList<WorkspaceStore.Note> = ArrayList<WorkspaceStore.Note>(store.notes)
        Collections.sort(notes, Comparator<WorkspaceStore.Note> { a, b -> java.lang.Boolean.compare(b.favorite, a.favorite) })
        val libraryTab: IntArray = intArrayOf(0)
        val queries: Array<String> = arrayOf<String>("", "", "")
        val aliases: MutableList<WorkspaceStore.Alias> = ArrayList<WorkspaceStore.Alias>(store.aliases)
        aliases.sortWith(Comparator<WorkspaceStore.Alias> { a, b -> java.lang.Boolean.compare(b.favorite, a.favorite) })
        val visible: MutableList<Any> = ArrayList<Any>(commands)
        val adapter: BaseAdapter = object : BaseAdapter() {
            override fun getCount(): Int {
                return visible.size
            }

            override fun getItem(position: Int): Any {
                return visible.get(position)
            }

            override fun getItemId(position: Int): Long {
                return position.toLong()
            }

            override fun getView(position: Int, recycled: View, parent: ViewGroup): View {
                val outer = FrameLayout(activity)
                outer.setPadding(dp(16.0f), dp(4.0f), dp(16.0f), dp(4.0f))
                val item: Any = getItem(position)
                var preview: WorkspaceStore.Command
                if (item is WorkspaceStore.Note) {
                    val note: WorkspaceStore.Note = item as WorkspaceStore.Note
                    preview = WorkspaceStore.Command()
                    preview.name = note.name
                    preview.command = note.text
                    preview.category = note.category
                    preview.favorite = note.favorite
                } else if (item is WorkspaceStore.Alias) {
                    val a: WorkspaceStore.Alias = item as WorkspaceStore.Alias
                    preview = WorkspaceStore.Command()
                    preview.name = a.name
                    preview.command = a.command
                    preview.category = WorkspaceAliases.label(a.environment) + " · " + a.category
                    preview.favorite = a.favorite
                } else {
                    preview = item as WorkspaceStore.Command
                }
                val row: LinearLayout = WorkspaceDialogs.commandRow(activity, preview)
                row.setDuplicateParentStateEnabled(true)
                outer.addView(row, FrameLayout.LayoutParams(-1, -2))
                return outer
            }
        }
        val heading: LinearLayout = column()
        heading.setPadding(dp(20.0f), dp(18.0f), dp(20.0f), dp(12.0f))
        heading.setFocusableInTouchMode(true)
        val tabs: LinearLayout = row()
        val commandTab: TextView = button(tr("Команды", "Commands"), true, Runnable {  })
        val aliasTab: TextView = button("Alias", false, Runnable {  })
        val notesTab: TextView = button(WorkspaceText.tr("Заметки"), false, Runnable {  })
        commandTab.setMaxLines(2)
        notesTab.setMaxLines(2)
        commandTab.setPadding(dp(6.0f), dp(8.0f), dp(6.0f), dp(8.0f))
        val tabParams = LinearLayout.LayoutParams(0, -2, 1.0f)
        tabParams.setMarginEnd(dp(8.0f))
        tabs.addView(commandTab, tabParams)
        val aliasParams = LinearLayout.LayoutParams(0, -2, 1.0f)
        aliasParams.setMarginEnd(dp(8.0f))
        tabs.addView(aliasTab, aliasParams)
        tabs.addView(notesTab, LinearLayout.LayoutParams(0, -2, 1.0f))
        heading.addView(tabs)
        val hint: TextView = text(WorkspaceText.tr("Нажми на карточку, чтобы прочитать полностью"), 12, Pal.MUTED)
        hint.setPadding(0, dp(8.0f), 0, dp(12.0f))
        heading.addView(hint)
        val search: EditText = field(WorkspaceText.tr("Найти команду"), "", false)
        search.setContentDescription(WorkspaceText.tr("Поиск в меню звёздочки"))
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI)
        search.setMinHeight(dp(48.0f))
        heading.addView(search, LinearLayout.LayoutParams(-1, -2))
        val empty: TextView = text(if (commands.isEmpty()) WorkspaceText.tr("Сохраните команду в библиотеке или нажмите «Сохранить ввод».") else WorkspaceText.tr("Ничего не найдено"), 12, Pal.MUTED)
        empty.setPadding(0, dp(12.0f), 0, 0)
        empty.setVisibility(if (commands.isEmpty()) View.VISIBLE else View.GONE)
        heading.addView(empty)
        val builder: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setCustomTitle(heading).setAdapter(adapter, DialogInterface.OnClickListener { dialog, index ->
            hideDialogKeyboard()
            val list: AlertDialog = dialog as AlertDialog
            val position: Int = list.getListView().getFirstVisiblePosition()
            val first: View? = list.getListView().getChildAt(0)
            val top: Int = if (first == null) 0 else first.getTop()
            openInputEntry(visible.get(index), Runnable {
                list.show()
                list.getListView().setSelectionFromTop(position, top)
            })
        }).setNegativeButton(WorkspaceText.tr("Закрыть"), null)
        val input: String = terminalInput.getText().toString()
        if (!(input as java.lang.String).trim().isEmpty() && store.isWritable()) {
            builder.setNeutralButton(WorkspaceText.tr("Сохранить ввод"), DialogInterface.OnClickListener { dialog, which -> editCommand(null, input) })
        }
        val refresh = Runnable {
            val key: String = (search.getText().toString() as java.lang.String).toLowerCase(Locale.ROOT)
            visible.clear()
            if (libraryTab[0] == 1) {
                for (a in aliases) {
                    if (((a.name + " " + a.command + " " + a.category + " " + WorkspaceAliases.label(a.environment)) as java.lang.String).toLowerCase(Locale.ROOT).contains(key)) {
                        visible.add(a)
                    }
                }
            } else if (libraryTab[0] == 2) {
                for (note in notes) {
                    if (((note.name + " " + note.text + " " + note.category) as java.lang.String).toLowerCase(Locale.ROOT).contains(key)) {
                        visible.add(note)
                    }
                }
            } else {
                for (command in commands) {
                    if (((command.name + " " + command.command + " " + command.category) as java.lang.String).toLowerCase(Locale.ROOT).contains(key)) {
                        visible.add(command)
                    }
                }
            }
            val libraryEmpty: Boolean = if (libraryTab[0] == 1) aliases.isEmpty() else if (libraryTab[0] == 2) notes.isEmpty() else commands.isEmpty()
            empty.setText(if (libraryTab[0] == 1) if (libraryEmpty) tr("Сохраните alias в библиотеке.", "Save an alias in the library.") else WorkspaceText.tr("Ничего не найдено") else if (libraryEmpty) if (libraryTab[0] == 2) WorkspaceText.tr("Сохраните заметку в библиотеке.") else WorkspaceText.tr("Сохраните команду в библиотеке или нажмите «Сохранить ввод».") else WorkspaceText.tr("Ничего не найдено"))
            empty.setVisibility(if (visible.isEmpty()) View.VISIBLE else View.GONE)
            adapter.notifyDataSetChanged()
            hint.setText(WorkspaceText.tr("Нажми на карточку, чтобы прочитать полностью"))
            search.setHint(if (libraryTab[0] == 1) tr("Найти alias", "Find alias") else if (libraryTab[0] == 2) WorkspaceText.tr("Найти заметку") else WorkspaceText.tr("Найти команду"))
            for (tab in arrayOf<TextView>(commandTab, aliasTab, notesTab)) {
                val selected: Boolean = (if (tab === commandTab) 0 else if (tab === aliasTab) 1 else 2) == libraryTab[0]
                tab.setSelected(selected)
                tintButton(tab, if (selected) Pal.ACCENT else Pal.TEXT)
                tab.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), shape(if (selected) Pal.CARD2 else Pal.PANEL, if (selected) Pal.ACCENT else Pal.BORDER, 12), null))
            }
        }
        commandTab.setOnClickListener(View.OnClickListener { v ->
            libraryTab[0] = 0
            search.setText(queries[0])
            refresh.run()
        })
        aliasTab.setOnClickListener(View.OnClickListener { v ->
            libraryTab[0] = 1
            search.setText(queries[1])
            refresh.run()
        })
        notesTab.setOnClickListener(View.OnClickListener { v ->
            libraryTab[0] = 2
            search.setText(queries[2])
            refresh.run()
        })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(value: CharSequence, start: Int, before: Int, count: Int) {
                queries[libraryTab[0]] = value.toString()
                refresh.run()
            }

            override fun afterTextChanged(value: Editable) {
            }
        })
        refresh.run()
        search.setOnEditorActionListener(TextView.OnEditorActionListener { v, action, event ->
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return@OnEditorActionListener false
            }
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.getWindowToken(), 0)
            return@OnEditorActionListener true
        })
        val picker: AlertDialog = builder.create()
        val openSearchKeyboard = Runnable {
            search.requestFocus()
            search.post(Runnable {
                if (picker.isShowing() && search.hasFocus()) {
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
                }
            })
        }
        search.setShowSoftInputOnFocus(true)
        search.setOnClickListener(View.OnClickListener { v -> openSearchKeyboard.run() })
        search.setOnFocusChangeListener(View.OnFocusChangeListener { v, focused ->
            if (focused) {
                openSearchKeyboard.run()
            }
        })
        picker.setOnShowListener(DialogInterface.OnShowListener { d ->
            // AlertController does not inspect customTitle for text editors.
            picker.getWindow()!!.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            picker.getWindow()!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            heading.requestFocus()
        })
        picker.show()
    }

    /** The gear opens the single Settings screen of the app (appearance, language, updates, backups, about).*/
    private fun globalSettings() {
        activity.startActivity(Intent(activity, AppSettingsActivity::class.java))
    }

    private fun showBackup() {
        WorkspaceBackupUi.show(activity, store, Runnable { refreshLanguage() }, Consumer<String> { command ->
            val installation = WorkspaceStore.Session()
            installation.name = if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Пакеты из бэкапа" else "Backup packages"
            installation.environment = "termux"
            installation.directory = TermuxConstants.TERMUX_HOME_DIR_PATH
            installation.startup = command
            launchNew(installation, true)
        })
    }

    private fun showErrors() {
        WorkspaceErrorLibraryUi.show(activity, Consumer<String> { text ->
            val command = WorkspaceStore.Command()
            command.command = text
            insertCommand(command)
        })
    }

    private fun showStorage() {
        WorkspaceStorageCleanup(activity, store, Predicate<String>(this::cleanupScopeInUse), Predicate<WorkspaceStore.Session?> { p -> liveFor(p) != null }, Runnable { WorkspaceBackupUi.showRestored(activity, store, Runnable(this::refreshLanguage)) }, Runnable { showChatHistories(true) }, Runnable(this::refreshLanguage)).show()
    }

    /** Settings actions that need the workspace data (opened from the Settings and Environment screens).*/
    private fun runSetting(what: String) {
        if (closed) {
            return
        }
        if (what.equals("backup")) {
            showBackup()
        } else if (what.equals("histories")) {
            showChatHistories()
        } else if (what.equals("errors")) {
            showErrors()
        } else if (what.equals("storage")) {
            showStorage()
        }
    }

    private fun showChatHistories() {
        showChatHistories(false)
    }

    private fun showChatHistories(guarded: Boolean) {
        WorkspaceChatHistories(activity, store, reader, Predicate<WorkspaceChatHistories.Entry> { e ->
            for (p in store.sessions) {
                if ((p!!.environment.equals("codex") || p.aiAgent.equals("codex")) && !p.claudeAgent && !p.geminiAgent && p.codexEnvironment.equals(e.environment) && liveFor(p) != null && (p.codexThread.equals(e.id) || !p.codexChat.equals("id"))) {
                    return@Predicate true
                }
            }
            return@Predicate false
        }, Runnable {  }, guarded).show()
    }

    private fun cleanupScopeInUse(scope: String): Boolean {
        if (activity.getTermuxService() == null) {
            return true
        }
        if (scope.startsWith("history:")) {
            val id: String = scope.substring(8)
            for (p in store.sessions) {
                if (p!!.id.equals(id) && liveFor(p) != null) {
                    return true
                }
            }
            return false
        }
        val running: MutableSet<String> = HashSet<String>()
        for (item in activity.getTermuxService()!!.getTermuxSessions()) {
            val terminal: TerminalSession = item.getTerminalSession()
            if (terminal.isRunning()) {
                running.add(terminal.mHandle)
            }
        }
        if (WorkspaceStorageCleanup.scopeInUse(store, scope, running)) {
            return true
        }
        if (scope.equals("termux")) {
            for (item in activity.getTermuxService()!!.getTermuxSessions()) {
                val terminal: TerminalSession = item.getTerminalSession()
                val profile: WorkspaceStore.Session? = store.findByHandle(terminal.mHandle)
                if (terminal.isRunning() && WorkspaceStore.isBase(profile)) {
                    try {
                        val stat = String(Files.readAllBytes(Paths.get("/proc", Integer.toString(terminal.getPid()), "stat")), StandardCharsets.UTF_8)
                        if (!WorkspaceStorageCleanup.idleBaseShell(stat)) {
                            return true
                        }
                    } catch (unavailable: Exception) {
                        return true
                    }
                }
            }
        }
        if (scope.equals("termux")) {
            for (handle in running) {
                var known = false
                for (profile in store.sessions) {
                    if (handle.equals(profile!!.handle)) {
                        known = true
                        break
                    }
                }
                if (!known) {
                    return true
                }
            }
        }
        return false
    }

    private fun showDiagnostics(profile: WorkspaceStore.Session?) {
        WorkspaceDiagnostics.register(profile, false)
        WorkspaceDiagnosticsUi.show(activity, profile, Supplier<TerminalSession?> { liveFor(profile) }, Supplier<String> { if (desktopUi == null) "" else desktopUi!!.diagnostics(profile) }, Consumer<Runnable> { done ->
            if (desktopUi == null) {
                done.run()
            } else {
                desktopUi!!.prepareDiagnostics(profile, done)
            }
        })
    }

    private fun confirmCancelStartup(profile: WorkspaceStore.Session?) {
        if (!SessionShell.hasCancellableStartup(profile)) {
            return
        }
        val commands = StringBuilder()
        var step = 0
        for (command in profile!!.startupCommands()) {
            commands.append(++step).append(". ").append(command).append("\n\n")
        }
        val body: LinearLayout = previewBody((commands.toString() as java.lang.String).trim(), true)
        val explanation: TextView = text(WorkspaceText.tr("Отменить следующие шаги запуска? Ниже все настроенные команды. Уже выполняемая команда продолжит работу."), 13, Pal.MUTED)
        body.addView(explanation, 0)
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Отменить команды запуска")).setView(formScroll(body)).setPositiveButton(WorkspaceText.tr("Да"), DialogInterface.OnClickListener { d, w -> cancelStartup(profile) }).setNegativeButton(WorkspaceText.tr("Нет"), null).show()
    }

    private fun cancelStartup(profile: WorkspaceStore.Session?) {
        if (!SessionShell.hasCancellableStartup(profile)) {
            return
        }
        try {
            SessionShell.cancel(activity.getFilesDir(), profile!!.id)
            activity.showToast(WorkspaceText.tr("Следующие команды отменены. Текущий процесс продолжает работу."), true)
        } catch (e: IOException) {
            activity.showToast(WorkspaceText.tr("Не удалось отменить команды"), true)
        }
    }

    fun prepareKeyboardWindow() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
    }

    fun toggleKeyboard() {
        prepareKeyboardWindow()
        val keyboard: InputMethodManager? = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        val target: View? = if (interactiveInput) activity.getTerminalView() else terminalInput
        if (target!!.hasFocus() && KeyboardUtils.isSoftKeyboardVisible(activity)) {
            keyboard!!.hideSoftInputFromWindow(target.getWindowToken(), 0)
        } else {
            target.requestFocus()
            target.post(Runnable { keyboard!!.showSoftInput(target, 0) })
        }
    }

    private fun setInteractiveInput(enabled: Boolean) {
        prepareKeyboardWindow()
        interactiveInput = enabled
        activity.getSharedPreferences("workspace-input", Context.MODE_PRIVATE).edit().putBoolean("interactive-input", enabled).apply()
        activity.getTerminalView()!!.setSoftKeyboardInputEnabled(enabled)
        val keyboard: InputMethodManager? = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        keyboard!!.restartInput(activity.getTerminalView())
        if (agentChatUi != null && agentChatUi!!.structured()) {
            agentChatUi!!.interactive(enabled)
            if (!enabled) {
                terminalInput.requestFocus()
            }
            return
        }
        if (enabled) {
            activity.getTerminalView()!!.requestFocus()
            keyboard.showSoftInput(activity.getTerminalView(), InputMethodManager.SHOW_IMPLICIT)
        } else {
            terminalInput.requestFocus()
            keyboard.hideSoftInputFromWindow(terminalInput.getWindowToken(), 0)
        }
    }

    private fun insertCommand(command: WorkspaceStore.Command) {
        val targets: MutableList<TerminalSession> = ArrayList<TerminalSession>()
        for (session in live()) {
            if (session.getTerminalSession().isRunning()) {
                targets.add(session.getTerminalSession())
            }
        }
        if (targets.size > 1) {
            val labels = arrayOfNulls<String>(targets.size)
            var i = 0
            while (i < targets.size) {
                val target: TerminalSession = targets.get(i)
                labels[i] = (if (target.mSessionName == null) WorkspaceText.tr("Терминал ") + (i + 1) else target.mSessionName) + " · " + compact(target.getCwd())
                i++
            }
            WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("В какой терминал вставить?")).setItems(labels, DialogInterface.OnClickListener { dialog, index ->
                val target: TerminalSession = targets.get(index)
                var present = false
                for (session in live()) {
                    if (session.getTerminalSession() === target) {
                        present = true
                    }
                }
                if (!present || !target.isRunning()) {
                    activity.showToast(WorkspaceText.tr("Этот терминал уже завершён"), false)
                    return@OnClickListener
                }
                activity.getTermuxTerminalSessionClient()!!.setCurrentSession(target)
                insertIntoCurrent(command)
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
            return
        }
        if (targets.size == 1) {
            activity.getTermuxTerminalSessionClient()!!.setCurrentSession(targets.get(0))
        }
        insertIntoCurrent(command)
    }

    private fun insertIntoCurrent(command: WorkspaceStore.Command) {
        show(Screen.TERMINAL, true)
        terminalInput.setText(command.command)
        terminalInput.setSelection(terminalInput.length())
        terminalInput.requestFocus()
    }

    private fun resolve(directory: String?): String? {
        if (directory!!.isEmpty() || directory.equals("~")) {
            return TermuxConstants.TERMUX_HOME_DIR_PATH
        }
        return if (directory.startsWith("~/")) TermuxConstants.TERMUX_HOME_DIR_PATH + directory.substring(1) else directory
    }

    private fun launch(s: WorkspaceStore.Session?) {
        if (WorkspaceChatHistories.deleting(s)) {
            activity.showToast(tr("Удаление истории ещё выполняется", "History deletion is still running"), true)
            return
        }

        if (s!!.restoreReview) {
            editSession(s, false)
            return
        }
        if (WorkspaceStore.isBase(s)) {
            baseRestartFailures = 0
        }
        val existing: TerminalSession? = liveFor(s)
        if (existing != null && existing.isRunning()) {
            activity.getTermuxTerminalSessionClient()!!.setCurrentSession(existing)
            show(Screen.TERMINAL, true)
            return
        }
        if (existing != null) {
            store.save()
        }
        launchNew(s, false)
    }

    private fun launchNew(s: WorkspaceStore.Session?, add: Boolean) {
        if (AgentEnvironmentManager.busy(s)) {
            activity.showToast(tr("Окружение занято миграцией или удалением", "Environment migration or deletion is in progress"), true)
            return
        }

        if (s!!.environment.equals("ssh") && s.sshAuth.equals("password") && s.sshEncryptedPassword.isEmpty() && !pendingSshPasswords.containsKey(s.id)) {
            requestSshPassword(s, add)
            return
        }
        if (activity.getTermuxService() == null) {
            activity.showToast(WorkspaceText.tr("Терминальный сервис ещё запускается"), false)
            return
        }
        if (!store.isWritable()) {
            activity.showToast(WorkspaceText.tr("Библиотека недоступна для записи"), true)
            return
        }
        activity.prepareWorkspaceBootstrap(Runnable {
            if (closed || activity.isFinishing()) {
                return@Runnable
            }
            AgentPreparation.beforeLaunch(activity, s, Consumer<Boolean?> { openTerminal ->
                if (closed || activity.isFinishing()) {
                    return@Consumer
                }
                val previous: TerminalSession? = liveFor(s)
                if (previous != null && previous.isRunning()) {
                    activity.getTermuxTerminalSessionClient()!!.setCurrentSession(previous)
                    show(Screen.TERMINAL, true)
                    return@Consumer
                }
                val live: TerminalSession? = create(s, add, if (previous != null) previous else null)
                if (live != null) {
                    activity.getTermuxTerminalSessionClient()!!.setCurrentSession(live)
                    show(Screen.TERMINAL, true)
                    if (openTerminal!! && (s.geminiAgent || s.claudeAgent || s.aiAgent.equals("opencode") || CodexChatControl.enabled(activity.getFilesDir(), s))) {
                        codexHandler.post(Runnable {
                            if (!closed && activity.getCurrentSession() === live) {
                                agentChatUi!!.terminal()
                            }
                        })
                    }
                }
            })
        })
    }

    /** Used by the original new-session action after bootstrap setup.*/
    fun createDefault(name: String?, directory: String?): TermuxSession? {
        var profile: WorkspaceStore.Session? = null
        for (stored in store.sessions) {
            if (WorkspaceStore.isBase(stored) && (liveFor(stored) == null || !liveFor(stored)!!.isRunning())) {
                profile = stored
                break
            }
        }
        val add: Boolean = profile == null
        if (add) {
            profile = WorkspaceStore.Session()
        }
        profile!!.name = if (name == null || (name as java.lang.String).trim().isEmpty()) "Bash" else name
        if (!hasBaseProfile() || WorkspaceStore.isBase(profile)) {
            profile.id = WorkspaceStore.BASE_TERMINAL_ID
            profile.name = "Terminal"
            profile.environment = "termux"
        }
        profile.directory = if (WorkspaceStore.isBase(profile) || directory == null) TermuxConstants.TERMUX_HOME_DIR_PATH else directory
        val previous: TerminalSession? = liveFor(profile)
        val session: TerminalSession? = create(profile, add, previous)
        if (session == null) {
            return null
        }
        return activity.getTermuxService()!!.getTermuxSession(activity.getTermuxService()!!.getIndexOfSession(session))
    }

    private fun hasBaseProfile(): Boolean {
        for (s in store.sessions) {
            if (WorkspaceStore.isBase(s)) {
                return true
            }
        }
        return false
    }

    fun isBaseTerminal(terminal: TerminalSession?): Boolean {
        return terminal != null && WorkspaceStore.isBase(store.findByHandle(terminal.mHandle))
    }

    fun ensureBaseTerminal() {
        val service: TermuxService? = activity.getTermuxService()
        if (closed || creating || baseCreating || !store.isWritable() || service == null || service.wantsToStop() || !File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash").isFile()) {
            return
        }
        var base: WorkspaceStore.Session? = null
        for (s in store.sessions) {
            if (WorkspaceStore.isBase(s)) {
                base = s
                break
            }
        }
        if (base != null && liveFor(base) != null) {
            return
        }
        if (service.getTermuxSessionsSize() >= 8) {
            return
        }
        val add: Boolean = base == null
        if (add) {
            base = WorkspaceStore.Session()
            base.id = WorkspaceStore.BASE_TERMINAL_ID
        }
        base!!.name = "Terminal"
        base.environment = "termux"
        base.directory = TermuxConstants.TERMUX_HOME_DIR_PATH
        base.saved = false
        baseCreating = true
        try {
            val created: TerminalSession? = create(base, add)
            if (created != null && activity.getCurrentSession() == null) {
                activity.getTermuxTerminalSessionClient()!!.setCurrentSession(created)
            }
        } finally {
            baseCreating = false
        }
    }

    private fun create(profile: WorkspaceStore.Session?, add: Boolean): TerminalSession? {
        return create(profile, add, null)
    }

    private fun create(profile: WorkspaceStore.Session?, add: Boolean, replaced: TerminalSession?): TerminalSession? {
        if (profile!!.restoreReview) {
            editSession(profile, false)
            return null
        }
        if (AgentEnvironmentManager.busy(profile)) {
            activity.showToast(tr("Окружение занято миграцией или удалением", "Environment migration or deletion is in progress"), true)
            return null
        }
        val service: TermuxService? = activity.getTermuxService()
        if (service == null) {
            return null
        }
        if (WorkspaceStore.isBase(profile)) {
            baseStartedAt = System.currentTimeMillis()
        }
        val replacing: Boolean = replaced != null && !replaced.isRunning() && service.getIndexOfSession(replaced) >= 0
        if (service.getTermuxSessionsSize() >= 8 && !replacing) {
            activity.showToast(WorkspaceText.tr("Максимум 8 терминалов. Закройте завершённую сессию."), true)
            return null
        }
        val directory: String? = if (!WorkspaceEnvironments.alias(profile).isEmpty() || profile.environment.equals("ssh") || profile.environment.equals("codex") && AgentEnvironments.ubuntu(profile.codexEnvironment)) TermuxConstants.TERMUX_HOME_DIR_PATH else resolve(profile.directory)
        // AGENTDROID: on a fresh install the home directory does not exist yet (it is normally created
        // when the first shell starts, which is after this check), so create it here.
        if (directory!!.equals(TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            TermuxConstants.TERMUX_HOME_DIR.mkdirs()
        }
        if (!File(directory).isDirectory() || !File(directory).canExecute()) {
            activity.showToast(WorkspaceText.tr("Каталог недоступен: ") + directory, true)
            return null
        }
        var broker: SshPasswordBroker? = null
        var registeredNew = false
        try {
            var askpass: String? = null
            var socket: String? = null
            if (profile.environment.equals("ssh") && profile.sshAuth.equals("password")) {
                var secret: CharArray? = pendingSshPasswords.remove(profile.id)
                if (secret == null && !profile.sshEncryptedPassword.isEmpty()) {
                    secret = SshPasswordVault.decrypt(profile)
                }
                if (secret == null) {
                    activity.showToast(WorkspaceText.tr("Введите пароль в настройках сессии"), true)
                    return null
                }
                try {
                    askpass = SessionShell.prepareAskpass(activity.getFilesDir(), profile, activity.getApplicationInfo().sourceDir).getAbsolutePath()
                    broker = SshPasswordBroker(activity, secret)
                    socket = broker.address
                } finally {
                    Arrays.fill(secret, '\u0000')
                }
            }
            if (AgentPreparation.supports(profile)) {
                AgentPreparation.prepare(activity)
            }
            if (profile.geminiAgent) {
                GeminiCli.prepare(activity)
                profile.geminiSocketId = UUID.randomUUID().toString()
                if (profile.geminiChat.equals("new")) {
                    profile.geminiThread = UUID.randomUUID().toString()
                }
                GeminiApiKeyVault.prepare(activity, profile)
            }
            if (profile.claudeAgent) {
                ClaudeCode.prepare(activity)
                profile.claudeSocketId = UUID.randomUUID().toString()
                if (profile.claudeChat.equals("new")) {
                    profile.claudeThread = UUID.randomUUID().toString()
                }
            }
            if (profile.aiAgent.equals("opencode")) {
                AiAgents.prepare(activity)
                AiAgents.prepareLaunch(profile)
            }
            if (profile.environment.equals("codex")) {
                profile.codexSocketId = UUID.randomUUID().toString()
                CodexSessionShell.prepareBridge(activity)
                CodexChatControl.prepare(activity, profile)
            }
            if (profile.environment.equals("codex") && (profile.codexChat.equals("choose") || profile.codexChat.equals("last"))) {
                activity.showToast(WorkspaceText.tr("Выберите конкретный чат или введите его ID в настройках сессии"), true)
                return null
            }
            val rc: File = SessionShell.prepare(activity.getFilesDir(), profile, askpass, socket)
            codexStatuses.remove(profile.id)
            // service notifications are synchronous; register the new profile before they fire.
            if (add) {
                store.sessions.add(profile)
                registeredNew = true
            }
            creating = true
            var created: TermuxSession?
            try {
                created = service.createTermuxSession(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", arrayOf<String>("--rcfile", rc.getAbsolutePath(), "-i"), null, directory, false, profile.name)
            } finally {
                creating = false
            }
            if (created == null) {
                if (broker != null) {
                    broker.close()
                }
                rollbackUnstarted(profile, registeredNew)
                activity.showToast(WorkspaceText.tr("Не удалось создать терминал"), true)
                return null
            }
            val session: TerminalSession = created.getTerminalSession()
            // The synchronous callback may have adopted it; discard that temporary record.
            val adopted: WorkspaceStore.Session? = store.findByHandle(session.mHandle)
            if (adopted != null && adopted !== profile) {
                store.sessions.remove(adopted)
            }
            profile.handle = session.mHandle
            profile.updated = System.currentTimeMillis()
            configureAgentOutput(session, profile)
            WorkspaceDiagnostics.register(profile, true)
            // Create first: removing the last old session would otherwise stop the service.
            if (replacing) {
                service.removeTermuxSession(replaced)
            }
            store.save()
            refresh()
            if (profile.environment.equals("codex")) {
                codexHandler.postDelayed(Runnable {
                    if (!closed) {
                        loadCodexStatus(profile, null)
                    }
                }, 1500L)
            }
            return session
        } catch (e: GeneralSecurityException) {
            rollbackUnstarted(profile, registeredNew)
            if (broker != null) {
                broker.close()
            }
            activity.showToast(if (profile.geminiAgent) tr("Сохранённый API-ключ недоступен. Введите его заново в настройках сессии.", "The saved API key is unavailable. Enter it again in session settings.") else WorkspaceText.tr("Сохранённый пароль недоступен. Введите его заново в настройках сессии."), true)
            return null
        } catch (e: IOException) {
            rollbackUnstarted(profile, registeredNew)
            WorkspaceDiagnostics.failure(profile, e)
            if (broker != null) {
                broker.close()
            }
            activity.showToast(WorkspaceText.tr("Не удалось подготовить сессию"), true)
            return null
        } catch (e: RuntimeException) {
            rollbackUnstarted(profile, registeredNew)
            WorkspaceDiagnostics.failure(profile, e)
            if (broker != null) {
                broker.close()
            }
            activity.showToast(WorkspaceText.tr("Не удалось подготовить сессию"), true)
            return null
        }
    }

    private fun rollbackUnstarted(profile: WorkspaceStore.Session?, registeredNew: Boolean) {
        if (profile!!.geminiAgent) {
            GeminiApiKeyVault.discard(activity, profile)
        }
        if (registeredNew && !profile.saved && profile.handle.isEmpty() && store.sessions.remove(profile)) {
            store.save()
        }
    }

    fun refresh() {
        if (closed || creating) {
            return
        }
        var changed = false
        for (live in live()) {
            val terminal: TerminalSession = live.getTerminalSession()
            if (store.findByHandle(terminal.mHandle) == null) {
                val profile = WorkspaceStore.Session()
                profile.handle = terminal.mHandle
                profile.name = if (terminal.mSessionName == null) "Bash" else terminal.mSessionName
                profile.directory = if (live.getExecutionCommand().workingDirectory == null) TermuxConstants.TERMUX_HOME_DIR_PATH else live.getExecutionCommand().workingDirectory
                profile.updated = System.currentTimeMillis()
                store.sessions.add(profile)
                changed = true
            } else {
                val profile: WorkspaceStore.Session? = store.findByHandle(terminal.mHandle)
                configureAgentOutput(terminal, profile)
                if (terminal.mSessionName != null && !profile!!.name!!.equals(terminal.mSessionName)) {
                    profile.name = terminal.mSessionName
                    changed = true
                }
            }
        }
        if (activity.getTermuxService() != null && store.isWritable()) {
            for (profile in ArrayList<WorkspaceStore.Session?>(store.sessions)) {
                if (!WorkspaceStore.isBase(profile) && !profile!!.saved && liveFor(profile) == null && (!profile.handle.isEmpty() || unstartedOnLoad.contains(profile.id))) {
                    store.sessions.remove(profile)
                    if (!profile.handle.isEmpty() && !profile.keepHistory) {
                        eraseSessionFiles(profile)
                    }
                    changed = true
                }
            }
        }
        if (activity.getTermuxService() != null) {
            unstartedOnLoad.clear()
        }
        for (profile in store.sessions) {
            if (!profile!!.handle.isEmpty()) {
                WorkspaceDiagnostics.register(profile, false)
            }
        }
        if (changed) {
            store.save()
        }
        renderTabs()
        renderHeader()
        if (screen !== Screen.SERVERS) {
            renderContent()
        }
    }

    fun onFinished(session: TerminalSession?): Boolean {
        val profile: WorkspaceStore.Session? = store.findByHandle(session!!.mHandle)
        if (profile != null && !closed && File(activity.getFilesDir(), "workspace-package-jobs").isDirectory()) {
            try {
                reader.execute(Runnable {
                    try {
                        PackageOperationJournal.collect(activity.getFilesDir(), profile.id)
                    } catch (ignored: Exception) {
                    }
                })
            } catch (ignored: RejectedExecutionException) {
            }
        }
        WorkspaceDiagnostics.finished(session)
        if (desktopUi != null && profile != null) {
            desktopUi!!.sessionClosed(profile)
        }
        if (profile != null) {
            store.save()
        }
        if (WorkspaceStore.isBase(profile) && activity.getTermuxService() != null && !activity.getTermuxService()!!.wantsToStop()) {
            if (System.currentTimeMillis() - baseStartedAt < 5000) {
                baseRestartFailures++
            } else {
                baseRestartFailures = 0
            }
            if (baseRestartFailures > 3) {
                activity.showToast(WorkspaceText.tr("Terminal сразу завершается. Проверьте shell-настройки и перезапустите вручную."), true)
                return true
            }
            val selected: Boolean = activity.getCurrentSession() === session
            val delay: Long = Math.max(300L, 3000 - (System.currentTimeMillis() - lastBaseRestart))
            lastBaseRestart = System.currentTimeMillis()
            codexHandler.postDelayed(Runnable {
                if (closed || activity.getTermuxService() == null || activity.getTermuxService()!!.wantsToStop() || liveFor(profile) !== session) {
                    return@Runnable
                }
                val next: TerminalSession? = create(profile, false, session)
                if (next != null) {
                    if (selected) {
                        activity.getTermuxTerminalSessionClient()!!.setCurrentSession(next)
                    }
                    refresh()
                }
            }, delay)
            return true
        }
        if (pendingCloses.remove(session.mHandle)) {
            if (profile != null) {
                retireProfile(profile)
            }
            activity.getTermuxTerminalSessionClient()!!.removeFinishedSession(session)
            refresh()
            return true
        }
        val restart: WorkspaceStore.Session? = pendingRestarts.remove(session.mHandle)
        if (restart == null || activity.getTermuxService() == null || activity.getTermuxService()!!.wantsToStop()) {
            return false
        }
        val next: TerminalSession? = create(restart, false, session)
        if (next != null) {
            activity.getTermuxTerminalSessionClient()!!.setCurrentSession(next)
            show(Screen.TERMINAL, true)
        } else {
            store.save()
            refresh()
            show(Screen.SESSIONS, true)
        }
        return true
    }

    fun onBack(): Boolean {
        if (desktopUi != null && desktopUi!!.fullscreenBack()) {
            return true
        }
        if (screen === Screen.SERVERS && servers.back()) {
            return true
        }
        if (screen === Screen.HOME) {
            return false
        }
        show(Screen.HOME, true)
        return true
    }

    fun close() {
        activity.getTerminalView()!!.setWorkspaceTerminalVisible(false)
        if (transferBorder != null) {
            transferBorder!!.active(false)
        }
        if (agentTerminalBorder != null) {
            agentTerminalBorder!!.active(false)
        }
        if (desktopSessionBar != null) {
            desktopSessionBar!!.working(false)
        }
        servers.close()
        if (activityLine != null) {
            activityLine!!.working(false)
        }
        if (desktopUi != null) {
            desktopUi!!.close()
        }
        geminiInputDispatch.cancel()
        if (agentChatUi != null) {
            agentChatUi!!.close()
        }
        if (geminiLiveUi != null) {
            geminiLiveUi!!.close()
        }
        if (claudeLiveUi != null) {
            claudeLiveUi!!.close()
        }
        if (agentReceiptsUi != null) {
            agentReceiptsUi!!.close()
        }
        activity.getTerminalView()!!.setTranscriptTapListener(null)
        terminalDrafts.clear()
        for (browser in ArrayList<WorkspacePackages>(packageBrowsers)) {
            browser.close()
        }
        packageBrowsers.clear()
        for (browser in ArrayList<WorkspaceFileBrowser>(fileBrowsers)) {
            browser.close()
        }
        fileBrowsers.clear()
        for (secret in pendingSshPasswords.values) {
            Arrays.fill(secret, '\u0000')
        }
        pendingSshPasswords.clear()
        agentEnvironments.close()
        environmentSetup.close()
        closed = true
        codexApplyGeneration++
        if (codexApplyDialog != null) {
            codexApplyDialog!!.dismiss()
        }
        codexHandler.removeCallbacksAndMessages(null)
        codexReader.shutdown()
        reader.shutdown()
        store.close()
    }

    companion object {
        private val ALIAS_BUSY: AtomicBoolean = WorkspaceAliases.BUSY

        @JvmStatic
        fun configureAgentOutput(session: TerminalSession, profile: WorkspaceStore.Session?) {
            val framed: Boolean = profile != null && (profile.geminiAgent || profile.aiAgent.equals("opencode"))
            session.setPreserveTranscriptScroll(framed)
            session.setSynchronizedFramesEnabled(framed)
            session.setStableResizeEnabled(profile != null && profile.aiAgent.equals("opencode"))
        }

        @JvmStatic
        fun sessionIcon(p: WorkspaceStore.Session?): Int {
            return if (p != null && (p.environment.equals("codex") || p.claudeAgent || p.geminiAgent || !p.aiAgent.isEmpty())) R.drawable.workspace_ai_agent else R.drawable.workspace_terminal
        }

        @JvmStatic
        private fun agentQueryProfile(env: String): WorkspaceStore.Session {
            val p = WorkspaceStore.Session()
            p.codexEnvironment = env
            return p
        }
    }
}
