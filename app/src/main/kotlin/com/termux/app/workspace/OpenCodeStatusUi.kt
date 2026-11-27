package com.termux.app.workspace

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier
import org.json.JSONException
import org.json.JSONObject

/** On-demand OpenCode settings; no polling, terminal injection or process replacement.*/
class OpenCodeStatusUi {
    fun interface Rpc {
        @Throws(Exception::class)
        fun call(request: JSONObject): JSONObject
    }

    private val activity: Activity
    private val profile: WorkspaceStore.Session
    private val source: WorkspaceStore.Session
    private val changed: Runnable
    private val rpc: Rpc
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val thread: String
    private val connection: String
    private val handle: String
    private var dialog: AlertDialog? = null
    private var model: TextView? = null
    private var mode: TextView? = null
    private var rights: TextView? = null
    private var info: TextView? = null
    private var state: JSONObject? = null
    private var settings: JSONObject? = null
    private var closed: Boolean = false
    private var busy: Boolean = false
    private var chatPreview: Runnable = Runnable {  }
    private var chatSave: Runnable = Runnable {  }

    fun chatPresentation(preview: Runnable, save: Runnable) {
        chatPreview = preview
        chatSave = save
    }

    constructor(a: Activity, p: WorkspaceStore.Session, changed: Runnable, rpc: Rpc?) {
        activity = a
        profile = p
        this.changed = changed
        source = AiAgents.snapshot(p)
        thread = p.aiThread
        connection = p.aiConnection
        handle = p.handle
        this.rpc = if (rpc == null) Rpc { q -> AiAgents.call(a, connection, source, q) } else rpc
    }

    private fun tr(ru: String, en: String): String {
        return AgentStatusUi.tr(activity, ru, en)
    }

    private fun owns(): Boolean {
        return !closed && !activity.isFinishing() && !activity.isDestroyed() && thread.equals(profile.aiThread) && connection.equals(profile.aiConnection) && handle.equals(profile.handle) && source.aiSocketId.equals(profile.aiSocketId)
    }

    private fun canChange(): Boolean {
        return !busy && settings != null && settings!!.optBoolean("canChange")
    }

