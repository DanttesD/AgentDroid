package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Claude-only local status and native model/mode controls; no remote quota estimate.*/
class ClaudeLiveUi {
    fun interface Applied {
        fun save(p: WorkspaceStore.Session, run: String, model: String?, mode: String?)
    }

    private val activity: TermuxActivity
    private lateinit var current: Supplier<WorkspaceStore.Session?>
    private lateinit var visible: BooleanSupplier
    private val applied: Applied
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val reader: ExecutorService = Executors.newSingleThreadExecutor()
    private val actions: ExecutorService = Executors.newSingleThreadExecutor()
    private var effortApplied: BiConsumer<WorkspaceStore.Session, String> = BiConsumer<WorkspaceStore.Session, String> { p, v ->  }

    fun effortApplied(save: BiConsumer<WorkspaceStore.Session, String>) {
        effortApplied = save
    }

    private var chatPreview: Runnable = Runnable {  }
    private var chatSave: Runnable = Runnable {  }

    fun chatPresentation(preview: Runnable, save: Runnable) {
        chatPreview = preview
        chatSave = save
    }

    private var closed: Boolean = false
    private var loading: Boolean = false
    private var run: String = ""
    private var status: JSONObject? = null
    private var chip: TextView? = null
    private var detailsModel: TextView? = null
    private var detailsMode: TextView? = null
    private var detailsEffort: TextView? = null
    private var detailsInfo: TextView? = null
    private var detailsDialog: AlertDialog? = null
    private var detailsRun: String = ""

