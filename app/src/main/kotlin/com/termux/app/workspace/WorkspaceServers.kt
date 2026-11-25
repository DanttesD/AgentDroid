package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.termux.app.TermuxActivity
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer
import java.util.function.Predicate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Full server workspace. Monitoring is activity-scoped, no background phone service.*/
class WorkspaceServers : AutoCloseable {
    fun interface Terminal {
        fun open(name: String, command: String)
    }

    private val activity: TermuxActivity
    private val terminal: Terminal
    lateinit var store: ServerProfiles
    private val queues: ServerWorkQueues = ServerWorkQueues()
    private val worker: ExecutorService = queues.foreground
    private val auxiliary: MutableSet<ServerConnection> = ConcurrentHashMap.newKeySet()
    private var manualEpoch: Int = 0
    private val main: Handler = Handler(Looper.getMainLooper())
    private val connections: MutableMap<String, ServerConnection?> = HashMap<String, ServerConnection?>()
    private val snapshots: MutableMap<String, JSONObject?> = HashMap<String, JSONObject?>()
    private val connected: MutableSet<String> = HashSet<String>()
    private val agentPresence: MutableMap<String, Boolean> = HashMap<String, Boolean>()
    private val sshBrowsers: MutableList<WorkspaceFileBrowser> = ArrayList<WorkspaceFileBrowser>()
    private val textFiles: MutableList<ProjectFiles> = ArrayList<ProjectFiles>()
    private val editors: MutableList<WorkspaceTextEditor> = ArrayList<WorkspaceTextEditor>()
    private var player: WorkspaceServerPlayer? = null
    private var playerProfile: String = ""
    private var scheduleUi: WorkspaceServerSchedules? = null
    private val uploads: WorkspaceServerUploads
    private val downloads: WorkspaceServerDownloads
    private var root: LinearLayout? = null
    private var scroll: ServerScrollView? = null
    private var sectionBody: LinearLayout? = null
    private var projectBanner: LinearLayout? = null
    private val sectionButtons: MutableMap<String, TextView> = HashMap<String, TextView>()
    private var selected: ServerProfiles.Profile? = null
    private var project: ServerProfiles.Project? = null
    private var tab: String = "overview"
    private var directory: String = "~"
    private var error: String = ""
    private var sampleText: TextView? = null
    private var monitorStatus: TextView? = null
    private var filePathLabel: TextView? = null
    private var fileStatus: TextView? = null
    private var metricCards: ServerMetricCards? = null
    private var taskRows: LinearLayout? = null
    private var streamPanel: LinearLayout? = null
    private var fileRows: LinearLayout? = null
    private var favoriteButton: TextView? = null
    private var taskFingerprint: String = ""
    private val streamNames: MutableMap<String, String> = HashMap<String, String>()
    private var serviceText: TextView? = null
    private var listing: JSONObject? = null
    private var foreground: Boolean = false
    private var visible: Boolean = false
    private var busy: Boolean = false
    private var pollBusy: Boolean = false
    private var closed: Boolean = false
    private var generation: Int = 0
    private var dialogs: Int = 0
    private val ownedDialogs: MutableList<AlertDialog> = ArrayList<AlertDialog>()
    private val lastPoll: MutableMap<String, Long> = HashMap<String, Long>()
    private val pollLabels: MutableMap<String, TextView> = HashMap<String, TextView>()
    private val listMetrics: MutableMap<String, TextView> = HashMap<String, TextView>()
    private val transferRows: MutableMap<String, LinearLayout> = HashMap<String, LinearLayout>()
    private var transferListener: Runnable = Runnable {  }

    fun transferListener(listener: Runnable?) {
        transferListener = if (listener == null) Runnable {  } else listener
    }

    fun transfersActive(): Boolean {
        return downloads.presentation.active() || uploads.presentation.active()
    }

    private fun transferChanged() {
        if (closed) {
            return
        }
        for (entry in transferRows.entries) {
            val upload: Boolean = entry.key.startsWith("up:")
            val id: String = entry.key.substring(3)
            val state: ServerTransferPresentation = if (upload) uploads.presentation else downloads.presentation
            val row: LinearLayout = entry.value
            val label: TextView = row.getChildAt(0) as TextView
            val bar: ProgressBar = row.getChildAt(1) as ProgressBar
            val active: Boolean = state.belongs(id)
            val visibility: Int = if (active) View.VISIBLE else View.GONE
            if (row.getVisibility() != visibility) {
                row.setVisibility(visibility)
            }
            if (active) {
                val value: String = (if (upload) t("Загрузка на сервер", "Uploading to server") else t("Скачивание с сервера", "Downloading from server")) + "\n" + state.text()
                if (!(value as java.lang.String).contentEquals(label.getText())) {
                    label.setText(value)
                }
                bar.setIndeterminate(state.indeterminate())
                if (!state.indeterminate()) {
                    bar.setProgress(state.progress())
                }
            }
        }
        transferListener.run()
    }

    private fun transferRows(parent: LinearLayout, profile: ServerProfiles.Profile?) {
        for (upload in booleanArrayOf(true, false)) {
            val state: ServerTransferPresentation = if (upload) uploads.presentation else downloads.presentation
            val row: LinearLayout = column()
            val label: TextView = button("", Runnable {  })
            row.setBackground(label.getBackground())
            label.setBackground(null)
            label.setClickable(false)
            label.setFocusable(false)
            label.setGravity(Gravity.START or Gravity.CENTER_VERTICAL)
            label.setMaxLines(3)
            label.setEllipsize(TextUtils.TruncateAt.END)
            label.setTextSize(12.0f)
            row.addView(label, LinearLayout.LayoutParams(-1, 0, 1.0f))
            val bar = ProgressBar(activity, null, R.attr.progressBarStyleHorizontal)
            bar.setMax(1000)
            bar.setProgressTintList(ColorStateList.valueOf(Pal.ACCENT))
            bar.setIndeterminateTintList(ColorStateList.valueOf(Pal.ACCENT))
            val bp = LinearLayout.LayoutParams(-1, dp(4))
            bp.setMargins(dp(12), 0, dp(12), dp(10))
            row.addView(bar, bp)
            row.setOnClickListener(View.OnClickListener { v -> state.foreground() })
            row.setContentDescription(if (upload) t("Прогресс загрузки на сервер", "Upload progress") else t("Прогресс скачивания с сервера", "Download progress"))
            row.setTag((if (upload) "up:" else "dn:") + profile!!.id)
            parent.addView(row, LinearLayout.LayoutParams(-1, dp(96)))
            transferRows.put((if (upload) "up:" else "dn:") + profile.id, row)
            row.setVisibility(View.GONE)
        }
        transferChanged()
    }

    fun pollingEnabled(p: ServerProfiles.Profile?): Boolean {
        return connected.contains(p!!.id) && !ServerConnection.disconnected(activity, p.id)
    }