    fun show() {
        val body: LinearLayout = AgentStatusUi.body(activity)
        model = AgentStatusUi.row(body, "", "model", Runnable(this::chooseModel))
        rights = AgentStatusUi.row(body, "", "permissions", Runnable { choose(true) })
        mode = AgentStatusUi.row(body, "", "mode", Runnable { choose(false) })
        info = AgentStatusUi.info(body, "")
        info!!.setId(R.id.message)
        AgentChatScale.controls(body, profile, BooleanSupplier(this::owns), chatPreview, chatSave)
        dialog = WorkspaceDialogs.builder(activity).setTitle(tr("Статус OpenCode", "OpenCode status")).setView(AgentStatusUi.scroll(body)).setPositiveButton(tr("Обновить", "Refresh"), null).setNegativeButton(tr("Закрыть", "Close"), null).create()
        dialog!!.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            closed = true
            worker.shutdown()
        })
        dialog!!.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v -> refresh() })
        })
        dialog!!.show()
        render("")
        refresh()
    }

    private fun render(notice: String) {
        if (!owns()) {
            return
        }
        model!!.setText(tr("Модель: ", "Model: ") + (if (state == null) tr("нет данных", "unavailable") else AiAgentsUi.modelLabel(state!!.optJSONObject("model"))) + " ›")
        AgentStatusUi.available(model, !busy && state != null)
        val access: String = if (settings == null) profile.aiAccess else settings!!.optString("access")
        val agent: String = if (settings == null) profile.aiMode else settings!!.optString("mode")
        rights!!.setText(tr("Права: ", "Permissions: ") + (if (access.equals("custom")) tr("Свои правила", "Custom rules") else AiAgentsUi.accessLabel(access)) + " ›")
        mode!!.setText(tr("Режим работы: ", "Work mode: ") + agent + " ›")
        AgentStatusUi.available(rights, canChange())
        AgentStatusUi.available(mode, canChange())
        val status: String = if (busy) tr("Получение подтверждения…", "Waiting for confirmation…") else if (state == null) tr("Нет подключения к локальному серверу.", "No local server connection.") else tr("Подключено к локальному серверу. Доступ к провайдеру проверяется при запросе.", "Connected to the local server. Provider access is checked when sending a request.")
        val scope: String = if (settings == null) if (profile.aiProjectOnly) "project" else "unrestricted" else settings!!.optString("scope")
        val scopeText: String = if (scope.equals("project")) tr("Работа в пределах проекта: внешние каталоги, shell и внешние инструменты запрещены правилами запуска.", "Project scope: launch rules deny external directories, shell and external tools.") else tr("Внешние каталоги регулируются выбранными правами OpenCode.", "Selected OpenCode permissions govern external directories.")
        info!!.setText(status + "\n\n" + tr("Подключение: ", "Connection: ") + AiAgentsUi.title(activity, connection) + "\n" + scopeText + "\n\n" + (if (settings == null) tr("Права и режим показаны по настройкам запуска. Для переключения перезапустите OpenCode с этой версией приложения.", "Permissions and mode show launch settings. Relaunch OpenCode with this app version to enable switching.") else if (canChange()) "" else tr("Переключение доступно, когда чат свободен, нет вопросов/подтверждений и терминальное окно закрыто.", "Switching is available when chat is idle, no questions/approvals are pending and the native terminal is closed.")) + "\n\n" + tr("Остаток лимитов аккаунта этим каналом не предоставляется.", "Remaining account limits are not supplied by this channel.") + (if (notice.isEmpty()) "" else "\n\n" + notice))
        dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!busy)
        dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!busy)
        dialog!!.setCancelable(!busy)
        dialog!!.setCanceledOnTouchOutside(!busy)
    }

    private fun refresh() {
        if (!owns() || busy) {
            return
        }
        busy = true
        render("")
        worker.execute(Runnable {
            var next: JSONObject? = null
            var config: JSONObject? = null
            try {
                next = rpc.call(AiAgentsUi.op("status"))
                if (next.optBoolean("nativeChat")) {
                    try {
                        val read: JSONObject = rpc.call(AiAgentsUi.op("settings-read"))
                        if (validSettings(read, next.optString("id"))) {
                            config = read
                        }
                    } catch (ignored: Exception) {
                    }
                }
            } catch (ignored: Exception) {
            }
            val value: JSONObject? = next
            val options: JSONObject? = config
            activity.runOnUiThread(Runnable {
                if (!owns()) {
                    return@Runnable
                }
                busy = false
                state = value
                settings = options
                render("")
            })
        })
    }

    private fun choose(permissions: Boolean) {
        if (!owns() || !canChange()) {
            return
        }
        val captured: JSONObject? = settings
        val codes: Array<String> = if (permissions) arrayOf<String>("full", "ask", "read") else arrayOf<String>("build", "plan")
        val labels = arrayOfNulls<String>(codes.size)
        for (i in 0 until (codes.size)) {
            labels[i] = if (permissions) AiAgentsUi.accessLabel(codes[i]) + "\n" + AiAgentsUi.accessDescription(codes[i]) else codes[i] + "\n" + AiAgentsUi.modeDescription(codes[i])
        }
        val checked: Int = Arrays.asList(*codes).indexOf(captured!!.optString(if (permissions) "access" else "mode"))
        WorkspaceDialogs.builder(activity).setTitle(if (permissions) tr("Права OpenCode", "OpenCode permissions") else tr("Режим OpenCode", "OpenCode work mode")).setSingleChoiceItems(labels, checked, DialogInterface.OnClickListener { d, w ->
            d.dismiss()
            WorkspaceDialogs.builder(activity).setTitle(tr("Подтвердить выбор", "Confirm selection")).setMessage(labels[w] + "\n\n" + tr("Применяется к текущему чату и сохраняется в его настройках после подтверждения сервера. Текущие правила этого чата будут заменены; ограничение проекта остаётся прежним.", "Applies to this chat and is saved after server confirmation. This chat's current rules will be replaced; its project scope stays unchanged.")).setPositiveButton(tr("Применить", "Apply"), DialogInterface.OnClickListener { confirm, index -> apply(captured, permissions, codes[w]) }).setNegativeButton(tr("Назад", "Back"), null).show()
        }).setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun apply(captured: JSONObject?, permissions: Boolean, value: String) {
        if (!owns() || busy) {
            return
        }
        val before: String = if (permissions) profile.aiAccess else profile.aiMode
        val request: JSONObject = AiAgentsUi.op(if (permissions) "settings-permissions" else "settings-mode")
        try {
            request.put("id", "cfg_" + UUID.randomUUID()).put("session", captured!!.getString("session")).put("revision", captured.getString("revision")).put(if (permissions) "access" else "mode", value)
        } catch (invalid: JSONException) {
            return
        }
        busy = true
        render("")
        worker.execute(Runnable {
            var result: JSONObject? = null
            try {
                result = rpc.call(request)
            } catch (ignored: Exception) {
            }
            val response: JSONObject? = result
            activity.runOnUiThread(Runnable {
                if (!owns()) {
                    return@Runnable
                }
                busy = false
                val actual: JSONObject? = if (response == null) null else response.optJSONObject("settings")
                if (!validSettings(actual, captured.optString("session"))) {
                    settings = null
                    render(tr("Изменение не подтверждено. Права могли измениться; обновите данные перед повтором. Настройки запуска не сохранены.", "Change not confirmed. Permissions may have changed; refresh before retrying. Launch settings were not saved."))
                    return@Runnable
                }
                settings = actual
                if (response!!.optBoolean("applied") && value.equals(actual!!.optString(if (permissions) "access" else "mode"))) {
                    if (before.equals(if (permissions) profile.aiAccess else profile.aiMode)) {
                        if (permissions) {
                            profile.aiAccess = value
                        } else {
                            profile.aiMode = value
                        }
                        changed.run()
                        render(tr("Выбор подтверждён сервером. Настройки сессии обновлены.", "Selection confirmed by server. Session settings updated."))
                    } else {
                        render(tr("Сервер применил выбор, но настройки были изменены отдельно. Проверьте пресет.", "Server applied the selection, but settings changed separately. Check the preset."))
                    }
                } else {
                    render(tr("Выбор не применён: чат занят или настройки изменились. Обновите данные и повторите выбор.", "Selection not applied: chat is busy or settings changed. Refresh and choose again."))
                }
            })
        })
    }

    private fun chooseModel() {
        if (!owns() || busy || state == null) {
            return
        }
        AiAgentsUi.chooseModel(activity, connection, source, AiAgentsUi.ModelChoice { provider, id, variant ->
            if (!owns()) {
                return@ModelChoice
            }
            WorkspaceDialogs.builder(activity).setTitle(tr("Сменить модель?", "Change model?")).setMessage(provider + " / " + id + (if (variant!!.isEmpty()) "" else " · " + variant) + "\n\n" + tr("История чата сохраняется. Дождитесь завершения ответа.", "Chat history is preserved. Wait for the current response to finish.")).setPositiveButton(tr("Применить", "Apply"), DialogInterface.OnClickListener { d, w ->
                if (!owns() || busy) {
                    return@OnClickListener
                }
                val q: JSONObject = AiAgentsUi.op("model")
                try {
                    q.put("provider", provider).put("model", id).put("variant", variant).put("reopen", true)
                } catch (ignored: JSONException) {
                    return@OnClickListener
                }
                busy = true
                render("")
                worker.execute(Runnable {
                    var result: JSONObject? = null
                    try {
                        result = rpc.call(q)
                    } catch (ignored: Exception) {
                    }
                    val r: JSONObject? = result
                    activity.runOnUiThread(Runnable {
                        if (!owns()) {
                            return@Runnable
                        }
                        busy = false
                        if (r != null && r.optBoolean("applied") && r.optBoolean("reopened") && r.optJSONObject("model") != null) {
                            if (AiAgentsUi.showConfirmedModel(profile, r.optJSONObject("model"), model, changed)) {
                                refresh()
                            } else {
                                render(tr("Некорректный ответ о модели. Обновите данные.", "Invalid model response. Refresh the data."))
                            }
                        } else {
                            render(tr("Смена модели не подтверждена. Обновите данные перед повтором.", "Model change not confirmed. Refresh before retrying."))
                        }
                    })
                })
            }).setNegativeButton(tr("Назад", "Back"), null).show()
        })
    }

    companion object {
        @JvmStatic
        fun validSettings(value: JSONObject?, session: String): Boolean {
            return value != null && session.equals(value.optString("session")) && (value.optString("revision") as java.lang.String).matches("[0-9a-f]{64}") && Arrays.asList("project", "unrestricted").contains(value.optString("scope")) && Arrays.asList("full", "ask", "read", "custom").contains(value.optString("access")) && Arrays.asList("build", "plan", "custom").contains(value.optString("mode"))
        }
    }
}