    constructor(a: TermuxActivity, c: Supplier<WorkspaceStore.Session?>, v: BooleanSupplier, save: Applied) {
        activity = a
        current = c
        visible = v
        applied = save
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
            val p: WorkspaceStore.Session? = current.get()
            if (p != null && visible.getAsBoolean()) {
                load(p)
            }
            handler.postDelayed(this, 1500L)
        }
    }

    private fun owns(r: String): Boolean {
        val p: WorkspaceStore.Session? = current.get()
        return !closed && p != null && r.equals(p.claudeSocketId)
    }

    fun render(parent: LinearLayout?, p: WorkspaceStore.Session) {
        if (!run.equals(p.claudeSocketId)) {
            run = p.claudeSocketId
            status = null
        }
        chip = AgentStatusUi.chip(activity, "", tr("Статус, модель и права Claude", "Claude status, model and permissions"), Runnable { menu(p) })
        AgentStatusUi.attach(parent, chip)
        update()
        load(p)
    }

    private fun mode(value: String?): String {
        return if (value!!.equals("plan")) tr("План / чтение", "Plan / read") else if (value.equals("acceptEdits")) tr("Разрешать правки", "Accept edits") else tr("Подтверждать действия", "Confirm actions")
    }

    private fun state(): String {
        if (status == null) {
            return tr("подключение…", "connecting…")
        }
        if (!status!!.optBoolean("ready")) {
            return tr("терминал / вход", "terminal / sign-in")
        }
        when (status!!.optString("state")) {
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
                return tr("проверка состояния", "checking status")
            }
        }
    }

    private fun model(p: WorkspaceStore.Session): String? {
        return if (status == null) if (p.claudeModel!!.isEmpty()) tr("По умолчанию", "Default") else p.claudeModel else status!!.optString("model", tr("Нет данных", "Unavailable"))
    }

    private fun canControl(): Boolean {
        return status != null && status!!.optBoolean("ready") && status!!.optString("state").equals("idle") && status!!.optBoolean("canChange", true)
    }

    private fun usage(): String {
        val value: JSONObject? = if (status == null) null else status!!.optJSONObject("usage")
        var body: String = tr("Расход последнего запроса. Остаток лимитов подписки этим каналом не предоставляется.", "Usage for the last request. Remaining subscription limits are not supplied by this channel.")
        if (value != null && value.has("input") && value.has("output")) {
            body += "\n\n" + tr("Входные токены: ", "Input tokens: ") + value.optLong("input") + "\n" + tr("Выходные токены: ", "Output tokens: ") + value.optLong("output")
        } else {
            body += "\n" + tr("Данных о расходе пока нет.", "No usage data yet.")
        }
        return body
    }

    private fun update() {
        if (chip == null) {
            return
        }
        val p: WorkspaceStore.Session? = current.get()
        if (p == null || !owns(run)) {
            return
        }
        val rights: String = mode(if (status == null) p.claudeMode else status!!.optString("mode"))
        val label: String = "Claude · " + model(p) + "\n" + state() + " · " + rights + " ›"
        if (!(label as java.lang.String).contentEquals(chip!!.getText())) {
            chip!!.setText(label)
        }
        if (detailsDialog != null && detailsDialog!!.isShowing()) {
            if (!owns(detailsRun)) {
                detailsDialog!!.dismiss()
                return
            }
            detailsModel!!.setText(tr("Модель: ", "Model: ") + model(p) + " ›")
            detailsMode!!.setText(tr("Режим прав: ", "Permissions: ") + rights + " ›")
            AgentStatusUi.available(detailsModel, canControl())
            AgentStatusUi.available(detailsMode, canControl())
            detailsEffort!!.setText(tr("Рассуждения: ", "Reasoning: ") + AgentStatusUi.level(activity, if (status == null) "" else status!!.optString("effort")) + " ›")
            AgentStatusUi.available(detailsEffort, canControl() && effortOptions().length() > 0)
            detailsInfo!!.setText(tr("Состояние: ", "State: ") + state() + "\n\n" + usage() + (if (canControl()) "" else "\n\n" + tr("Выбор доступен после подключения и завершения текущего действия Claude.", "Selection is available after connecting and when Claude finishes its current action.")))
        }
    }

    private fun load(p: WorkspaceStore.Session) {
        if (closed || loading) {
            return
        }
        loading = true
        val captured: String = p.claudeSocketId
        reader.execute(Runnable {
            var r: JSONObject? = null
            try {
                r = ClaudeLiveControl.request(activity.getFilesDir(), captured, AgentChatUi.op("status"))
            } catch (ignored: Exception) {
            }
            val value: JSONObject? = r
            handler.post(Runnable {
                loading = false
                if (owns(captured)) {
                    status = value
                    update()
                }
            })
        })
    }

    private fun menu(p: WorkspaceStore.Session) {
        val captured: String = p.claudeSocketId
        if (!owns(captured)) {
            return
        }
        if (detailsDialog != null) {
            detailsDialog!!.dismiss()
        }
        val body: LinearLayout = AgentStatusUi.body(activity)
        detailsModel = AgentStatusUi.row(body, "", "model", Runnable {
            if (owns(captured)) {
                models(p)
            }
        })
        detailsMode = AgentStatusUi.row(body, "", "permissions", Runnable {
            if (owns(captured)) {
                modes(p)
            }
        })
        detailsEffort = AgentStatusUi.row(body, "", "reasoning", Runnable {
            if (owns(captured)) {
                efforts(p)
            }
        })
        detailsInfo = AgentStatusUi.info(body, "")
        detailsInfo!!.setId(R.id.message)
        detailsRun = captured
        AgentChatScale.controls(body, p, BooleanSupplier { owns(captured) }, chatPreview, chatSave)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("Статус Claude", "Claude status")).setView(AgentStatusUi.scroll(body)).setPositiveButton(tr("Обновить", "Refresh"), null).setNegativeButton(tr("Закрыть", "Close"), null).create()
        detailsDialog = dialog
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            if (detailsDialog === dialog) {
                detailsDialog = null
            }
        })
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if (owns(captured)) {
                    load(p)
                }
            })
        })
        dialog.show()
        update()
    }

    private fun models(p: WorkspaceStore.Session) {
        if (!canControl()) {
            return
        }
        val captured: String = p.claudeSocketId
        reader.execute(Runnable {
            var r: JSONObject? = null
            try {
                r = ClaudeLiveControl.request(activity.getFilesDir(), captured, AgentChatUi.op("models"))
            } catch (ignored: Exception) {
            }
            val result: JSONObject? = r
            handler.post(Runnable {
                if (!owns(captured)) {
                    return@Runnable
                }
                val models: JSONArray? = if (result == null) null else result.optJSONArray("models")
                if (models == null || models.length() == 0) {
                    failure()
                    return@Runnable
                }
                val labels = arrayOfNulls<String>(models.length())
                val values = arrayOfNulls<String>(models.length())
                var i = 0
                while (i < models.length()) {
                    val m: JSONObject = models.optJSONObject(i)
                    labels[i] = m.optString("label")
                    values[i] = if (m.optString("value").equals("default")) "" else m.optString("value")
                    i++
                }
                var checked = -1
                for (i in 0 until (values.size)) {
                    if (status != null && (if (values[i]!!.isEmpty()) "default" else values[i])!!.equals(status!!.optString("selectedModel", status!!.optString("model")))) {
                        checked = i
                    }
                }
                WorkspaceDialogs.builder(activity).setTitle(tr("Модель Claude", "Claude model")).setSingleChoiceItems(labels, checked, DialogInterface.OnClickListener { d, w ->
                    if (!owns(captured)) {
                        return@OnClickListener
                    }
                    d.dismiss()
                    confirm(p, "set-model", "model", values[w], labels[w])
                }).setNegativeButton(tr("Назад", "Back"), null).show()
            })
        })
    }

    private fun effortOptions(): JSONArray {
        val out = JSONArray()
        val values: JSONArray? = if (status == null) null else status!!.optJSONArray("effortOptions")
        if (values != null) {
            var i = 0
            while (i < values.length()) {
                val v: String = values.optString(i)
                if (Arrays.asList("default", "low", "medium", "high", "xhigh", "max").contains(v)) {
                    out.put(v)
                }
                i++
            }
        }
        return out
    }

    private fun efforts(p: WorkspaceStore.Session) {
        if (!canControl()) {
            return
        }
        val values: JSONArray = effortOptions()
        if (values.length() == 0) {
            return
        }
        val captured: String = p.claudeSocketId
        val selected: String = status!!.optString("selectedModel")
        val before: String = p.claudeEffort
        val labels = arrayOfNulls<String>(values.length())
        var checked = -1
        for (i in 0 until (labels.size)) {
            labels[i] = AgentStatusUi.level(activity, values.optString(i))
            if (values.optString(i).equals(status!!.optString("effort"))) {
                checked = i
            }
        }
        WorkspaceDialogs.builder(activity).setTitle(tr("Рассуждения Claude", "Claude reasoning")).setSingleChoiceItems(labels, checked, DialogInterface.OnClickListener { d, w ->
            d.dismiss()
            val value: String = values.optString(w)
            WorkspaceDialogs.builder(activity).setTitle(tr("Подтвердить выбор", "Confirm selection")).setMessage(labels[w] + "\n\n" + tr("Уровень штатного effort Claude. Применяется к следующим запросам; расход и время ответа могут измениться. Права не меняются.", "Native Claude effort level. Applies to subsequent requests; usage and response time may change. Permissions stay the same.")).setPositiveButton(tr("Применить", "Apply"), DialogInterface.OnClickListener { confirm, index ->
                if (!owns(captured) || !canControl()) {
                    return@OnClickListener
                }
                actions.execute(Runnable {
                    var ok = false
                    try {
                        val r: JSONObject = ClaudeLiveControl.request(activity.getFilesDir(), captured, JSONObject().put("op", "set-effort").put("model", selected).put("effort", value))
                        ok = r.optBoolean("ok") && value.equals(r.optString("effort"))
                    } catch (ignored: Exception) {
                    }
                    val accepted: Boolean = ok
                    handler.post(Runnable {
                        if (closed || !captured.equals(p.claudeSocketId)) {
                            return@Runnable
                        }
                        if (accepted && before.equals(p.claudeEffort)) {
                            effortApplied.accept(p, value)
                        }
                        if (owns(captured)) {
                            if (!accepted) {
                                failure()
                            }
                            load(p)
                        }
                    })
                })
            }).setNegativeButton(tr("Назад", "Back"), null).show()
        }).setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun modes(p: WorkspaceStore.Session) {
        if (!canControl()) {
            return
        }
        val captured: String = p.claudeSocketId
        val codes: Array<String> = arrayOf<String>("manual", "acceptEdits", "plan")
        val labels: Array<String> = arrayOf<String>(mode(codes[0]) + tr(" — запрашивать разрешения на действия", " — ask before actions"), mode(codes[1]) + tr(" — правки файлов без отдельного запроса; команды по правилам Claude", " — approve file edits; commands follow Claude policy"), mode(codes[2]) + tr(" — анализ и планирование по штатным правилам Claude", " — analysis and planning under Claude policy"))
        val checked: Int = Arrays.asList(*codes).indexOf(status!!.optString("mode"))
        WorkspaceDialogs.builder(activity).setTitle(tr("Режим прав Claude", "Claude permissions")).setSingleChoiceItems(labels, checked, DialogInterface.OnClickListener { d, w ->
            if (!owns(captured)) {
                return@OnClickListener
            }
            d.dismiss()
            confirm(p, "set-mode", "mode", codes[w], labels[w])
        }).setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun confirm(p: WorkspaceStore.Session, op: String, field: String, value: String?, label: String?) {
        val captured: String = p.claudeSocketId
        WorkspaceDialogs.builder(activity).setTitle(tr("Подтвердить выбор", "Confirm selection")).setMessage(label).setPositiveButton(tr("Применить", "Apply"), DialogInterface.OnClickListener { d, w ->
            if (owns(captured)) {
                apply(p, op, field, value)
            }
        }).setNegativeButton(tr("Назад", "Back"), null).show()
    }

    private fun apply(p: WorkspaceStore.Session, op: String, field: String, value: String?) {
        if (!owns(p.claudeSocketId) || !canControl()) {
            return
        }
        val captured: String = p.claudeSocketId
        val q: JSONObject = AgentChatUi.op(op)
        try {
            q.put(field, value)
        } catch (impossible: JSONException) {
            throw IllegalStateException(impossible)
        }
        actions.execute(Runnable {
            var ok = false
            try {
                ok = ClaudeLiveControl.request(activity.getFilesDir(), captured, q).optBoolean("ok")
            } catch (ignored: Exception) {
            }
            val accepted: Boolean = ok
            handler.post(Runnable {
                if (closed || !captured.equals(p.claudeSocketId)) {
                    return@Runnable
                }
                if (accepted) {
                    applied.save(p, captured, if (field.equals("model")) value else p.claudeModel, if (field.equals("mode")) value else p.claudeMode)
                    if (owns(captured)) {
                        load(p)
                    }
                } else if (owns(captured)) {
                    failure()
                }
            })
        })
    }

    private fun failure() {
        WorkspaceDialogs.builder(activity).setTitle("Claude").setMessage(tr("Claude пока не подтвердил действие. Дождитесь окончания ответа или откройте терминал кнопкой робота и проверьте вход. Для старой сессии потребуется новый запуск.", "Claude has not confirmed the action. Wait for the reply to finish or check sign-in using the robot terminal. An older session needs a new launch.")).setPositiveButton(tr("Назад", "Back"), null).show()
    }

    fun close() {
        closed = true
        if (detailsDialog != null) {
            detailsDialog!!.dismiss()
        }
        handler.removeCallbacks(poll)
        reader.shutdownNow()
        actions.shutdown()
    }
}
