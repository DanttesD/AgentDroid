package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.IntPredicate
import java.util.function.Supplier
import org.json.JSONObject

/** Gemini-only local polling: updates the chip text, never the terminal view or input.*/
class GeminiLiveUi {
    private val activity: TermuxActivity
    private lateinit var current: Supplier<WorkspaceStore.Session?>
    private lateinit var visible: BooleanSupplier
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val reader: ExecutorService = Executors.newSingleThreadExecutor()
    private val control: ExecutorService = Executors.newSingleThreadExecutor()

    fun interface ModeApplied {
        fun save(profile: WorkspaceStore.Session, run: String, before: String, mode: String): Boolean
    }

    private val modeApplied: ModeApplied
    private lateinit var permissions: GeminiPermissionUi
    private var permissionAllowed: BooleanSupplier = BooleanSupplier { true }

    fun permissionVisibility(allowed: BooleanSupplier) {
        permissionAllowed = allowed
        permissions.syncVisibility()
    }

    private class PendingMode {
        @JvmField val profile: WorkspaceStore.Session
        @JvmField val id: String
        @JvmField val run: String
        @JvmField val before: String
        @JvmField val mode: String
        @JvmField val created: Long = SystemClock.uptimeMillis()
        @JvmField var unavailable: Int = 0

        constructor(p: WorkspaceStore.Session, id: String, mode: String) {
            this.profile = p
            this.id = id
            this.mode = mode
            run = p.geminiSocketId
            before = p.geminiMode
        }
    }

    private val pendingModes: MutableMap<String, PendingMode> = HashMap<String, PendingMode>()
    private val loadingRuns: MutableSet<String> = HashSet<String>()
    private var thinkingApplied: BiConsumer<WorkspaceStore.Session, String> = BiConsumer<WorkspaceStore.Session, String> { p, v ->  }

    fun thinkingApplied(save: BiConsumer<WorkspaceStore.Session, String>) {
        thinkingApplied = save
    }

    private var closed: Boolean = false
    private var run: String = ""
    private var status: GeminiLiveStatus? = null
    private var chip: TextView? = null
    private var detailsDialog: AlertDialog? = null
    private var detailsModel: TextView? = null
    private var detailsMode: TextView? = null
    private var detailsThinking: TextView? = null
    private var detailsInfo: TextView? = null
    private var detailsRun: String = ""
    private var chatPreview: Runnable = Runnable {  }
    private var chatSave: Runnable = Runnable {  }

    fun chatPresentation(preview: Runnable, save: Runnable) {
        chatPreview = preview
        chatSave = save
    }

    constructor(activity: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier) : this(activity, current, visible, ModeApplied { p, run, before, mode -> true })

