package com.termux.app.workspace

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.app.terminal.io.TermuxTerminalExtraKeys
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.terminal.TerminalSession
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Shared Android chat surface; each provider retains its own native bridge and permissions.*/
class AgentChatUi {
    fun interface Transport {
        @Throws(Exception::class)
        fun request(p: WorkspaceStore.Session, q: JSONObject): JSONObject
    }

    private val activity: TermuxActivity
    private val current: Supplier<WorkspaceStore.Session?>
    private val visible: BooleanSupplier
    private val transport: Transport
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val reads: ExecutorService = Executors.newSingleThreadExecutor()
    private val actions: ExecutorService = Executors.newSingleThreadExecutor()
    private val surface: FrameLayout?
    private val view: AgentChatView
    private val attachments: ChatAttachmentUi
    private var attachmentsSupported: Boolean = false
    private var codexAuthRequired: Boolean = false
    private var codexLoginTerminal: Boolean = false

    fun hasAttachments(): Boolean {
        return attachments.has()
    }

    fun applyPresentationScale() {
        val p: WorkspaceStore.Session? = current.get()
        view.scale(if (p == null) 100 else p.chatScale)
        if (composerArea != null) {
            AgentChatScale.apply(composerArea, if (selected() && terminal == null && view.getVisibility() == View.VISIBLE && p != null) p.chatScale else 100)
        }
    }

    private val rows: LinkedHashMap<String, JSONObject?> = LinkedHashMap<String, JSONObject?>()
    private val cache: AgentChatCache = AgentChatCache()
    private var connected: Boolean = false
    private var transportFailed: Boolean = false
    private var owner: String = ""
    private var older: String = ""
    private var requestStamp: String = ""
    private var currentModel: String? = ""
    private var nativeSession: String = ""
    private var actionNotice: String = ""

    fun modelChanged(p: WorkspaceStore.Session) {
        if (key(p).equals(owner)) {
            currentModel = ""
        }
    }

    fun modelLabel(p: WorkspaceStore.Session): String? {
        return if (key(p).equals(owner) && !currentModel!!.isEmpty()) currentModel else AiAgentsUi.sessionModelLabel(p, null)
    }

    @Volatile private var closed: Boolean = false
    private var olderQueued: Boolean = false
    private var historyExhausted: Boolean = false
    private var loading: Boolean = false
    private var nativeChat: Boolean = false
    private var olderLoading: Boolean = false
    private var wasVisible: Boolean = false
    private var resolved: Boolean = false
    private var presented: Boolean = false
    private var busy: Boolean = false
    private var directShell: Boolean = false
    private var requiresTerminal: Boolean = false
    @Volatile private var generation: Int = 0
    private var terminal: AlertDialog? = null
    private var details: AlertDialog? = null
    private var formDialog: AlertDialog? = null
    private var vertexDialog: AlertDialog? = null
    private var vertexPrompted: String = ""
    private var thinkingBody: TextView? = null
    private var thinkingScroll: ScrollView? = null
    private var thinkingKey: String = ""
    private var terminalParent: ViewGroup? = null
    private var terminalParams: ViewGroup.LayoutParams? = null
    private var visibilityChanged: Runnable = Runnable {  }

    fun onVisibilityChanged(listener: Runnable) {
        visibilityChanged = listener
    }

    private val shellDrafts: MutableMap<String, String> = HashMap<String, String>()
    private var extraVisible: Boolean = false
    private val extraByOwner: LinkedHashMap<String, Boolean> = LinkedHashMap<String, Boolean>()
    private var mountedKeys: View? = null
    private var keysHome: ViewGroup? = null
    private var keysHomeParams: ViewGroup.LayoutParams? = null
    private var keysHomeIndex: Int = 0
    private var keysHomeVisibility: Int = 0

    fun extraKeysVisible(): Boolean {
        return extraVisible
    }

    fun toggleExtraKeys() {
        extraVisible = !extraVisible
        sync()
    }

    private fun restoreChatKeys() {
        if (mountedKeys != null) {
            val parent: ViewGroup? = mountedKeys!!.getParent() as ViewGroup?
            if (parent != null) {
                parent.removeView(mountedKeys)
            }
            if (keysHome != null) {
                keysHome!!.addView(mountedKeys, Math.min(keysHomeIndex, keysHome!!.getChildCount()), keysHomeParams)
            }
            mountedKeys!!.setVisibility(keysHomeVisibility)
            mountedKeys = null
            keysHome = null
        }
        view.keysHost.setVisibility(View.GONE)
    }

    private fun layoutChatKeys() {
        val keys: View? = activity.getExtraKeysView()
        if (!showing() || !extraVisible || keys !== mountedKeys) {
            restoreChatKeys()
        }
        if (showing() && extraVisible && keys != null && mountedKeys == null) {
            mountedKeys = keys
            keysHome = keys.getParent() as ViewGroup?
            keysHomeParams = keys.getLayoutParams()
            keysHomeIndex = if (keysHome == null) 0 else keysHome!!.indexOfChild(keys)
            keysHomeVisibility = keys.getVisibility()
            if (keysHome != null) {
                keysHome!!.removeView(keys)
            }
            keys.setVisibility(View.VISIBLE)
            val count: Int = if (activity.getProperties() == null || activity.getProperties()!!.getExtraKeysInfo() == null) 2 else activity.getProperties()!!.getExtraKeysInfo()!!.getMatrix().size
            view.keysHost.addView(keys, LinearLayout.LayoutParams(-1, view.dp(Math.max(1, count) * 40)))
            view.keysHost.setVisibility(View.VISIBLE)
        }
    }

    private var composer: EditText? = null
    private var composerArea: View? = null

    fun composer(input: EditText) {
        composer(input, input)
    }

    fun composer(input: EditText, area: View?) {
        composer = input
        composerArea = area
    }

    private var chatKeys: ExtraKeysView? = null
    private var previousKeys: ExtraKeysView.IExtraKeysView? = null

    private fun syncExtraKeys() {
        val keys: ExtraKeysView? = activity.getExtraKeysView()
        if (chatKeys != null && (!showing() || chatKeys !== keys)) {
            chatKeys!!.setExtraKeysViewClient(previousKeys)
            chatKeys = null
            previousKeys = null
        }
        if (showing() && keys != null && chatKeys == null) {
            chatKeys = keys
            previousKeys = keys.getExtraKeysViewClient()
            keys.setExtraKeysViewClient(ChatExtraKeys(activity.getTerminalView(), keys, Supplier<EditText?> {
                if (showing()) if (view.shellInput.hasFocus() && view.shellInput.isEnabled()) view.shellInput else composer else null
            }))
        }
    }

    private var recordShell: Consumer<String> = Consumer<String> { s ->  }

    fun onShellCommand(listener: Consumer<String>) {
        recordShell = listener
    }

    private val sendIDs: MutableMap<String, String?> = HashMap<String, String?>()

    constructor(a: TermuxActivity, c: Supplier<WorkspaceStore.Session?>, v: BooleanSupplier) : this(a, c, v, Transport { p, q ->
        if (p.environment.equals("codex")) CodexChatControl.request(a.getFilesDir(), p, q) else if (p.claudeAgent) ClaudeLiveControl.request(a.getFilesDir(), p.claudeSocketId, q) else if (p.geminiAgent) GeminiLiveControl.request(a.getFilesDir(), p.geminiSocketId, q) else AiAgents.liveRequest(a, p, q)
    })

