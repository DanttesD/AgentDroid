package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Environment
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.app.TermuxActivity
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Complete component overview; explicit guarded deletion, no implicit app reset.*/
class WorkspaceStorageCleanup {
    private val activity: TermuxActivity
    private val inUse: Predicate<String>
    private val restored: Runnable
    private val changed: Runnable
    private val histories: Runnable
    private val store: WorkspaceStore
    private val live: Predicate<WorkspaceStore.Session?>
    private var archiveRoot: String = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()

    constructor(activity: TermuxActivity, store: WorkspaceStore, inUse: Predicate<String>, live: Predicate<WorkspaceStore.Session?>, restored: Runnable, histories: Runnable, changed: Runnable) {
        this.activity = activity
        this.store = store
        this.inUse = inUse
        this.live = live
        this.restored = restored
        this.histories = histories
        this.changed = changed
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        val p: Int = WorkspaceDialogs.dp(activity, 16.0f)
        v.setPadding(p, 0, p, p)
        return v
    }

    private fun info(body: LinearLayout, value: String): TextView {
        val v = TextView(activity)
        v.setText(value)
        v.setTextColor(Pal.TEXT)
        v.setTextSize(13.0f)
        body.addView(v)
        return v
    }

    private fun action(body: LinearLayout, value: String, click: Runnable) {
        val v: TextView = WorkspaceDialogs.action(activity, value, 0)
        v.setOnClickListener(View.OnClickListener { w -> click.run() })
        body.addView(v)
    }

    private fun scroll(body: LinearLayout): ScrollView {
        val s = ScrollView(activity)
        s.addView(body)
        return s
    }

    private fun size(bytes: Long): String {
        return Formatter.formatFileSize(activity, Math.max(0L, bytes))
    }

    private fun request(operation: String): JSONObject {
        try {
            return WorkspaceBackupEngine.base(activity, operation).put("archiveRoot", archiveRoot).put("overview", operation.equals("cleanup-inventory"))
        } catch (error: Exception) {
            throw IllegalStateException(error)
        }
    }

    fun show() {
        scan()
    }

    private fun message(value: String) {
        if (!activity.isDestroyed()) {
            WorkspaceDialogs.builder(activity).blockOutsideDismiss().setTitle(label(activity)).setMessage(value).setPositiveButton("OK", null).show()
        }
    }

    private fun error(code: String): String {
        when (code) {
            "CLEANUP_ENVIRONMENT_BUSY" -> {
                return t("Окружение или менеджер пакетов используется. Закройте связанные сессии и повторите анализ.", "The environment or package manager is in use. Close related sessions and rescan.")
            }
            "CLEANUP_CONSENT_REQUIRED" -> {
                return t("Подтвердите, что данные могут быть потеряны навсегда.", "Acknowledge that data may be permanently lost.")
            }
            "CLEANUP_PLAN_EXPIRED", "CLEANUP_CHANGED_RESCAN" -> {
                return t("Данные изменились или анализ устарел. Выполните анализ заново.", "Data changed or the scan expired. Rescan before deleting.")
            }
            "CLEANUP_ENVIRONMENT_REMOVE_FAILED" -> {
                return t("Окружение не удалено полностью. Оно может использоваться другой сессией. Закройте её и обновите список; принудительная остановка не выполняется.", "Environment removal did not complete. Another session may be using it. Close it and refresh; no sessions are forcibly stopped.")
            }
            "CLEANUP_OVERLAPPING_SELECTION" -> {
                return t("Выбрано окружение целиком и данные внутри него. Снимите отдельные пункты внутри этой среды.", "A whole environment and data inside it are both selected. Deselect the nested items.")
            }
            "RESTORED_LOCKS_REQUIRED" -> {
                return t("Для очистки окружения нужен proot-distro с поддерживаемой блокировкой сессий.", "Environment cleanup requires proot-distro with supported session locking.")
            }
            "CLEANUP_TOO_MANY_FILES" -> {
                return t("Список слишком большой для этой операции. Выберите меньше пунктов или более узкую папку.", "The list is too large for this operation. Select fewer items or a narrower folder.")
            }
            "PYTHON_REQUIRED" -> {
                return t("Для анализа нужен Python в Termux: pkg install python.", "Scanning requires Python in Termux: pkg install python.")
            }
            "CANCELLED" -> {
                return t("Анализ отменён. Выбранные данные не удалялись.", "Scan cancelled. Selected data was not deleted.")
            }
            else -> {
                return t("Операция не завершена. Выполните анализ заново; часть уже удалённых файлов восстановить автоматически нельзя.", "Operation did not complete. Rescan; files already deleted cannot be restored automatically.")
            }
        }
    }