    fun pollingInterval(p: ServerProfiles.Profile): Long {
        return (if (selected === p && visible) 10000 else 30000).toLong()
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (closed || !foreground) {
                return
            }
            if (!busy && !pollBusy && dialogs == 0) {
                val now: Long = SystemClock.elapsedRealtime()
                for (p in store.profiles) {
                    if (pollingEnabled(p) && now - lastPoll.getOrDefault(p.id, -30000L) >= pollingInterval(p)) {
                        lastPoll.put(p.id, now)
                        refreshMonitor(p)
                        break
                    }
                }
            }
            main.postDelayed(this, 1000L)
        }
    }

    fun pollingLabel(p: ServerProfiles.Profile?): String {
        return if (pollingEnabled(p)) t("Подключён", "Connected") else t("Отключён", "Disconnected")
    }

    private fun connectionButton(p: ServerProfiles.Profile?): TextView {
        val b: TextView = button(if (pollingEnabled(p)) t("Отключиться", "Disconnect") else t("Подключиться", "Connect"), Runnable {
            if (pollingEnabled(p)) {
                disconnectProfile(p)
            } else {
                connect(p)
            }
        })
        b.setTag("server-connect-" + p!!.id)
        b.setEnabled(!busy || pollingEnabled(p))
        return b
    }

    fun disconnectProfile(p: ServerProfiles.Profile?) {
        ServerConnection.disconnectAll(activity, p!!.id)
        connected.remove(p.id)
        snapshots.remove(p.id)
        lastPoll.remove(p.id)
        generation++
        manualEpoch++
        closeAuxiliary(p.id)
        val c: ServerConnection? = connections.remove(p.id)
        if (c != null) {
            c.close()
        }
        uploads.disconnect(p.id)
        downloads.disconnect(p.id)
        for (browser in ArrayList<WorkspaceFileBrowser>(sshBrowsers)) {
            if (browser.serverProfileId().equals(p.id)) {
                browser.close()
            }
        }
        for (editor in ArrayList<WorkspaceTextEditor>(editors)) {
            if (editor.serverProfileId().equals(p.id)) {
                editor.close()
            }
        }
        for (files in ArrayList<ProjectFiles>(textFiles)) {
            if (files.profile.id.equals(p.id)) {
                files.close()
                textFiles.remove(files)
            }
        }
        if (scheduleUi != null && scheduleUi!!.serverProfileId().equals(p.id)) {
            scheduleUi!!.close()
            scheduleUi = null
        }
        if (playerProfile.equals(p.id)) {
            closePlayer()
        }
        streamNames.remove(p.id)
        if (selected === p) {
            listing = null
            downloadSelection.clear()
            deleteSelectionMode = false
            if (moveProfile.equals(p.id)) {
                moveSelection.clear()
                moveProfile = ""
            }
        }
        render()
    }

    private fun updatePollLabel(p: ServerProfiles.Profile?, label: TextView) {
        val active: Boolean = pollingEnabled(p)
        label.setText("●  " + pollingLabel(p))
        val value = SpannableString(label.getText())
        value.setSpan(ForegroundColorSpan(if (active) 0xffe7bc55.toInt() else Pal.DANGER), 0, 1, 0)
        value.setSpan(RelativeSizeSpan(0.65f), 0, 1, 0)
        label.setText(value)
    }

    constructor(a: TermuxActivity, t: Terminal) {
        activity = a
        terminal = t
        store = ServerProfiles(a)
        downloads = WorkspaceServerDownloads(a)
        uploads = WorkspaceServerUploads(a, Runnable {
            if (!closed && visible && selected != null && !ServerConnection.disconnected(activity, selected!!.id) && tab.equals("files")) {
                loadFiles()
            }
        })
        downloads.presentation.listener(Runnable(this::transferChanged))
        uploads.presentation.listener(Runnable(this::transferChanged))
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    private fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    private fun row(): LinearLayout {
        val v = LinearLayout(activity)
        v.setGravity(Gravity.CENTER_VERTICAL)
        return v
    }

    private fun text(s: String, size: Int, color: Int): TextView {
        val v = TextView(activity)
        v.setText(s)
        v.setTextSize(size.toFloat())
        v.setTextColor(color)
        return v
    }

    private fun shape(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(dp(12).toFloat())
        d.setStroke(dp(1), Pal.BORDER)
        return d
    }

    private fun button(s: String, action: Runnable): TextView {
        val b: TextView = text(s, 14, Pal.ACCENT)
        b.setGravity(Gravity.CENTER)
        b.setMinHeight(dp(48))
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        b.setBackground(WorkspaceDialogs.background(activity, false))
        b.setOnClickListener(View.OnClickListener { v -> action.run() })
        return b
    }

    private fun setLeadingIcon(view: TextView, resource: Int) {
        val icon: Drawable = activity.getDrawable(resource)!!.mutate()
        icon.setTint(Pal.ACCENT)
        icon.setBounds(0, 0, dp(22), dp(22))
        view.setCompoundDrawables(icon, null, null, null)
        view.setCompoundDrawablePadding(dp(8))
    }

    private fun iconButton(label: String, resource: Int, click: Runnable): TextView {
        val b = WorkspaceIconButton(activity)
        b.setTextColor(Pal.ACCENT)
        b.setGravity(Gravity.CENTER)
        b.setMinWidth(dp(48))
        b.setMinHeight(dp(48))
        b.setBackground(WorkspaceDialogs.background(activity, false))
        b.setIcon(activity.getDrawable(resource)!!.mutate(), dp(22))
        b.setContentDescription(label)
        b.setOnClickListener(View.OnClickListener { v -> click.run() })
        if (Build.VERSION.SDK_INT >= 26) {
            b.setTooltipText(label)
        }
        return b
    }

    private fun control(row: LinearLayout, button: View?) {
        val p = LinearLayout.LayoutParams(dp(48), dp(48))
        if (row.getChildCount() > 0) {
            p.setMarginStart(dp(8))
        }
        row.addView(button, p)
    }

    private fun add(parent: LinearLayout?, child: View?) {
        val p = LinearLayout.LayoutParams(-1, -2)
        p.bottomMargin = dp(10)
        parent!!.addView(child, p)
    }

    private fun card(parent: LinearLayout?): LinearLayout {
        val c: LinearLayout = column()
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        c.setBackground(shape(Pal.PANEL))
        add(parent, c)
        return c
    }

    private fun title(parent: LinearLayout, s: String) {
        val v: TextView = text(s, 18, Pal.TEXT)
        v.setTypeface(null, Typeface.BOLD)
        v.setPadding(0, dp(8), 0, dp(12))
        parent.addView(v)
    }

    private fun field(parent: LinearLayout, label: String, value: String, multi: Boolean): EditText {
        parent.addView(text(label, 12, Pal.MUTED))
        val v = EditText(activity)
        v.setText(value)
        v.setTextColor(Pal.TEXT)
        v.setTextSize(14.0f)
        v.setSingleLine(!multi)
        v.setMinHeight(dp(48))
        if (multi) {
            v.setMinLines(3)
        }
        v.setContentDescription(label)
        add(parent, v)
        return v
    }

    private fun dialog(title: String, form: View): AlertDialog {
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(title).setView(form).setNegativeButton(t("Отмена", "Cancel"), null).create()
        return d
    }

    private fun show(d: AlertDialog) {
        dialogs++
        ownedDialogs.add(d)
        d.setOnDismissListener(DialogInterface.OnDismissListener { x ->
            dialogs = Math.max(0, dialogs - 1)
            ownedDialogs.remove(d)
        })
        d.show()
    }

    private fun message(title: String, body: String) {
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(title).setMessage(body).setPositiveButton("OK", null).create()
        show(d)
    }

    private fun confirm(title: String, body: String, action: Runnable) {
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(title).setMessage(body).setPositiveButton(t("Да", "Yes"), DialogInterface.OnClickListener { x, w -> action.run() }).setNegativeButton(t("Нет", "No"), null).create()
        show(d)
    }

    private fun connection(p: ServerProfiles.Profile?): ServerConnection? {
        var c: ServerConnection? = connections.get(p!!.id)
        if (c == null) {
            c = ServerConnection(activity, p)
            connections.put(p.id, c)
        }
        return c
    }

    fun interface Work {
        @Throws(Exception::class)
        fun run(): JSONObject
    }

    fun interface Done {
        @Throws(Exception::class)
        fun done(value: JSONObject?)
    }

    private fun run(work: Work, done: Done) {
        run(work, done, true)
    }

    private fun run(work: Work, done: Done, feedback: Boolean) {
        if (closed || busy || !feedback && pollBusy) {
            return
        }
        if (feedback) {
            manualEpoch++
            busy = true
            error = ""
            render()
        } else {
            pollBusy = true
        }
        val g: Int = generation
        worker.execute(Runnable {
            var result: JSONObject? = null
            var failure: String? = null
            try {
                result = work.run()
            } catch (e: Exception) {
                val code: String? = e.message
                failure = if (code != null && (code as java.lang.String).matches("[A-Z_]{1,80}")) code else "OPERATION_FAILED"
            }
            val value: JSONObject? = result
            val problem: String? = failure
            main.post(Runnable {
                if (closed) {
                    return@Runnable
                }
                if (feedback) {
                    busy = false
                } else {
                    pollBusy = false
                }
                if (g != generation) {
                    if (feedback) {
                        render()
                    }
                    return@Runnable
                }
                if (problem != null) {
                    if (feedback) {
                        error = explain(problem)
                        render()
                    } else {
                        monitorError(problem)
                    }
                    return@Runnable
                }
                try {
                    done.done(value)
                    if (feedback) {
                        render()
                    }
                } catch (e: Exception) {
                    if (feedback) {
                        error = explain("OPERATION_FAILED")
                        render()
                    } else {
                        monitorError("OPERATION_FAILED")
                    }
                }
            })
        })
    }

    /** Explicit file actions update their own region/dialog, never the page.*/
    private fun runInline(work: Work, done: Done, failure: Consumer<String>) {
        runInline(work, done, failure, Runnable {  })
    }

    private fun runInline(work: Work, done: Done, failure: Consumer<String>, discarded: Runnable) {
        if (closed || busy) {
            return
        }
        manualEpoch++
        busy = true
        val g: Int = generation
        worker.execute(Runnable {
            var result: JSONObject? = null
            var problem: String? = null
            try {
                result = work.run()
            } catch (e: Exception) {
                val code: String? = e.message
                problem = if (code != null && (code as java.lang.String).matches("[A-Z_]{1,80}")) code else "OPERATION_FAILED"
            }
            val value: JSONObject? = result
            val errorCode: String? = problem
            main.post(Runnable {
                if (closed) {
                    discarded.run()
                    return@Runnable
                }
                busy = false
                if (g != generation) {
                    discarded.run()
                    return@Runnable
                }
                if (errorCode != null) {
                    failure.accept(errorCode)
                    return@Runnable
                }
                try {
                    done.done(value)
                } catch (e: Exception) {
                    failure.accept("OPERATION_FAILED")
                }
            })
        })
    }

    private fun monitorError(code: String) {
        if (visible && foreground) {
            updateStreamPanel()
        }
        if (visible && foreground && monitorStatus != null) {
            val value: String = t("Метрики не обновлены: ", "Metrics not updated: ") + code
            monitorStatus!!.setText(value)
            monitorStatus!!.setContentDescription(explain(code))
            monitorStatus!!.setTextColor(Pal.DANGER)
        }
    }

    private fun updateLiveViews(data: JSONObject) {
        if (!visible || !foreground) {
            return
        }
        val metrics: JSONObject? = data.optJSONObject("metrics")
        if (metrics == null) {
            return
        }
        if (metricCards != null) {
            metricCards!!.update(metrics)
        }
        if (sampleText != null) {
            sampleText!!.setText(sampleLabel(metrics))
        }
        if (monitorStatus != null) {
            monitorStatus!!.setText(t("Автообновление данных от сервера", "Auto-update data from server"))
            monitorStatus!!.setContentDescription(monitorStatus!!.getText())
            monitorStatus!!.setTextColor(Pal.MUTED)
        }
        updateStreamPanel()
        val tasks: JSONArray? = data.optJSONArray("tasks")
        val fingerprint: String = if (tasks == null) "" else tasks.toString()
        if (taskRows != null && !fingerprint.equals(taskFingerprint)) {
            taskFingerprint = fingerprint
            preserveViewport(Runnable {
                taskRows!!.removeAllViews()
                tasks(taskRows, tasks)
            })
        }
    }

    private fun sampleLabel(metrics: JSONObject): String {
        return t("Снимок: ", "Snapshot: ") + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(metrics.optLong("time") * 1000))
    }

    private fun explain(code: String): String {
        when (code) {
            "SSH_FAILED" -> {
                return t("SSH недоступен. Проверьте сеть, ключ/пароль, права и доверенный ключ хоста. При смене ключа требуется повторная проверка отпечатка.", "SSH unavailable. Check network, credentials, permissions and pinned host key. A changed key requires fingerprint verification.")
            }
            "OPENSSH_REQUIRED" -> {
                return t("Нужен пакет openssh в Termux. Установите его через менеджер пакетов.", "The Termux openssh package is required. Install it using the package manager.")
            }
            "PYTHON_OR_AGENT_UNAVAILABLE" -> {
                return t("Нужен Python 3 на сервере или совместимый агент. SSH-терминал остаётся доступен.", "Python 3 or a compatible agent is required on the server. The SSH terminal is still available.")
            }
            "ACTIVE_TASKS" -> {
                return t("Удаление заблокировано: есть активные или неподконтрольные задачи. Завершите их отдельно; для неподконтрольной задачи проверьте процессы вручную и удалите только её запись.", "Removal blocked: active or unmanaged tasks exist. Finish them separately; inspect unmanaged processes manually before forgetting their records.")
            }
            "SERVICE_PERMISSION_OR_UNAVAILABLE" -> {
                return t("Недостаточно прав или служба недоступна. Агент не использует sudo и не повышает права.", "Insufficient permissions or service unavailable. The agent does not use sudo or elevate privileges.")
            }
            "PASSWORD_REQUIRED" -> {
                return t("Введите и сохраните пароль в профиле сервера; он шифруется Android Keystore.", "Enter and save the server password; Android Keystore encrypts it.")
            }
            else -> {
                return t("Операция не выполнена. Код: ", "Operation failed. Code: ") + code
            }
        }
    }

    fun attach(host: LinearLayout) {
        root = host
        visible = true
        render()
    }

    fun visibility(v: Boolean) {
        visible = v
    }

    fun resume() {
        foreground = true
        main.removeCallbacks(poll)
        main.postDelayed(poll, 1000L)
    }

    fun pause() {
        foreground = false
        main.removeCallbacks(poll)
        closePlayer()
    }

    fun back(): Boolean {
        if (selected != null) {
            closeAuxiliary(selected!!.id)
            selected = null
            project = null
            listing = null
            generation++
            render()
            return true
        }
        return false
    }

    fun info() {
        message(t("Сервер + Agent", "Server + Agent"), t("Устанавливается только с согласия в ~/.local/share/termux-jobs-server, требует Linux, Bash и Python 3.8+. Ведёт задачи и отдаёт файлы. Права равны SSH-пользователю; root означает полный доступ. Для ограничения прав используйте отдельную учётную запись.\n\nАвтозапуска после перезагрузки сервера нет. При подключении агент возобновится. Задачи продолжаются без телефона, но не после перезагрузки сервера; команды должны работать на переднем плане. Журналы до 2 МБ на задачу, последние 40 записей; команды и вывод могут содержать секреты.\n\nВ фоне телефона мониторинг приостановлен. Просмотр видео — через временный SSH-туннель до 30 минут; встроенный плеер освобождается в фоне. Агент удаляется отдельной кнопкой после проверки задач, проекты не удаляются. SSH в создании сессий также сохранён.", "Installed with consent under ~/.local/share/termux-jobs-server; requires Linux, Bash and Python 3.8+. Manages tasks and serves files. It has the SSH user's permissions; root means full access. Use a separate account to restrict access.\n\nNo automatic server-boot startup. Connecting restarts the agent. Tasks survive phone disconnection, not server reboot; commands must stay in the foreground. Logs capped at 2 MB/task, latest 40 records. Commands/output may contain secrets.\n\nPhone background monitoring pauses. Video uses a temporary SSH tunnel up to 30 minutes; the built-in player is released in the background. Agent removal is a separate action after checking tasks, without deleting projects. Existing SSH session creation is retained."))
    }

    fun rerender() {
        render()
    }

    private fun render() {
        if (root == null || closed || !visible) {
            return
        }
        val y: Int = if (scroll == null) 0 else scroll!!.getScrollY()
        sampleText = null
        monitorStatus = null
        metricCards = null
        taskRows = null
        streamPanel = null
        fileRows = null
        filePathLabel = null
        fileStatus = null
        serviceText = null
        sectionBody = null
        projectBanner = null
        sectionButtons.clear()
        pollLabels.clear()
        listMetrics.clear()
        transferRows.clear()
        root!!.removeAllViews()
        scroll = ServerScrollView(activity)
        scroll!!.setFillViewport(true)
        val body: LinearLayout = column()
        body.setPadding(0, dp(12), 0, dp(16))
        scroll!!.addView(body)
        root!!.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        if (!store.writable) {
            add(body, text(t("Профили повреждены или имеют неподдерживаемый формат; запись отключена, исходный файл сохранён.", "Profiles are damaged or unsupported; writes disabled, original file preserved."), 14, Pal.DANGER))
        }
        val heading: LinearLayout = row()
        val h: TextView = text(if (selected == null) t("Серверы", "Servers") else selected!!.name, 19, Pal.TEXT)
        h.setTypeface(null, Typeface.BOLD)
        heading.addView(h, LinearLayout.LayoutParams(0, -2, 1.0f))
        h.setMaxLines(2)
        h.setEllipsize(TextUtils.TruncateAt.END)
        if (selected != null) {
            val diagnostics: TextView = button(t("Диагностика", "Diagnostics"), Runnable(this::serverDiagnostics))
            diagnostics.setTextSize(12.0f)
            diagnostics.setPadding(dp(4), 0, dp(4), 0)
            diagnostics.setContentDescription(t("Диагностика сервера", "Server diagnostics"))
            val diagnosticsParams = LinearLayout.LayoutParams(dp(106), dp(48))
            diagnosticsParams.setMarginEnd(dp(8))
            heading.addView(diagnostics, diagnosticsParams)
        }
        heading.addView(iconButton(t("Об агенте сервера", "About server agent"), com.termux.R.drawable.workspace_server_info, Runnable(this::info)))
        if (selected != null) {
            val menuParams = LinearLayout.LayoutParams(dp(48), dp(48))
            menuParams.setMarginStart(dp(8))
            heading.addView(iconButton(t("Настройки сервера", "Server settings"), com.termux.R.drawable.workspace_more, Runnable(this::menu)), menuParams)
        }
        add(body, heading)
        if (busy) {
            add(body, text(t("Подключение / выполнение…", "Connecting / working…"), 13, Pal.ACCENT))
        }
        if (!error.isEmpty()) {
            add(body, text(error, 13, Pal.DANGER))
        }
        if (selected == null) {
            renderList(body)
        } else {
            renderServer(body)
        }
        scroll!!.restoreTo(y)
    }

    private fun renderList(body: LinearLayout) {
        if (store.profiles.isEmpty()) {
            add(body, text(t("Добавьте свой сервер. Подключения независимы от обычных SSH-сессий.", "Add your server. These connections are independent of existing SSH sessions."), 14, Pal.MUTED))
        }
        if (!store.profiles.isEmpty()) {
            add(body, text(t("Автообновление данных от сервера: после подключения каждые 10 с внутри сервера и 30 с в остальных разделах приложения. В фоне приложения приостановлено. «Отключиться» закрывает соединения панели и передачи; сервер, агент, удалённые задачи и отдельные SSH-сессии продолжают работать.", "Auto-update data from server: every 10 s inside the server view and 30 s elsewhere after connecting. Paused while the app is in the background. Disconnect closes workspace connections and transfers; the server, agent, remote tasks and independent SSH sessions keep running."), 12, Pal.MUTED))
        }
        for (p in store.profiles) {
            val c: LinearLayout = card(body)
            title(c, p.name)
            val address: LinearLayout = row()
            address.addView(text(p.user + "@" + p.host + ":" + p.port, 13, Pal.MUTED), LinearLayout.LayoutParams(0, -2, 1.0f))
            val toggle: TextView = connectionButton(p)
            val toggleParams = LinearLayout.LayoutParams(-2, dp(48))
            toggleParams.setMarginStart(dp(8))
            address.addView(toggle, toggleParams)
            add(c, address)
            val status: TextView = text("", 12, Pal.MUTED)
            pollLabels.put(p.id, status)
            updatePollLabel(p, status)
            add(c, status)
            val snapshot: JSONObject? = snapshots.get(p.id)
            if (snapshot != null && snapshot.has("metrics")) {
                val metrics: TextView = text(metricSummary(snapshot.optJSONObject("metrics")), 13, Pal.ACCENT)
                listMetrics.put(p.id, metrics)
                add(c, metrics)
            }
            transferRows(c, p)
            add(c, button(t("Открыть", "Open"), Runnable {
                if (busy) {
                    return@Runnable
                }
                selected = p
                directory = "~"
                tab = "overview"
                listing = null
                generation++
                render()
                if (p.automatic && !ServerConnection.disconnected(activity, p.id) && !pollingEnabled(p)) {
                    connect()
                }
            }))
            c.setOnLongClickListener(View.OnLongClickListener { v ->
                if (busy) {
                    return@OnLongClickListener true
                }
                selected = p
                generation++
                render()
                menu()
                return@OnLongClickListener true
            })
        }
        val add: TextView = button(t("Добавить сервер", "Add server"), Runnable { edit(null) })
        add.setEnabled(store.writable && !busy)
        add(body, add)
        add(body, text(t("Профиль хранится на телефоне: это адрес сервера, порт, пользователь и способ входа. Агент, задачи, файлы и программы находятся на сервере и остаются там после удаления профиля из приложения. Профили серверов пока не входят в экспорт библиотеки.", "A profile is stored on this phone: the server address, port, user and authentication method. The agent, tasks, files and programs live on the server and remain there when you delete the app profile. Server profiles are not yet included in library exports."), 12, Pal.MUTED))
    }

    private fun renderServer(body: LinearLayout) {
        val p: ServerProfiles.Profile? = selected
        add(body, text(p!!.user + "@" + p.host + ":" + p.port, 13, Pal.MUTED))
        transferRows(body, p)
        val all: TextView = button(t("Все серверы", "All servers"), Runnable {
            if (!busy) {
                back()
            }
        })
        setLeadingIcon(all, com.termux.R.drawable.workspace_server_back)
        add(body, all)
        val actions: LinearLayout = row()
        actions.addView(connectionButton(p), LinearLayout.LayoutParams(0, -2, 1.0f))
        val terminalButton: TextView = iconButton(t("Терминал", "Terminal"), com.termux.R.drawable.workspace_terminal, Runnable(this::openTerminal))
        val terminalParams = LinearLayout.LayoutParams(dp(48), dp(48))
        terminalParams.setMarginStart(dp(8))
        actions.addView(terminalButton, terminalParams)
        add(body, actions)
        if (ServerConnection.disconnected(activity, p.id)) {
            add(body, text(t("Отключено. Подключитесь для работы с панелью и автообновления данных от сервера. Удалённые задачи и отдельные SSH-сессии продолжают работать.", "Disconnected. Connect to use the workspace and auto-update data from server. Remote tasks and independent SSH sessions keep running."), 13, Pal.MUTED))
            return
        }
        val data: JSONObject? = snapshots.get(p.id)
        if (data == null || !data.has("metrics")) {
            add(body, text(t("Подключитесь для проверки Python и агента. Без агента доступны SSH-терминал и файловый менеджер.", "Connect to check Python and agent. SSH terminal and file browser work without the agent."), 14, Pal.MUTED))
            add(body, button(t("Проверить состояние агента", "Check agent status"), Runnable(this::agentStateDialog)))
            add(body, button(t("Файлы через SSH", "Files over SSH"), Runnable(this::sshFiles)))
            add(body, button(t("Расписания задач", "Task schedules"), Runnable {
                if (!busy && selected != null) {
                    scheduleUi()!!.list()
                }
            }))
            return
        }
        val metrics: JSONObject = data.optJSONObject("metrics")
        metricCards = ServerMetricCards(activity)
        metricCards!!.update(metrics)
        add(body, metricCards)
        sampleText = text(sampleLabel(metrics), 12, Pal.MUTED)
        add(body, sampleText)
        monitorStatus = text(t("Автообновление данных от сервера", "Auto-update data from server"), 11, Pal.MUTED)
        monitorStatus!!.setSingleLine(true)
        monitorStatus!!.setEllipsize(TextUtils.TruncateAt.END)
        add(body, monitorStatus)
        val strip = HorizontalScrollView(activity)
        strip.setHorizontalScrollBarEnabled(false)
        val choices: LinearLayout = row()
        val keys: Array<String> = arrayOf<String>("overview", "tasks", "files", "services")
        val labels: Array<String> = arrayOf<String>(t("Проекты", "Projects"), t("Задачи", "Tasks"), t("Файлы", "Files"), t("Службы", "Services"))
        for (i in 0 until (keys.size)) {
            val key: String = keys[i]
            val b: TextView = button(labels[i], Runnable { selectSection(key) })
            sectionButtons.put(key, b)
            b.setSelected(tab.equals(key))
            b.setBackground(WorkspaceDialogs.background(activity, tab.equals(key)))
            b.setSingleLine(true)
            val option = LinearLayout.LayoutParams(-2, dp(48))
            if (i < keys.size - 1) {
                option.setMarginEnd(dp(8))
            }
            choices.addView(b, option)
        }
        strip.addView(choices)
        add(body, strip)
        projectBanner = column()
        body.addView(projectBanner)
        sectionBody = column()
        body.addView(sectionBody)
        renderSection()
        streamPanel = column()
        body.addView(streamPanel)
        updateStreamPanel()
    }

    private fun selectSection(key: String) {
        if (busy || selected == null || key.equals(tab) || !sectionButtons.containsKey(key)) {
            return
        }
        tab = key
        listing = null
        renderSection()
        if (key.equals("files")) {
            loadFiles()
        } else if (key.equals("services")) {
            services()
        }
    }

    private fun preserveViewport(update: Runnable) {
        if (scroll == null) {
            update.run()
        } else {
            scroll!!.preserve(update)
        }
    }

    private fun renderSection() {
        if (sectionBody == null || projectBanner == null || selected == null) {
            return
        }
        val data: JSONObject? = snapshots.get(selected!!.id)
        if (data == null) {
            return
        }
        val previousHeight: Int = sectionBody!!.getHeight()
        preserveViewport(Runnable {
            taskRows = null
            fileRows = null
            filePathLabel = null
            fileStatus = null
            serviceText = null
            sectionBody!!.setMinimumHeight(if (tab.equals("files") || tab.equals("services")) previousHeight else 0)
            sectionBody!!.removeAllViews()
            projectBanner!!.removeAllViews()
            for (entry in sectionButtons.entries) {
                val active: Boolean = tab.equals(entry.key)
                entry.value.setSelected(active)
                entry.value.setBackground(WorkspaceDialogs.background(activity, active))
            }
            if (project != null) {
                add(projectBanner, text(t("Проект: ", "Project: ") + project!!.name + "\n" + project!!.path, 13, Pal.MUTED))
                add(projectBanner, button(t("Снять выбор проекта", "Clear project selection"), Runnable {
                    project = null
                    directory = "~"
                    renderSection()
                }))
            }
            if (tab.equals("overview")) {
                projects(sectionBody)
            } else if (tab.equals("tasks")) {
                taskRows = column()
                add(sectionBody, taskRows)
                taskFingerprint = if (data.optJSONArray("tasks") == null) "" else data.optJSONArray("tasks").toString()
                tasks(taskRows, data.optJSONArray("tasks"))
            } else if (tab.equals("files")) {
                files(sectionBody)
            } else {
                add(sectionBody, button(t("Обновить службы", "Refresh services"), Runnable(this::services)))
                add(sectionBody, button(t("Журнал / перезапуск службы…", "Service log / restart…"), Runnable(this::serviceDialog)))
                serviceText = text(if (listing == null) "" else listing!!.optString("text"), 12, Pal.TEXT)
                add(sectionBody, serviceText)
            }
        })
    }

    private fun state(value: String): String {
        when (value) {
            "queued" -> {
                return t("В очереди", "Queued")
            }
            "starting" -> {
                return t("Запускается", "Starting")
            }
            "running" -> {
                return t("Выполняется", "Running")
            }
            "done" -> {
                return t("Завершена", "Completed")
            }
            "failed" -> {
                return t("Ошибка", "Failed")
            }
            "cancelled" -> {
                return t("Остановлена", "Stopped")
            }
            "unmanaged" -> {
                return t("Требует ручной проверки", "Needs manual inspection")
            }
            else -> {
                return value
            }
        }
    }

    private fun metricSummary(m: JSONObject?): String {
        if (m == null) {
            return ""
        }
        return "CPU " + (if (m.isNull("cpu")) "—" else m.optDouble("cpu").toString() + "%") + "  · RAM " + size(m.optLong("ramUsed")) + " / " + size(m.optLong("ramTotal")) + "\n" + t("Диск ", "Disk ") + size(m.optLong("diskUsed")) + " / " + size(m.optLong("diskTotal"))
    }

    private fun menu() {
        if (selected == null || busy) {
            return
        }
        val labels: Array<String> = arrayOf<String>(t("Настройки агента", "Agent settings"), t("Настройки подключения", "Connection settings"), t("Проверить / заменить ключ хоста…", "Verify / replace host key…"), t("Отключиться", "Disconnect"), t("Удалить профиль из приложения…", "Delete profile from app…"), t("Диагностика подключения", "Connection diagnostics"))
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(selected!!.name).setItems(labels, DialogInterface.OnClickListener { x, w ->
            if (w == 0) {
                agentSettings()
            }
            if (w == 1) {
                edit(selected)
            }
            if (w == 2) {
                scan(true)
            }
            if (w == 5) {
                connectionDiagnostics()
            }
            if (w == 3) {
                disconnectProfile(selected)
            }
            if (w == 4) {
                val p: ServerProfiles.Profile? = selected
                confirm(t("Удалить профиль?", "Delete profile?"), t("Агент и задачи на сервере останутся. Удаляется только профиль подключения на телефоне.", "Server agent and tasks remain. Only the phone connection profile is removed."), Runnable {
                    try {
                        store.profiles.remove(p)
                        store.save()
                        closeAuxiliary(p!!.id)
                        val c: ServerConnection? = connections.remove(p.id)
                        if (c != null) {
                            c.close()
                        }
                        connected.remove(p.id)
                        snapshots.remove(p.id)
                        agentPresence.remove(p.id)
                        back()
                    } catch (e: Exception) {
                        message("Error", explain("STORE_ERROR"))
                    }
                })
            }
        }).create()
        show(d)
    }

    private fun serverDiagnostics() {
        if (selected == null) {
            return
        }
        ownedDialogs.removeIf(Predicate<AlertDialog> { d -> !d.isShowing() })
        val dialog: AlertDialog = ServerDiagnosticsUi.show(activity, selected, store)
        ownedDialogs.add(dialog)
    }

    private fun connectionDiagnostics() {
        if (selected == null || busy) {
            return
        }
        val c = ServerConnection(activity, selected)
        auxiliary.add(c)
        val future: Array<Future<*>?> = arrayOf<Future<*>?>(null)
        val status: TextView = text(t("Проверка доступности порта → доверия SSH → Python → файла агента…", "Checking port reachability → SSH trust → Python → agent file…"), 14, Pal.TEXT)
        status.setPadding(dp(16), dp(8), dp(16), dp(8))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Диагностика подключения", "Connection diagnostics")).setView(status).setPositiveButton(t("Закрыть", "Close"), null).create()
        show(dialog)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            dialogs = Math.max(0, dialogs - 1)
            ownedDialogs.remove(dialog)
            queues.cancelHelper(future[0])
            c.close()
            auxiliary.remove(c)
        })
        try {
            future[0] = queues.helper(Runnable {
                var result: JSONObject? = null
                try {
                    result = c.diagnose()
                } catch (ignored: Exception) {
                } finally {
                    c.close()
                    auxiliary.remove(c)
                }
                val report: JSONObject? = result
                main.post(Runnable {
                    if (closed || !dialog.isShowing()) {
                        return@Runnable
                    }
                    status.setText(diagnosticReport(report))
                })
            })
        } catch (e: RejectedExecutionException) {
            c.close()
            auxiliary.remove(c)
            status.setText(t("Все фоновые проверки заняты. Повторите позже.", "Background checks are busy. Try again later."))
        }
    }

    fun diagnosticReport(report: JSONObject?): String {
        if (report == null) {
            return t("Проверка не завершена.", "Check did not complete.")
        }
        if (!report.optBoolean("network")) {
            return t("Порт недоступен. Проверьте адрес, сеть, VPN, firewall и запущен ли SSH-сервер.", "Port unreachable. Check address, connectivity, VPN, firewall and whether SSH is running.")
        }
        val text: String = t("Порт доступен.", "Port reachable.")
        if (!report.optBoolean("trusted")) {
            return text + "\n" + t("Ключ сервера ещё не подтверждён. Подключитесь и сверьте отпечаток. Авторизация не проверялась.", "Server key not pinned yet. Connect and verify the fingerprint. Authentication was not tested.")
        }
        val probe: JSONObject? = report.optJSONObject("probe")
        if (probe == null) {
            return text + "\n" + t("SSH / авторизация / Python: проверка не пройдена. Этот ответ не позволяет точно отличить неверный ключ/пароль, изменённый ключ хоста и отсутствие Python. Откройте SSH-терминал для уточнения.", "SSH / authentication / Python: check failed. This response cannot distinguish bad credentials, a changed host key or missing Python. Open the SSH terminal for details.")
        }
        return text + "\n" + t("SSH и авторизация: успешно.", "SSH and authentication: passed.") + "\nPython: " + probe.optString("python") + " · " + (if (probe.optBoolean("supported")) "OK" else t("Нужен 3.8+", "Requires 3.8+")) + "\n" + (if (probe.optBoolean("agent")) t("Файл агента найден.", "Agent file found.") else t("Файл агента не найден.", "Agent file not found.")) + "\n" + t("Агент и задачи не запускались; наличие файла не доказывает его работоспособность.", "Agent and tasks were not started; file presence does not prove agent health.")
    }

    fun agentLabel(profile: ServerProfiles.Profile): String {
        val present: Boolean? = knownAgent(profile)
        return if (present == null) t("Агент: состояние не проверено", "Agent: not checked") else if (present) t("Агент: установлен · по последней проверке", "Agent: installed · last check") else t("Агент: не установлен · по последней проверке", "Agent: not installed · last check")
    }

    private fun knownAgent(p: ServerProfiles.Profile?): Boolean? {
        val data: JSONObject? = snapshots.get(p!!.id)
        if (data != null && data.optInt("protocol") == 1 && data.has("metrics")) {
            return true
        }
        return agentPresence.get(p.id)
    }

    private fun agentSettings() {
        if (selected == null || busy) {
            return
        }
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Настройки агента", "Agent settings")).setItems(arrayOf<String>(t("Проверить состояние агента", "Check agent status"), t("Удалить агента…", "Remove agent…"), t("Компоненты агента", "Agent components")), DialogInterface.OnClickListener { d, w ->
            if (w == 0) {
                agentStateDialog()
            } else if (w == 1) {
                uninstall()
            } else {
                components()
            }
        }).create()
        show(dialog)
    }

    private fun agentStateDialog() {
        if (selected == null || busy) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val c: ServerConnection? = connection(p)
        val body: LinearLayout = column()
        body.setPadding(dp(16), 0, dp(16), dp(12))
        val cached: Boolean? = knownAgent(p)
        val status: TextView = text(if (java.lang.Boolean.TRUE.equals(cached)) t("Агент установлен · проверка состояния…", "Agent installed · checking status…") else t("Проверка состояния агента…", "Checking agent status…"), 14, Pal.TEXT)
        add(body, status)
        val installButton: TextView = button(t("Установить Agent…", "Install Agent…"), Runnable {  })
        val removeButton: TextView = button(t("Удалить агента…", "Remove agent…"), Runnable {  })
        val retryButton: TextView = button(t("Проверить снова", "Check again"), Runnable {  })
        for (button in arrayOf<TextView>(installButton, removeButton, retryButton)) {
            button.setVisibility(View.GONE)
            add(body, button)
        }
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Состояние агента", "Agent status")).setView(body).setPositiveButton("OK", null).create()
        show(dialog)
        installButton.setOnClickListener(View.OnClickListener { v ->
            dialog.dismiss()
            if (selected === p) {
                install()
            }
        })
        removeButton.setOnClickListener(View.OnClickListener { v ->
            dialog.dismiss()
            if (selected === p) {
                uninstall()
            }
        })
        retryButton.setOnClickListener(View.OnClickListener { v ->
            dialog.dismiss()
            if (selected === p) {
                if (c!!.trusted()) {
                    agentStateDialog()
                } else {
                    connect()
                }
            }
        })
        if (!c!!.trusted()) {
            status.setText(t("Сначала подключитесь и подтвердите ключ сервера. Состояние агента пока неизвестно.", "Connect and verify the server key first. Agent status is unknown."))
            retryButton.setText(t("Подключить / проверить", "Connect / verify"))
            retryButton.setVisibility(View.VISIBLE)
            return
        }
        checkAgentSettings(Work(c::probe), p, dialog, status, installButton, removeButton, retryButton)
    }

    private fun checkAgentSettings(probe: Work, p: ServerProfiles.Profile?, dialog: AlertDialog, status: TextView, installButton: TextView, removeButton: TextView, retryButton: TextView) {
        runInline(probe, Done { result ->
            val installed: Boolean = result!!.getBoolean("agent")
            agentPresence.put(p!!.id, installed)
            if (!installed) {
                snapshots.remove(p.id)
                connected.remove(p.id)
            }
            if (!dialog.isShowing() || selected !== p) {
                return@Done
            }
            status.setText(if (installed) t("Агент установлен", "Agent installed") else t("Агент не установлен", "Agent not installed"))
            if (!result.optBoolean("supported", false)) {
                status.append("\n" + t("Для работы агента нужен Python 3.8+.", "Agent requires Python 3.8+."))
            }
            installButton.setVisibility(if (!installed && result.optBoolean("supported", false)) View.VISIBLE else View.GONE)
            removeButton.setVisibility(if (installed) View.VISIBLE else View.GONE)
            retryButton.setVisibility(View.GONE)
        }, Consumer<String> { code ->
            if (dialog.isShowing() && selected === p) {
                status.setText(t("Не удалось проверить состояние агента. Ошибка подключения не означает, что агент отсутствует.", "Could not verify agent status. A connection error does not mean the agent is absent.") + "\n" + explain(code))
                retryButton.setVisibility(View.VISIBLE)
            }
        })
    }

    private fun edit(old: ServerProfiles.Profile?) {
        if (busy || !store.writable) {
            return
        }
        val draft = ServerProfiles.Profile()
        if (old != null) {
            draft.id = old.id
        }
        val form: LinearLayout = column()
        form.setPadding(dp(16), 0, dp(16), 0)
        val name: EditText = field(form, t("Название", "Name"), if (old == null) "" else old.name, false)
        val host: EditText = field(form, t("Адрес без порта", "Host without port"), if (old == null) "" else old.host, false)
        val port: EditText = field(form, t("Порт", "Port"), if (old == null) "22" else "" + old.port, false)
        val user: EditText = field(form, t("Пользователь", "User"), if (old == null) "" else old.user, false)
        port.setInputType(2)
        val auth = Spinner(activity)
        auth.setAdapter(ArrayAdapter<String>(activity, R.layout.simple_spinner_dropdown_item, arrayOf<String>(t("Автоматически / SSH-ключ", "Automatic / SSH key"), t("Файл SSH-ключа", "SSH key file"), t("Пароль (Keystore)", "Password (Keystore)"))))
        val modes: Array<String> = arrayOf<String>("auto", "key", "password")
        auth.setSelection(if (old == null) 0 else Arrays.asList(*modes).indexOf(old.auth))
        add(form, auth)
        val keyFields: LinearLayout = column()
        val passwordFields: LinearLayout = column()
        val identity: EditText = field(keyFields, t("Путь приватного ключа", "Private key path"), if (old == null) "" else old.identity, false)
        val keyPicker = WorkspaceSshKeyPicker(activity)
        keyFields.removeView(identity)
        val identityRow: LinearLayout = row()
        identityRow.addView(identity, LinearLayout.LayoutParams(0, -2, 1.0f))
        control(identityRow, iconButton(t("Выбрать файл ключа: Termux, Ubuntu или память устройства", "Choose key file: Termux, Ubuntu or device storage"), com.termux.R.drawable.workspace_folder, Runnable { keyPicker.choose(Consumer<String>(identity::setText)) }))
        add(keyFields, identityRow)
        val passphrase: EditText = field(keyFields, t("Passphrase (если требуется)", "Passphrase (if required)"), "", false)
        passphrase.setInputType(0x81)
        passphrase.setSaveEnabled(false)
        passphrase.setHint(t("Пусто — сохранить прежний для того же ключа", "Blank keeps existing for the same key"))
        val clearPassphrase = CheckBox(activity)
        clearPassphrase.setText(t("Удалить сохранённый passphrase", "Remove saved passphrase"))
        clearPassphrase.setTextColor(Pal.TEXT)
        add(keyFields, clearPassphrase)
        val password: EditText = field(passwordFields, t("Пароль: пусто — сохранить прежний", "Password: blank keeps existing"), "", false)
        password.setInputType(0x81)
        password.setSaveEnabled(false)
        if (Build.VERSION.SDK_INT >= 26) {
            passphrase.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
            password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
        }
        add(form, keyFields)
        add(form, passwordFields)
        val updateAuth = Runnable {
            keyFields.setVisibility(if (auth.getSelectedItemPosition() == 1) View.VISIBLE else View.GONE)
            passwordFields.setVisibility(if (auth.getSelectedItemPosition() == 2) View.VISIBLE else View.GONE)
        }
        auth.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                updateAuth.run()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
            }
        })
        updateAuth.run()
        add(form, text(t("Автоматически: доступные SSH-ключи без запроса пароля. Файл ключа: указанный приватный ключ. Пароль: пароль пользователя сервера. Passphrase расшифровывает защищённый файл ключа.", "Automatic: available SSH keys without password prompts. Key file: the specified private key. Password: the server user’s password. A passphrase unlocks an encrypted key file."), 12, Pal.MUTED))
        val automatic = CheckBox(activity)
        automatic.setText(t("Подключаться при открытии сервера", "Connect when opening server"))
        automatic.setTextColor(Pal.TEXT)
        automatic.setChecked(old == null || old.automatic)
        add(form, automatic)
        add(form, text(t("Пароль и passphrase сохраняются только зашифрованными Android Keystore. После переноса данных может потребоваться повторный ввод. Ключ хоста подтверждается отдельно.", "Password and passphrase are saved only encrypted with Android Keystore. Moving data may require re-entry. Host key is verified separately."), 12, Pal.MUTED))
        val viewport = ScrollView(activity)
        viewport.addView(form)
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Профиль сервера", "Server profile")).setView(viewport).setPositiveButton(t("Сохранить", "Save"), null).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(d)
        d.setOnDismissListener(DialogInterface.OnDismissListener { x ->
            dialogs = Math.max(0, dialogs - 1)
            ownedDialogs.remove(d)
            password.setText("")
            passphrase.setText("")
            keyPicker.close()
        })
        d.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            try {
                draft.name = (name.getText().toString() as java.lang.String).trim()
                draft.host = (host.getText().toString() as java.lang.String).trim()
                draft.user = (user.getText().toString() as java.lang.String).trim()
                draft.port = Integer.parseInt(port.getText().toString())
                draft.auth = modes[auth.getSelectedItemPosition()]
                draft.identity = (identity.getText().toString() as java.lang.String).trim()
                draft.automatic = automatic.isChecked()
                if (!ServerProfiles.valid(draft)) {
                    throw IllegalArgumentException()
                }
                val same: Boolean = old != null && old.host.equals(draft.host) && old.user.equals(draft.user) && old.port == draft.port
                if (draft.auth.equals("password") || draft.auth.equals("key")) {
                    val input: EditText = if (draft.auth.equals("key")) passphrase else password
                    val secret = CharArray(input.length())
                    TextUtils.getChars(input.getText(), 0, input.length(), secret, 0)
                    try {
                        for (c in secret) {
                            if (c == '\n' || c == '\r' || c == '\u0000') {
                                throw IllegalArgumentException()
                            }
                        }
                        if (secret.size > 256) {
                            throw IllegalArgumentException()
                        }
                        val keep: Boolean = same && old!!.auth.equals(draft.auth) && (!draft.auth.equals("key") || old.identity.equals(draft.identity))
                        draft.encrypted = if (draft.auth.equals("key") && clearPassphrase.isChecked()) "" else if (secret.size > 0) SshPasswordVault.encrypt(draft.session(), secret) else if (keep) old!!.encrypted else ""
                        if (draft.auth.equals("password") && draft.encrypted.isEmpty()) {
                            throw IllegalArgumentException()
                        }
                    } finally {
                        Arrays.fill(secret, '\u0000')
                    }
                }
                if (old != null) {
                    draft.diagnosticsPaused = old.diagnosticsPaused
                    draft.backgroundPolling = old.backgroundPolling
                    draft.projects.addAll(old.projects)
                    draft.favorites.addAll(old.favorites)
                    draft.favoriteFiles.addAll(old.favoriteFiles)
                    draft.actions.addAll(old.actions)
                }
                val index: Int = if (old == null) store.profiles.size else store.profiles.indexOf(old)
                if (old == null) {
                    store.profiles.add(draft)
                } else {
                    store.profiles.set(index, draft)
                }
                try {
                    store.save()
                } catch (failure: Exception) {
                    if (old == null) {
                        store.profiles.remove(draft)
                    } else {
                        store.profiles.set(index, old)
                    }
                    throw failure
                }
                closeAuxiliary(draft.id)
                val c: ServerConnection? = connections.remove(draft.id)
                if (c != null) {
                    c.close()
                }
                if (old != null && !same) {
                    File(activity.getFilesDir(), "server-host-" + old.id).delete()
                }
                snapshots.remove(draft.id)
                connected.remove(draft.id)
                agentPresence.remove(draft.id)
                if (selected === old) {
                    selected = draft
                }
                generation++
                keyPicker.retain(if (draft.auth.equals("key")) draft.identity else "")
                password.setText("")
                passphrase.setText("")
                d.dismiss()
                render()
            } catch (e: Exception) {
                host.setError(t("Проверьте поля, порт и пароль; профиль не сохранён", "Check fields, port and password; profile not saved"))
            }
        })
    }

    private fun connect() {
        if (selected != null) {
            connect(selected)
        }
    }

    private fun connect(p: ServerProfiles.Profile?) {
        if (busy || closed) {
            return
        }
        ServerConnection.allow(activity, p!!.id)
        val c: ServerConnection? = connection(p)
        if (!c!!.trusted()) {
            selected = p
            generation++
            render()
            scan(false)
            return
        }
        run(Work(c::probe), Done { result ->
            val installed: Boolean = result!!.getBoolean("agent")
            agentPresence.put(p.id, installed)
            if (!result.optBoolean("supported", false)) {
                connected.remove(p.id)
                snapshots.remove(p.id)
                error = t("Для агента нужен Python 3.8+; SSH-терминал доступен.", "Agent requires Python 3.8+; SSH terminal is available.")
                return@Done
            }
            if (installed) {
                run(Work { c.rpc(JSONObject().put("op", "status")) }, Done { r ->
                    if (r!!.optInt("protocol") != 1) {
                        connected.remove(p.id)
                        error = explain("PROTOCOL_MISMATCH")
                        return@Done
                    }
                    snapshots.put(p.id, r)
                    agentPresence.put(p.id, true)
                    connected.add(p.id)
                })
            } else {
                connected.remove(p.id)
                snapshots.remove(p.id)
            }
        })
    }

    private fun scan(replacement: Boolean) {
        if (selected == null || busy) {
            return
        }
        val c: ServerConnection? = connection(selected)
        val keys: Array<Array<String>?> = arrayOf<Array<String>?>(null)
        run(Work {
            keys[0] = c!!.scan()
            return@Work JSONObject()
        }, Done { r ->
            confirm(t("Проверка SSH-сервера", "Verify SSH server"), selected!!.user + "@" + selected!!.host + "\n\n" + keys[0]!![1] + t("\nСверьте отпечаток по независимому каналу. Сканирование само по себе не доказывает подлинность сервера. Доверять этому ключу?", "\nCompare this fingerprint through an independent channel. Scanning alone does not authenticate a server. Trust this key?") + (if (replacement) t("\nПрежний доверенный ключ будет заменён.", "\nThe previous pinned key will be replaced.") else ""), Runnable {
                try {
                    c!!.trust(keys[0]!![0])
                    connect()
                } catch (e: Exception) {
                    message("SSH", explain("TRUST_SAVE_FAILED"))
                }
            })
        })
    }

    private fun closeAuxiliary(id: String) {
        for (c in auxiliary) {
            if (c.profile!!.id.equals(id)) {
                c.close()
            }
        }
    }

    private fun refresh(explicit: Boolean) {
        if (selected == null || busy || closed) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        if (explicit) {
            val c: ServerConnection? = connection(p)
            run(Work { c!!.rpc(JSONObject().put("op", "status")) }, Done { r ->
                if (r!!.optInt("protocol") != 1) {
                    connected.remove(p!!.id)
                    error = explain("PROTOCOL_MISMATCH")
                    return@Done
                }
                snapshots.put(p!!.id, r)
                agentPresence.put(p.id, true)
                connected.add(p.id)
            })
            return
        }
        refreshMonitor(p)
    }

    private fun refreshMonitor(p: ServerProfiles.Profile?) {
        if (pollBusy || closed || busy) {
            return
        }
        pollBusy = true
        val g: Int = generation
        val epoch: Int = manualEpoch
        val owned = ServerConnection(activity, p)
        auxiliary.add(owned)
        try {
            queues.monitor.execute(Runnable {
                var result: JSONObject? = null
                var failure: String? = null
                try {
                    result = owned.rpc(JSONObject().put("op", "status"))
                } catch (e: Exception) {
                    val code: String? = e.message
                    failure = if (code != null && (code as java.lang.String).matches("[A-Z_]{1,80}")) code else "OPERATION_FAILED"
                } finally {
                    owned.close()
                    auxiliary.remove(owned)
                }
                val data: JSONObject? = result
                val problem: String? = failure
                main.post(Runnable { applyMonitor(p, g, epoch, data, problem) })
            })
        } catch (e: RejectedExecutionException) {
            pollBusy = false
            auxiliary.remove(owned)
            owned.close()
        }
    }

    fun applyMonitor(p: ServerProfiles.Profile?, g: Int, epoch: Int, data: JSONObject?, problem: String?) {
        pollBusy = false
        if (closed || g != generation || epoch != manualEpoch || !store.profiles.contains(p) || !pollingEnabled(p)) {
            return
        }
        if (problem != null) {
            if (selected === p) {
                monitorError(problem)
            } else {
                val label: TextView? = pollLabels.get(p!!.id)
                if (label != null) {
                    updatePollLabel(p, label)
                    label.append(" · " + t("Нет ответа", "No response"))
                }
            }
            return
        }
        if (data == null || data.optInt("protocol") != 1) {
            if (selected === p) {
                monitorError("PROTOCOL_MISMATCH")
            }
            return
        }
        snapshots.put(p!!.id, data)
        agentPresence.put(p.id, true)
        connected.add(p.id)
        if (selected === p) {
            updateLiveViews(data)
        } else {
            val label: TextView? = pollLabels.get(p.id)
            if (label != null) {
                updatePollLabel(p, label)
            }
            val metrics: TextView? = listMetrics.get(p.id)
            if (metrics != null) {
                metrics.setText(metricSummary(data.optJSONObject("metrics")))
            }
        }
    }

    private fun install() {
        if (selected == null || busy) {
            return
        }
        if (java.lang.Boolean.TRUE.equals(knownAgent(selected))) {
            message(t("Настройки агента", "Agent settings"), t("Агент уже установлен. Повторная установка не требуется.", "Agent is already installed. Reinstallation is not required."))
            return
        }
        val c: ServerConnection? = connection(selected)
        if (!c!!.trusted()) {
            scan(false)
            return
        }
        confirm(t("Установить Agent?", "Install Agent?"), t("На выбранном сервере будет создан ~/.local/share/termux-jobs-server. Нужны Linux, Bash и Python 3.8+; пакеты и службы ОС не устанавливаются. Агент работает с правами SSH-пользователя (root = полный доступ), собирает метрики до 24 ч и хранит команды/журналы задач. Публичный порт не открывается. После перезагрузки стартует при подключении приложения. Удаление — отдельным пунктом; активные задачи его блокируют. Продолжить?", "Creates ~/.local/share/termux-jobs-server on the selected server. Requires Linux, Bash, Python 3.8+; installs no OS packages/services. Runs with SSH-user permissions (root = full access), retains up to 24h metrics and task commands/logs. No public port. After server reboot, starts when the app reconnects. Separate removal is blocked by active tasks. Continue?"), Runnable {
            run(Work {
                c.install()
                return@Work JSONObject()
            }, Done { r ->
                agentPresence.put(selected!!.id, true)
                connected.add(selected!!.id)
                refresh(true)
            })
        })
    }

    private fun uninstall() {
        if (selected == null || busy) {
            return
        }
        if (java.lang.Boolean.FALSE.equals(knownAgent(selected))) {
            message(t("Настройки агента", "Agent settings"), t("Агент не установлен. Удалять нечего.", "Agent is not installed. Nothing to remove."))
            return
        }
        val c: ServerConnection? = connection(selected)
        confirm(t("Удалить Agent?", "Remove Agent?"), t("Будут удалены ТОЛЬКО каталог агента, история состояния, записи команд и журналы задач. Активные или неподконтрольные задачи заблокируют удаление. Просмотр видео остановится. Проекты, видео, SSH и другие службы не удаляются. Расписания Cron/systemd сохраняются и продолжат работать независимо; ими можно управлять в Расписаниях задач. Профиль сервера останется.", "Deletes ONLY the agent directory, metric history, task records and logs. Active or unmanaged tasks block removal. Video streaming stops. Projects, videos, SSH and other services remain. Cron/systemd schedules remain active independently; manage them under Task schedules. The server profile is retained."), Runnable {
            run(Work { c!!.rpc(JSONObject().put("op", "uninstall")) }, Done { r ->
                c!!.stopTunnel()
                snapshots.remove(selected!!.id)
                connected.remove(selected!!.id)
                agentPresence.put(selected!!.id, false)
            })
        })
    }

    private fun openTerminal() {
        if (selected == null || busy) {
            return
        }
        val c: ServerConnection? = connection(selected)
        if (!c!!.trusted()) {
            scan(false)
            return
        }
        terminal.open(selected!!.name, c.terminalCommand(if (project == null) directory else project!!.path))
    }

    private fun sshFiles() {
        if (selected == null || busy) {
            return
        }
        val browser = WorkspaceFileBrowser(activity, selected!!.session(), directory, null)
        browser.setServerKnownHosts(connection(selected)!!.knownHosts())
        browser.setOnClosed(Runnable { sshBrowsers.remove(browser) })
        sshBrowsers.add(browser)
        browser.show()
    }

    private fun projects(body: LinearLayout?) {
        add(body, button(t("+ Добавить проект", "+ Add project"), Runnable(this::addProject)))
        for (item in selected!!.projects) {
            val c: LinearLayout = card(body)
            title(c, item.name)
            add(c, text(item.path, 12, Pal.MUTED))
            add(c, button(t("Выбрать проект", "Select project"), Runnable {
                project = item
                directory = item.path
                renderSection()
            }))
            add(c, button(t("Открыть файлы", "Open files"), Runnable {
                project = item
                directory = item.path
                tab = "files"
                listing = null
                renderSection()
                loadFiles()
            }))
            c.setOnLongClickListener(View.OnLongClickListener { v ->
                confirm(t("Убрать проект из списка?", "Remove project from list?"), t("Файлы останутся на сервере.", "Files remain on the server."), Runnable {
                    selected!!.projects.remove(item)
                    if (project === item) {
                        project = null
                    }
                    save()
                    renderSection()
                })
                return@OnLongClickListener true
            })
        }
    }

    private fun addProject() {
        val f: LinearLayout = column()
        val name: EditText = field(f, t("Название проекта", "Project name"), "", false)
        val path: EditText = field(f, t("Каталог на сервере", "Server directory"), directory, false)
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Проект", "Project")).setView(f).setPositiveButton(t("Сохранить", "Save"), null).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(d)
        d.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            if ((name.getText().toString() as java.lang.String).trim().isEmpty() || (path.getText().toString() as java.lang.String).trim().isEmpty()) {
                return@OnClickListener
            }
            val c: ServerConnection? = connection(selected)
            val n: String = (name.getText().toString() as java.lang.String).trim()
            val value: String = path.getText().toString()
            d.dismiss()
            run(Work { c!!.rpc(JSONObject().put("op", "list").put("path", value)) }, Done { r ->
                val item = ServerProfiles.Project(n, r!!.getString("path"))
                selected!!.projects.add(item)
                project = item
                directory = item.path
                save()
            })
        })
    }

    private fun save() {
        try {
            store.save()
        } catch (e: Exception) {
            error = explain("STORE_ERROR")
        }
    }

    private fun tasks(body: LinearLayout?, items: JSONArray?) {
        add(body, button(t("Закреплённые команды", "Pinned commands"), Runnable(this::pinnedActions)))
        add(body, button(t("+ Запустить задачу", "+ Run task"), Runnable(this::startTask)))
        add(body, button(t("Расписания задач", "Task schedules"), Runnable {
            if (!busy && selected != null) {
                scheduleUi()!!.list()
            }
        }))
        add(body, text(t("Команды выполняются на сервере без телефона. До 2 одновременно, очередь до 10, предел 24 часа. Не запускайте фоновые команды с & / nohup: их потомки не управляются как отдельные задачи.", "Commands run on the server without the phone. Up to 2 running, 10 queued, 24-hour limit. Do not use & / nohup: detached descendants are not managed as separate tasks."), 12, Pal.MUTED))
        if (items == null) {
            return
        }
        var i = 0
        while (i < items.length()) {
            val item: JSONObject? = items.optJSONObject(i)
            if (item == null || project != null && !project!!.path.equals(item.optString("directory"))) {
                i++
                continue
            }
            val c: LinearLayout = card(body)
            title(c, item.optString("title"))
            add(c, text(taskSummary(item) + " · " + item.optString("directory"), 12, Pal.MUTED))
            add(c, button(t("Журнал / действия", "Log / actions"), Runnable { taskLog(item) }))
            i++
        }
    }

    private fun pinnedActions() {
        if (selected == null || busy) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val body: LinearLayout = column()
        val viewport = ScrollView(activity)
        viewport.addView(body)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Закреплённые команды", "Pinned commands")).setView(viewport).setPositiveButton(t("Закрыть", "Close"), null).create()
        add(body, button(t("Добавить команду", "Add command"), Runnable {
            dialog.dismiss()
            editAction(null, null)
        }))
        add(body, text(t("Нажатие — запуск с подтверждением. Долгий тап — изменить или удалить. Каждое выполнение создаёт отдельную задачу и журнал.", "Tap to run with confirmation. Long press to edit or remove. Each run creates its own task and log."), 12, Pal.MUTED))
        for (action in ArrayList<ServerProfiles.Action>(p!!.actions)) {
            val item: TextView = button(action.name + "\n" + action.path, Runnable {
                if (selected !== p || busy) {
                    return@Runnable
                }
                confirm(t("Запустить на сервере?", "Run on server?"), p.user + "@" + p.host + ":" + p.port + "\n" + action.path + "\n\n" + action.command + "\n\n" + t("Команда выполняется с правами SSH-пользователя. Проверьте её перед запуском.", "Runs with the SSH user's permissions. Review the command before starting."), Runnable {
                    if (selected !== p) {
                        return@Runnable
                    }
                    dialog.dismiss()
                    val c: ServerConnection? = connection(p)
                    run(Work {
                        c!!.rpc(JSONObject().put("op", "start").put("title", action.name).put("directory", action.path).put("command", action.command))
                    }, Done { r -> refresh(true) })
                })
            })
            item.setOnLongClickListener(View.OnLongClickListener { v ->
                val choices: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(action.name).setItems(arrayOf<String>(t("Редактировать", "Edit"), t("Убрать закрепление", "Unpin")), DialogInterface.OnClickListener { d, w ->
                    if (w == 0) {
                        dialog.dismiss()
                        editAction(action, null)
                    } else {
                        confirm(t("Убрать закреплённую команду?", "Unpin command?"), t("Уже запущенные задачи продолжат работу.", "Already running tasks continue."), Runnable {
                            val index: Int = p.actions.indexOf(action)
                            if (index < 0) {
                                return@Runnable
                            }
                            p.actions.removeAt(index)
                            try {
                                store.save()
                                dialog.dismiss()
                            } catch (e: Exception) {
                                p.actions.add(index, action)
                                message("Error", explain("STORE_ERROR"))
                            }
                        })
                    }
                }).create()
                show(choices)
                return@OnLongClickListener true
            })
            add(body, item)
        }
        show(dialog)
    }

    private fun editAction(old: ServerProfiles.Action?, task: JSONObject?) {
        if (selected == null || busy) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val body: LinearLayout = column()
        val name: EditText = field(body, t("Название", "Name"), if (old != null) old.name else if (task == null) "" else task.optString("title"), false)
        val path: EditText = field(body, t("Каталог на сервере", "Server directory"), if (old != null) old.path else if (task == null) directory else task.optString("directory"), false)
        val command: EditText = field(body, t("Команда Bash", "Bash command"), if (old != null) old.command else if (task == null) "" else task.optString("command"), true)
        add(body, text(t("Команда сохраняется локально в профиле сервера. Не включайте пароли и токены в её текст.", "The command is stored locally in the server profile. Avoid embedding passwords or tokens."), 12, Pal.MUTED))
        val scroll = ScrollView(activity)
        scroll.addView(body)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Закрепить команду", "Pin command")).setView(scroll).setPositiveButton(t("Сохранить", "Save"), null).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(dialog)
        dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            val n: String = (name.getText().toString() as java.lang.String).trim()
            val cwd: String = path.getText().toString()
            val cmd: String = command.getText().toString()
            if (n.isEmpty() || n.length > 160 || cwd.isEmpty() || cwd.length > 4096 || cwd.indexOf('\u0000') >= 0 || (cmd as java.lang.String).trim().isEmpty() || cmd.length > 8192 || cmd.indexOf('\u0000') >= 0) {
                command.setError(t("Проверьте название, каталог и команду (до 8192 символов).", "Check name, directory and command (up to 8192 characters)."))
                return@OnClickListener
            }
            if (old == null && p!!.actions.size >= 50) {
                command.setError(t("До 50 закреплённых команд", "Up to 50 pinned commands"))
                return@OnClickListener
            }
            val index: Int = if (old == null) p!!.actions.size else p!!.actions.indexOf(old)
            if (index < 0) {
                return@OnClickListener
            }
            val next = ServerProfiles.Action(n, cwd, cmd)
            if (old == null) {
                p.actions.add(next)
            } else {
                p.actions.set(index, next)
            }
            try {
                store.save()
                dialog.dismiss()
            } catch (error: Exception) {
                if (old == null) {
                    p.actions.remove(next)
                } else {
                    p.actions.set(index, old)
                }
                message("Error", explain("STORE_ERROR"))
            }
        })
    }

    private fun scheduleUi(): WorkspaceServerSchedules? {
        if (scheduleUi != null) {
            scheduleUi!!.close()
        }
        scheduleUi = WorkspaceServerSchedules(activity, connection(selected), worker)
        return scheduleUi
    }

    private fun startTask() {
        if (busy) {
            return
        }
        val f: LinearLayout = column()
        val name: EditText = field(f, t("Название", "Name"), "", false)
        val cwd: EditText = field(f, t("Каталог на сервере", "Server directory"), if (project == null) directory else project!!.path, false)
        val command: EditText = field(f, t("Команда Bash", "Bash command"), "", true)
        val mode = Spinner(activity)
        mode.setAdapter(ArrayAdapter<String>(activity, R.layout.simple_spinner_dropdown_item, arrayOf<String>(t("Сейчас", "Now"), t("По расписанию", "Scheduled"))))
        add(f, mode)
        val sv = ScrollView(activity)
        sv.addView(f)
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Новая задача", "New task")).setView(sv).setPositiveButton(t("Продолжить", "Continue"), null).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(d)
        d.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            if ((command.getText().toString() as java.lang.String).trim().isEmpty()) {
                return@OnClickListener
            }
            val cmd: String = command.getText().toString()
            val path: String = cwd.getText().toString()
            val label: String = name.getText().toString()
            d.dismiss()
            if (mode.getSelectedItemPosition() == 1) {
                scheduleUi()!!.create(label, path, cmd)
                return@OnClickListener
            }
            confirm(t("Запустить на сервере?", "Run on server?"), selected!!.user + "@" + selected!!.host + "\n" + path + "\n\n" + cmd + "\n\n" + t("Команда получит права выбранного пользователя. Ввод с клавиатуры недоступен; для интерактивных задач используйте терминал. Команда и вывод сохраняются в каталоге агента.", "Runs with the selected user's permissions. Keyboard input is unavailable; use terminal for interactive work. Command/output are saved in the agent directory."), Runnable {
                val c: ServerConnection? = connection(selected)
                run(Work {
                    c!!.rpc(JSONObject().put("op", "start").put("title", label).put("directory", path).put("command", cmd))
                }, Done { r -> refresh(true) })
            })
        })
    }

    private fun taskSummary(item: JSONObject): String {
        var out: String = state(item.optString("state"))
        val started: Double = item.optDouble("started", 0.0)
        val ended: Double = item.optDouble("ended", System.currentTimeMillis() / 1000.0)
        if (started > 0) {
            out += " · " + Math.max(0L, (ended - started).toLong()) + t(" с", " s")
        }
        if (item.has("exitCode")) {
            out += " · exit " + item.optInt("exitCode")
        }
        return out
    }

    private fun taskLog(task: JSONObject) {
        if (selected == null || busy) {
            return
        }
        val c: ServerConnection? = connection(selected)
        run(Work { c!!.rpc(JSONObject().put("op", "log").put("id", task.getString("id"))) }, Done { r ->
            val current: Array<JSONObject> = arrayOf<JSONObject>(r!!.getJSONObject("task"))
            val body: LinearLayout = column()
            val log: TextView = text(r.optString("log"), 12, Pal.TEXT)
            log.setTypeface(Typeface.MONOSPACE)
            log.setTextIsSelectable(true)
            val viewport = ScrollView(activity)
            viewport.addView(log)
            body.addView(viewport, LinearLayout.LayoutParams(-1, dp(280)))
            val status: TextView = text(taskSummary(current[0]) + "\n" + t("Последние 32 КБ вывода; полная запись до 2 МБ.", "Last 32 KB shown; full log up to 2 MB."), 12, Pal.MUTED)
            add(body, status)
            add(body, button(t("Скопировать вывод", "Copy output"), Runnable {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("task", log.getText()))
            }))
            val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(current[0].optString("title")).setView(body).setPositiveButton(t("Закрыть", "Close"), null).create()
            val stop: TextView = button(t("Остановить…", "Stop…"), Runnable {
                confirm(t("Остановить задачу?", "Stop task?"), current[0].optString("command") + (if (current[0].optString("command").contains("apt-get")) t("\nОстановка APT может оставить пакеты недонастроенными.", "\nStopping APT may leave packages unconfigured.") else ""), Runnable {
                    run(Work { c!!.rpc(JSONObject().put("op", "cancel").put("id", current[0].getString("id"))) }, Done { v -> refresh(true) })
                })
            })
            add(body, stop)
            val remove: TextView = button(t("Убрать из списка…", "Remove from list…"), Runnable {
                val state: String = current[0].optString("state")
                if (!finishedTask(state) && !state.equals("unmanaged")) {
                    return@Runnable
                }
                confirm(t("Удалить запись задачи?", "Remove task record?"), if (state.equals("unmanaged")) t("Процесс неподконтролен. Проверьте его вручную: удаление записи НЕ останавливает процесс.", "Process is unmanaged. Inspect it manually: removing its record does NOT stop it.") else t("Удаляются только запись и журнал. Файлы и результат команды останутся на сервере.", "Only the record and log are removed. Files and command results remain on the server."), Runnable {
                    run(Work { c!!.rpc(JSONObject().put("op", "forget").put("id", current[0].getString("id"))) }, Done { v ->
                        dialog.dismiss()
                        refresh(true)
                    })
                })
            })
            add(body, remove)
            add(body, button(t("Закрепить команду…", "Pin command…"), Runnable { editAction(null, current[0]) }))
            val actions = Runnable {
                val state: String = current[0].optString("state")
                stop.setVisibility(if (state.equals("running") || state.equals("starting") || state.equals("queued")) View.VISIBLE else View.GONE)
                remove.setVisibility(if (finishedTask(state) || state.equals("unmanaged")) View.VISIBLE else View.GONE)
                status.setText(taskSummary(current[0]) + "\n" + t("Последние 32 КБ вывода; полная запись до 2 МБ.", "Last 32 KB shown; full log up to 2 MB."))
            }
            actions.run()
            show(dialog)
            val watch: Runnable = object : Runnable {
                override fun run() {
                    if (closed || !dialog.isShowing()) {
                        return
                    }
                    if (foreground && !busy && !pollBusy) {
                        this@WorkspaceServers.run(Work { c!!.rpc(JSONObject().put("op", "log").put("id", task.getString("id"))) }, Done { update ->
                            if (dialog.isShowing()) {
                                current[0] = update!!.getJSONObject("task")
                                updateLog(log, viewport, update.optString("log"))
                                actions.run()
                            }
                        }, false)
                    }
                    main.postDelayed(this, 3000L)
                }
            }
            main.postDelayed(watch, 3000L)
        })
    }

    private fun loadFiles() {
        if (busy || selected == null) {
            return
        }
        val c: ServerConnection? = connection(selected)
        val target: String = directory
        if (fileStatus != null) {
            fileStatus!!.setText(t("Чтение каталога…", "Reading directory…"))
            fileStatus!!.setTextColor(Pal.MUTED)
        }
        runInline(Work { c!!.rpc(JSONObject().put("op", "list").put("path", target)) }, Done { r ->
            listing = r
            directory = r!!.getString("path")
            updateFileViews()
        }, Consumer<String> { code ->
            if (listing != null && listing!!.has("path")) {
                directory = listing!!.optString("path")
            }
            if (filePathLabel != null) {
                filePathLabel!!.setText(directory)
            }
            if (fileStatus != null) {
                fileStatus!!.setText(explain(code))
                fileStatus!!.setContentDescription(explain(code))
                fileStatus!!.setTextColor(Pal.DANGER)
            }
        })
    }

    private val downloadSelection: LinkedHashMap<String, JSONObject> = LinkedHashMap<String, JSONObject>()
    private var downloadSelectedButton: TextView? = null
    private var deleteSelectedButton: TextView? = null
    private var clearFileSelectionButton: TextView? = null
    private var selectAllFilesButton: TextView? = null
    private var fileSelectionActions: LinearLayout? = null
    private var deleteSelectionMode: Boolean = false
    private val moveSelection: MutableList<JSONObject> = ArrayList<JSONObject>()
    private var moveProfile: String = ""
    private var selectionProfile: String = ""
    private var selectionDirectory: String = ""

    private fun updateDownloadSelection() {
        updateDeleteSelection()
        val selectionActive: Boolean = !downloadSelection.isEmpty() || !moveSelection.isEmpty()
        if (fileSelectionActions != null) {
            fileSelectionActions!!.setVisibility(if (selectionActive) View.VISIBLE else View.GONE)
        }
        if (selectAllFilesButton != null) {
            selectAllFilesButton!!.setEnabled(selectionActive && !busy && moveSelection.isEmpty() && listing != null)
        }
        if (clearFileSelectionButton != null) {
            val active: Boolean = !downloadSelection.isEmpty() || !moveSelection.isEmpty()
            clearFileSelectionButton!!.setVisibility(if (active) View.VISIBLE else View.GONE)
            clearFileSelectionButton!!.setText("× " + (downloadSelection.size + moveSelection.size))
        }
        if (downloadSelectedButton == null) {
            return
        }
        val pending: Boolean = selected != null && moveProfile.equals(selected!!.id) && !moveSelection.isEmpty()
        downloadSelectedButton!!.setVisibility(View.VISIBLE)
        downloadSelectedButton!!.setSelected(pending || !downloadSelection.isEmpty())
        downloadSelectedButton!!.setBackground(WorkspaceDialogs.background(activity, pending || !downloadSelection.isEmpty()))
        downloadSelectedButton!!.setContentDescription(if (pending) t("Переместить сюда: ", "Move here: ") + moveSelection.size else if (downloadSelection.isEmpty()) t("Скачать с сервера / переместить", "Download from server / move") else t("Действия с выбранным: ", "Selection actions: ") + downloadSelection.size)
        if (downloadSelectedButton is WorkspaceIconButton) {
            (downloadSelectedButton as WorkspaceIconButton?)!!.setIcon(activity.getDrawable(if (pending) com.termux.R.drawable.workspace_move_here else com.termux.R.drawable.workspace_diag_download)!!.mutate(), dp(22))
        }
    }

    private fun updateDeleteSelection() {
        if (deleteSelectedButton == null) {
            return
        }
        val active: Boolean = deleteSelectionMode || !downloadSelection.isEmpty()
        deleteSelectedButton!!.setSelected(active)
        deleteSelectedButton!!.setBackground(WorkspaceDialogs.background(activity, active))
        val label: String = if (downloadSelection.isEmpty()) if (deleteSelectionMode) t("Отменить выбор для удаления", "Cancel delete selection") else t("Выбрать для удаления", "Select for deletion") else t("Удалить выбранное: ", "Delete selected: ") + downloadSelection.size
        deleteSelectedButton!!.setContentDescription(label)
        deleteSelectedButton!!.setTooltipText(label)
    }

    private fun deleteSelectionAction() {
        if (closed || busy || selected == null || listing == null) {
            return
        }
        if (downloadSelection.isEmpty()) {
            deleteSelectionMode = !deleteSelectionMode
            updateFileViews()
            return
        }
        val profile: ServerProfiles.Profile? = selected
        val folder: String = listing!!.optString("path")
        val entries = JSONArray()
        val names = StringBuilder()
        for (item in downloadSelection.values) {
            try {
                val copy = JSONObject(item.toString())
                entries.put(copy)
                names.append(copy.optString("path")).append(if (copy.optBoolean("directory")) "/" else "").append("\n")
            } catch (invalid: JSONException) {
                return
            }
        }
        val viewport = ScrollView(activity)
        val body: TextView = text(profile!!.name + "\n\n" + names + t("\nУдаление с сервера безвозвратное. Папки удаляются со всем содержимым. Цели символических ссылок не удаляются. При обрыве уже удалённые объекты не восстанавливаются.", "\nDeletion from the server is permanent. Folders include all contents. Symbolic-link targets are not deleted. Interrupted deletion does not restore already removed entries."), 14, Pal.TEXT)
        body.setTextIsSelectable(true)
        body.setPadding(dp(16), dp(8), dp(16), dp(8))
        viewport.addView(body)
        val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Удалить выбранное с сервера?", "Delete selection from server?")).setView(viewport).setNegativeButton(t("Нет", "No"), null).setPositiveButton(t("Да", "Yes"), DialogInterface.OnClickListener { d, w ->
            if (closed || busy || selected !== profile || listing == null || !folder.equals(listing!!.optString("path")) || ServerConnection.disconnected(activity, profile.id)) {
                return@OnClickListener
            }
            if (fileStatus != null) {
                fileStatus!!.setText(t("Удаление…", "Deleting…"))
            }
            val c: ServerConnection? = connection(profile)
            runInline(Work { c!!.deleteFiles(entries) }, Done { result ->
                downloadSelection.clear()
                deleteSelectionMode = false
                if (moveProfile.equals(profile.id)) {
                    moveSelection.clear()
                    moveProfile = ""
                }
                updateDownloadSelection()
                loadFiles()
                val code: String = result!!.optString("error")
                var summary: String = t("Удалено выбранных объектов: ", "Deleted selected entries: ") + result.getJSONArray("deleted").length() + " / " + entries.length()
                if (!code.isEmpty()) {
                    summary += "\n" + t("Удаление не завершено: ", "Deletion incomplete: ") + code + t("\nЧасть содержимого могла быть удалена. Обновите список перед повтором.", "\nSome contents may already be deleted. Refresh before retrying.")
                }
                message(t("Удаление с сервера", "Server deletion"), summary)
            }, Consumer<String> { code ->
                downloadSelection.clear()
                deleteSelectionMode = false
                updateDownloadSelection()
                loadFiles()
                message(t("Удаление не подтверждено", "Deletion not confirmed"), t("Проверьте список: часть объектов могла быть удалена. Автоматический повтор не выполняется.", "Check the listing: some entries may have been deleted. No automatic retry.") + "\n" + code)
            })
        }).create()
        show(confirm)
    }

    private fun selectedFileActions() {
        if (selected == null || busy) {
            return
        }
        val pending: Boolean = moveProfile.equals(selected!!.id) && !moveSelection.isEmpty()
        if (pending) {
            WorkspaceDialogs.builder(activity).setTitle(t("Перемещение", "Move")).setItems(arrayOf<String>(t("Переместить сюда", "Move here"), t("Отменить перемещение", "Cancel move")), DialogInterface.OnClickListener { d, w ->
                if (w == 0) {
                    val p: ServerProfiles.Profile? = selected
                    downloads.move(p, ArrayList<JSONObject>(moveSelection), if (listing == null) directory else listing!!.optString("path", directory), Runnable {
                        moveSelection.clear()
                        moveProfile = ""
                        downloadSelection.clear()
                        if (selected === p) {
                            loadFiles()
                        }
                        updateDownloadSelection()
                    })
                } else {
                    moveSelection.clear()
                    moveProfile = ""
                    updateDownloadSelection()
                }
            }).show()
            return
        }
        if (downloadSelection.isEmpty()) {
            WorkspaceDialogs.builder(activity).setTitle(t("Файлы сервера", "Server files")).setItems(arrayOf<String>(t("Скачать с сервера…", "Download from server…"), t("Переместить по серверу…", "Move within server…")), DialogInterface.OnClickListener { d, w -> chooseServerSelection(w == 1) }).show()
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(t("Выбрано: ", "Selected: ") + downloadSelection.size).setItems(arrayOf<String>(t("Скачать…", "Download…"), t("Переместить…", "Move…"), t("Выбрать всё", "Select all"), t("Снять выделение", "Clear selection")), DialogInterface.OnClickListener { d, w ->
            if (w == 0) {
                downloads.selected(selected, ArrayList<JSONObject>(downloadSelection.values))
            } else if (w == 1) {
                prepareMoveSelection()
            } else if (w == 2) {
                selectAllCurrentFiles()
            } else {
                downloadSelection.clear()
                deleteSelectionMode = false
                updateFileViews()
            }
        }).show()
    }

    private fun selectAllCurrentFiles() {
        if (busy || selected == null || listing == null || downloadSelection.isEmpty() || !moveSelection.isEmpty()) {
            return
        }
        val entries: JSONArray? = listing!!.optJSONArray("entries")
        var limited = false
        if (entries != null) {
            var i = 0
            while (i < entries.length()) {
                val e: JSONObject? = entries.optJSONObject(i)
                if (e == null || downloadSelection.containsKey(e.optString("path"))) {
                    i++
                    continue
                }
                if (downloadSelection.size >= 500) {
                    limited = true
                    break
                }
                downloadSelection.put(e.optString("path"), e)
                i++
            }
        }
        updateFileViews()
        if (limited) {
            activity.showToast(t("Достигнут лимит: 500 выбранных объектов", "Selection limit reached: 500 entries"), false)
        }
    }

    private fun prepareMoveSelection() {
        moveSelection.clear()
        moveSelection.addAll(downloadSelection.values)
        moveProfile = selected!!.id
        downloadSelection.clear()
        updateFileViews()
        activity.showToast(t("Откройте папку назначения и нажмите «Переместить сюда»", "Open the destination folder and tap Move here"), true)
    }

    private fun chooseServerSelection(move: Boolean) {
        if (selected == null || listing == null) {
            return
        }
        val array: JSONArray? = listing!!.optJSONArray("entries")
        if (array == null || array.length() == 0) {
            message(t("Файлы сервера", "Server files"), t("В этой папке нет доступных записей. Откройте другую папку.", "No available entries in this folder. Open another directory."))
            return
        }
        val profile: ServerProfiles.Profile? = selected
        val entries: MutableList<JSONObject> = ArrayList<JSONObject>()
        var i = 0
        while (i < array.length() && entries.size < 500) {
            if (array.optJSONObject(i) != null) {
                entries.add(array.optJSONObject(i))
            }
            i++
        }
        val names = arrayOfNulls<String>(entries.size)
        val checked = BooleanArray(entries.size)
        for (i in 0 until (names.size)) {
            val e: JSONObject = entries.get(i)
            names[i] = e.optString("name") + (if (e.optBoolean("directory")) "/" else "")
        }
        val holder: Array<AlertDialog?> = arrayOf<AlertDialog?>(null)
        val enabled = Runnable {
            var any = false
            for (value in checked) {
                any = any or value
            }
            if (holder[0] != null) {
                holder[0]!!.getButton(-1).setEnabled(any)
            }
        }
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (move) t("Что переместить?", "What to move?") else t("Что скачать?", "What to download?")).setMultiChoiceItems(names, checked, DialogInterface.OnMultiChoiceClickListener { d, w, value ->
            checked[w] = value
            enabled.run()
        }).setPositiveButton(t("Далее", "Next"), null).setNegativeButton(t("Назад", "Back"), null).setNeutralButton(t("Выбрать всё", "Select all"), null).create()
        holder[0] = dialog
        show(dialog)
        enabled.run()
        dialog.getButton(-3).setOnClickListener(View.OnClickListener { v ->
            for (i in 0 until (checked.size)) {
                checked[i] = true
                dialog.getListView().setItemChecked(i, true)
            }
            enabled.run()
        })
        dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            if (selected !== profile) {
                return@OnClickListener
            }
            downloadSelection.clear()
            for (i in 0 until (checked.size)) {
                if (checked[i]) {
                    downloadSelection.put(entries.get(i).optString("path"), entries.get(i))
                }
            }
            dialog.dismiss()
            updateFileViews()
            if (move) {
                prepareMoveSelection()
            } else {
                downloads.selected(profile, ArrayList<JSONObject>(downloadSelection.values))
            }
        })
    }

    private fun toggleDownloadSelection(entry: JSONObject) {
        val path: String = entry.optString("path")
        if (downloadSelection.containsKey(path)) {
            downloadSelection.remove(path)
        } else if (downloadSelection.size < 500) {
            downloadSelection.put(path, entry)
        } else {
            activity.showToast(t("До 500 выбранных записей", "Up to 500 selected entries"), false)
        }
        if (downloadSelection.isEmpty()) {
            deleteSelectionMode = false
        }
        updateFileViews()
    }

    private fun files(body: LinearLayout?) {
        filePathLabel = text(directory, 13, Pal.MUTED)
        add(body, filePathLabel)
        val controls: LinearLayout = row()
        control(controls, iconButton(t("На уровень выше", "Parent directory"), com.termux.R.drawable.workspace_server_back, Runnable {
            if (busy) {
                return@Runnable
            }
            directory = if (listing == null) "~" else listing!!.optString("parent", "~")
            loadFiles()
        }))
        control(controls, iconButton(t("Домашний каталог сервера", "Server home directory"), com.termux.R.drawable.workspace_home, Runnable {
            if (busy) {
                return@Runnable
            }
            directory = "~"
            loadFiles()
        }))
        deleteSelectedButton = iconButton(t("Выбрать для удаления", "Select for deletion"), com.termux.R.drawable.workspace_delete, Runnable(this::deleteSelectionAction))
        deleteSelectedButton!!.setTag("server-delete-selection")
        control(controls, deleteSelectedButton)
        control(controls, iconButton(t("Путь…", "Path…"), com.termux.R.drawable.workspace_folder, Runnable(this::pathDialog)))
        control(controls, iconButton(t("Обновить", "Refresh"), com.termux.R.drawable.workspace_server_refresh, Runnable(this::loadFiles)))
        favoriteButton = iconButton(t("Избранные папки и файлы", "Favorite folders and files"), com.termux.R.drawable.workspace_star, Runnable(this::favoriteFolders))
        control(controls, favoriteButton)
        control(controls, iconButton(t("Поиск по именам", "Search names"), com.termux.R.drawable.workspace_search, Runnable(this::searchFiles)))
        val toolbar = HorizontalScrollView(activity)
        toolbar.setHorizontalScrollBarEnabled(false)
        toolbar.addView(controls)
        add(body, toolbar)
        fileStatus = text("", 11, Pal.MUTED)
        fileStatus!!.setSingleLine(true)
        fileStatus!!.setEllipsize(TextUtils.TruncateAt.END)
        fileStatus!!.setMinHeight(dp(18))
        add(body, fileStatus)
        val transfers: LinearLayout = row()
        val upload: TextView = iconButton(t("Загрузить на сервер", "Upload to server"), com.termux.R.drawable.workspace_server_upload, Runnable {
            if (!busy && selected != null) {
                uploads.pick(selected, if (listing == null) null else listing!!.optString("path"))
            }
        })
        val uploadParams = LinearLayout.LayoutParams(0, dp(48), 1.0f)
        uploadParams.setMarginEnd(dp(8))
        transfers.addView(upload, uploadParams)
        downloadSelectedButton = iconButton(t("Скачать с сервера / переместить", "Download from server / move"), com.termux.R.drawable.workspace_diag_download, Runnable(this::selectedFileActions))
        transfers.addView(downloadSelectedButton, LinearLayout.LayoutParams(0, dp(48), 1.0f))
        clearFileSelectionButton = button("", Runnable {
            if (busy) {
                return@Runnable
            }
            downloadSelection.clear()
            deleteSelectionMode = false
            moveSelection.clear()
            moveProfile = ""
            updateFileViews()
        })
        clearFileSelectionButton!!.setContentDescription(t("Снять всё выделение", "Clear selection"))
        clearFileSelectionButton!!.setTag("server-clear-selection")
        add(body, transfers)
        fileSelectionActions = row()
        fileSelectionActions!!.setTag("server-selection-actions")
        val clearParams = LinearLayout.LayoutParams(-2, dp(48))
        clearParams.setMarginEnd(dp(8))
        fileSelectionActions!!.addView(clearFileSelectionButton, clearParams)
        selectAllFilesButton = iconButton(t("Выбрать всё в открытой папке", "Select all in current folder"), com.termux.R.drawable.workspace_select_all, Runnable(this::selectAllCurrentFiles))
        selectAllFilesButton!!.setTag("server-select-all")
        fileSelectionActions!!.addView(selectAllFilesButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        val selectionParams = LinearLayout.LayoutParams(-2, -2)
        selectionParams.setMarginStart(dp(8))
        transfers.addView(fileSelectionActions, selectionParams)
        updateDownloadSelection()
        fileRows = column()
        body!!.addView(fileRows)
        updateFileViews()
        add(body, text(t("До 500 записей. Для остальных откройте вложенную папку или SSH-терминал.", "Up to 500 entries. For others use a subfolder or SSH terminal."), 12, Pal.MUTED))
    }

    private fun updateFileViews() {
        if (fileRows == null || listing == null || !listing!!.has("entries")) {
            return
        }
        preserveViewport(Runnable(this::replaceFileRows))
    }

    private fun replaceFileRows() {
        if (sectionBody != null) {
            sectionBody!!.setMinimumHeight(0)
        }
        val owner: String = if (selected == null) "" else selected!!.id
        val currentDirectory: String = listing!!.optString("path", directory)
        if (!selectionProfile.equals(owner)) {
            downloadSelection.clear()
            deleteSelectionMode = false
            moveSelection.clear()
            moveProfile = ""
            selectionProfile = owner
        }
        selectionDirectory = currentDirectory
        updateDownloadSelection()
        if (filePathLabel != null) {
            filePathLabel!!.setText(listing!!.optString("path", directory))
        }
        if (favoriteButton != null) {
            val starred: Boolean = selected != null && selected!!.favorites.contains(listing!!.optString("path"))
            favoriteButton!!.setSelected(starred)
            favoriteButton!!.setBackground(WorkspaceDialogs.background(activity, starred))
        }
        fileRows!!.removeAllViews()
        val entries: JSONArray? = listing!!.optJSONArray("entries")
        if (entries == null) {
            return
        }
        var i = 0
        while (i < entries.length()) {
            val entry: JSONObject? = entries.optJSONObject(i)
            if (entry != null) {
                add(fileRows, fileRow(entry))
            }
            i++
        }
        if (fileStatus != null) {
            fileStatus!!.setText(t("Записей: ", "Entries: ") + entries.length())
            fileStatus!!.setTextColor(Pal.MUTED)
            fileStatus!!.setContentDescription(fileStatus!!.getText())
        }
    }

    private fun fileRow(entry: JSONObject): View {
        val item: LinearLayout = row()
        val checked: Boolean = downloadSelection.containsKey(entry.optString("path"))
        item.setBackground(WorkspaceDialogs.background(activity, checked))
        item.setPadding(dp(14), dp(10), dp(14), dp(10))
        item.setMinimumHeight(dp(48))
        if (deleteSelectionMode || !downloadSelection.isEmpty()) {
            val check = CheckBox(activity)
            check.setChecked(checked)
            check.setContentDescription(t("Выбрать ", "Select ") + entry.optString("name"))
            check.setOnClickListener(View.OnClickListener { v ->
                if (!busy) {
                    toggleDownloadSelection(entry)
                }
            })
            item.addView(check, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val folder: Boolean = entry.optBoolean("directory")
        val icon = ImageView(activity)
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER)
        icon.setImageResource(if (folder) com.termux.R.drawable.workspace_folder else com.termux.R.drawable.workspace_server_file)
        icon.setColorFilter(Pal.ACCENT)
        val iconParams = LinearLayout.LayoutParams(dp(22), dp(22))
        iconParams.setMarginEnd(dp(12))
        item.addView(icon, iconParams)
        val name: TextView = text(entry.optString("name"), 14, Pal.TEXT)
        name.setMaxLines(2)
        name.setEllipsize(TextUtils.TruncateAt.END)
        item.addView(name, LinearLayout.LayoutParams(0, -2, 1.0f))
        if (folder) {
            val next = ImageView(activity)
            next.setScaleType(ImageView.ScaleType.FIT_CENTER)
            next.setImageResource(com.termux.R.drawable.workspace_next)
            next.setColorFilter(Pal.MUTED)
            item.addView(next, LinearLayout.LayoutParams(dp(18), dp(18)))
        } else {
            val bytes: TextView = text(ServerFileInfo.size(entry.optLong("size"), WorkspaceLanguage.code(activity)!!.equals("ru")), 11, Pal.MUTED)
            bytes.setPadding(dp(8), 0, 0, 0)
            item.addView(bytes)
        }
        item.setContentDescription(entry.optString("name") + (if (folder) t(" · Папка", " · Folder") else ""))
        item.setOnLongClickListener(View.OnLongClickListener { v ->
            if (!busy) {
                toggleDownloadSelection(entry)
            }
            return@OnLongClickListener true
        })
        item.setOnClickListener(View.OnClickListener { v ->
            if (busy) {
                return@OnClickListener
            }
            if (folder) {
                directory = entry.optString("path")
                loadFiles()
            } else {
                fileMenu(entry)
            }
        })
        return item
    }

    private fun favoriteFolders() {
        if (selected == null || busy) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val current: String? = if (listing == null) null else listing!!.optString("path")
        val body: LinearLayout = column()
        val viewport = ScrollView(activity)
        viewport.addView(body)
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Избранные папки и файлы", "Favorite folders and files")).setView(viewport).setPositiveButton(t("Закрыть", "Close"), null).create()
        if (current != null && current.startsWith("/")) {
            val exists: Boolean = p!!.favorites.contains(current)
            add(body, button(if (exists) t("Убрать текущую папку из избранного", "Remove current folder from favorites") else t("Добавить текущую папку", "Add current folder"), Runnable {
                if (selected !== p) {
                    return@Runnable
                }
                try {
                    if (exists) {
                        p.favorites.remove(current)
                    } else {
                        if (p.favorites.size >= 100) {
                            throw IOException("FAVORITES_LIMIT")
                        }
                        p.favorites.add(current)
                    }
                    store.save()
                    dialog.dismiss()
                    updateFileViews()
                } catch (error: Exception) {
                    if (exists) {
                        if (!p.favorites.contains(current)) {
                            p.favorites.add(current)
                        }
                    } else {
                        p.favorites.remove(current)
                    }
                    message(t("Ошибка", "Error"), t("Не удалось сохранить избранное. Предел: 100 папок.", "Could not save favorites. Limit: 100 folders."))
                }
            }))
        }
        if (!p!!.favorites.isEmpty()) {
            add(body, text(t("Папки", "Folders"), 13, Pal.MUTED))
        }
        for (path in ArrayList<String>(p.favorites)) {
            val item: TextView = button(path, Runnable {
                dialog.dismiss()
                if (selected === p && !busy) {
                    directory = path
                    loadFiles()
                }
            })
            item.setOnLongClickListener(View.OnLongClickListener { v ->
                confirm(t("Убрать из избранного?", "Remove favorite?"), path, Runnable {
                    try {
                        p.favorites.remove(path)
                        store.save()
                        dialog.dismiss()
                        if (selected === p) {
                            updateFileViews()
                        }
                    } catch (e: Exception) {
                        if (!p.favorites.contains(path)) {
                            p.favorites.add(path)
                        }
                        message("Error", explain("STORE_ERROR"))
                    }
                })
                return@OnLongClickListener true
            })
            add(body, item)
        }
        if (!p.favoriteFiles.isEmpty()) {
            add(body, text(t("Файлы", "Files"), 13, Pal.MUTED))
        }
        for (path in ArrayList<String>(p.favoriteFiles)) {
            val item: TextView = button(path, Runnable {
                dialog.dismiss()
                if (selected === p && !busy) {
                    try {
                        fileMenu(JSONObject().put("name", File(path).getName()).put("path", path))
                    } catch (ignored: Exception) {
                    }
                }
            })
            item.setOnLongClickListener(View.OnLongClickListener { v ->
                confirm(t("Убрать из избранного?", "Remove favorite?"), path, Runnable {
                    val index: Int = p.favoriteFiles.indexOf(path)
                    if (index < 0) {
                        return@Runnable
                    }
                    p.favoriteFiles.removeAt(index)
                    try {
                        store.save()
                        dialog.dismiss()
                    } catch (e: Exception) {
                        p.favoriteFiles.add(index, path)
                        message("Error", explain("STORE_ERROR"))
                    }
                })
                return@OnLongClickListener true
            })
            add(body, item)
        }
        if (p.favorites.isEmpty() && p.favoriteFiles.isEmpty()) {
            add(body, text(t("Папки добавляются кнопкой выше, файлы — через меню выбранного файла.", "Add folders using the button above; add files through the selected file's menu."), 13, Pal.MUTED))
        }
        show(dialog)
    }

    private fun toggleFileFavorite(entry: JSONObject) {
        if (selected == null || busy) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val path: String = entry.optString("path")
        if (!path.startsWith("/") || path.length > 4096 || path.indexOf('\u0000') >= 0) {
            return
        }
        val existed: Boolean = p!!.favoriteFiles.contains(path)
        val index: Int = p.favoriteFiles.indexOf(path)
        if (!existed && p.favoriteFiles.size >= 100) {
            message(t("Избранное", "Favorites"), t("До 100 файлов на сервер.", "Up to 100 files per server."))
            return
        }
        if (existed) {
            p.favoriteFiles.remove(path)
        } else {
            p.favoriteFiles.add(path)
        }
        try {
            store.save()
            activity.showToast(if (existed) t("Убрано из избранного", "Removed from favorites") else t("Добавлено в избранное", "Added to favorites"), false)
        } catch (e: Exception) {
            if (existed) {
                p.favoriteFiles.add(index, path)
            } else {
                p.favoriteFiles.remove(path)
            }
            message("Error", explain("STORE_ERROR"))
        }
    }

    private fun searchFiles() {
        if (selected == null || busy) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val base: String = if (listing == null) directory else listing!!.optString("path", directory)
        val body: LinearLayout = column()
        val query: EditText = field(body, t("Имя файла или папки", "File or folder name"), "", false)
        add(body, text(base + "\n" + t("В выбранном каталоге и подпапках, без перехода по ссылкам. До 20 000 записей / 8 секунд / 500 результатов. Содержимое файлов не читается.", "Current directory and subfolders, without following links. Up to 20,000 entries / 8 seconds / 500 results. File contents are not read."), 12, Pal.MUTED))
        val state: TextView = text("", 12, Pal.MUTED)
        add(body, state)
        val rows: LinearLayout = column()
        val viewport = ScrollView(activity)
        viewport.addView(rows)
        body.addView(viewport, LinearLayout.LayoutParams(-1, dp(280)))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Поиск на сервере", "Search server")).setView(body).setPositiveButton(t("Найти", "Search"), null).setNegativeButton(t("Закрыть", "Close"), null).create()
        val owned: Array<ServerConnection?> = arrayOf<ServerConnection?>(null)
        val future: Array<Future<*>?> = arrayOf<Future<*>?>(null)
        val running: BooleanArray = booleanArrayOf(false)
        show(dialog)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            dialogs = Math.max(0, dialogs - 1)
            ownedDialogs.remove(dialog)
            queues.cancelHelper(future[0])
            val c: ServerConnection? = owned[0]
            owned[0] = null
            if (c != null) {
                c.close()
                auxiliary.remove(c)
            }
        })
        dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            val value: String = (query.getText().toString() as java.lang.String).trim()
            if (running[0] || value.isEmpty()) {
                return@OnClickListener
            }
            if (value.length > 200) {
                query.setError(t("До 200 символов", "Up to 200 characters"))
                return@OnClickListener
            }
            rows.removeAllViews()
            running[0] = true
            query.setEnabled(false)
            dialog.getButton(-1).setEnabled(false)
            dialog.getButton(-2).setText(t("Остановить / закрыть", "Stop / close"))
            state.setText(t("Поиск…", "Searching…"))
            val c = ServerConnection(activity, p)
            owned[0] = c
            auxiliary.add(c)
            try {
                future[0] = queues.helper(Runnable {
                    var result: JSONObject? = null
                    try {
                        result = c.search(base, value)
                    } catch (ignored: Exception) {
                    } finally {
                        c.close()
                        auxiliary.remove(c)
                    }
                    val found: JSONObject? = result
                    main.post(Runnable {
                        if (closed || !dialog.isShowing() || owned[0] !== c) {
                            return@Runnable
                        }
                        owned[0] = null
                        running[0] = false
                        query.setEnabled(true)
                        dialog.getButton(-1).setEnabled(true)
                        dialog.getButton(-2).setText(t("Закрыть", "Close"))
                        if (found == null) {
                            state.setText(t("Поиск не завершён. Проверьте подключение, Python и права на каталог.", "Search failed. Check connectivity, Python and directory permissions."))
                            return@Runnable
                        }
                        val entries: JSONArray? = found.optJSONArray("entries")
                        state.setText(t("Найдено: ", "Found: ") + (if (entries == null) 0 else entries.length()) + " · " + t("Просмотрено: ", "Scanned: ") + found.optInt("scanned") + " · " + t("Пропущено: ", "Skipped: ") + found.optInt("skipped") + (if (found.optBoolean("partial")) "\n" + t("Результат неполный — сузьте каталог или запрос.", "Partial results — narrow the directory or query.") else ""))
                        if (entries != null) {
                            var i = 0
                            while (i < entries.length()) {
                                val entry: JSONObject? = entries.optJSONObject(i)
                                if (entry == null) {
                                    i++
                                    continue
                                }
                                val item: TextView = button(entry.optString("path"), Runnable {
                                    if (selected !== p || busy) {
                                        return@Runnable
                                    }
                                    dialog.dismiss()
                                    if (entry.optBoolean("directory")) {
                                        directory = entry.optString("path")
                                        loadFiles()
                                    } else {
                                        fileMenu(entry)
                                    }
                                })
                                add(rows, item)
                                i++
                            }
                        }
                    })
                })
            } catch (e: RejectedExecutionException) {
                c.close()
                auxiliary.remove(c)
                owned[0] = null
                running[0] = false
                query.setEnabled(true)
                dialog.getButton(-1).setEnabled(true)
                dialog.getButton(-2).setText(t("Закрыть", "Close"))
                state.setText(t("Все фоновые проверки заняты. Повторите позже.", "Background checks are busy. Try again later."))
            }
        })
    }

    private fun updateStreamPanel() {
        if (streamPanel == null || selected == null) {
            return
        }
        val c: ServerConnection? = connections.get(selected!!.id)
        val active: Boolean = c != null && c.tunnelActive()
        if (!active) {
            streamNames.remove(selected!!.id)
            if (streamPanel!!.getChildCount() > 0) {
                streamPanel!!.removeAllViews()
            }
            streamPanel!!.setVisibility(View.GONE)
            return
        }
        val tag: String = selected!!.id + "\n" + streamNames.getOrDefault(selected!!.id, "")
        if (streamPanel!!.getChildCount() > 0 && tag.equals(streamPanel!!.getTag())) {
            return
        }
        streamPanel!!.removeAllViews()
        streamPanel!!.setTag(tag)
        streamPanel!!.setVisibility(View.VISIBLE)
        val line: LinearLayout = row()
        line.setBackground(shape(Pal.PANEL))
        line.setPadding(dp(10), dp(4), dp(6), dp(4))
        val label: TextView = text(t("Просмотр: ", "Playing: ") + streamNames.getOrDefault(selected!!.id, t("Медиа", "Media")), 12, Pal.TEXT)
        label.setMaxLines(2)
        label.setEllipsize(TextUtils.TruncateAt.END)
        line.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
        val stop: TextView = button(t("Остановить", "Stop"), Runnable {
            c!!.stopTunnel()
            streamNames.remove(selected!!.id)
            streamPanel!!.removeAllViews()
            updateStreamPanel()
        })
        line.addView(stop)
        streamPanel!!.addView(line, LinearLayout.LayoutParams(-1, -2))
    }

    private fun components() {
        if (busy || selected == null) {
            return
        }
        val p: ServerProfiles.Profile? = selected
        val c: ServerConnection? = connection(p)
        run(Work(c!!::components), Done { status ->
            val body: LinearLayout = column()
            body.setPadding(dp(16), 0, dp(16), 0)
            title(body, "FFmpeg / ffprobe")
            add(body, text("FFmpeg: " + (if (status!!.optBoolean("ffmpeg")) t("Установлен", "Installed") else t("Не установлен", "Not installed")) + "\nffprobe: " + (if (status.optBoolean("ffprobe")) t("Установлен", "Installed") else t("Не установлен", "Not installed")), 14, Pal.TEXT))
            add(body, text(t("ffprobe читает длительность, разрешение и кодеки. FFmpeg также нужен для обработки медиа. Сам просмотр файлов через SSH не требует установки этих пакетов.", "ffprobe reads duration, resolution and codecs. FFmpeg also processes media. Streaming files over SSH does not require these packages."), 13, Pal.MUTED))
            val install: TextView = button(t("Установить FFmpeg на сервер…", "Install FFmpeg on server…"), Runnable { installFfmpeg(p, status) })
            install.setEnabled(!status.optBoolean("ffprobe") || !status.optBoolean("ffmpeg"))
            add(body, install)
            val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Компоненты агента", "Agent components")).setView(body).setPositiveButton(t("Закрыть", "Close"), null).create()
            show(d)
            install.setOnClickListener(View.OnClickListener { v ->
                d.dismiss()
                installFfmpeg(p, status)
            })
        })
    }

    private fun installFfmpeg(p: ServerProfiles.Profile?, status: JSONObject?) {
        if (selected !== p || busy) {
            return
        }
        if (!status!!.optBoolean("agent")) {
            message(t("Нужен Agent", "Agent required"), t("Сначала установите агент через меню сервера. Установка пакета будет отдельной задачей с журналом.", "Install the agent from the server menu first. Package installation will be a separate logged task."))
            return
        }
        if (!status.optBoolean("apt")) {
            message(t("Установка недоступна", "Installation unavailable"), t("Автоустановка пока поддерживает только серверы с APT. На других системах установите FFmpeg вручную через SSH.", "Automatic installation currently supports APT servers only. Install FFmpeg manually over SSH on other systems."))
            return
        }
        val root: Boolean = status.optInt("uid", -1) == 0
        if (!root && !status.optBoolean("sudo")) {
            message(t("Нужны права", "Permissions required"), t("Для APT нужны root или настроенный sudo. Выполните установку вручную в SSH-терминале.", "APT needs root or configured sudo. Install manually in the SSH terminal."))
            return
        }
        val command: String = ServerFileInfo.installCommand(root)
        confirm(t("Установить FFmpeg на сервер?", "Install FFmpeg on server?"), p!!.user + "@" + p.host + "\n\n" + command + "\n\n" + t("Будут обновлены индексы APT и установлены FFmpeg, ffprobe и зависимости на выбранном сервере. Нужны место, сеть и административные права. sudo выполняется без запроса пароля; если права не настроены, задача завершится ошибкой — используйте SSH-терминал. Пакеты автоматически не удаляются вместе с агентом. Предпочтительно дождаться окончания установки, не прерывая APT.", "Updates APT indexes and installs FFmpeg, ffprobe and dependencies on the selected server. Requires disk space, network and administrative permissions. sudo will not prompt; without configured permissions the task fails — use SSH terminal. Packages are not removed with the agent. Prefer waiting for installation to finish without interrupting APT."), Runnable {
            val c: ServerConnection? = connection(p)
            run(Work {
                c!!.rpc(JSONObject().put("op", "start").put("title", "FFmpeg / ffprobe").put("directory", status.optString("home", "~")).put("command", command))
            }, Done { r ->
                project = null
                tab = "tasks"
                refresh(true)
            })
        })
    }

    private fun showProperties(entry: JSONObject) {
        if (busy || selected == null) {
            return
        }
        val c: ServerConnection? = connection(selected)
        val p: ServerProfiles.Profile? = selected
        val path: String = entry.optString("path")
        val info: TextView = text(t("Чтение свойств…", "Reading properties…"), 14, Pal.TEXT)
        info.setTextIsSelectable(true)
        info.setPadding(dp(16), dp(8), dp(16), dp(8))
        info.setMinHeight(dp(120))
        val viewport = ScrollView(activity)
        viewport.addView(info)
        val builder: AlertDialog.Builder = WorkspaceDialogs.builder(activity).setTitle(t("Свойства", "Properties")).setView(viewport).setPositiveButton(t("Закрыть", "Close"), null)
        if (ServerFileInfo.media(path)) {
            builder.setNeutralButton(t("Установить FFmpeg…", "Install FFmpeg…"), null)
        }
        val dialog: AlertDialog = builder.create()
        show(dialog)
        if (dialog.getButton(DialogInterface.BUTTON_NEUTRAL) != null) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(View.GONE)
        }
        runInline(Work { c!!.properties(path) }, Done { data ->
            if (!dialog.isShowing()) {
                return@Done
            }
            info.setText(ServerFileInfo.properties(data, WorkspaceLanguage.code(activity)!!.equals("ru"), TimeZone.getDefault()))
            val components: JSONObject? = data!!.optJSONObject("components")
            if (ServerFileInfo.media(path) && components != null && !components.optBoolean("ffprobe")) {
                val install: TextView = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                install.setVisibility(View.VISIBLE)
                install.setOnClickListener(View.OnClickListener { v ->
                    dialog.dismiss()
                    installFfmpeg(p, components)
                })
            }
        }, Consumer<String> { code ->
            if (dialog.isShowing()) {
                info.setText(explain(code))
            }
        })
    }

    private fun pathDialog() {
        if (busy) {
            return
        }
        val f: LinearLayout = column()
        val input: EditText = field(f, t("Каталог на сервере", "Server directory"), directory, false)
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Открыть каталог", "Open directory")).setView(f).setPositiveButton(t("Открыть", "Open"), DialogInterface.OnClickListener { x, w ->
            directory = input.getText().toString()
            loadFiles()
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(d)
    }

    private fun fileMenu(entry: JSONObject) {
        if (busy) {
            return
        }
        val path: String = entry.optString("path")
        val labels: Array<String> = arrayOf<String>(t("Посмотреть", "View"), t("Свойства / медиаинформация", "Properties / media info"), t("Скачать в Download", "Download to Download"), t("Скачать в другую папку…", "Download to another folder…"), t("Открыть видео / аудио", "Open video / audio"), t("Переименовать…", "Rename…"), t("Удалить файл…", "Delete file…"), if (selected!!.favoriteFiles.contains(path)) t("Убрать из избранного", "Remove from favorites") else t("В избранное", "Add to favorites"))
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(entry.optString("name")).setItems(labels, DialogInterface.OnClickListener { x, w ->
            val c: ServerConnection? = connection(selected)
            if (w == 7) {
                toggleFileFavorite(entry)
            }
            if (w == 0) {
                viewText(entry)
            }
            if (w == 1) {
                showProperties(entry)
            }
            if (w == 2) {
                download(entry)
            }
            if (w == 3) {
                downloadTo(entry)
            }
            if (w == 4) {
                stream(entry)
            }
            if (w == 5) {
                val f: LinearLayout = column()
                val name: EditText = field(f, t("Новое имя", "New name"), entry.optString("name"), false)
                val rename: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Переименовать", "Rename")).setView(f).setPositiveButton(t("Сохранить", "Save"), DialogInterface.OnClickListener { v, which ->
                    run(Work { c!!.rpc(JSONObject().put("op", "rename").put("path", path).put("name", name.getText().toString())) }, Done { r -> loadFiles() })
                }).setNegativeButton(t("Отмена", "Cancel"), null).create()
                show(rename)
            }
            if (w == 6) {
                confirm(t("Удалить файл с сервера?", "Delete file from server?"), path, Runnable {
                    run(Work { c!!.rpc(JSONObject().put("op", "delete").put("path", path)) }, Done { r -> loadFiles() })
                })
            }
        }).create()
        show(d)
    }

    private fun download(entry: JSONObject) {
        if (selected == null || busy) {
            return
        }
        downloads.quick(selected, entry)
    }

    private fun downloadTo(entry: JSONObject) {
        if (selected == null || busy) {
            return
        }
        val c: ServerConnection? = connection(selected)
        if (!c!!.trusted()) {
            scan(false)
            return
        }
        downloads.start(selected, entry)
    }

    private fun closePlayer() {
        val owned: WorkspaceServerPlayer? = player
        player = null
        playerProfile = ""
        if (owned != null) {
            owned.close()
        }
    }

    private fun externalMedia(c: ServerConnection?, id: String, url: String, mime: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime))
        } catch (e: ActivityNotFoundException) {
            c!!.stopTunnel()
            streamNames.remove(id)
            updateStreamPanel()
            message(t("Нет плеера", "No player"), t("Скачайте файл для просмотра.", "Download the file to view it."))
        }
    }

    private fun stream(entry: JSONObject) {
        if (selected == null || busy) {
            return
        }
        closePlayer()
        val p: ServerProfiles.Profile? = selected
        val c: ServerConnection? = connection(p)
        runInline(Work {
            val r: JSONObject = c!!.rpc(JSONObject().put("op", "stream").put("path", entry.getString("path")))
            val token: String = r.getString("token")
            if (!(token as java.lang.String).matches("[a-f0-9]{64}")) {
                throw IOException("INVALID_TOKEN")
            }
            val port: IntArray = intArrayOf(0)
            c.openTunnel(r.getInt("port"), port)
            return@Work JSONObject().put("url", "http://127.0.0.1:" + port[0] + "/" + token).put("mime", r.optString("mime", "application/octet-stream"))
        }, Done { r ->
            if (!foreground || !visible || selected !== p) {
                c!!.stopTunnel()
                return@Done
            }
            streamNames.put(p!!.id, entry.optString("name"))
            updateStreamPanel()
            val url: String = r!!.getString("url")
            val mime: String = r.getString("mime")
            try {
                playerProfile = p.id
                player = WorkspaceServerPlayer(activity, entry.optString("name"), url, Runnable {
                    c!!.stopTunnel()
                    streamNames.remove(p.id)
                    updateStreamPanel()
                }, Runnable { externalMedia(c, p.id, url, mime) }, Runnable {
                    if (selected === p) {
                        download(entry)
                    }
                })
            } catch (e: RuntimeException) {
                c!!.stopTunnel()
                streamNames.remove(p.id)
                updateStreamPanel()
                message(t("Ошибка плеера", "Player error"), t("Не удалось открыть плеер. Файл можно скачать.", "Could not open player. You can download the file."))
            }
        }, Consumer<String> { code ->
            c!!.stopTunnel()
            message(t("Ошибка", "Error"), explain(code))
        }, Runnable(c!!::stopTunnel))
    }

    private fun viewText(entry: JSONObject) {
        if (selected == null || busy) {
            return
        }
        val files = ProjectFiles(activity, selected!!.session())
        files.setServerKnownHosts(connection(selected)!!.knownHosts())
        textFiles.add(files)
        val text: TextView = text(t("Чтение файла…", "Reading file…"), 14, Pal.TEXT)
        text.setTypeface(Typeface.MONOSPACE)
        text.setTextIsSelectable(true)
        text.setSaveEnabled(false)
        val viewport = ScrollView(activity)
        viewport.addView(text)
        viewport.setMinimumHeight(dp(240))
        val viewer: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(entry.optString("name")).setView(viewport).setPositiveButton(t("Закрыть", "Close"), null).setNeutralButton(t("Редактировать", "Edit"), null).create()
        show(viewer)
        viewer.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(false)
        val editing: BooleanArray = booleanArrayOf(false)
        viewer.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            dialogs = Math.max(0, dialogs - 1)
            ownedDialogs.remove(viewer)
            if (!editing[0]) {
                files.close()
                textFiles.remove(files)
            }
        })
        val path: String = entry.optString("path")
        val f = File(path)
        val file = ProjectFiles.Entry(if (f.getParent() == null) "/" else f.getParent(), f.getName(), false, entry.optLong("size"))
        runInline(Work {
            val snapshot: TextFileEdits.Snapshot = files.editRead(file)
            return@Work JSONObject().put("path", snapshot.path).put("revision", snapshot.revision).put("text", snapshot.text)
        }, Done { r ->
            if (!viewer.isShowing()) {
                return@Done
            }
            val snapshot = TextFileEdits.Snapshot(r!!.getString("path"), r.getString("revision"), r.getString("text"))
            text.setText(snapshot.text)
            viewer.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(true)
            viewer.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v ->
                editing[0] = true
                viewer.dismiss()
                val holder = arrayOfNulls<WorkspaceTextEditor>(1)
                val editor = WorkspaceTextEditor(activity, files, worker, snapshot, Runnable {
                    if (!closed && visible && selected != null && !ServerConnection.disconnected(activity, selected!!.id) && tab.equals("files")) {
                        loadFiles()
                    }
                }, Runnable {
                    files.close()
                    textFiles.remove(files)
                    editors.remove(holder[0])
                })
                holder[0] = editor
                editors.add(editor)
                editor.show()
            })
        }, Consumer<String> { code ->
            if (viewer.isShowing()) {
                text.setText(t("Не удалось открыть. Нужен обычный файл UTF-8 до 256 КБ, без символьных и жёстких ссылок.", "Unable to open. Requires a regular UTF-8 file up to 256 KB, without symbolic or hard links."))
            }
        })
    }

    private fun services() {
        if (busy || selected == null) {
            return
        }
        val c: ServerConnection? = connection(selected)
        runInline(Work { c!!.rpc(JSONObject().put("op", "services")) }, Done { r ->
            listing = r
            if (serviceText != null) {
                val value: String = if (r!!.optBoolean("available")) r.optString("text") else explain("SERVICE_PERMISSION_OR_UNAVAILABLE")
                preserveViewport(Runnable {
                    if (sectionBody != null) {
                        sectionBody!!.setMinimumHeight(0)
                    }
                    if (!(value as java.lang.String).contentEquals(serviceText!!.getText())) {
                        serviceText!!.setText(value)
                    }
                })
            }
        }, Consumer<String> { code ->
            if (serviceText != null) {
                preserveViewport(Runnable {
                    if (sectionBody != null) {
                        sectionBody!!.setMinimumHeight(0)
                    }
                    serviceText!!.setText(explain(code))
                })
            }
        })
    }

    private fun showServiceLog(c: ServerConnection?, name: String) {
        val log: TextView = text(t("Чтение журнала…", "Reading log…"), 12, Pal.TEXT)
        log.setTypeface(Typeface.MONOSPACE)
        log.setTextIsSelectable(true)
        val viewport = ScrollView(activity)
        viewport.addView(log)
        viewport.setMinimumHeight(dp(240))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Журнал службы", "Service log") + " · " + name).setView(viewport).setPositiveButton(t("Закрыть", "Close"), null).setNeutralButton(t("Обновить", "Refresh"), null).create()
        show(dialog)
        val update = Runnable {
            if (!dialog.isShowing()) {
                return@Runnable
            }
            runInline(Work { c!!.rpc(JSONObject().put("op", "service-log").put("name", name)) }, Done { r ->
                if (!dialog.isShowing()) {
                    return@Done
                }
                val value: String = r!!.optString("text")
                updateLog(log, viewport, value)
            }, Consumer<String> { code ->
                if (dialog.isShowing()) {
                    Toast.makeText(activity, explain(code), Toast.LENGTH_SHORT).show()
                }
            })
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v -> update.run() })
        update.run()
    }

    private fun serviceDialog() {
        val f: LinearLayout = column()
        val name: EditText = field(f, t("Имя службы, например turnip-bot.service", "Service name, e.g. turnip-bot.service"), "", false)
        val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Управление службой", "Service management")).setView(f).setPositiveButton(t("Журнал", "Log"), DialogInterface.OnClickListener { x, w ->
            val c: ServerConnection? = connection(selected)
            showServiceLog(c, (name.getText().toString() as java.lang.String).trim())
        }).setNeutralButton(t("Перезапустить…", "Restart…"), DialogInterface.OnClickListener { x, w ->
            confirm(t("Перезапустить службу?", "Restart service?"), name.getText().toString() + "\n" + t("Текущая работа службы будет прервана. Без sudo; только доступные SSH-пользователю действия.", "Current service work will be interrupted. No sudo; only actions allowed for the SSH user."), Runnable {
                val c: ServerConnection? = connection(selected)
                run(Work {
                    c!!.rpc(JSONObject().put("op", "service-restart").put("name", (name.getText().toString() as java.lang.String).trim()))
                }, Done { r -> message("OK", t("Запрос выполнен", "Request completed")) })
            })
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(d)
    }

    override fun close() {
        closed = true
        if (scheduleUi != null) {
            scheduleUi!!.close()
        }
        uploads.close()
        downloads.close()
        closePlayer()
        for (editor in ArrayList<WorkspaceTextEditor>(editors)) {
            editor.close()
        }
        for (files in textFiles) {
            files.close()
        }
        editors.clear()
        textFiles.clear()
        main.removeCallbacksAndMessages(null)
        for (d in ArrayList<AlertDialog>(ownedDialogs)) {
            d.dismiss()
        }
        for (c in connections.values) {
            c!!.close()
        }
        for (browser in ArrayList<WorkspaceFileBrowser>(sshBrowsers)) {
            browser.close()
        }
        sshBrowsers.clear()
        for (c in auxiliary) {
            c.close()
        }
        auxiliary.clear()
        queues.close()
        connections.clear()
        root = null
    }

    companion object {
        @JvmStatic
        fun size(n: Long): String {
            return ServerFileInfo.size(n, false)
        }

        @JvmStatic
        fun finishedTask(state: String): Boolean {
            return state.equals("done") || state.equals("failed") || state.equals("cancelled")
        }

        @JvmStatic
        fun updateLog(text: TextView, viewport: ScrollView, value: String) {
            if ((value as java.lang.String).contentEquals(text.getText())) {
                return
            }
            val y: Int = viewport.getScrollY()
            val previous: String = text.getText().toString()
            if (!previous.isEmpty() && value.startsWith(previous)) {
                text.append(value.substring(previous.length))
            } else {
                text.setText(value)
            }
            viewport.post(Runnable { viewport.scrollTo(0, y) })
        }
    }
}