    constructor(a: TermuxActivity, c: Supplier<WorkspaceStore.Session?>, v: BooleanSupplier, t: Transport) {
        activity = a
        current = c
        visible = v
        transport = t
        surface = a.findViewById(R.id.workspace_terminal_surface)
        view = AgentChatView(a, Consumer<JSONObject>(this::details), Runnable(this::older), Runnable(this::sendShell), Runnable(this::terminal), Runnable(this::interrupt))
        attachments = ChatAttachmentUi(a, view)
        view.thumbnail = BiConsumer<JSONObject, ImageView>(attachments::thumbnail)
        view.assistantName("OpenCode")
        view.shellEnabled(activity.getSharedPreferences("workspace-input", Context.MODE_PRIVATE).getBoolean("interactive-input", false))
        view.setVisibility(View.GONE)
        surface!!.addView(view, FrameLayout.LayoutParams(-1, -1))
        handler.post(poll)
    }

    private fun eligible(): Boolean {
        val p: WorkspaceStore.Session? = current.get()
        return !closed && p != null && (p.aiAgent.equals("opencode") || p.geminiAgent || p.claudeAgent || CodexChatControl.enabled(activity.getFilesDir(), p))
    }

    private fun selected(): Boolean {
        return eligible() && visible.getAsBoolean()
    }

    private fun key(p: WorkspaceStore.Session?): String {
        return p!!.id + ":" + (if (p.environment.equals("codex")) "codex:" + p.codexSocketId else if (p.claudeAgent) "claude:" + p.claudeSocketId + ":" + p.claudeThread else if (p.geminiAgent) p.geminiSocketId + ":" + p.geminiThread else p.aiSocketId + ":" + p.aiThread) + ":" + p.handle
    }

    private fun snapshot(): WorkspaceStore.Session {
        val p: WorkspaceStore.Session? = current.get()
        val s = WorkspaceStore.Session()
        s.environment = p!!.environment
        s.codexEnvironment = p.codexEnvironment
        s.codexSocketId = p.codexSocketId
        s.id = p.id
        s.aiThread = p.aiThread
        s.aiSocketId = p.aiSocketId
        s.aiAgent = p.aiAgent
        s.aiConnection = p.aiConnection
        s.aiModel = p.aiModel
        s.aiVariant = p.aiVariant
        s.geminiAgent = p.geminiAgent
        s.geminiSocketId = p.geminiSocketId
        s.geminiThread = p.geminiThread
        s.geminiModel = p.geminiModel
        s.claudeAgent = p.claudeAgent
        s.claudeSocketId = p.claudeSocketId
        s.claudeThread = p.claudeThread
        s.claudeModel = p.claudeModel
        s.claudeMode = p.claudeMode
        return s
    }

    fun handlesComposer(): Boolean {
        return selected() && terminal == null && (nativeChat || !resolved)
    }

    fun name(): String {
        val p: WorkspaceStore.Session? = current.get()
        return if (p != null && p.environment.equals("codex")) "Codex" else if (p != null && p.claudeAgent) "Claude" else if (p != null && p.geminiAgent) "Gemini" else "OpenCode"
    }

    fun structured(): Boolean {
        return selected() && nativeChat
    }

    fun terminalAvailable(): Boolean {
        return selected()
    }

    fun requiresTerminal(): Boolean {
        return showing() && requiresTerminal
    }

    fun working(): Boolean {
        return showing() && busy
    }

    fun canInterrupt(): Boolean {
        return !closed && selected() && nativeChat && busy
    }

    fun stopCurrent() {
        if (canInterrupt()) {
            interrupt()
        }
    }

    fun showing(): Boolean {
        return selected() && nativeChat && terminal == null
    }

    private var externalDisplay: BooleanSupplier = BooleanSupplier { false }

    fun externalDisplay(value: BooleanSupplier) {
        externalDisplay = value
    }

    fun sync() {
        val next: String = if (eligible()) key(current.get()) else ""
        val nowVisible: Boolean = selected()
        if (wasVisible && !nowVisible) {
            generation++
            loading = false
            olderLoading = false
            olderQueued = false
            view.olderState(false, null)
            if (terminal != null) {
                terminal!!.dismiss()
            }
            if (details != null) {
                details!!.dismiss()
            }
            if (formDialog != null) {
                formDialog!!.dismiss()
            }
            if (vertexDialog != null) {
                vertexDialog!!.dismiss()
            }
        }
        wasVisible = nowVisible
        if (!next.equals(owner)) {
            if (!owner.isEmpty()) {
                extraByOwner.put(owner, extraVisible)
                while (extraByOwner.size > 32) {
                    extraByOwner.remove(extraByOwner.keys.iterator().next())
                }
            }
            restoreChatKeys()
            extraVisible = extraByOwner.getOrDefault(next, false)
            if (!owner.isEmpty()) {
                shellDrafts.put(owner, view.shellInput.getText().toString())
                savePresentation()
            }
            generation++
            attachmentsSupported = false
            codexAuthRequired = false
            codexLoginTerminal = false
            owner = next
            actionNotice = ""
            currentModel = ""
            nativeSession = ""
            connected = false
            transportFailed = false
            view.assistantName(name())
            view.actionCards(current.get() != null && !current.get()!!.environment.equals("codex"))
            view.shellInput.setText(shellDrafts.getOrDefault(next, ""))
            nativeChat = false
            resolved = false
            loading = false
            olderLoading = false
            olderQueued = false
            busy = false
            directShell = false
            requiresTerminal = false
            rows.clear()
            older = ""
            historyExhausted = false
            requestStamp = ""
            view.clear()
            attachments.sync(owner, if (current.get() == null) null else snapshot(), false)
            val saved: AgentChatCache.State? = cache.take(next)
            if (saved == null) {
                view.connection(if (next.isEmpty()) "" else tr("Подключение…", "Connecting…"))
            } else {
                restorePresentation(saved)
            }
            if (terminal != null) {
                terminal!!.dismiss()
            }
            if (details != null) {
                details!!.dismiss()
            }
            if (formDialog != null) {
                formDialog!!.dismiss()
            }
            if (vertexDialog != null) {
                vertexDialog!!.dismiss()
            }
        }
        applyPresentationScale()
        attachments.sync(owner, if (current.get() == null) null else snapshot(), attachmentsSupported)
        val cover: Boolean = showing() || selected() && !resolved && terminal == null
        val changed: Boolean = (view.getVisibility() == View.VISIBLE) != cover || presented != showing()
        presented = showing()
        view.setVisibility(if (cover) View.VISIBLE else View.GONE)
        applyPresentationScale()
        // show(TERMINAL) may restore the shared toolbar even without a chat visibility
        // transition. Enforce the structured-chat boundary on every synchronization.
        if ((cover || terminal != null) && activity.getTerminalToolbarViewPager() != null) {
            activity.getTerminalToolbarViewPager()!!.setVisibility(View.GONE)
        }
        activity.getTerminalView()!!.setVisibility(if (cover || externalDisplay.getAsBoolean()) View.INVISIBLE else View.VISIBLE)
        layoutChatKeys()
        syncExtraKeys()
        if (changed) {
            visibilityChanged.run()
        }
        activity.getTerminalView()!!.setWorkspaceTerminalVisible(!closed && visible.getAsBoolean() && !cover && !externalDisplay.getAsBoolean())
    }