    private fun worker(request: JSONObject, deleting: Boolean, done: Consumer<JSONObject?>) {
        val engine = WorkspaceBackupEngine()
        val progress = WorkspaceBackupProgress(activity)
        val builder: AlertDialog.Builder = WorkspaceDialogs.builder(activity).blockOutsideDismiss().setTitle(label(activity)).setView(progress.view)
        if (!request.optBoolean("nativeRemoval")) {
            builder.setNegativeButton(t("Отмена", "Cancel"), DialogInterface.OnClickListener { d, w -> engine.cancel() })
        }
        val dialog: AlertDialog = builder.create()
        dialog.setCancelable(false)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> progress.close() })
        val started: Boolean = engine.start(activity, request, object : WorkspaceBackupEngine.Callback {
            override fun progress(event: JSONObject) {
                if (!dialog.isShowing() || progress.isStopping()) {
                    return
                }
                progress.update(t(if (deleting) "Удаление выбранных файлов" else "Анализ хранилища", if (deleting) "Deleting selected files" else "Scanning storage"), event)
                if (event.optLong("total") == 0L && event.optString("unit").equals("files")) {
                    progress.amount.setText(event.optLong("processed").toString() + t(" файлов", " files"))
                }
            }

            override fun done(result: JSONObject?) {
                progress.close()
                dialog.dismiss()
                done.accept(result)
            }

            override fun error(code: String) {
                progress.close()
                dialog.dismiss()
                message(if (deleting && code.equals("CANCELLED")) t("Очистка остановлена. Часть файлов могла быть уже удалена. Выполните анализ заново.", "Cleanup stopped. Some files may already have been deleted. Rescan storage.") else this@WorkspaceStorageCleanup.error(code))
            }
        })
        if (started) {
            dialog.show()
            progress.bindCancellation(dialog, Runnable(engine::cancel))
            progress.start()
        } else {
            progress.close()
            message(t("Дождитесь завершения другой операции бэкапа или очистки.", "Wait for the other backup or cleanup operation to finish."))
        }
    }

    private fun scan() {
        try {
            selection(JSONObject().put("groups", JSONArray()).put("overview", JSONArray()), true)
        } catch (impossible: JSONException) {
            throw IllegalStateException(impossible)
        }
    }

    private fun chooseFolder() {
        val picker = WorkspaceLocalFilePicker(activity)
        picker.chooseDirectory(t("Папка с резервными копиями", "Backup folder"), BiConsumer<WorkspaceStore.Session, String?> { profile, path ->
            try {
                val env: String = ProjectFiles.environment(profile)
                var physical: File
                if (env.startsWith("proot:")) {
                    val root: File? = WorkspaceEnvironments.root(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"), env.substring(6))
                    if (root == null) {
                        throw IOException()
                    }
                    physical = File(root, if (path!!.startsWith("/")) path.substring(1) else path).getCanonicalFile()
                    if (!physical.getPath().equals(root.getCanonicalPath()) && !physical.getPath().startsWith(root.getCanonicalPath() + "/")) {
                        throw IOException()
                    }
                } else {
                    physical = File(path).getCanonicalFile()
                }
                archiveRoot = physical.getPath()
                picker.close()
                scan()
            } catch (error: Exception) {
                picker.close()
                message(t("Выбранная папка недоступна.", "Selected folder is unavailable."))
            }
        })
    }

    private fun kind(group: JSONObject): String {
        when (group.optString("kind")) {
            "environment" -> {
                return t("Окружение целиком · ", "Whole environment · ") + WorkspaceEnvironments.label(group.optString("scope"))
            }
            "app-history" -> {
                return t("Истории оболочек · ", "Shell histories · ") + group.optString("name")
            }
            "home-data" -> {
                return "HOME · " + WorkspaceEnvironments.label((group.optString("scope") as java.lang.String).split("/")[0]) + " · " + group.optString("name")
            }
            "cache" -> {
                return t("Кэш пакетов · ", "Package cache · ") + WorkspaceEnvironments.label(group.optString("scope"))
            }
            "reports" -> {
                return t("Сохранённые отчёты диагностики", "Saved diagnostic reports")
            }
            "partial" -> {
                return t("Незавершённый бэкап · ", "Incomplete backup · ") + group.optString("name")
            }
            else -> {
                return t("Архив бэкапа · ", "Backup archive · ") + group.optString("name")
            }
        }
    }

    private fun selection(result: JSONObject?) {
        selection(result, false)
    }

    private fun selection(result: JSONObject?, scanOnOpen: Boolean) {
        val overview: Boolean = result!!.has("overview")
        val header = WorkspaceBackupScanHeader(activity, label(activity))
        header.ready = !scanOnOpen
        if (!overview) {
            header.refresh.setVisibility(View.GONE)
        }
        val body: LinearLayout = column()
        info(body, t("Выберите компоненты для удаления или откройте их состав. Ничего не отмечено заранее. Закройте сессии выбранной среды. PREFIX самого Termux доступен для просмотра; это runtime приложения.", "Select components to delete or open their contents. Nothing is preselected. Close sessions in the selected environment. Termux PREFIX is available for inspection; it is the app runtime."))
        if (overview) {
            overviewRows(body, result)
            info(body, t("Кэши, отчёты, архивы и окружения", "Caches, reports, archives and environments"))
            info(body, t("Папка с бэкапами: ", "Backup folder: ") + archiveRoot)
        }
        val groups: JSONArray? = result.optJSONArray("groups")
        val values: MutableList<JSONObject> = ArrayList<JSONObject>()
        val checks: MutableList<CheckBox> = ArrayList<CheckBox>()
        val total: TextView = info(body, t("Выбрано: ", "Selected: ") + size(0L))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).blockOutsideDismiss().setCustomTitle(header.view).setView(scroll(body)).setPositiveButton(t("Продолжить", "Continue"), null).setNegativeButton(t("Отмена", "Cancel"), null).create()
        val update = Runnable {
            var bytes: Long = 0L
            var count = 0
            var i = 0
            while (i < checks.size) {
                if (checks.get(i).isChecked()) {
                    bytes += values.get(i).optLong("bytes")
                    count++
                }
                i++
            }
            total.setText(t("Выбрано: ", "Selected: ") + size(bytes))
            if (dialog.isShowing()) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(header.ready && !header.running && count > 0)
            }
        }
        if (groups != null) {
            var i = 0
            while (i < groups.length()) {
                val group: JSONObject? = groups.optJSONObject(i)
                if (group == null) {
                    i++
                    continue
                }
                val environment: Boolean = group.optString("kind").equals("environment")
                val box = CheckBox(activity)
                box.setTextColor(Pal.TEXT)
                box.setText(kind(group) + "\n" + size(group.optLong("bytes")) + (if (environment) t(" · включая HOME, программы и настройки", " · including HOME, programs and settings") else " · " + group.optInt("count") + t(" файлов", " files")) + "\n" + group.optString("path") + (if (!group.optBoolean("selectable", true)) "\n" + t("Нужен proot-distro с блокировкой используемых сред", "Requires proot-distro with active-environment locking") else ""))
                box.setEnabled(group.optBoolean("selectable", true))
                body.addView(box)
                values.add(group)
                checks.add(box)
                box.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { v, checked -> update.run() })
                i++
            }
        }
        if (values.isEmpty() && !scanOnOpen) {
            info(body, t("Компонентов для удаления не найдено.", "No deletable components found."))
        }
        if (overview) {
            if (result.optBoolean("archiveUnavailable")) {
                action(body, t("Разрешить доступ к памяти и повторить", "Allow storage access and retry"), Runnable {
                    activity.requestWorkspaceStorageAccess(Runnable {
                        dialog.dismiss()
                        scan()
                    })
                })
            }
            action(body, t("Выбрать другую папку с бэкапами", "Choose another backup folder"), Runnable {
                dialog.dismiss()
                chooseFolder()
            })
        }
        dialog.setCancelable(false)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> header.close() })
        dialog.show()
        update.run()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
            val selected: MutableList<JSONObject> = ArrayList<JSONObject>()
            var i = 0
            while (i < checks.size) {
                if (checks.get(i).isChecked()) {
                    selected.add(values.get(i))
                }
                i++
            }
            confirmGroups(result, selected, dialog)
        })
        header.refresh.setOnClickListener(View.OnClickListener { v -> inlineScan(dialog, header, body) })
        if (scanOnOpen) {
            inlineScan(dialog, header, body)
        }
    }

    private fun inlineScan(dialog: AlertDialog, header: WorkspaceBackupScanHeader, body: LinearLayout) {
        if (header.closed || header.running) {
            return
        }
        val engine = WorkspaceBackupEngine()
        val enabled: MutableMap<View, Boolean> = IdentityHashMap<View, Boolean>()
        rememberEnabled(body, enabled)
        for (view in enabled.keys) {
            view.setEnabled(false)
        }
        val positive: Button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val wasEnabled: Boolean = positive.isEnabled()
        positive.setEnabled(false)
        header.start(engine, t("Сканирование…", "Scanning…"))
        val restore = Runnable {
            for (e in enabled.entries) {
                e.key.setEnabled(e.value)
            }
            positive.setEnabled(header.ready && wasEnabled)
        }
        val started: Boolean = engine.start(activity, request("cleanup-inventory"), object : WorkspaceBackupEngine.Callback {
            override fun progress(event: JSONObject) {
                if (!header.closed && dialog.isShowing()) {
                    // The shared header expects byte counts. For file counts keep only the path.
                    try {
                        val line = JSONObject(event.toString())
                        if (line.optString("unit").equals("files")) {
                            line.put("processed", 0)
                        }
                        header.progress(t("Сканирование", "Scanning"), line)
                    } catch (ignored: JSONException) {
                    }
                }
            }

            override fun done(result: JSONObject?) {
                if (header.closed || !dialog.isShowing()) {
                    return
                }
                header.ready = true
                header.finish(null)
                dialog.dismiss()
                selection(result)
            }

            override fun error(code: String) {
                if (header.closed || !dialog.isShowing()) {
                    return
                }
                header.finish(this@WorkspaceStorageCleanup.error(code))
                restore.run()
            }
        })
        if (!started) {
            header.finish(t("Другая операция ещё выполняется. Повторите позже.", "Another operation is running. Try again later."))
            restore.run()
        }
    }

    private fun rememberEnabled(view: View, enabled: MutableMap<View, Boolean>) {
        enabled.put(view, view.isEnabled())
        if (view is ViewGroup) {
            val group: ViewGroup = view as ViewGroup
            var i = 0
            while (i < group.getChildCount()) {
                rememberEnabled(group.getChildAt(i), enabled)
                i++
            }
        }
    }

    private fun confirmGroups(result: JSONObject?, selected: MutableList<JSONObject>, dialog: AlertDialog) {
        if (selected.isEmpty() || blocked(selected)) {
            return
        }
        val environments: MutableSet<String> = HashSet<String>()
        for (g in selected) {
            if (g.optString("kind").equals("environment")) {
                environments.add(g.optString("scope"))
            }
        }
        for (g in selected) {
            if (!g.optString("kind").equals("environment") && environments.contains((g.optString("scope") as java.lang.String).split("/")[0])) {
                message(error("CLEANUP_OVERLAPPING_SELECTION"))
                return
            }
        }
        val summary = StringBuilder()
        var bytes: Long = 0L
        for (g in selected) {
            summary.append("• ").append(kind(g)).append(" · ").append(size(g.optLong("bytes"))).append('\n')
            bytes += g.optLong("bytes")
        }
        summary.append('\n').append(t("Выбрано к удалению: ", "Selected for deletion: ")).append(size(bytes)).append('\n').append(t("Удаление без корзины. Выбранные окружения удаляются целиком, включая HOME, установленные программы, проекты и авторизацию. Пресеты запуска и архивы бэкапов вне выбранных папок сохраняются. Отдельные файлы HOME также могут содержать авторизацию. При ошибке возможен частичный результат.", "Deletion bypasses trash. Selected environments are removed in full, including HOME, installed programs, projects and authentication. Launch presets and backup archives outside selected folders are retained. Selected HOME files may also contain authentication. A failure can leave a partial result."))
        if (!environments.isEmpty()) {
            summary.append("\n\n").append(t("После начала штатного удаления окружений отмена недоступна. Дождитесь результата.", "Once native environment removal starts, cancellation is unavailable. Wait for the result."))
        } else {
            summary.append("\n").append(t("Отмена прекращает дальнейшее удаление, но не возвращает удалённое.", "Cancel stops further deletion but cannot restore removed files."))
        }
        StorageDeletionConsent.show(activity, label(activity), summary.toString(), Runnable {
            if (blocked(selected)) {
                return@Runnable
            }
            try {
                val ids = JSONArray()
                for (g in selected) {
                    ids.put(g.getString("id"))
                }
                val req: JSONObject = request("cleanup-execute").put("plan", result!!.getString("plan")).put("selected", ids).put("acknowledged", true).put("nativeRemoval", !environments.isEmpty())
                dialog.dismiss()
                worker(req, true, Consumer<JSONObject?> { r ->
                    BackupInventoryCache.SHARED.clear()
                    val removed: JSONArray? = r!!.optJSONArray("removedEnvironments")
                    var text: String = t("Удалено отдельных файлов: ", "Individual files deleted: ") + r.optInt("files") + "\n" + t("Объём отдельных файлов: ", "Individual file sizes: ") + size(r.optLong("bytes"))
                    if (removed != null && removed.length() > 0) {
                        text += "\n" + t("Удалены окружения: ", "Environments removed: ")
                        var i = 0
                        while (i < removed.length()) {
                            text += (if (i == 0) "" else ", ") + WorkspaceEnvironments.label(removed.optString(i))
                            i++
                        }
                        changed.run()
                    }
                    val errors: JSONArray? = r.optJSONArray("errors")
                    if (errors != null && errors.length() > 0) {
                        text += "\n\n" + t("Часть данных не удалена:\n", "Some data was not deleted:\n")
                        var i = 0
                        while (i < errors.length()) {
                            text += error(errors.optJSONObject(i).optString("code")) + "\n"
                            i++
                        }
                    }
                    message(text)
                })
            } catch (invalid: Exception) {
                message(error("CLEANUP_PLAN_INVALID"))
            }
        })
    }

    private fun overviewRows(body: LinearLayout, result: JSONObject?) {
        info(body, t("Данные приложения", "App data"))
        libraryRow(body, "commands", t("Команды", "Commands"))
        libraryRow(body, "notes", t("Заметки", "Notes"))
        libraryRow(body, "sessions", t("Пресеты запуска", "Launch presets"))
        libraryRow(body, "recentCommands", t("Недавние команды", "Recent commands"))
        action(body, t("Истории чатов Codex · просмотр и удаление", "Codex chat histories · view and delete"), histories)
        val sections: JSONArray? = result!!.optJSONArray("overview")
        if (sections == null) {
            return
        }
        info(body, t("Termux и Linux-окружения", "Termux and Linux environments"))
        var i = 0
        while (i < sections.length()) {
            val section: JSONObject? = sections.optJSONObject(i)
            if (section == null) {
                i++
                continue
            }
            val scope: String = section.optString("scope")
            val name: String = WorkspaceEnvironments.label((scope as java.lang.String).split("/")[0])
            val history: Boolean = section.optString("kind").equals("history")
            val home: Boolean = section.optString("kind").equals("home")
            val title: String = if (history) t("Истории оболочек приложения", "App shell histories") + " · " + size(section.optLong("bytes")) else name + " · " + (if (home) if (scope.endsWith("/home")) "/home" else "HOME" else if (scope.equals("termux")) "PREFIX" else "/ · " + t("Система", "System")) + " · " + size(section.optLong("bytes"))
            action(body, title, Runnable { component(section, title, home || history, result) })
            i++
        }
    }

    private fun component(section: JSONObject, title: String, removable: Boolean, inventory: JSONObject?) {
        val body: LinearLayout = column()
        info(body, section.optString("path"))
        if (section.optString("kind").equals("history")) {
            info(body, t("Это сохранённый вывод оболочек приложения. Файлы выбираются по ID сессии; собственные разговоры CLI-агентов находятся в HOME и здесь не удаляются.", "These are app-side shell records. Folders are identified by session ID; native CLI conversations are stored in HOME and are not deleted here."))
        }
        info(body, t(if (removable) "Выберите папки и файлы. Настройки, авторизация и проекты внутри выбранного будут удалены. Ссылки удаляются сами, без удаления их целей. Размер приблизительный." else "Состав системы доступен для просмотра. Целиком удалять PREFIX или системные файлы из работающего приложения нельзя: это повредит запуск и пакетный менеджер. Пакеты удаляйте через «Установка пакетов». Отдельное окружение можно удалить целиком кнопкой ниже после закрытия его сессий.", if (removable) "Select folders and files. Settings, authentication and projects inside the selection will be deleted. Links themselves are removed, not their targets. Sizes are approximate." else "System contents are available for inspection. Removing PREFIX or system files from the running app would break startup and the package manager. Remove packages through Package installation. An entire environment can be removed with the button below after closing its sessions."))
        val entries: JSONArray? = section.optJSONArray("entries")
        val values: MutableList<JSONObject> = ArrayList<JSONObject>()
        val checks: MutableList<CheckBox> = ArrayList<CheckBox>()
        if (entries != null) {
            var i = 0
            while (i < entries.length()) {
                val e: JSONObject? = entries.optJSONObject(i)
                if (e == null) {
                    i++
                    continue
                }
                val text: String = e.optString("name") + " · " + size(e.optLong("bytes")) + " · " + e.optLong("count") + t(" файлов", " files") + (if (e.optLong("unreadable") > 0) t(" · часть недоступна", " · partially unreadable") else "")
                if (!removable) {
                    info(body, text)
                    i++
                    continue
                }
                val check = CheckBox(activity)
                check.setTextColor(Pal.TEXT)
                check.setText(text)
                check.setEnabled(e.optJSONArray("identity") != null && e.optLong("unreadable") == 0L)
                body.addView(check)
                values.add(e)
                checks.add(check)
                i++
            }
        }
        if (entries == null || entries.length() == 0) {
            info(body, t("Пусто", "Empty"))
        }
        val builder: AlertDialog.Builder = WorkspaceDialogs.builder(activity).blockOutsideDismiss().setTitle(title).setView(scroll(body)).setNegativeButton(t("Отмена", "Cancel"), null)
        if (removable) {
            builder.setPositiveButton(t("Продолжить", "Continue"), null)
        }
        val dialog: AlertDialog = builder.create()
        dialog.setCancelable(false)
        dialog.show()
        if (!removable) {
            val groups: JSONArray? = inventory!!.optJSONArray("groups")
            if (groups != null) {
                var i = 0
                while (i < groups.length()) {
                    val group: JSONObject? = groups.optJSONObject(i)
                    if (group != null && group.optString("kind").equals("environment") && group.optString("scope").equals(section.optString("scope"))) {
                        if (group.optBoolean("selectable", true)) {
                            action(body, t("Удалить это окружение целиком", "Delete this entire environment"), Runnable { confirmGroups(inventory, Collections.singletonList(group), dialog) })
                        } else {
                            info(body, error("RESTORED_LOCKS_REQUIRED"))
                        }
                        break
                    }
                    i++
                }
            }
            return
        }
        val button: Button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        button.setEnabled(false)
        for (c in checks) {
            c.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { v, checked ->
                var selected = false
                for (item in checks) {
                    selected = selected or item.isChecked()
                }
                button.setEnabled(selected)
            })
        }
        button.setOnClickListener(View.OnClickListener { v ->
            val scope: String = section.optString("scope")
            var i = 0
            while (i < checks.size) {
                if (checks.get(i).isChecked() && scope.equals("app-history") && inUse.test("history:" + values.get(i).optString("name"))) {
                    message(t("Сначала закройте выбранную сессию.", "Close the selected session first."))
                    return@OnClickListener
                }
                i++
            }
            if (!scope.equals("app-history") && inUse.test((scope as java.lang.String).split("/")[0])) {
                message(t("Сначала закройте связанные сессии.", "Close related sessions first."))
                return@OnClickListener
            }
            val chosen = JSONArray()
            run {
                var i = 0
                while (i < checks.size) {
                    if (checks.get(i).isChecked()) {
                        chosen.put(values.get(i))
                    }
                    i++
                }
            }
            if (chosen.length() == 0) {
                return@OnClickListener
            }
            try {
                val req: JSONObject = request("cleanup-prepare-home").put("scope", scope).put("entries", chosen)
                dialog.dismiss()
                worker(req, false, Consumer<JSONObject?>(this::selection))
            } catch (invalid: JSONException) {
                message(error("CLEANUP_PLAN_INVALID"))
            }
        })
    }

    private fun libraryRow(body: LinearLayout, key: String, title: String) {
        try {
            val all: JSONArray = store.encode().getJSONArray(key)
            var count = 0
            var bytes: Long = 0L
            var i = 0
            while (i < all.length()) {
                val e: JSONObject? = all.optJSONObject(i)
                if (key.equals("sessions") && (e == null || !e.optBoolean("saved") || WorkspaceStore.BASE_TERMINAL_ID.equals(e.optString("id")))) {
                    i++
                    continue
                }
                count++
                bytes += (all.get(i).toString() as java.lang.String).getBytes(StandardCharsets.UTF_8).size
                i++
            }
            action(body, title + " · " + count + " · " + size(bytes), Runnable { library(key, title) })
        } catch (invalid: JSONException) {
            info(body, title + " · " + t("Недоступно", "Unavailable"))
        }
    }

    private fun library(key: String, title: String) {
        try {
            val all: JSONArray = store.encode().getJSONArray(key)
            val expected: String = all.toString()
            val body: LinearLayout = column()
            val checks: MutableList<CheckBox> = ArrayList<CheckBox>()
            val ids: MutableList<String> = ArrayList<String>()
            info(body, t("Выберите записи. Будут удалены только записи библиотеки; файлы проектов и собственные чаты CLI сохраняются.", "Select entries. Only library entries are removed; project files and native CLI conversations are retained."))
            var i = 0
            while (i < all.length()) {
                val e: JSONObject? = all.optJSONObject(i)
                val id: String = if (key.equals("recentCommands")) Integer.toString(i) else if (e == null) "" else e.optString("id")
                if (key.equals("sessions") && (e == null || !e.optBoolean("saved") || WorkspaceStore.BASE_TERMINAL_ID.equals(id))) {
                    i++
                    continue
                }
                val check = CheckBox(activity)
                check.setTextColor(Pal.TEXT)
                var preview: String = if (e == null) all.optString(i) else e.optString("name")
                if (e != null) {
                    val content: String = e.optString("command", e.optString("text"))
                    if (!content.isEmpty()) {
                        preview += (if (preview.isEmpty()) "" else "\n") + (if (content.length > 512) content.substring(0, 512) + "…" else content)
                    }
                }
                check.setText(preview)
                body.addView(check)
                checks.add(check)
                ids.add(id)
                i++
            }
            val dialog: AlertDialog = WorkspaceDialogs.builder(activity).blockOutsideDismiss().setTitle(title).setView(scroll(body)).setNegativeButton(t("Отмена", "Cancel"), null).setPositiveButton(t("Продолжить", "Continue"), null).create()
            dialog.setCancelable(false)
            dialog.show()
            val button: Button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            button.setEnabled(false)
            for (c in checks) {
                c.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { v, b ->
                    var any = false
                    for (item in checks) {
                        any = any or item.isChecked()
                    }
                    button.setEnabled(any)
                })
            }
            button.setOnClickListener(View.OnClickListener { v ->
                val selected: MutableSet<String> = HashSet<String>()
                var i = 0
                while (i < checks.size) {
                    if (checks.get(i).isChecked()) {
                        selected.add(ids.get(i))
                    }
                    i++
                }
                if (selected.isEmpty()) {
                    return@OnClickListener
                }
                val remove = Runnable {
                    if (key.equals("sessions")) {
                        for (p in store.sessions) {
                            if (selected.contains(p!!.id) && live.test(p)) {
                                message(t("Сначала закройте выбранные сессии.", "Close the selected sessions first."))
                                return@Runnable
                            }
                        }
                    }
                    if (!clearLibrarySelection(store, key, expected, selected, Runnable {
                        if (activity.isDestroyed()) {
                            return@Runnable
                        }
                        changed.run()
                        message(t("Выбранные записи удалены.", "Selected entries deleted."))
                    })) {
                        message(t("Список изменился или недоступен. Откройте его заново.", "The list changed or is unavailable. Reopen it."))
                        return@Runnable
                    }
                    dialog.dismiss()
                }
                StorageDeletionConsent.show(activity, title, t("Будут удалены выбранные записи: ", "Selected entries to delete: ") + selected.size, remove)
            })
        } catch (invalid: JSONException) {
            message(error("CLEANUP_PLAN_INVALID"))
        }
    }

    private fun blocked(groups: MutableList<JSONObject>): Boolean {
        for (g in groups) {
            val busy: JSONArray? = g.optJSONArray("busyScopes")
            if (busy != null) {
                var i = 0
                while (i < busy.length()) {
                    if (inUse.test(busy.optString(i))) {
                        message(t("Сначала закройте выбранные сессии.", "Close the selected sessions first."))
                        return true
                    }
                    i++
                }
            }
            val scope: String = (g.optString("scope") as java.lang.String).split("/")[0]
            if (!scope.isEmpty() && inUse.test(scope)) {
                message(t("Сначала закройте сессии окружения: ", "Close sessions in this environment first: ") + WorkspaceEnvironments.label(scope))
                return true
            }
        }
        return false
    }

    companion object {
        @JvmStatic
        fun label(a: Activity): String {
            return if (WorkspaceLanguage.code(a)!!.equals("ru")) "Очистить хранилище" else "Clean up storage"
        }

        @JvmStatic
        fun scope(profile: WorkspaceStore.Session?): String {
            val env: String = ProjectFiles.environment(profile)
            return if (env.startsWith("proot:")) env.substring(6) else if (env.equals("ubuntu")) "ubuntu" else if (env.equals("termux")) "termux" else ""
        }

        @JvmStatic
        fun scopeInUse(store: WorkspaceStore, scope: String, runningHandles: MutableSet<String>): Boolean {
            if (scope.isEmpty()) {
                return false
            }
            for (profile in store.sessions) {
                if (runningHandles.contains(profile!!.handle) && scope.equals(scope(profile)) && !WorkspaceStore.isBase(profile)) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun idleBaseShell(stat: String): Boolean {
            try {
                val end: Int = stat.lastIndexOf(')')
                val fields: Array<String> = ((stat.substring(end + 1) as java.lang.String).trim() as java.lang.String).split("\\s+")
                val pgrp: Int = Integer.parseInt(fields[2])
                val foreground: Int = Integer.parseInt(fields[5])
                return end > 0 && pgrp > 0 && pgrp == foreground
            } catch (invalid: Exception) {
                return false
            }
        }

        @JvmStatic
        fun clearLibrarySelection(store: WorkspaceStore, key: String, expected: String, selected: MutableSet<String>): Boolean {
            return clearLibrarySelection(store, key, expected, selected, null)
        }

        @JvmStatic
        fun clearLibrarySelection(store: WorkspaceStore, key: String, expected: String, selected: MutableSet<String>, completed: Runnable?): Boolean {
            if (!store.isWritable() || selected.isEmpty()) {
                return false
            }
            try {
                if (!store.encode().getJSONArray(key).toString().equals(expected)) {
                    return false
                }
            } catch (invalid: JSONException) {
                return false
            }
            var found = false
            if (key.equals("commands")) {
                for (c in store.commands) {
                    found = found or selected.contains(c.id)
                }
            }
            if (key.equals("notes")) {
                for (n in store.notes) {
                    found = found or selected.contains(n.id)
                }
            }
            if (key.equals("sessions")) {
                for (p in store.sessions) {
                    found = found or (p!!.saved && !WorkspaceStore.isBase(p) && selected.contains(p.id))
                }
            }
            if (key.equals("recentCommands")) {
                var i = 0
                while (i < store.recentCommands.size) {
                    found = found or selected.contains(Integer.toString(i))
                    i++
                }
            }
            if (!found) {
                return false
            }
            when (key) {
                "commands" -> {
                    store.commands.removeIf(Predicate<WorkspaceStore.Command> { e -> selected.contains(e.id) })
                }
                "notes" -> {
                    store.notes.removeIf(Predicate<WorkspaceStore.Note> { e -> selected.contains(e.id) })
                }
                "sessions" -> {
                    store.sessions.removeIf(Predicate<WorkspaceStore.Session?> { e -> e!!.saved && !WorkspaceStore.isBase(e) && selected.contains(e.id) })
                }
                "recentCommands" -> {
                    for (i in store.recentCommands.size - 1 downTo 0) {
                        if (selected.contains(Integer.toString(i))) {
                            store.recentCommands.removeAt(i)
                        }
                    }
                }
                else -> {
                    return false
                }
            }
            store.save(completed)
            return true
        }
    }
}
