package com.termux.app.workspace

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.termux.app.TermuxActivity
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Session-targeted catalogue; PTY operations require an explicit destination confirmation.*/
class WorkspacePackages : AutoCloseable {
    fun interface Launch {
        fun run(profile: WorkspaceStore.Session, selectedSession: Boolean)
    }

    private val activity: TermuxActivity
    private val files: ProjectFiles
    private val launch: Launch
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var onClosed: Runnable? = null

    fun setOnClosed(callback: Runnable) {
        onClosed = callback
    }

    private var dialog: AlertDialog? = null
    private var rows: LinearLayout? = null
    private var tabs: LinearLayout? = null
    private var status: TextView? = null
    private var search: EditText? = null
    private var backend: EnvironmentPackages.Kind? = null
    private var catalogHint: TextView? = null
    private var refreshButton: TextView? = null
    @Volatile private var historyUnavailable: Boolean = false
    private var entries: MutableList<PackageCatalog.Entry>? = Collections.emptyList()
    private var tab: Int = 0
    private var limit: Int = 100
    private var generation: Int = 0
    @Volatile private var closed: Boolean = false
    private var busy: Boolean = false

    constructor(activity: TermuxActivity, profile: WorkspaceStore.Session?, launch: Launch) {
        this.activity = activity
        this.files = ProjectFiles(activity, profile)
        this.launch = launch
        val environment: String = ProjectFiles.environment(profile)
        if (environment.equals("termux") || environment.equals("ubuntu")) {
            backend = EnvironmentPackages.Kind.APT
        }
    }