    private fun savePresentation() {
        if (!resolved && !nativeChat) {
            return
        }
        val state = AgentChatCache.State()
        state.rows.putAll(rows)
        state.historyExhausted = historyExhausted
        state.older = older
        state.model = currentModel
        state.session = nativeSession
        state.notice = actionNotice
        state.nativeChat = nativeChat
        state.resolved = resolved
        state.busy = busy
        state.directShell = directShell
        state.needsTerminal = requiresTerminal
        state.connected = connected
        state.failed = transportFailed
        state.position = view.position()
        cache.put(owner, state)
    }

    private fun restorePresentation(state: AgentChatCache.State) {
        rows.putAll(state.rows)
        historyExhausted = state.historyExhausted && !state.trimmed
        older = if (state.trimmed) "" else state.older
        currentModel = state.model
        nativeSession = state.session
        actionNotice = state.notice
        nativeChat = state.nativeChat
        resolved = state.resolved
        busy = state.busy
        directShell = state.directShell
        requiresTerminal = state.needsTerminal
        connected = state.connected
        transportFailed = state.failed
        view.rows(ArrayList<JSONObject?>(rows.values), !older.isEmpty())
        if (transportFailed) {
            connectionFailure()
        } else {
            val status: JSONObject = op("chat-snapshot")
            try {
                status.put("connected", connected).put("needsTerminal", requiresTerminal)
            } catch (impossible: JSONException) {
                throw IllegalStateException(impossible)
            }
            connectionState(status)
        }
        view.restorePosition(state.position)
        // Pending permissions/forms are intentionally rebuilt only from a fresh native snapshot.
    }