    constructor(activity: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier, modeApplied: ModeApplied) {
        this.activity = activity
        this.current = current
        this.visible = visible
        this.modeApplied = modeApplied
        permissions = GeminiPermissionUi(activity, current, BooleanSupplier { visible.getAsBoolean() && permissionAllowed.getAsBoolean() })
        handler.post(poll)
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (closed) {
                return
            }
            permissions.syncVisibility()
            val p: WorkspaceStore.Session? = current.get()
            if (visible.getAsBoolean() && p != null) {
                load(p)
            }
            for (pending in ArrayList<PendingMode>(pendingModes.values)) {
                if (!pending.run.equals(pending.profile.geminiSocketId)) {
                    pendingModes.remove(pending.run)
                } else {
                    load(pending.profile)
                }
            }
            handler.postDelayed(this, 1500L)
        }
    }

    private fun owns(captured: String): Boolean {
        val p: WorkspaceStore.Session? = current.get()
        return !closed && p != null && captured.equals(p.geminiSocketId)
    }

    fun render(parent: LinearLayout?, p: WorkspaceStore.Session) {
        if (!run.equals(p.geminiSocketId)) {
            run = p.geminiSocketId
            status = null
        }
        chip = AgentStatusUi.chip(activity, "", tr("Статус, модель и права Gemini", "Gemini status, model and permissions"), Runnable { details(p) })
        AgentStatusUi.attach(parent, chip)
        update()
        load(p)
    }

    private fun load(profile: WorkspaceStore.Session) {
        if (closed) {
            return
        }
        val captured: String = profile.geminiSocketId
        if (!loadingRuns.add(captured)) {
            return
        }
        val snapshot = WorkspaceStore.Session()
        snapshot.geminiAgent = true
        snapshot.geminiSocketId = captured
        reader.execute(Runnable {
            val value: GeminiLiveStatus = GeminiLiveStatus.read(activity.getFilesDir(), snapshot)
            handler.post(Runnable {
                loadingRuns.remove(captured)
                if (closed) {
                    return@Runnable
                }
                reconcileMode(captured, value)
                if (!owns(captured)) {
                    return@Runnable
                }
                status = value
                update()
            })
        })
    }

    private fun state(): String {
        if (status == null) {
            return tr("подключение…", "connecting…")
        }
        if (!status!!.available) {
            return tr("нет подключения", "disconnected")
        }
        when (status!!.state) {
            "idle" -> {
                return tr("готов", "ready")
            }
            "responding" -> {
                return tr("отвечает", "responding")
            }
            "waiting_for_confirmation" -> {
                return tr("ожидает подтверждения", "awaiting confirmation")
            }
            else -> {
                return tr("нет подключения", "disconnected")
            }
        }
    }

    fun syncPermissionVisibility() {
        permissions.syncVisibility()
    }

    private fun update() {
        val active: WorkspaceStore.Session? = current.get()
        if (active != null) {
            permissions.update(active, status)
        } else {
            permissions.syncVisibility()
        }
        if (chip == null) {
            return
        }
        val text: String = "Gemini · " + (if (status == null || !status!!.available) tr("нет данных", "unavailable") else status!!.model) + "\n" + state() + (if (status != null && status!!.available) " · " + GeminiRightsUi.label(status!!.mode) else "") + (if (status != null && status!!.changeState.equals("queued")) tr(" · смена модели ожидает", " · model change queued") else "") + " ›"
        if (!(text as java.lang.String).contentEquals(chip!!.getText())) {
            chip!!.setText(text)
        }
        if (detailsDialog != null && detailsDialog!!.isShowing()) {
            if (!owns(detailsRun)) {
                detailsDialog!!.dismiss()
                return
            }
            val profile: WorkspaceStore.Session? = current.get()
            val available: Boolean = status != null && status!!.available
            detailsModel!!.setText(tr("Модель: ", "Model: ") + (if (available) status!!.model else tr("нет данных", "unavailable")) + " ›")
            AgentStatusUi.available(detailsModel, available)
            detailsMode!!.setText(tr("Режим прав: ", "Permissions: ") + (if (available) GeminiRightsUi.label(status!!.mode) else tr("нет данных", "unavailable")) + " ›")
            AgentStatusUi.available(detailsMode, available && status!!.canChangeMode)
            detailsThinking!!.setText(tr("Рассуждения: ", "Reasoning: ") + (if (available) if (status!!.thinkingEffective) AgentStatusUi.level(activity, status!!.thinking) else tr("По умолчанию CLI", "CLI default") else tr("нет данных", "unavailable")) + " ›")
            AgentStatusUi.available(detailsThinking, available && status!!.canChangeThinking && status!!.state.equals("idle"))
            val latest: String = detailsSummary() + (if (available && !status!!.canChangeThinking) "\n\n" + tr("Выбор рассуждений для этой модели пока недоступен. Используются настройки CLI.", "Reasoning selection is unavailable for this model. CLI settings are used.") else "")
            if (!(latest as java.lang.String).contentEquals(detailsInfo!!.getText())) {
                detailsInfo!!.setText(latest)
            }
        }
    }

    private fun detailsMessage(s: GeminiLiveStatus?): String {
        var message: String = if (s == null || !s.available) tr("Нет подключения к мосту Gemini. Для старой запущенной сессии потребуется новый запуск. Авторизация выполняется в терминале. Несовместимый CLI продолжает работать без карточки.", "No Gemini bridge connection. An older running session needs a new launch. Authenticate in the terminal. An unsupported CLI continues without the card.") else tr("Модель: ", "Model: ") + s.model + "\n" + tr("Выбор CLI: ", "CLI selection: ") + s.selectedModel + "\n" + tr("Режим прав: ", "Permission mode: ") + GeminiRightsUi.label(s.mode) + (if (s.canChangeMode) " ▾" else "") + "\n" + tr("Состояние: ", "State: ") + state() + "\n\n" + tr("Расход за текущий запуск CLI, включая служебные запросы:", "Usage in the current CLI launch, including internal requests:") + "\n" + tr("Запросы: ", "Requests: ") + s.requests + "\n" + tr("Токены всего (по данным API): ", "Total tokens (reported by API): ") + (if (s.total > 0 || s.requests == 0L) java.lang.Long.toString(s.total) else "—") + "\n" + tr("Вход / ответ: ", "Input / answer: ") + s.input + " / " + s.output + "\n" + tr("Из кэша / рассуждения: ", "Cached / thinking: ") + s.cache + " / " + s.thoughts + "\n" + tr("Время запуска: ", "Launch duration: ") + Math.max(0L, (System.currentTimeMillis() - s.startedAt) / 1000) + tr(" с\n", " s\n") + tr("Суммарное время API: ", "Total API time: ") + s.apiMs / 1000.0 + tr(" с", " s") + "\n\n" + tr("Кэш — часть входных токенов. Всего — счётчик API, без повторного сложения частей. Это не общий расход аккаунта и не размер контекста. Остаток квоты недоступен.", "Cached tokens are part of the input. Total is the API counter, without adding subsets twice. This is not account-wide usage or context size. Remaining quota is unavailable.")
        if (s != null && !s.changeState.isEmpty()) {
            message += "\n\n" + tr("Смена модели: ", "Model change: ") + s.changeModel + " · " + changeLabel(s.changeState)
        }
        if (s != null && !s.modeChangeState.isEmpty()) {
            message += "\n\n" + tr("Смена прав: ", "Permission change: ") + GeminiRightsUi.label(s.modeChangeMode) + " · " + changeLabel(s.modeChangeState)
        }
        return message
    }

    private fun detailsSummary(): String {
        val full: String = detailsMessage(status)
        if (status == null || !status!!.available) {
            return full
        }
        val heading: String = tr("Расход за текущий запуск CLI, включая служебные запросы:", "Usage in the current CLI launch, including internal requests:")
        val at: Int = full.indexOf(heading)
        return tr("Состояние: ", "State: ") + state() + (if (status!!.selectedModel.isEmpty() || status!!.selectedModel.equals(status!!.model)) "" else "\n" + tr("Выбор CLI: ", "CLI selection: ") + status!!.selectedModel) + "\n\n" + (if (at < 0) full else full.substring(at))
    }

    private fun chooseMode(profile: WorkspaceStore.Session) {
        val captured: String = profile.geminiSocketId
        if (!owns(captured) || status == null || !status!!.canChangeMode) {
            return
        }
        val labels = arrayOfNulls<String>(3)
        for (i in 0 until (3)) {
            labels[i] = GeminiRightsUi.label(GeminiRightsUi.MODES[i]) + "\n" + GeminiRightsUi.description(GeminiRightsUi.MODES[i])
        }
        val selected: String = GeminiRightsUi.normalize(status!!.mode)
        val checked: Int = Arrays.asList(*GeminiRightsUi.MODES).indexOf(selected)
        val enabled = IntPredicate { index ->
            index == 0 || (if (index == 1) status != null && status!!.canEdit else status != null && status!!.canPlan)
        }
        WorkspaceDialogs.builder(activity).setTitle(tr("Режим прав Gemini", "Gemini permission mode")).setSingleChoiceItems(WorkspaceDialogs.choiceAdapter(activity, labels, checked, enabled), checked, DialogInterface.OnClickListener { d, w ->
            if (!enabled.test(w) || !owns(captured)) {
                return@OnClickListener
            }
            d.dismiss()
            requestMode(profile, GeminiRightsUi.MODES[w])
        }).setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun requestMode(profile: WorkspaceStore.Session, mode: String) {
        if (!owns(profile.geminiSocketId) || !GeminiRightsUi.valid(mode)) {
            return
        }
        val request = PendingMode(profile, UUID.randomUUID().toString(), mode)
        pendingModes.put(request.run, request)
        control.execute(Runnable {
            var error = ""
            try {
                GeminiLiveControl.request(activity.getFilesDir(), request.run, JSONObject().put("op", "mode").put("mode", mode).put("id", request.id))
            } catch (failure: Exception) {
                error = failureCode(failure)
            }
            val reason: String = error
            handler.post(Runnable {
                if (closed) {
                    return@Runnable
                }
                if (!reason.isEmpty()) {
                    if (!reason.equals("CONTROL_TIMEOUT") && !reason.equals("PROTOCOL_FAILED") && pendingModes.get(request.run) === request) {
                        pendingModes.remove(request.run)
                    }
                    if (owns(request.run)) {
                        controlFailure(reason)
                    }
                }
                load(profile)
            })
        })
    }

    private fun reconcileMode(captured: String, value: GeminiLiveStatus) {
        val pending: PendingMode? = pendingModes.get(captured)
        if (pending == null) {
            return
        }
        if (!captured.equals(pending.profile.geminiSocketId)) {
            pendingModes.remove(captured)
            return
        }
        if (!value.error.isEmpty()) {
            if (++pending.unavailable >= 10) {
                pendingModes.remove(captured)
            }
            return
        }
        pending.unavailable = 0
        if (value.state.equals("unsupported")) {
            pendingModes.remove(captured)
            return
        }
        if (!pending.id.equals(value.modeChangeId) || !pending.mode.equals(value.modeChangeMode)) {
            if (value.state.equals("ended") || SystemClock.uptimeMillis() - pending.created > 30000) {
                pendingModes.remove(captured)
            }
            return
        }
        if (value.modeChangeState.equals("queued")) {
            if (value.state.equals("ended")) {
                pendingModes.remove(captured)
            }
            return
        }
        pendingModes.remove(captured)
        if (value.modeChangeState.equals("applied") && pending.mode.equals(GeminiRightsUi.normalize(value.mode))) {
            val saved: Boolean = modeApplied.save(pending.profile, captured, pending.before, pending.mode)
            if (owns(captured)) {
                activity.showToast(if (saved) if (pending.profile.saved) tr("Права применены. Настройки сессии и пресета обновлены.", "Permissions applied. Session/preset settings updated.") else tr("Права применены. Настройки сессии обновлены.", "Permissions applied. Session settings updated.") else tr("Права применены, но настройки изменились отдельно или недоступны для записи. Проверьте пресет.", "Permissions applied, but settings changed separately or cannot be saved. Check the preset."), true)
            }
        } else if (owns(captured)) {
            activity.showToast(tr("Смена прав не подтверждена или отменена в Gemini. Пресет не изменён.", "Permission change was not confirmed or was cancelled in Gemini. Preset unchanged."), true)
        }
    }

    private fun details(profile: WorkspaceStore.Session) {
        val captured: String = profile.geminiSocketId
        if (!owns(captured)) {
            return
        }
        if (detailsDialog != null) {
            detailsDialog!!.dismiss()
        }
        val body: LinearLayout = AgentStatusUi.body(activity)
        detailsModel = AgentStatusUi.row(body, "", "model", Runnable {
            if (owns(captured)) {
                choose(profile)
            }
        })
        detailsMode = AgentStatusUi.row(body, "", "permissions", Runnable {
            if (owns(captured)) {
                chooseMode(profile)
            }
        })
        detailsThinking = AgentStatusUi.row(body, "", "reasoning", Runnable {
            if (owns(captured)) {
                chooseThinking(profile)
            }
        })
        detailsInfo = AgentStatusUi.info(body, detailsSummary())
        detailsInfo!!.setId(R.id.message)
        AgentStatusUi.row(body, tr("Квоты AI Studio", "AI Studio quotas"), "quotas", Runnable { openQuotas() })
        AgentChatScale.controls(body, profile, BooleanSupplier { owns(captured) }, chatPreview, chatSave)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Статус Gemini", "Gemini status")).setView(AgentStatusUi.scroll(body)).setPositiveButton(tr("Обновить", "Refresh"), null).setNegativeButton(tr("Закрыть", "Close"), null).create()
        detailsDialog = dialog
        detailsRun = captured
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            if (detailsDialog === dialog) {
                detailsDialog = null
            }
        })
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if (owns(captured)) {
                    load(profile)
                }
            })
        })
        dialog.show()
        update()
    }

    private fun chooseThinking(p: WorkspaceStore.Session) {
        if (status == null || !status!!.available || !status!!.canChangeThinking || !status!!.state.equals("idle")) {
            return
        }
        val captured: String = p.geminiSocketId
        val model: String = status!!.model
        val before: String = p.geminiThinking
        val values: MutableList<String> = ArrayList<String>(status!!.thinkingOptions)
        val labels = arrayOfNulls<String>(values.size)
        for (i in 0 until (labels.size)) {
            labels[i] = AgentStatusUi.level(activity, values.get(i)) + (if (status!!.thinkingModel.startsWith("gemini-2.5-") && !values.get(i).equals("default")) " · " + (if (values.get(i).equals("low")) 1024 else if (values.get(i).equals("medium")) 8192 else if (status!!.thinkingModel.equals("gemini-2.5-pro")) 32768 else 24576) + tr(" токенов", " tokens") else "")
        }
        val checked: Int = values.indexOf(status!!.thinking)
        WorkspaceDialogs.builder(activity).setTitle(tr("Рассуждения Gemini", "Gemini reasoning")).setSingleChoiceItems(labels, checked, DialogInterface.OnClickListener { d, w ->
            d.dismiss()
            val level: String = values.get(w)
            WorkspaceDialogs.builder(activity).setTitle(tr("Подтвердить выбор", "Confirm selection")).setMessage(model + " · " + labels[w] + "\n\n" + tr("Применяется к следующим запросам основного чата через thinkingConfig Gemini CLI. У Gemini 2.5 это бюджет токенов, у поддерживаемых новых моделей — уровень. Расход и время ответа могут измениться. Права не меняются.", "Applies to subsequent main-chat requests through Gemini CLI thinkingConfig. Gemini 2.5 uses a token budget; supported newer models use a level. Usage and response time may change. Permissions stay the same.")).setPositiveButton(tr("Применить", "Apply"), DialogInterface.OnClickListener { confirm, index ->
                if (!owns(captured)) {
                    return@OnClickListener
                }
                control.execute(Runnable {
                    var ok = false
                    var error = "CONTROL_FAILED"
                    try {
                        val r: JSONObject = GeminiLiveControl.request(activity.getFilesDir(), captured, JSONObject().put("op", "thinking").put("model", model).put("level", level))
                        val t: JSONObject? = r.optJSONObject("thinking")
                        ok = t != null && level.equals(t.optString("value")) && t.optBoolean("effective")
                    } catch (e: Exception) {
                        error = failureCode(e)
                    }
                    val accepted: Boolean = ok
                    val reason: String = error
                    handler.post(Runnable {
                        if (closed || !captured.equals(p.geminiSocketId)) {
                            return@Runnable
                        }
                        if (accepted && before.equals(p.geminiThinking)) {
                            thinkingApplied.accept(p, level)
                        }
                        if (owns(captured)) {
                            if (!accepted) {
                                controlFailure(reason)
                            }
                            load(p)
                        }
                    })
                })
            }).setNegativeButton(tr("Назад", "Back"), null).show()
        }).setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun openQuotas() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/rate-limit")))
        } catch (noBrowser: ActivityNotFoundException) {
            WorkspaceDialogs.builder(activity).setTitle(tr("Квоты Gemini", "Gemini quotas")).setMessage("https://aistudio.google.com/rate-limit").setPositiveButton(tr("Закрыть", "Close"), null).show()
        }
    }

    private fun controlFailure(code: String) {
        var message: String
        when (code) {
            "THINKING_UNAVAILABLE", "THINKING_BUSY" -> {
                message = tr("Выбор рассуждений недоступен: модель сменилась, не поддерживается или чат занят. Обновите карточку после завершения ответа.", "Reasoning selection is unavailable: the model changed, is unsupported, or chat is busy. Refresh after the response finishes.")
            }
            "MODE_UNAVAILABLE" -> {
                message = tr("Gemini пока не разрешает переключение этих прав. Перезапустите сессию после обновления; также проверьте доверие к проекту и доступность планирования.", "Gemini cannot switch these permissions yet. Relaunch after updating; check project trust and plan-mode availability.")
            }
            "MODE_BUSY" -> {
                message = tr("Предыдущая смена прав ещё применяется. Дождитесь результата.", "The previous permission change is still being applied. Wait for its result.")
            }
            "CONNECT_FAILED" -> {
                message = tr("Не удалось подключиться к локальному мосту Gemini. Чат может продолжать работать. Для новой версии моста заново запустите Gemini-сессию.", "Could not connect to the local Gemini bridge. The chat can keep working. Relaunch the Gemini session for the new bridge.")
            }
            "CONTROL_TIMEOUT" -> {
                message = tr("Истекло время ожидания локального моста. Повторите попытку после завершения запуска или текущего действия Gemini.", "The local bridge timed out. Retry after Gemini finishes starting or its current operation.")
            }
            "NOT_READY" -> {
                message = tr("Gemini ещё не готов к управлению. Дождитесь запуска; если в терминале запрошен вход, завершите его.", "Gemini is not ready for controls. Wait until startup completes; finish authentication if the terminal requests it.")
            }
            "RESTARTED", "ENDED" -> {
                message = tr("Этот запуск Gemini завершён или перезапускается. Дождитесь подключения новой сессии.", "This Gemini run has ended or is restarting. Wait for the new session to connect.")
            }
            "EMPTY_CATALOG" -> {
                message = tr("Gemini не вернул варианты моделей. Чат и авторизация не изменены.", "Gemini returned no model options. Chat and authentication are unchanged.")
            }
            else -> {
                message = tr("Не удалось получить корректный ответ локального моста Gemini. Чат и авторизация не изменены.", "Could not read a valid response from the local Gemini bridge. Chat and authentication are unchanged.")
            }
        }
        WorkspaceDialogs.builder(activity).setTitle(tr("Управление Gemini недоступно", "Gemini controls unavailable")).setMessage(message + "\n\n" + tr("Код: ", "Code: ") + code).setPositiveButton(tr("Закрыть", "Close"), null).show()
    }

    private fun changeLabel(value: String): String {
        when (value) {
            "applied" -> {
                return tr("применена CLI", "applied by CLI")
            }
            "queued" -> {
                return tr("ожидает завершения ответа", "waiting until idle")
            }
            "cancelled" -> {
                return tr("отменена: выбор изменён в CLI", "cancelled: selection changed in CLI")
            }
            else -> {
                return tr("ошибка", "failed")
            }
        }
    }

    private fun choose(profile: WorkspaceStore.Session) {
        val captured: String = profile.geminiSocketId
        if (!owns(captured)) {
            return
        }
        val wait: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Модели Gemini", "Gemini models")).setMessage(tr("Получение доступного каталога…", "Loading model catalog…")).setNegativeButton(tr("Отмена", "Cancel"), null).show()
        control.execute(Runnable {
            var models: MutableList<String>? = null
            var error = "EMPTY_CATALOG"
            try {
                models = GeminiLiveControl.models(GeminiLiveControl.request(activity.getFilesDir(), captured, JSONObject().put("op", "models")))
            } catch (failure: Exception) {
                error = failureCode(failure)
            }
            val options: MutableList<String>? = models
            val reason: String = error
            handler.post(Runnable {
                val show: Boolean = wait.isShowing()
                wait.dismiss()
                if (!show || !owns(captured)) {
                    return@Runnable
                }
                if (options == null || options.isEmpty()) {
                    controlFailure(reason)
                    return@Runnable
                }
                var selected: Int = if (status == null) -1 else options.indexOf(status!!.selectedModel)
                if (selected < 0 && status != null) {
                    selected = options.indexOf(status!!.model)
                }
                WorkspaceDialogs.builder(activity).setTitle(tr("Модель Gemini", "Gemini model")).setSingleChoiceItems(options.toTypedArray(), selected, DialogInterface.OnClickListener { d, w ->
                    d.dismiss()
                    confirm(profile, options.get(w))
                }).setNegativeButton(tr("Назад", "Back"), null).show()
            })
        })
    }

    private fun confirm(profile: WorkspaceStore.Session, model: String) {
        val captured: String = profile.geminiSocketId
        if (!owns(captured)) {
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(model).setMessage(tr("Сменить модель в текущем чате? История сохраняется. Во время ответа смена ждёт готовности CLI. Каталог не гарантирует доступность модели или бесплатный тариф: сервер проверит это при запросе. Изменение действует до следующего запуска.", "Change the model in this chat? History is preserved. While responding, the change waits until idle. The catalog does not guarantee model access or free pricing: the server checks at request time. This change lasts until the next launch.")).setPositiveButton(tr("Применить", "Apply"), DialogInterface.OnClickListener { d, w ->
            if (!owns(captured)) {
                return@OnClickListener
            }
            control.execute(Runnable {
                var ok = false
                var error = "CONTROL_FAILED"
                try {
                    GeminiLiveControl.request(activity.getFilesDir(), captured, JSONObject().put("op", "model").put("model", model).put("id", UUID.randomUUID().toString()))
                    ok = true
                } catch (failure: Exception) {
                    error = failureCode(failure)
                }
                val accepted: Boolean = ok
                val reason: String = error
                handler.post(Runnable {
                    if (!owns(captured)) {
                        return@Runnable
                    }
                    if (accepted) {
                        activity.showToast(tr("Запрос передан Gemini. Результат — в карточке модели.", "Request sent to Gemini. See the model card for its result."), true)
                    } else {
                        controlFailure(reason)
                    }
                    load(profile)
                })
            })
        }).setNegativeButton(tr("Отмена", "Cancel"), null).show()
    }

    fun close() {
        closed = true
        permissions.close()
        pendingModes.clear()
        if (detailsDialog != null) {
            detailsDialog!!.dismiss()
        }
        handler.removeCallbacksAndMessages(null)
        reader.shutdownNow()
        control.shutdownNow()
    }

    companion object {
        @JvmStatic
        private fun failureCode(failure: Exception): String {
            return if (failure is GeminiLiveControl.Failure) (failure as GeminiLiveControl.Failure).code else "PROTOCOL_FAILED"
        }
    }
}