    private fun tr(ru: String, en: String): String {
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

    private fun text(value: String?, size: Int): TextView {
        val t = TextView(activity)
        t.setText(value)
        t.setTextColor(Pal.TEXT)
        t.setTextSize(size.toFloat())
        return t
    }

    private fun action(label: String?, run: Runnable): TextView {
        val t: TextView = WorkspaceDialogs.action(activity, label, 0)
        t.setOnClickListener(View.OnClickListener { v -> run.run() })
        return t
    }

    private fun compactParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, dp(56), 1.0f)
    }

    fun show() {
        val body: LinearLayout = column()
        body.setPadding(dp(12), 0, dp(12), dp(8))
        body.addView(text(WorkspaceText.tr("Сессия: ") + files.profile.name, 12))
        body.addView(text(WorkspaceText.tr("Установленные пакеты сохраняются в выбранном окружении и доступны всем его сессиям. Закрытие сессии без сохранения не удаляет пакеты."), 12))
        tabs = LinearLayout(activity)
        tabs!!.setGravity(Gravity.CENTER_VERTICAL)
        body.addView(tabs)
        renderTabs()
        search = EditText(activity)
        search!!.setSingleLine(true)
        search!!.setTextColor(Pal.TEXT)
        search!!.setHintTextColor(Pal.MUTED)
        search!!.setHint(WorkspaceText.tr("Поиск пакетов"))
        body.addView(search)
        search!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {
            }

            override fun onTextChanged(s: CharSequence, st: Int, before: Int, count: Int) {
                limit = 100
                render()
            }

            override fun afterTextChanged(e: Editable) {
            }
        })
        catalogHint = text(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Пакеты выбранного окружения. Для свежих данных обновите каталог." else "Packages in the selected environment. Refresh the catalogue for current metadata.", 12)
        body.addView(catalogHint)
        val controls = LinearLayout(activity)
        controls.addView(compactAction(activity, WorkspaceText.tr("Перечитать список"), Runnable(this::load)), compactParams())
        refreshButton = compactAction(activity, WorkspaceText.tr("Обновить каталог"), Runnable { confirm("refresh", null) })
        controls.addView(refreshButton, compactParams())
        body.addView(controls)
        status = text("", 12)
        body.addView(status)
        rows = column()
        val scroll = ScrollView(activity)
        scroll.addView(rows)
        val height: Int = Math.min(dp(330), Math.max(dp(120), (activity.getResources().getDisplayMetrics().heightPixels * 0.32).toInt()))
        body.addView(scroll, LinearLayout.LayoutParams(-1, height))
        dialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Пакеты · ") + ProjectFiles.environmentLabel(files.profile)).setView(body).setNeutralButton(tr("История", "History"), DialogInterface.OnClickListener { d, w ->  }).setNegativeButton(WorkspaceText.tr("Закрыть"), null).create()
        dialog!!.setOnDismissListener(DialogInterface.OnDismissListener { d -> close() })
        dialog!!.show()
        dialog!!.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v -> showHistory() })
        load()
    }

    private fun renderTabs() {
        if (dialog != null && dialog!!.isShowing()) {
            dialog!!.getButton(DialogInterface.BUTTON_NEUTRAL).setVisibility(if (tab == 0) View.VISIBLE else View.GONE)
        }
        tabs!!.removeAllViews()
        val names: Array<String?> = arrayOf<String?>(WorkspaceText.tr("Установленные"), WorkspaceText.tr("Доступные"), WorkspaceText.tr("Обновления"))
        for (i in 0 until (3)) {
            val next: Int = i
            val t: TextView = compactAction(activity, names[i], Runnable {
                if (tab == next) {
                    return@Runnable
                }
                tab = next
                limit = 100
                entries = Collections.emptyList()
                renderTabs()
                load()
            })
            t.setSelected(tab == i)
            t.setBackground(WorkspaceDialogs.background(activity, tab == i))
            tabs!!.addView(t, compactParams())
        }
    }

    private fun load() {
        if (closed) {
            return
        }
        val request: Int = ++generation
        val selected: Int = tab
        busy = true
        entries = Collections.emptyList()
        render()
        status!!.setText(WorkspaceText.tr("Загрузка пакетов…"))
        worker.submit(Runnable {
            if (closed) {
                return@Runnable
            }
            var result: MutableList<PackageCatalog.Entry>? = null
            var error: String? = null
            try {
                if (backend == null) {
                    backend = files.packageBackend()
                }
                result = EnvironmentPackages.parse(backend, files.packageQuery(backend, selected), selected)
                if (selected == 0) {
                    recordHistory(result)
                }
            } catch (e: Exception) {
                error = e.message
            }
            val loaded: MutableList<PackageCatalog.Entry>? = result
            val message: String? = error
            activity.runOnUiThread(Runnable {
                if (closed || generation != request) {
                    return@Runnable
                }
                busy = false
                if (backend != null) {
                    catalogHint!!.setText(backend!!.name + " · " + ProjectFiles.environmentLabel(files.profile) + "\n" + tr("Чтение локального каталога. Если он пуст или устарел, обновите его явно.", "Reads local metadata. Refresh it explicitly if empty or outdated."))
                    refreshButton!!.setText(if (backend === EnvironmentPackages.Kind.PACMAN) tr("Обновить систему", "Upgrade system") else WorkspaceText.tr("Обновить каталог"))
                }
                if (message != null) {
                    status!!.setText(message)
                    return@Runnable
                }
                entries = loaded
                status!!.setText(Integer.toString(entries!!.size))
                render()
            })
        })
    }

    private fun recordHistory(installed: MutableList<PackageCatalog.Entry>?) {
        try {
            PackageOperationJournal.collect(activity.getFilesDir(), null)
            val key: String = WorkspacePackageHistory.key(files.profile)
            if (PackageOperationJournal.pending(activity.getFilesDir(), key).length() == 0) {
                WorkspacePackageHistory.observe(activity.getFilesDir(), key, installed, System.currentTimeMillis())
            }
            historyUnavailable = false
        } catch (error: Exception) {
            historyUnavailable = true
        }
    }

    private fun showHistory() {
        if (closed || tab != 0) {
            return
        }
        val progress: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(tr("История пакетов", "Package history")).setMessage(tr("Загрузка истории…", "Loading history…")).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        progress.show()
        worker.submit(Runnable {
            var records: JSONArray? = null
            try {
                if (!historyUnavailable) {
                    PackageOperationJournal.collect(activity.getFilesDir(), null)
                    val key: String = WorkspacePackageHistory.key(files.profile)
                    records = WorkspacePackageHistory.read(activity.getFilesDir(), key)
                    val pending: JSONArray = PackageOperationJournal.pending(activity.getFilesDir(), key)
                    records = WorkspacePackageHistory.merge(records, pending)
                }
            } catch (error: Exception) {
            }
            val result: JSONArray? = records
            activity.runOnUiThread(Runnable {
                if (closed || !progress.isShowing()) {
                    return@Runnable
                }
                progress.dismiss()
                val body: LinearLayout = column()
                body.setPadding(dp(16), 0, dp(16), dp(12))
                body.addView(text(tr("Общая история сессий и пресетов этого окружения. Действия из нашего менеджера записываются с результатом. Прочие изменения обнаруживаются при чтении установленного списка. Дата — время получения результата или обнаружения. Последние 300 записей.", "Shared by sessions and presets in this environment. Actions from this manager include their outcome. Other changes are detected when the installed list is read. Dates show result collection or detection time. Latest 300 records."), 12))
                if (result == null) {
                    body.addView(text(tr("Не удалось прочитать историю. Перечитайте установленный список и повторите.", "Could not read history. Reload the installed list and retry."), 14))
                } else if (result.length() == 0) {
                    body.addView(text(tr("Изменений пока нет. Начальный список сохранён; следующие установки, удаления и смены версий появятся после перечитывания списка.", "No changes yet. The initial list is saved; later installs, removals and version changes appear after reloading the list."), 14))
                } else {
                    for (i in result.length() - 1 downTo 0) {
                        val row: JSONObject? = result.optJSONObject(i)
                        if (row == null) {
                            continue
                        }
                        val action: String = row.optString("action")
                        var label: String = if (action.equals("installed")) tr("Установлен", "Installed") else if (action.equals("removed")) tr("Удалён", "Removed") else tr("Версия изменена", "Version changed")
                        var versions: String = if (action.equals("installed")) row.optString("after") else if (action.equals("removed")) row.optString("before") else row.optString("before") + " → " + row.optString("after")
                        if (action.equals("operation")) {
                            val op: String = row.optString("operation")
                            val phase: String = row.optString("phase")
                            val noun: String = if (op.equals("remove")) tr("Удаление", "Removal") else if (op.equals("upgrade")) tr("Обновление", "Upgrade") else if (op.equals("version")) tr("Установка версии", "Version installation") else tr("Установка", "Installation")
                            label = noun + " · " + (if (phase.equals("success")) tr("завершено", "completed") else if (phase.equals("cancelled")) tr("отменено", "cancelled") else if (phase.equals("failure")) tr("ошибка или отмена", "failed or declined") else tr("результат не получен", "result not received"))
                            if (phase.equals("failure")) {
                                label += " · " + tr("код ", "code ") + row.optInt("exit")
                            }
                            versions = if (row.optBoolean("reconciled")) row.optString("before") + " → " + row.optString("after") else if (row.optString("after").isEmpty()) "" else tr("Версия в запросе: ", "Requested/catalogue version: ") + row.optString("after")
                        }
                        val date: String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("observed")))
                        val line: TextView = text(row.optString("name") + " · " + label + "\n" + versions + "\n" + date, 14)
                        line.setPadding(0, dp(10), 0, dp(10))
                        line.setTextIsSelectable(true)
                        body.addView(line)
                    }
                }
                val scroll = ScrollView(activity)
                scroll.addView(body)
                val frame: LinearLayout = column()
                frame.addView(scroll, LinearLayout.LayoutParams(-1, Math.min(dp(420), activity.getResources().getDisplayMetrics().heightPixels / 2)))
                WorkspaceDialogs.builder(activity).setTitle(tr("История пакетов · ", "Package history · ") + ProjectFiles.environmentLabel(files.profile)).setView(frame).setNegativeButton(tr("Назад", "Back"), null).show()
            })
        })
    }

    private fun render() {
        if (rows == null) {
            return
        }
        rows!!.removeAllViews()
        val query: String = ((search!!.getText().toString() as java.lang.String).trim() as java.lang.String).toLowerCase(Locale.ROOT)
        var shown = 0
        var total = 0
        for (e in entries!!) {
            if (!(e.name as java.lang.String).toLowerCase(Locale.ROOT).contains(query)) {
                continue
            }
            total++
            if (shown++ >= limit) {
                continue
            }
            val row: TextView = action(e.name + "\n" + WorkspaceText.tr("Версия: ") + e.version, Runnable { details(e) })
            rows!!.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (total > limit) {
            rows!!.addView(action(WorkspaceText.tr("Показать ещё"), Runnable {
                limit += 100
                render()
            }))
        } else if (total == 0 && !busy) {
            rows!!.addView(text(WorkspaceText.tr("Пакеты не найдены"), 14))
        }
    }

    private fun details(entry: PackageCatalog.Entry) {
        if (closed) {
            return
        }
        val selected: Int = tab
        val body: LinearLayout = column()
        body.setPadding(dp(16), 0, dp(16), dp(8))
        body.addView(text(WorkspaceText.tr("Описание"), 12))
        val description: TextView = text(WorkspaceText.tr("Загрузка описания…"), 14)
        description.setMaxLines(3)
        description.setEllipsize(TextUtils.TruncateAt.END)
        body.addView(description)
        val full: Array<String> = arrayOf<String>("")
        val more: TextView = action(WorkspaceText.tr("Подробнее"), Runnable { fullDescription(entry, full[0]) })
        more.setVisibility(View.GONE)
        body.addView(more)
        val card: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(entry.name + " · " + entry.version).setView(body).setNegativeButton(WorkspaceText.tr("Закрыть"), null).create()
        body.addView(action(if (selected == 1) WorkspaceText.tr("Установить") else WorkspaceText.tr("Обновить пакет"), Runnable {
            card.dismiss()
            confirm(if (selected == 1) "install" else "upgrade", entry)
        }))
        if (backend === EnvironmentPackages.Kind.APT || EnvironmentPackageVersions.supported(backend)) {
            body.addView(action(WorkspaceText.tr("Выбрать версию"), Runnable {
                card.dismiss()
                chooseVersion(entry)
            }))
        } else {
            body.addView(text(tr("Выбор отдельных версий для этого менеджера пока недоступен. Менеджер покажет план установки в терминале.", "Version selection is not available for this manager yet. The package manager shows its installation plan in the terminal."), 12))
        }
        if (selected == 0) {
            body.addView(action(WorkspaceText.tr("Удалить пакет"), Runnable {
                card.dismiss()
                confirm("remove", entry)
            }))
        }
        card.show()
        worker.submit(Runnable {
            if (closed) {
                return@Runnable
            }
            var result = ""
            try {
                result = EnvironmentPackages.parseDescription(backend, files.packageDescription(backend, entry, selected == 0))
            } catch (ignored: Exception) {
            }
            val value: String = result
            activity.runOnUiThread(Runnable {
                if (closed || !card.isShowing()) {
                    return@Runnable
                }
                full[0] = value
                description.setText(if (value.isEmpty()) WorkspaceText.tr("Описание недоступно. Обновите каталог или проверьте выбранное окружение.") else value)
                more.setVisibility(if (value.isEmpty()) View.GONE else View.VISIBLE)
            })
        })
    }

    private fun fullDescription(entry: PackageCatalog.Entry, value: String) {
        if (closed || value.isEmpty()) {
            return
        }
        val body: LinearLayout = column()
        body.setPadding(dp(16), 0, dp(16), dp(8))
        body.addView(text(WorkspaceText.tr("Описание из репозитория"), 12))
        val full: TextView = text(value, 14)
        full.setTextIsSelectable(true)
        full.setPadding(0, dp(10), 0, dp(10))
        val scroll = ScrollView(activity)
        scroll.addView(full)
        body.addView(scroll, LinearLayout.LayoutParams(-1, Math.min(dp(360), activity.getResources().getDisplayMetrics().heightPixels / 3)))
        body.addView(text(WorkspaceText.tr("Язык описания зависит от репозитория."), 12))
        WorkspaceDialogs.builder(activity).setTitle(entry.name + " · " + WorkspaceText.tr("Описание")).setView(body).setNegativeButton(WorkspaceText.tr("Закрыть"), null).show()
    }

    private fun chooseVersion(entry: PackageCatalog.Entry) {
        if (backend !== EnvironmentPackages.Kind.APT) {
            chooseNativeVersion(entry)
            return
        }
        if (closed) {
            return
        }
        val progress: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(entry.name).setMessage(WorkspaceText.tr("Загрузка версий…")).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        progress.show()
        worker.submit(Runnable {
            if (closed) {
                return@Runnable
            }
            var versions: MutableList<PackageCatalog.Entry>? = null
            var failure: String? = null
            try {
                versions = PackageCatalog.parseVersions(entry.name, files.packageVersions(entry.name))
            } catch (e: Exception) {
                failure = e.message
            }
            val result: MutableList<PackageCatalog.Entry>? = versions
            val error: String? = failure
            activity.runOnUiThread(Runnable {
                if (closed || !progress.isShowing()) {
                    return@Runnable
                }
                progress.dismiss()
                if (error != null) {
                    activity.showToast(error, true)
                    return@Runnable
                }
                if (result!!.isEmpty()) {
                    activity.showToast(WorkspaceText.tr("В подключённых репозиториях нет доступных версий. Обновите каталог."), true)
                    return@Runnable
                }
                val labels = arrayOfNulls<String>(result.size)
                for (i in 0 until (labels.size)) {
                    labels[i] = result.get(i).version
                }
                WorkspaceDialogs.builder(activity).setTitle(entry.name + " · " + WorkspaceText.tr("Выбрать версию")).setItems(labels, DialogInterface.OnClickListener { d, index -> confirm("version", result.get(index)) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
            })
        })
    }

    private fun chooseNativeVersion(entry: PackageCatalog.Entry) {
        if (closed || !EnvironmentPackageVersions.supported(backend)) {
            return
        }
        val kind: EnvironmentPackages.Kind? = backend
        val progress: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(entry.name).setMessage(WorkspaceText.tr("Загрузка версий…")).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        progress.show()
        worker.submit(Runnable {
            var versions: MutableList<EnvironmentPackageVersions.Version>? = null
            var failure: String? = null
            try {
                if (closed) {
                    return@Runnable
                }
                versions = EnvironmentPackageVersions.parse(kind, entry.name, files.packageVersions(kind, entry.name))
            } catch (error: Exception) {
                failure = error.message
            }
            val result: MutableList<EnvironmentPackageVersions.Version>? = versions
            val error: String? = failure
            activity.runOnUiThread(Runnable {
                if (closed || !progress.isShowing()) {
                    return@Runnable
                }
                progress.dismiss()
                if (error != null) {
                    activity.showToast(tr("Не удалось прочитать версии. Обновите каталог выбранного окружения и повторите.", "Could not read versions. Refresh the selected environment's catalogue and retry."), true)
                    return@Runnable
                }
                if (result!!.isEmpty()) {
                    activity.showToast(WorkspaceText.tr("В подключённых репозиториях нет доступных версий. Обновите каталог."), true)
                    return@Runnable
                }
                val labels = arrayOfNulls<String>(result.size)
                for (i in 0 until (labels.size)) {
                    labels[i] = result.get(i).label()
                }
                val title: String = entry.name + " · " + WorkspaceText.tr("Выбрать версию") + (if (result.size == 1) "\n" + tr("Других версий в подключённых репозиториях нет", "No other versions in enabled repositories") else "")
                WorkspaceDialogs.builder(activity).setTitle(title).setItems(labels, DialogInterface.OnClickListener { d, index -> confirm("version", entry, result.get(index)) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
            })
        })
    }

    private fun confirm(action: String, entry: PackageCatalog.Entry?) {
        confirm(action, entry, null)
    }

    private fun confirm(action: String, entry: PackageCatalog.Entry?, selectedVersion: EnvironmentPackageVersions.Version?) {
        if (closed || backend == null) {
            return
        }
        val command: String = if (selectedVersion != null) EnvironmentPackageVersions.operation(backend, selectedVersion) else if (action.equals("version")) PackageCatalog.versionOperation(entry) else EnvironmentPackages.operation(backend, action, if (entry == null) null else entry.name)
        val body: LinearLayout = column()
        body.setPadding(dp(16), 0, dp(16), dp(8))
        body.addView(text(command, 14))
        if (selectedVersion != null) {
            body.addView(text(selectedVersion.label(), 13))
            val warning: TextView = text(tr("Выбрана точная версия. Она может быть старее установленной; зависимости могут измениться. Менеджер покажет план в терминале и запросит подтверждение. Если версия исчезла из репозитория, установка завершится ошибкой.", "An exact version is selected. It may be older than the installed one and dependencies may change. The manager will show its plan in the terminal and ask for confirmation. Installation will fail if the version is no longer in the repository."), 13)
            warning.setTextColor(Pal.DANGER)
            body.addView(warning)
            if (backend === EnvironmentPackages.Kind.APK) {
                body.addView(text(tr("Alpine сохранит ограничение на эту версию в world. Последующие обновления пакета могут потребовать снять это ограничение.", "Alpine records this exact-version constraint in world. Future package upgrades may require removing the constraint."), 13))
            }
        }
        if (backend === EnvironmentPackages.Kind.PACMAN && !action.equals("remove")) {
            body.addView(text(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Pacman выполнит полное обновление окружения вместе с выбранным действием. Частичные обновления Arch не поддерживает; проверьте план перед подтверждением." else "Pacman performs a full environment upgrade with this action. Arch does not support partial upgrades; review the plan before confirming.", 13))
        }
        if (action.equals("version") && entry!!.downgrade) {
            val warning: TextView = text(WorkspaceText.tr("Выбрана более старая версия. Понижение может потребовать изменения зависимостей; проверьте план APT перед подтверждением."), 13)
            warning.setTextColor(Pal.DANGER)
            body.addView(warning)
        }
        body.addView(text(tr("В выбранной сессии должна быть свободная оболочка без незавершённого ввода. Если работает агент или выполняется команда, выберите новую сессию того же окружения. Пользователь сам проверяет, свободна ли оболочка.", "The selected session must have an idle shell with no unfinished input. If an agent or command is running, choose a new session in the same environment. Check that the shell is idle yourself."), 13))
        val choices = RadioGroup(activity)
        val selected = RadioButton(activity)
        selected.setId(View.generateViewId())
        selected.setText(WorkspaceText.tr("В выбранной сессии"))
        selected.setTextColor(Pal.TEXT)
        choices.addView(selected)
        val separate = RadioButton(activity)
        separate.setId(View.generateViewId())
        separate.setText(WorkspaceText.tr("В новой сессии того же окружения"))
        separate.setTextColor(Pal.TEXT)
        choices.addView(separate)
        choices.check(selected.getId())
        body.addView(choices)
        val scroll = ScrollView(activity)
        scroll.addView(body)
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Пакеты · ") + ProjectFiles.environmentLabel(files.profile)).setView(scroll).setPositiveButton(WorkspaceText.tr("Продолжить"), DialogInterface.OnClickListener { d, w ->
            if (closed) {
                return@OnClickListener
            }
            val current: Boolean = choices.getCheckedRadioButtonId() == selected.getId()
            val operation: WorkspaceStore.Session = PackageCatalog.operationProfile(files.profile, command)
            if (entry != null && !action.equals("refresh")) {
                operation.packageOperation = PackageOperationJournal.Intent(action, entry.name, if (selectedVersion == null) entry.version else selectedVersion.version)
            }
            close()
            launch.run(operation, current)
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        ++generation
        if (dialog != null && dialog!!.isShowing()) {
            dialog!!.dismiss()
        }
        try {
            files.close()
        } finally {
            worker.shutdownNow()
            entries = Collections.emptyList()
            val callback: Runnable? = onClosed
            onClosed = null
            if (callback != null) {
                callback.run()
            }
        }
    }

    companion object {
        @JvmStatic
        fun compactAction(context: Context, label: String?, run: Runnable): TextView {
            val view: TextView = WorkspaceDialogs.action(context, label, 0)
            view.setGravity(Gravity.CENTER)
            view.setSingleLine(true)
            view.setPadding(WorkspaceDialogs.dp(context, 6.0f), WorkspaceDialogs.dp(context, 8.0f), WorkspaceDialogs.dp(context, 6.0f), WorkspaceDialogs.dp(context, 8.0f))
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(view, 10, 15, 1, TypedValue.COMPLEX_UNIT_SP)
            view.setOnClickListener(View.OnClickListener { v -> run.run() })
            return view
        }
    }
}