    private fun connectionFailure() {
        if (vertexSetup()) {
            return
        }
        view.connection(tr("Нет связи с сессией. Повторяем подключение автоматически.\nЕсли ожидание затянулось, откройте терминал кнопкой робота и проверьте запуск агента. Нажмите здесь, чтобы проверить снова.", "No connection to the session. Retrying automatically.\nIf this persists, open the robot terminal and check that the agent started. Tap here to check again."), Runnable(this::retryConnection))
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (closed) {
                return
            }
            sync()
            if (selected() && !vertexSetup() && !loading && (terminal == null || codexLoginTerminal)) {
                load(false)
            }
            handler.postDelayed(this, (if (nativeChat && !busy) 2000 else 500).toLong())
        }
    }

    private fun vertexSetup(): Boolean {
        val p: WorkspaceStore.Session? = current.get()
        if (connected) {
            return false
        }
        if (!selected() || !VertexConnection.pending(activity, p) || activity.getCurrentSession() == null || !activity.getCurrentSession()!!.isRunning()) {
            if (vertexDialog != null && vertexDialog!!.isShowing()) {
                vertexDialog!!.dismiss()
            }
            return false
        }
        if (terminal != null) {
            terminal!!.dismiss()
        }
        if (VertexConnection.file(activity, p, "response").isFile()) {
            view.connection(tr("Параметры Vertex сохранены. Продолжаем подключение…", "Vertex settings saved. Continuing connection…"))
            return true
        }
        val run: String = key(p)
        val open = Runnable {
            if (vertexDialog != null && vertexDialog!!.isShowing()) {
                return@Runnable
            }
            vertexDialog = VertexConnection.prompt(activity, p, BooleanSupplier { !closed && selected() && key(current.get()).equals(run) }, Runnable {
                view.connection(tr("Параметры Vertex сохранены. Продолжаем подключение…", "Vertex settings saved. Continuing connection…"))
            })
        }
        view.connection(tr("Учётные данные Google Cloud найдены. Укажите ID облачного проекта и регион для Vertex AI. Нажмите здесь, чтобы продолжить настройку.", "Google Cloud credentials were found. Enter a cloud project ID and location for Vertex AI. Tap here to continue setup."), open)
        if (!vertexPrompted.equals(run)) {
            vertexPrompted = run
            open.run()
        }
        return true
    }

    private fun sendShell() {
        if (!view.shellInput.isEnabled()) {
            return
        }
        val command: String = view.shellInput.getText().toString()
        if ((command as java.lang.String).trim().isEmpty()) {
            return
        }
        submit(command, "shell", Runnable {
            recordShell.accept(command)
            if (view.shellInput.getText().toString().equals(command)) {
                view.shellInput.setText("")
            }
        })
    }

    fun shellMode(enabled: Boolean) {
        if (view.shellInput.isEnabled() != enabled) {
            view.shellEnabled(enabled)
        }
    }

    fun interactive(enabled: Boolean) {
        shellMode(enabled)
        activity.getTerminalView()!!.setSoftKeyboardInputEnabled(enabled)
        val target: View? = if (terminal != null) activity.getTerminalView() else if (enabled) view.shellInput else null
        val keyboard: InputMethodManager? = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (target != null && enabled) {
            target.requestFocus()
            keyboard!!.restartInput(target)
            keyboard.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        } else {
            keyboard!!.hideSoftInputFromWindow(view.getWindowToken(), 0)
        }
    }

    fun focusRequest(): Boolean {
        return focus(view.requests)
    }

    private fun focus(v: View): Boolean {
        if (v is ViewGroup) {
            val group: ViewGroup = v as ViewGroup
            var i = 0
            while (i < group.getChildCount()) {
                if (focus(group.getChildAt(i))) {
                    return true
                }
                i++
            }
        } else if (v.isClickable() && v is TextView && !(v as TextView).getText().toString().contains("\n")) {
            v.setFocusableInTouchMode(true)
            return v.requestFocus()
        }
        return false
    }

    private fun interrupt() {
        if (!selected()) {
            return
        }
        val g: Int = generation
        val owner: String = key(current.get())
        val q: JSONObject = op("chat-interrupt")
        try {
            q.put("session", nativeSession)
        } catch (ignored: JSONException) {
        }
        val send = Runnable {
            if (selected() && g == generation && owner.equals(key(current.get()))) {
                operation(q, null)
            }
        }
        if (current.get()!!.environment.equals("codex")) {
            send.run()
            return
        }
        if (directShell) {
            WorkspaceDialogs.builder(activity).setMessage(tr("Прервать команду? Штатный OpenCode удалит её процесс и сохранённый вывод; частичный вывод может стать недоступен.", "Interrupt command? Native OpenCode removes its process and retained output; partial output may become unavailable.")).setPositiveButton(tr("Прервать", "Interrupt"), DialogInterface.OnClickListener { d, w -> send.run() }).setNegativeButton(tr("Назад", "Back"), null).show()
        } else {
            send.run()
        }
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun load(previous: Boolean) {
        if (!selected() || loading) {
            return
        }
        loading = true
        if (previous) {
            olderLoading = true
            view.olderState(true, null)
        }
        val g: Int = generation
        val p: WorkspaceStore.Session = snapshot()
        val q: JSONObject = op("chat-snapshot")
        try {
            if (previous) {
                q.put("cursor", older)
            }
        } catch (ignored: JSONException) {
        }
        reads.execute(Runnable {
            var result: JSONObject? = null
            try {
                result = transport.request(p, q)
            } catch (ignored: Exception) {
                try {
                    val status: JSONObject = transport.request(p, op("status"))
                    if (status.optBoolean("ok") && !status.optBoolean("nativeChat")) {
                        result = status
                    }
                } catch (unavailable: Exception) {
                }
            }
            val r: JSONObject? = result
            handler.post(Runnable {
                if (closed || g != generation) {
                    return@Runnable
                }
                loading = false
                olderLoading = false
                try {
                    if (previous && r != null && r.optBoolean("historyReset")) {
                        val cursor: Any = r.opt("older")
                        older = if (cursor is String) cursor as String else ""
                        historyExhausted = older.isEmpty()
                        view.rows(ArrayList<JSONObject?>(rows.values), !older.isEmpty())
                        view.olderState(false, tr("История обновилась. Загрузить предыдущие", "History refreshed. Load earlier"))
                        return@Runnable
                    }
                    if (previous && (r == null || !r.optBoolean("nativeChat"))) {
                        view.olderState(false, tr("Не удалось загрузить. Повторить", "Could not load. Retry"))
                        return@Runnable
                    }
                    if (r == null || !r.optBoolean("nativeChat")) {
                        if (r != null) {
                            resolved = true
                            nativeChat = false
                            connected = false
                            sync()
                        }
                        if (r == null) {
                            transportFailed = true
                            connected = false
                            connectionFailure()
                        } else {
                            view.connection("")
                        }
                        return@Runnable
                    }
                    codexAuthRequired = r.optBoolean("authRequired")
                    transportFailed = false
                    attachmentsSupported = r.optBoolean("attachmentsSupported")
                    connected = r.optBoolean("connected")
                    val actualSession: String = r.optString("session")
                    if ((p.geminiAgent || p.claudeAgent || p.environment.equals("codex") || p.aiAgent.equals("opencode")) && !actualSession.equals(nativeSession)) {
                        if (!nativeSession.isEmpty()) {
                            generation++
                            olderQueued = false
                            view.olderState(false, null)
                            actionNotice = ""
                            rows.clear()
                            older = ""
                            historyExhausted = false
                            requestStamp = ""
                            view.clear()
                            if (details != null) {
                                details!!.dismiss()
                            }
                            if (formDialog != null) {
                                formDialog!!.dismiss()
                            }
                            if (vertexDialog != null) {
                                vertexDialog!!.dismiss()
                            }
                        }
                        nativeSession = actualSession
                    }
                    val model: String? = AiAgentsUi.sessionModelLabel(p, r.optJSONObject("model"))
                    val modelChanged: Boolean = !model!!.equals(currentModel)
                    currentModel = model
                    val changed: Boolean = modelChanged || requiresTerminal != r.optBoolean("needsTerminal")
                    requiresTerminal = r.optBoolean("needsTerminal")
                    nativeChat = true
                    resolved = true
                    busy = r.optBoolean("busy")
                    directShell = r.optBoolean("directShell")
                    sync()
                    layoutChatKeys()
                    syncExtraKeys()
                    if (changed) {
                        visibilityChanged.run()
                    }
                    var known: MutableSet<String>? = null
                    if ((p.geminiAgent || p.claudeAgent || p.environment.equals("codex") || p.aiAgent.equals("opencode")) && r.has("knownMessages") && !r.optBoolean("partialHistory")) {
                        known = HashSet<String>()
                        val ids: JSONArray? = r.optJSONArray("knownMessages")
                        if (ids != null) {
                            var i = 0
                            while (i < ids.length()) {
                                known.add(ids.optString(i))
                                i++
                            }
                        }
                    }
                    val list: JSONArray? = r.optJSONArray("rows")
                    val updated = LinkedHashMap<String, JSONObject?>()
                    if (previous && list != null) {
                        var i = 0
                        while (i < list.length()) {
                            val row: JSONObject? = list.optJSONObject(i)
                            if (row != null && !rows.containsKey(row.optString("key"))) {
                                updated.put(row.optString("key"), row)
                            }
                            i++
                        }
                    }
                    if (previous) {
                        updated.putAll(rows)
                    } else {
                        val refreshed: MutableSet<String> = HashSet<String>()
                        if (list != null) {
                            var i = 0
                            while (i < list.length()) {
                                refreshed.add(list.optJSONObject(i).optString("message"))
                                i++
                            }
                        }
                        for (e in rows.entries) {
                            if ((known == null || known.contains(e.value!!.optString("message"))) && !refreshed.contains(e.value!!.optString("message")) && !e.value!!.optBoolean("queued")) {
                                updated.put(e.key, e.value)
                            }
                        }
                    }
                    if (!previous && list != null) {
                        var i = 0
                        while (i < list.length()) {
                            val row: JSONObject? = list.optJSONObject(i)
                            if (row != null) {
                                updated.put(row.optString("key"), row)
                            }
                            i++
                        }
                    }
                    rows.clear()
                    rows.putAll(updated)
                    if (previous || older.isEmpty() && !historyExhausted) {
                        val cursor: Any = r.opt("older")
                        older = if (cursor is String) cursor as String else ""
                        if (previous) {
                            historyExhausted = older.isEmpty()
                        }
                    }
                    connectionState(r)
                    if (previous) {
                        view.olderRows(ArrayList<JSONObject?>(rows.values), !older.isEmpty())
                        view.olderState(false, null)
                    } else {
                        view.rows(ArrayList<JSONObject?>(rows.values), !older.isEmpty())
                    }
                    requests(r.optJSONObject("permission"), r.optJSONArray("forms"), r.optBoolean("needsTerminal"))
                    if (p.environment.equals("codex") && codexLoginTerminal && !codexAuthRequired && connected && terminal != null) {
                        terminal!!.dismiss()
                    }
                    val thought: JSONObject? = rows.get(thinkingKey)
                    if (details != null && details!!.isShowing() && thinkingBody != null && thought != null && !thought.optBoolean("more") && thinkingBody!!.getSelectionStart() == thinkingBody!!.getSelectionEnd() && !thinkingBody!!.getText().toString().equals(thought.optString("body"))) {
                        val bottom: Boolean = thinkingBody!!.getHeight() - thinkingScroll!!.getHeight() - thinkingScroll!!.getScrollY() < view.dp(48)
                        thinkingBody!!.setText(thought.optString("body"))
                        if (bottom) {
                            val held: ScrollView? = thinkingScroll
                            held!!.post(Runnable { held.fullScroll(View.FOCUS_DOWN) })
                        }
                    }
                } finally {
                    if (!closed && g == generation && olderQueued) {
                        olderQueued = false
                        if (selected() && !older.isEmpty()) {
                            load(true)
                        } else {
                            view.olderState(false, null)
                        }
                    }
                }
            })
        })
    }

    private fun retryConnection() {
        if (!loading) {
            load(false)
        }
    }

    private fun connectionState(r: JSONObject) {
        if (current.get()!!.environment.equals("codex") && r.optBoolean("authRequired")) {
            view.connection(if (r.optBoolean("loginPending")) tr("Вход ожидает подтверждения в браузере. Нажмите, чтобы открыть окно входа.", "Sign-in awaits browser confirmation. Tap to open sign-in.") else if (r.optBoolean("loginError")) tr("Вход не завершён. Нажмите, чтобы повторить вход в Codex.", "Sign-in did not complete. Tap to retry Codex sign-in.") else tr("Войти в Codex — нажмите для подключения аккаунта.", "Sign in to Codex — tap to connect your account."), Runnable(this::terminal))
            return
        }
        if (current.get()!!.geminiAgent && current.get()!!.geminiAuth.equals("api") && r.optBoolean("authError")) {
            view.connection(tr("Не удалось войти в Gemini. Проверьте API-ключ в настройках сессии/пресета и перезапустите её. Нажмите для просмотра терминала.", "Could not sign in to Gemini. Check the API key in session/preset settings and restart the session. Tap to inspect the terminal."), Runnable(this::terminal))
            return
        }
        if (r.optBoolean("needsTerminal")) {
            if ((current.get()!!.claudeAgent || current.get()!!.environment.equals("codex")) && !r.optString("connectionNotice").isEmpty()) {
                val notice: String = r.optString("connectionNotice")
                view.connection(if (notice.length > 600) notice.substring(0, 600) else notice)
                return
            }
            view.connection(tr("Требуется действие в терминале. Нажмите кнопку робота сверху.", "An action is needed in the terminal. Tap the robot button above."))
            return
        }
        if (!actionNotice.isEmpty()) {
            view.connection(actionNotice)
            return
        }
        if (!r.optBoolean("connected")) {
            view.connection(tr("Подключаем чат к сессии. Если ожидание затянулось, проверьте запуск через кнопку робота.", "Connecting chat to the session. If this persists, check startup using the robot button."), Runnable(this::retryConnection))
            return
        }
        view.connection(if (rows.isEmpty()) tr("Подключено к сессии ", "Connected to the ") + name() + tr(". Напишите сообщение, чтобы начать.", " session. Send a message to begin.") else "")
    }

    private fun older() {
        if (older.isEmpty() || olderLoading || olderQueued) {
            return
        }
        view.olderState(true, null)
        if (loading) {
            olderQueued = true
        } else {
            load(true)
        }
    }

    fun send(text: String, accepted: Runnable) {
        if (!showing()) {
            if (selected() && !resolved) {
                view.connection(tr("Подключение ещё не установлено. Текст сохранён во вводе.", "Not connected yet. Your text was kept in the input field."))
            }
            return
        }
        submit(text, "message", accepted)
    }

    private fun submit(text: String, kind: String, accepted: Runnable) {
        if (!showing()) {
            return
        }
        if (kind.equals("message") && attachments.busy()) {
            Toast.makeText(activity, tr("Дождитесь подготовки вложения.", "Wait for the attachment to finish preparing."), Toast.LENGTH_SHORT).show()
            return
        }
        val refs: JSONArray = if (kind.equals("message")) attachments.references() else JSONArray()
        val attachmentOwner: String = owner
        if (refs.length() > 0 && !attachmentsSupported) {
            Toast.makeText(activity, tr("Эта сессия ещё не поддерживает вложения. Перезапустите её с сохранением чата.", "This session does not support attachments yet. Restart it with the existing chat."), Toast.LENGTH_LONG).show()
            return
        }
        val p: WorkspaceStore.Session = snapshot()
        val k: String = owner + ":" + (if (p.geminiAgent || p.claudeAgent || p.environment.equals("codex") || p.aiAgent.equals("opencode")) nativeSession + ":" else "") + kind + ":" + text + ":" + refs
        var id: String? = sendIDs.get(k)
        if (id == null) {
            id = "msg_" + (UUID.randomUUID().toString() as java.lang.String).replace("-", "")
            sendIDs.put(k, id)
        }
        val q: JSONObject = op("chat-send")
        try {
            q.put("text", text).put("kind", kind).put("id", id)
            if (refs.length() > 0) {
                q.put("attachments", refs)
            }
            if (p.geminiAgent || p.claudeAgent || p.environment.equals("codex") || p.aiAgent.equals("opencode")) {
                q.put("session", nativeSession)
            }
        } catch (ignored: JSONException) {
        }
        if (refs.length() > 0) {
            attachments.submitted(refs)
        }
        operation(q, Runnable {
            sendIDs.remove(k)
            if (refs.length() > 0) {
                attachments.accepted(attachmentOwner, refs)
            }
            view.followAfterSend()
            accepted.run()
        })
    }

    private fun operation(q: JSONObject, success: Runnable?) {
        operation(q, success, null)
    }

    private fun operation(q: JSONObject, success: Runnable?, failure: Runnable?) {
        if (!selected()) {
            return
        }
        val p: WorkspaceStore.Session = snapshot()
        val g: Int = generation
        actions.execute(Runnable {
            if (closed || g != generation) {
                return@Runnable
            }
            var ok = false
            try {
                val r: JSONObject = transport.request(p, q)
                ok = r.optBoolean("ok")
            } catch (ignored: Exception) {
            }
            val done: Boolean = ok
            handler.post(Runnable {
                if (closed || g != generation) {
                    return@Runnable
                }
                if (done) {
                    actionNotice = ""
                    if (success != null) {
                        success.run()
                    }
                    requestStamp = ""
                    if (!loading) {
                        load(false)
                    }
                } else {
                    actionNotice = tr("Агент не подтвердил действие: он может запускаться, ожидать выбора в терминале или быть недоступен. Текст остался в поле ввода.\nПроверьте терминал кнопкой робота. Перед повтором убедитесь, что действие не выполнено; сообщение можно отправить ещё раз.", "The agent did not confirm the action: it may be starting, waiting for a terminal choice or unavailable. Your text remains in the input field.\nCheck the robot terminal. Before retrying, check that the action did not run; you can send the message again.")
                    view.connection(actionNotice)
                    if (failure != null) {
                        failure.run()
                    }
                }
            })
        })
    }

    private fun details(row: JSONObject) {
        if (!selected()) {
            return
        }
        if (row.optString("kind").equals("attachment") && row.optJSONObject("attachment") != null) {
            attachments.open(row.optJSONObject("attachment"))
            return
        }
        if (details != null) {
            details!!.dismiss()
        }
        thinkingBody = null
        thinkingScroll = null
        thinkingKey = ""
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val body: TextView = view.label(row.optString("body"), 14)
        body.setTextIsSelectable(true)
        if ((row.optString("toolType").equals("contextCompaction") || row.optString("part").equals("context")) && !row.optBoolean("more")) {
            body.setPadding(view.dp(20), view.dp(14), view.dp(20), view.dp(18))
            details = WorkspaceDialogs.builder(activity).setTitle(CodexActionLabels.title(row, WorkspaceLanguage.code(activity)!!.equals("ru"))).setView(body).create()
            details!!.setCanceledOnTouchOutside(true)
            details!!.show()
            val width: Int = Math.min(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.94f), Math.max(view.dp(240), Math.round(body.getPaint().measureText(row.optString("body"))) + view.dp(64)))
            details!!.getWindow()!!.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            return
        }
        val markdownBody: AgentMarkdownView? = if (row.optString("kind").equals("text")) AgentMarkdownView(activity) else null
        if (markdownBody != null) {
            markdownBody.update(name(), row.optString("body"), null)
        }
        val scroll = ScrollView(activity)
        scroll.addView(if (markdownBody == null) body else markdownBody)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.0f))
        val next: TextView = view.pill(tr("Загрузить полный текст", "Load full text"), Runnable {  })
        box.addView(next)
        next.setVisibility(if (row.optBoolean("more")) View.VISIBLE else View.GONE)
        details = WorkspaceDialogs.builder(activity).setTitle(if (row.optString("kind").equals("reasoning")) tr("Рассуждение", "Thinking") else CodexActionLabels.title(row, WorkspaceLanguage.code(activity)!!.equals("ru"))).setView(box).setPositiveButton(tr("Назад", "Back"), null).create()
        details!!.show()
        details!!.getWindow()!!.setLayout(-1, Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.72f))
        if (row.optString("kind").equals("reasoning")) {
            thinkingBody = body
            thinkingScroll = scroll
            thinkingKey = row.optString("key")
        }
        val dialog: AlertDialog? = details
        val offset: IntArray = intArrayOf(0)
        val g: Int = generation
        val p: WorkspaceStore.Session = snapshot()
        val page = arrayOfNulls<Runnable>(1)
        page[0] = Runnable {
            next.setEnabled(false)
            val q: JSONObject = op("chat-detail")
            try {
                if (p.geminiAgent || p.claudeAgent || p.environment.equals("codex") || p.aiAgent.equals("opencode")) {
                    q.put("session", nativeSession)
                }
                q.put("message", row.optString("message")).put("part", row.optString("part")).put("offset", offset[0])
            } catch (ignored: JSONException) {
            }
            reads.execute(Runnable {
                var r: JSONObject? = null
                try {
                    r = transport.request(p, q)
                } catch (ignored: Exception) {
                }
                val result: JSONObject? = r
                handler.post(Runnable {
                    if (closed || g != generation || !dialog!!.isShowing()) {
                        return@Runnable
                    }
                    next.setEnabled(true)
                    if (result == null) {
                        next.setVisibility(View.VISIBLE)
                        next.setText(tr("Повторить загрузку", "Retry loading"))
                        return@Runnable
                    }
                    if (offset[0] == 0) {
                        body.setText((if (row.optString("kind").equals("receipt")) tr("Разрешение не означает успешного выполнения.\n\n", "Permission does not mean successful execution.\n\n") else "") + result.optString("body"))
                    } else {
                        body.append(result.optString("body"))
                    }
                    if (markdownBody != null) {
                        markdownBody.update(name(), body.getText().toString(), null)
                    }
                    offset[0] = if (result.isNull("next")) -1 else result.optInt("next", -1)
                    next.setVisibility(if (offset[0] < 0) View.GONE else View.VISIBLE)
                    next.setText(tr("Далее", "More"))
                })
            })
        }
        next.setOnClickListener(View.OnClickListener { v -> page[0]!!.run() })
        page[0]!!.run()
    }

    private fun requests(permission: JSONObject?, forms: JSONArray?, needsTerminal: Boolean) {
        val stamp: String = java.lang.String.valueOf(permission) + java.lang.String.valueOf(forms) + needsTerminal
        if (stamp.equals(requestStamp)) {
            return
        }
        requestStamp = stamp
        val bottom: Boolean = view.timeline.getHeight() - view.scroll.getHeight() - view.scroll.getScrollY() < view.dp(48)
        view.clearRequests()
        if (permission != null) {
            val title: TextView = view.label(permission.optString("title") + "\n" + permission.optString("body"), 13)
            title.setMaxLines(5)
            title.setOnClickListener(View.OnClickListener { v ->
                if (details != null) {
                    details!!.dismiss()
                }
                details = WorkspaceDialogs.builder(activity).setMessage(permission.optString("body")).setPositiveButton(tr("Назад", "Back"), null).show()
            })
            view.requests.addView(title)
            val buttons = LinearLayout(activity)
            for (outcome in arrayOf<String>("proceed_once", "cancel")) {
                buttons.addView(view.pill(if (outcome.equals("cancel")) tr("Нет", "No") else tr("Да, разово", "Yes, once"), Runnable {
                    val q: JSONObject = op("answer")
                    try {
                        q.put("id", permission.optString("id")).put("outcome", outcome)
                        if (current.get()!!.claudeAgent || current.get()!!.environment.equals("codex")) {
                            q.put("session", nativeSession).put("nonce", permission.optString("nonce"))
                        }
                    } catch (ignored: JSONException) {
                    }
                    var i = 0
                    while (i < buttons.getChildCount()) {
                        buttons.getChildAt(i).setEnabled(false)
                        i++
                    }
                    operation(q, Runnable { view.requests.removeAllViews() }, Runnable {
                        if (buttons.getParent() === view.requests) {
                            var i = 0
                            while (i < buttons.getChildCount()) {
                                buttons.getChildAt(i).setEnabled(true)
                                i++
                            }
                        }
                    })
                }))
            }
            view.requests.addView(buttons)
        }
        if (needsTerminal) {
            view.requests.addView(view.label(tr("Завершите действие в терминале или нажмите «Вернуться в чат ", "Finish the action in the terminal or tap Return to ") + name() + tr("».", " chat."), 13))
            view.requests.addView(view.pill(tr("Открыть терминал ›", "Open terminal ›"), Runnable(this::terminal)))
            view.requests.addView(view.pill(returnChatLabel(), Runnable(this::returnAgentChat)))
        }
        if (forms != null) {
            var i = 0
            while (i < forms.length()) {
                val f: JSONObject? = forms.optJSONObject(i)
                if (f != null) {
                    view.requests.addView(view.pill(f.optString("title") + " ▾", Runnable { form(f) }))
                }
                i++
            }
        }
        view.scaleContent()
        if (bottom) {
            view.bottomAfterLayout()
        }
    }

    private fun form(f: JSONObject) {
        val g: Int = generation
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val fields: JSONArray? = f.optJSONArray("fields")
        val inputs: MutableMap<String, Supplier<Any>> = LinkedHashMap<String, Supplier<Any>>()
        var simple: Boolean = fields != null
        if (fields != null) {
            var i = 0
            while (i < fields.length()) {
                val field: JSONObject? = fields.optJSONObject(i)
                if (field == null) {
                    i++
                    continue
                }
                val type: String = field.optString("type")
                if (field.has("when") || field.optBoolean("hidden") || type.equals("multiselect") && field.optBoolean("custom") || !Arrays.asList("string", "boolean", "number", "integer", "multiselect").contains(type)) {
                    simple = false
                    i++
                    continue
                }
                val key: String = field.optString("key")
                box.addView(view.label(field.optString("title", key), 14))
                if (field.has("description")) {
                    box.addView(view.label(field.optString("description"), 12))
                }
                if (type.equals("boolean")) {
                    val check = CheckBox(activity)
                    check.setTextColor(Pal.TEXT2)
                    check.setText(tr("Да", "Yes"))
                    check.setChecked(field.optBoolean("default"))
                    box.addView(check)
                    inputs.put(key, Supplier<Any>(check::isChecked))
                    i++
                    continue
                }
                val options: JSONArray? = field.optJSONArray("options")
                if (type.equals("multiselect")) {
                    val checks: MutableList<CheckBox> = ArrayList<CheckBox>()
                    val optionValues: MutableList<String> = ArrayList<String>()
                    if (options != null) {
                        var j = 0
                        while (j < options.length()) {
                            val option: JSONObject? = options.optJSONObject(j)
                            if (option == null) {
                                j++
                                continue
                            }
                            val check = CheckBox(activity)
                            check.setTextColor(Pal.TEXT2)
                            check.setText(option.optString("label"))
                            check.setSaveEnabled(false)
                            box.addView(check)
                            checks.add(check)
                            optionValues.add(option.optString("value"))
                            j++
                        }
                    }
                    inputs.put(key, Supplier<Any> {
                        val selected = JSONArray()
                        var j = 0
                        while (j < checks.size) {
                            if (checks.get(j).isChecked()) {
                                selected.put(optionValues.get(j))
                            }
                            j++
                        }
                        return@Supplier selected
                    })
                    i++
                    continue
                }
                if (type.equals("string") && options != null) {
                    val labels: MutableList<String> = ArrayList<String>()
                    val optionValues: MutableList<String> = ArrayList<String>()
                    var j = 0
                    while (j < options.length()) {
                        val option: JSONObject? = options.optJSONObject(j)
                        if (option != null) {
                            labels.add(option.optString("label"))
                            optionValues.add(option.optString("value"))
                        }
                        j++
                    }
                    if (labels.isEmpty()) {
                        simple = false
                        i++
                        continue
                    }
                    val pick = Spinner(activity)
                    pick.setAdapter(ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, labels))
                    val initial: Int = optionValues.indexOf(field.optString("default"))
                    if (initial >= 0) {
                        pick.setSelection(initial)
                    }
                    box.addView(pick)
                    val custom = EditText(activity)
                    custom.setTextColor(Pal.TEXT2)
                    custom.setSaveEnabled(false)
                    custom.setHint(tr("Свой ответ (необязательно)", "Custom answer (optional)"))
                    if (field.optBoolean("custom")) {
                        box.addView(custom)
                    }
                    inputs.put(key, Supplier<Any> {
                        if (custom.length() > 0) custom.getText().toString() else optionValues.get(pick.getSelectedItemPosition())
                    })
                    i++
                    continue
                }
                val input = EditText(activity)
                input.setTextColor(Pal.TEXT2)
                input.setSaveEnabled(false)
                input.setText(field.optString("default"))
                if (!type.equals("string")) {
                    input.setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or (if (type.equals("number")) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0))
                }
                box.addView(input)
                inputs.put(key, Supplier<Any> {
                    if (type.equals("string")) input.getText().toString() else if (type.equals("integer")) java.lang.Long.valueOf(input.getText().toString()).toDouble() else java.lang.Double.valueOf(input.getText().toString())
                })
                i++
            }
        }
        if (!simple) {
            formDialog = WorkspaceDialogs.builder(activity).setTitle(f.optString("title")).setMessage(tr("Этот запрос требует штатного интерфейса ", "This request needs the native interface: ") + name()).setPositiveButton(tr("Открыть терминал", "Open terminal"), DialogInterface.OnClickListener { d, w ->
                if (g == generation) {
                    terminal()
                }
            }).setNegativeButton(tr("Назад", "Back"), null).show()
            return
        }
        val scroll = ScrollView(activity)
        scroll.addView(box)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(f.optString("title")).setView(scroll).setPositiveButton(tr("Ответить", "Reply"), null).setNegativeButton(tr("Назад", "Back"), null).create()
        formDialog = dialog
        dialog.show()
        dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            if (g != generation) {
                dialog.dismiss()
                return@OnClickListener
            }
            val q: JSONObject = op("chat-form")
            val answer = JSONObject()
            try {
                for (e in inputs.entries) {
                    answer.put(e.key, e.value.get())
                }
                q.put("id", f.optString("id")).put("nonce", f.optString("nonce")).put("answer", answer)
                if (current.get()!!.claudeAgent || current.get()!!.environment.equals("codex") || current.get()!!.aiAgent.equals("opencode")) {
                    q.put("session", nativeSession)
                }
            } catch (invalid: Exception) {
                Toast.makeText(activity, tr("Проверьте заполненные поля.", "Check the entered values."), Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }
            operation(q, Runnable(dialog::dismiss))
        })
    }

    private fun createTerminalKeys(): ExtraKeysView {
        val keys: ExtraKeysView = LayoutInflater.from(activity).inflate(R.layout.view_terminal_toolbar_extra_keys, null, false) as ExtraKeysView
        keys.setExtraKeysViewClient(TermuxTerminalExtraKeys(activity.getTerminalView(), activity.getTermuxTerminalViewClient(), activity.getTermuxTerminalSessionClient()))
        var info: ExtraKeysInfo? = if (activity.getProperties() == null) null else activity.getProperties()!!.getExtraKeysInfo()
        if (info == null || info.getMatrix().size == 0) {
            try {
                info = ExtraKeysInfo("[['ESC','/','-','HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]", "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES)
            } catch (impossible: JSONException) {
                throw IllegalStateException(impossible)
            }
        }
        keys.setButtonTextAllCaps(activity.getProperties() == null || activity.getProperties()!!.shouldExtraKeysTextBeAllCaps())
        keys.reload(info)
        return keys
    }

    private fun returnChatLabel(): String {
        return tr("Вернуться в чат ", "Return to ") + name() + tr("", " chat")
    }

    private fun returnAgentChat() {
        if (!selected()) {
            return
        }
        val profile: WorkspaceStore.Session = snapshot()
        val g: Int = generation
        val opened: AlertDialog? = terminal
        val command: String = if (profile.environment.equals("codex") || profile.claudeAgent) "terminal-exit" else "terminal-close"
        actions.execute(Runnable {
            if (closed || g != generation) {
                return@Runnable
            }
            var reply: JSONObject? = null
            try {
                reply = transport.request(profile, op(command))
            } catch (ignored: Exception) {
            }
            val result: JSONObject? = reply
            handler.post(Runnable {
                if (closed || g != generation) {
                    return@Runnable
                }
                if (result != null && result.optBoolean("ok")) {
                    actionNotice = ""
                    if (opened != null && terminal === opened) {
                        opened.dismiss()
                    }
                    requestStamp = ""
                    if (!loading) {
                        load(false)
                    }
                } else {
                    val notice: String = if (result != null && result.optString("error").equals("NATIVE_BUSY")) tr("Агент выполняет запрос. Дождитесь завершения и нажмите ещё раз.", "The agent is running a request. Wait for completion and tap again.") else tr("Не удалось вернуть управление чату. Завершите действие в терминале и повторите.", "Could not return control to chat. Finish the terminal action and retry.")
                    if (opened != null && terminal === opened) {
                        activity.showToast(notice, true)
                    } else {
                        view.connection(notice)
                    }
                }
            })
        })
    }

    fun terminal() {
        if (!selected() || terminal != null) {
            return
        }
        sync()
        val terminalOwner: WorkspaceStore.Session = snapshot()
        codexLoginTerminal = terminalOwner.environment.equals("codex") && codexAuthRequired
        val terminalGeneration: Int = generation
        restoreChatKeys()
        val session: TerminalSession? = activity.getCurrentSession()
        val keyboardBefore: Boolean = activity.getTerminalView()!!.isSoftKeyboardInputEnabled()
        activity.getTerminalView()!!.setSoftKeyboardInputEnabled(true)
        val tv: View? = activity.getTerminalView()
        terminalParent = tv!!.getParent() as ViewGroup?
        terminalParams = tv.getLayoutParams()
        terminalParent!!.removeView(tv)
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val terminalNotice: TextView = view.label(tr("Подключение терминала…", "Connecting terminal…"), 12)
        box.addView(terminalNotice, LinearLayout.LayoutParams(-1, -2))
        box.addView(tv, LinearLayout.LayoutParams(-1, 0, 1.0f))
        val originalKeys: ExtraKeysView? = activity.getExtraKeysView()
        val createdKeys: Boolean = originalKeys == null || originalKeys.getChildCount() == 0
        val keys: ExtraKeysView? = if (createdKeys) createTerminalKeys() else originalKeys
        if (createdKeys) {
            activity.setExtraKeysView(keys)
        }
        val keysParent: ViewGroup? = keys!!.getParent() as ViewGroup?
        val keysParams: ViewGroup.LayoutParams? = if (keys == null) null else keys.getLayoutParams()
        val keysIndex: Int = if (keysParent == null) 0 else keysParent.indexOfChild(keys)
        val keysVisibility: Int = if (keys == null) View.GONE else keys.getVisibility()
        if (keys != null) {
            if (keysParent != null) {
                keysParent.removeView(keys)
            }
            keys.setVisibility(View.VISIBLE)
            val rows: Int = keys.getRowCount()
            box.addView(keys, LinearLayout.LayoutParams(-1, view.dp(Math.max(1, rows) * 40)))
        }
        val returnChat: TextView = view.pill(returnChatLabel(), Runnable(this::returnAgentChat))
        returnChat.setGravity(Gravity.CENTER)
        box.addView(returnChat, LinearLayout.LayoutParams(-1, -2))
        terminal = WorkspaceDialogs.builder(activity).setTitle(name() + " · " + tr("Терминал", "Terminal")).setView(box).setNeutralButton(tr("Свернуть", "Minimize"), null).setPositiveButton("Enter", null).create()
        terminal!!.setCanceledOnTouchOutside(false)
        terminal!!.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            val parent: ViewGroup? = tv.getParent() as ViewGroup?
            if (parent != null) {
                parent.removeView(tv)
            }
            terminalParent!!.addView(tv, 0, terminalParams)
            if (keys != null) {
                val kp: ViewGroup? = keys.getParent() as ViewGroup?
                if (kp != null) {
                    kp.removeView(keys)
                }
                if (keysParent != null) {
                    keysParent.addView(keys, Math.min(keysIndex, keysParent.getChildCount()), keysParams)
                }
                keys.setVisibility(keysVisibility)
                if (createdKeys && activity.getExtraKeysView() === keys) {
                    activity.setExtraKeysView(originalKeys)
                }
            }
            activity.getTerminalView()!!.setSoftKeyboardInputEnabled(keyboardBefore)
            terminal = null
            codexLoginTerminal = false
            activity.getTerminalView()!!.setWorkspaceTerminalVisible(false)
            actions.execute(Runnable {
                try {
                    transport.request(terminalOwner, op("terminal-close"))
                } catch (ignored: Exception) {
                }
            })
            sync()
        })
        terminal!!.show()
        terminal!!.getWindow()!!.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        terminal!!.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
            if (activity.getCurrentSession() === session && session != null && session.isRunning()) {
                session.write("\r")
            }
        })
        terminal!!.getWindow()!!.setLayout(Math.round(activity.getResources().getDisplayMetrics().widthPixels * 0.92f), Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.62f))
        terminal!!.getWindow()!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tv.setVisibility(View.VISIBLE)
        activity.getTerminalView()!!.setWorkspaceTerminalVisible(true)
        tv.requestFocus()
        view.setVisibility(View.GONE)
        applyPresentationScale()
        syncExtraKeys()
        visibilityChanged.run()
        val opened: AlertDialog? = terminal
        actions.execute(Runnable {
            if (closed || terminalGeneration != generation) {
                return@Runnable
            }
            var ok = false
            var notice = ""
            try {
                val response: JSONObject = transport.request(terminalOwner, op("terminal-open"))
                ok = response.optBoolean("ok") && response.optBoolean("terminalReady", true)
                notice = response.optString("terminalNotice")
            } catch (ignored: Exception) {
            }
            val ready: Boolean = ok
            val explanation: String = if (notice.length > 600) notice.substring(0, 600) else notice
            handler.post(Runnable {
                if (closed || terminalGeneration != generation || terminal !== opened || !opened!!.isShowing()) {
                    return@Runnable
                }
                val nativeHint: String = if (terminalOwner.environment.equals("codex")) tr("«Свернуть» только скрывает окно. «Вернуться в чат Codex» возвращает управление чату. Выполняющийся запрос продолжится до завершения. Shell-поле выполняет вашу команду напрямую с правами Ubuntu, без обращения к модели.", "Minimize only hides this window. Return to Codex chat returns control to the chat. A running request continues until completion. The Shell field executes your command directly with Ubuntu privileges, without model inference.") else if (terminalOwner.claudeAgent) tr("После входа чат подключится автоматически. «Свернуть» только скрывает окно. Чтобы завершить штатный интерфейс, нажмите «Вернуться в чат Claude» после окончания запроса.", "After sign-in, chat reconnects automatically. Minimize only hides this window. To exit the native interface, tap Return to Claude chat after the request finishes.") else ""
                terminalNotice.setText(if (ready) nativeHint else if (!explanation.isEmpty()) explanation else tr("Канал управления пока недоступен. Ниже — терминал текущей сессии: проверьте запуск агента или вход.", "The control channel is not available yet. The current session terminal is shown below: check agent startup or sign-in."))
                terminalNotice.setVisibility(if (ready && nativeHint.isEmpty()) View.GONE else View.VISIBLE)
            })
        })
    }

    fun close() {
        closed = true
        applyPresentationScale()
        attachments.close()
        cache.clear()
        restoreChatKeys()
        syncExtraKeys()
        generation++
        handler.removeCallbacks(poll)
        if (terminal != null) {
            terminal!!.dismiss()
        }
        if (details != null) {
            details!!.dismiss()
        }
        if (formDialog != null) {
            formDialog!!.dismiss()
        }
        if (vertexDialog != null) {
            vertexDialog!!.dismiss()
        }
        reads.shutdownNow()
        actions.shutdown()
        surface!!.removeView(view)
    }

    companion object {
        @JvmStatic
        fun op(name: String): JSONObject {
            val q = JSONObject()
            try {
                q.put("op", name)
            } catch (impossible: JSONException) {
                throw IllegalStateException(impossible)
            }
            return q
        }
    }
}
