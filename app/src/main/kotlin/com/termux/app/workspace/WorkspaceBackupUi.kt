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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.app.TermuxActivity
import com.termux.terminal.TerminalSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Collections
import java.util.Comparator
import java.util.IdentityHashMap
import java.util.Objects
import java.util.UUID
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Collectors
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Deliberate backup/restore flows. No operation runs merely from opening settings.*/
class WorkspaceBackupUi {
    private var compressionLevel: Int = 3
    private var compressionThreads: Int = 1
    private var compressionEngine: String = "gzip"
    private var installingPigz: Boolean = false
    private val activity: TermuxActivity
    private val store: WorkspaceStore
    private val changed: Runnable
    private val packageInstall: Consumer<String>

    private constructor(a: TermuxActivity, s: WorkspaceStore, r: Runnable, install: Consumer<String>) {
        activity = a
        store = s
        changed = r
        packageInstall = install
    }

    private fun t(ru: String, en: String): String {
        return text(activity, ru, en)
    }

    private fun backupCreated(result: JSONObject?) {
        message(t("Бэкап создан и проверен", "Backup created and verified") + "\n" + result!!.optString("path"))
    }

    private fun message(text: String) {
        if (!activity.isDestroyed()) {
            backupBuilder(activity).setTitle(label(activity)).setMessage(text).setPositiveButton("OK", null).show()
        }
    }

    private fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        val d: Int = WorkspaceDialogs.dp(activity, 16.0f)
        v.setPadding(d, d, d, d)
        return v
    }

    private fun info(form: LinearLayout, value: String?) {
        val v = TextView(activity)
        v.setText(value)
        v.setTextColor(Pal.TEXT)
        v.setTextSize(14.0f)
        v.setPadding(0, 8, 0, 12)
        form.addView(v)
    }

    private fun check(form: LinearLayout, value: String, selected: Boolean): CheckBox {
        val b = CheckBox(activity)
        b.setText(value)
        b.setTextColor(Pal.TEXT)
        b.setChecked(selected)
        form.addView(b)
        return b
    }

    private fun field(form: LinearLayout, value: String, hint: String): EditText {
        val e = EditText(activity)
        e.setSingleLine()
        e.setText(value)
        e.setHint(hint)
        e.setTextColor(Pal.TEXT)
        e.setSelectAllOnFocus(true)
        form.addView(e)
        return e
    }

    private fun action(form: LinearLayout, title: String, click: Runnable) {
        val b: TextView = WorkspaceDialogs.action(activity, title, 0)
        b.setOnClickListener(View.OnClickListener { v -> click.run() })
        val p = LinearLayout.LayoutParams(-1, -2)
        p.bottomMargin = WorkspaceDialogs.dp(activity, 8.0f)
        form.addView(b, p)
    }

    private fun scroll(form: LinearLayout): ScrollView {
        val s = ScrollView(activity)
        s.setFillViewport(false)
        s.addView(form)
        return s
    }

    private fun compressionName(): String {
        return compressionEngine + " · " + compressionThreads + t(if (compressionThreads == 1) " поток" else " потока", if (compressionThreads == 1) " thread" else " threads")
    }

    private fun levelName(): String {
        return if (compressionLevel == 1) t("Быстрое", "Fast") else if (compressionLevel == 9) t("Максимальное", "Maximum") else t("Сбалансированное", "Balanced")
    }

    @Throws(JSONException::class)
    private fun compressedRequest(request: JSONObject): JSONObject {
        return request.put("compressionLevel", compressionLevel).put("compressionEngine", compressionEngine).put("compressionThreads", compressionThreads)
    }

    private fun pigzAvailable(): Boolean {
        return File(WorkspaceBackupEngine.prefix(), "bin/pigz").canExecute()
    }

    private fun compressionControl(form: LinearLayout) {
        info(form, t("Способ сжатия · .tar.gz", "Compression method · .tar.gz"))
        val control: TextView = WorkspaceDialogs.action(activity, compressionName(), 0)
        form.addView(control)
        control.setOnClickListener(View.OnClickListener { v ->
            backupBuilder(activity).setTitle(t("Способ сжатия", "Compression method")).setItems(arrayOf<String>(t("gzip — 1 поток", "gzip — 1 thread"), t("pigz — 2 потока", "pigz — 2 threads"), t("pigz — 4 потока", "pigz — 4 threads")), DialogInterface.OnClickListener { d, w ->
                if (w == 0) {
                    compressionEngine = "gzip"
                    compressionThreads = 1
                    control.setText(compressionName())
                } else if (pigzAvailable()) {
                    compressionEngine = "pigz"
                    compressionThreads = if (w == 1) 2 else 4
                    control.setText(compressionName())
                } else {
                    offerPigz(control, if (w == 1) 2 else 4)
                }
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        info(form, t("Уровень сжатия", "Compression level"))
        val level: TextView = WorkspaceDialogs.action(activity, levelName(), 0)
        form.addView(level)
        level.setOnClickListener(View.OnClickListener { v ->
            backupBuilder(activity).setTitle(t("Уровень сжатия", "Compression level")).setItems(arrayOf<String>(t("Быстрое · уровень 1, архив больше", "Fast · level 1, larger archive"), t("Сбалансированное · уровень 3", "Balanced · level 3"), t("Максимальное · уровень 9, медленнее", "Maximum · level 9, slower")), DialogInterface.OnClickListener { d, w ->
                compressionLevel = intArrayOf(1, 3, 9)[w]
                level.setText(levelName())
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        info(form, t("Все варианты создают совместимый .tar.gz. Уровень 3 выбран по умолчанию. Ускорение зависит от накопителя и состава файлов. Полная Ubuntu на старом proot-distro использует штатное сжатие независимо от выбора.", "All options create compatible .tar.gz archives. Level 3 is the default. Speed depends on storage and file mix. Full Ubuntu backups on legacy proot-distro use native compression regardless of this selection."))
    }

    private fun offerPigz(control: TextView, threads: Int) {
        if (installingPigz) {
            return
        }
        backupBuilder(activity).setTitle(t("Установить pigz?", "Install pigz?")).setMessage(t("Пакет pigz отсутствует. Установить его в общую среду Termux в фоне? Нужен интернет; APT может добавить зависимости. После успешной установки будет выбран pigz. Сжатие gzip доступно без установки.", "pigz is missing. Install it into the shared Termux environment in the background? Internet is required; APT may add dependencies. Successful installation selects pigz. gzip works without installation.")).setPositiveButton(t("Установить", "Install"), DialogInterface.OnClickListener { d, w ->
            val engine = WorkspaceBackupEngine()
            installingPigz = true
            control.setEnabled(false)
            control.setText(t("Установка pigz…", "Installing pigz…"))
            val started: Boolean = engine.start(activity, request("install-pigz"), object : WorkspaceBackupEngine.Callback {
                override fun progress(event: JSONObject) {
                }

                override fun done(result: JSONObject?) {
                    installingPigz = false
                    control.setEnabled(true)
                    if (pigzAvailable()) {
                        compressionEngine = "pigz"
                        compressionThreads = threads
                        control.setText(compressionName())
                    } else {
                        control.setText(compressionName())
                        message(errorText("PIGZ_INSTALL_FAILED"))
                    }
                }

                override fun error(code: String) {
                    installingPigz = false
                    control.setEnabled(true)
                    control.setText(compressionName())
                    message(errorText(code))
                }
            })
            if (!started) {
                installingPigz = false
                control.setEnabled(true)
                control.setText(compressionName())
                message(t("Другая операция ещё выполняется. Дождитесь её завершения и повторите установку.", "Another operation is running. Wait for it to finish and retry installation."))
            }
        }).setNegativeButton(t("Не сейчас", "Not now"), null).show()
    }

    private fun menu() {
        val form: LinearLayout = column()
        action(form, t("Создать резервную копию", "Create backup"), Runnable(this::inventory))
        action(form, t("Проверить резервную копию", "Verify backup"), Runnable { chooseFile(false) })
        action(form, t("Восстановить из файла", "Restore from file"), Runnable { chooseFile(true) })
        action(form, t("Импорт/Экспорт Alias", "Import/export aliases"), Runnable(this::aliasTransferMenu))
        backupBuilder(activity).setTitle(label(activity)).setView(scroll(form)).setNegativeButton(WorkspaceText.tr("Закрыть"), null).show()
    }

    private fun aliasTransferMenu() {
        val form: LinearLayout = column()
        action(form, t("Импорт из shell-файла", "Import from shell file"), Runnable { aliasSourceFile(true) })
        action(form, t("Импорт из JSON/бэкапа", "Import from JSON/backup"), Runnable { aliasSourceFile(false) })
        action(form, t("Экспорт в shell-файл", "Export to shell file"), Runnable { aliasExport(true) })
        action(form, t("Экспорт в JSON", "Export to JSON"), Runnable { aliasExport(false) })
        backupBuilder(activity).setTitle(t("Импорт/Экспорт Alias", "Import/export aliases")).setView(scroll(form)).setNegativeButton(WorkspaceText.tr("Назад"), null).show()
    }

    private fun aliasExport(shell: Boolean) {
        val copy: MutableList<WorkspaceStore.Alias> = ArrayList<WorkspaceStore.Alias>()
        for (a in store.aliases) {
            copy.add(a.copy())
        }
        if (!shell) {
            aliasPicker(copy, null, false)
            return
        }
        val environments: MutableList<String> = ArrayList<String>()
        for (environment in WorkspaceAliases.ENVIRONMENTS) {
            if (copy.stream().anyMatch(Predicate<WorkspaceStore.Alias> { a -> a.environment.equals(environment) })) {
                environments.add(environment)
            }
        }
        if (environments.isEmpty()) {
            message(t("Сохранённых Alias нет.", "No saved aliases found."))
            return
        }
        val labels = arrayOfNulls<String>(environments.size)
        for (i in 0 until (labels.size)) {
            labels[i] = WorkspaceAliases.label(environments.get(i))
        }
        backupBuilder(activity).setTitle(t("Окружение для экспорта", "Export environment")).setItems(labels, DialogInterface.OnClickListener { d, w ->
            val selected: MutableList<WorkspaceStore.Alias> = ArrayList<WorkspaceStore.Alias>()
            for (a in copy) {
                if (a.environment.equals(environments.get(w))) {
                    selected.add(a)
                }
            }
            aliasPicker(selected, null, true)
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun aliasSourceFile(shell: Boolean) {
        val form: LinearLayout = column()
        info(form, if (shell) t("Выберите .bashrc, .bash_aliases, .zshrc или другой shell-файл. Будут прочитаны только простые определения alias без выполнения файла. Динамические определения пропускаются. Затем выберите нужные Alias для импорта.", "Choose .bashrc, .bash_aliases, .zshrc or another shell file. Only literal alias definitions are read, without executing the file. Dynamic definitions are skipped. Then select aliases to import.") else t("Выберите JSON экспорта Alias, workspace-v1.json или manifest.json резервной копии. Файлы окружений не сканируются, команды не выполняются.", "Choose an alias-export JSON, workspace-v1.json or backup manifest.json. Environment files are not scanned and commands are not executed."))
        val path: EditText = field(form, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(), t("Путь к файлу", "File path"))
        action(form, t("Выбрать файл", "Choose file"), Runnable { browse(File(path.getText().toString()), false, Consumer<String>(path::setText)) })
        val environment: Array<String> = arrayOf<String>("termux")
        if (shell) {
            info(form, t("Окружение для импорта", "Import environment"))
            val target: TextView = WorkspaceDialogs.action(activity, "Termux ▾", 0)
            form.addView(target)
            target.setOnClickListener(View.OnClickListener { v ->
                val choices: MutableList<WorkspaceEnvironments.Choice> = WorkspaceAliases.installed(WorkspaceEnvironments.choices())
                val labels = arrayOfNulls<String>(choices.size)
                var selected = 0
                for (i in 0 until (labels.size)) {
                    val c: WorkspaceEnvironments.Choice = choices.get(i)
                    labels[i] = c.label
                    if ((if (c.alias.isEmpty()) "termux" else c.alias).equals(environment[0])) {
                        selected = i
                    }
                }
                backupBuilder(activity).setTitle(t("Окружение", "Environment")).setSingleChoiceItems(labels, selected, DialogInterface.OnClickListener { d, w ->
                    val c: WorkspaceEnvironments.Choice = choices.get(w)
                    environment[0] = if (c.alias.isEmpty()) "termux" else c.alias
                    target.setText(c.label + " ▾")
                    d.dismiss()
                }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
            })
        }
        backupBuilder(activity).setTitle(if (shell) t("Импорт из shell-файла", "Import from shell file") else t("Импорт из JSON/бэкапа", "Import from JSON/backup")).setView(scroll(form)).setPositiveButton(t("Проверить", "Verify"), DialogInterface.OnClickListener { d, w ->
            try {
                val req: JSONObject = request(if (shell) "alias-inspect-shell" else "inspect").put("path", (path.getText().toString() as java.lang.String).trim())
                if (shell) {
                    req.put("environment", environment[0])
                }
                run(req, Consumer<JSONObject?>(this::aliasImportReport))
            } catch (e: Exception) {
                message(errorText("INVALID_PATH"))
            }
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun aliasImportReport(report: JSONObject?) {
        try {
            aliasPicker(WorkspaceAliasImport.read(activity, report!!.optJSONObject("workspace")), report)
        } catch (e: Exception) {
            message(t("Не удалось прочитать Alias. Нужен совместимый экспорт или бэкап приложения.", "Could not read aliases. Choose a compatible export or app backup."))
        }
    }

    private fun aliasPicker(aliases: MutableList<WorkspaceStore.Alias>, source: JSONObject?) {
        aliasPicker(aliases, source, false)
    }

    private fun aliasPicker(aliases: MutableList<WorkspaceStore.Alias>, source: JSONObject?, shell: Boolean) {
        val importing: Boolean = source != null
        if (aliases.isEmpty()) {
            message(if (importing && source!!.optBoolean("aliasShell")) t("Простые определения Alias не найдены. Пропущено неподдерживаемых определений: ", "No literal alias definitions found. Unsupported definitions skipped: ") + source.optInt("skipped") else t("Сохранённых Alias нет.", "No saved aliases found."))
            return
        }
        val form: LinearLayout = column()
        info(form, if (importing) t("Выберите Alias для импорта в их окружения. Отсутствующее окружение сначала установите. Существующие записи с тем же именем и окружением заменяются только после подтверждения. Импортируйте только доверенные команды.", "Select aliases to import into their environments. Install missing environments first. Matching names in the same environment require replacement confirmation. Import only trusted commands.") else if (shell) t("Выберите Alias для экспорта в shell-файл. Команды могут содержать личные данные — проверьте перед передачей файла.", "Select aliases to export to a shell file. Commands may contain private data; review them before sharing.") else t("Выберите Alias для экспорта. Сохраняются только выбранные определения, окружения, категории и избранное. Команды могут содержать личные данные — проверьте перед передачей файла.", "Select aliases to export. Only selected definitions, environments, categories and favorites are saved. Commands may contain private data; review them before sharing."))
        if (importing && source!!.optBoolean("aliasShell")) {
            info(form, t("Пропущено неподдерживаемых определений: ", "Unsupported definitions skipped: ") + source.optInt("skipped"))
        }
        if (shell) {
            info(form, t("Shell-файл содержит только определения одного окружения. Его можно подключить из .bashrc или .zshrc. Категории и избранное сохраняются только в JSON.", "A shell file contains definitions for one environment. It can be sourced from .bashrc or .zshrc. Categories and favorites are retained only in JSON."))
        }
        val installed: MutableSet<String> = HashSet<String>()
        if (importing) {
            for (c in WorkspaceAliases.installed(WorkspaceEnvironments.choices())) {
                installed.add(if (c.alias.isEmpty()) "termux" else c.alias)
            }
        }
        val checks: MutableList<CheckBox> = ArrayList<CheckBox>()
        for (a in aliases) {
            val available: Boolean = !importing || installed.contains(a.environment)
            val box: CheckBox = check(form, a.name + " · " + WorkspaceAliases.label(a.environment) + " · " + (if ((a.category!! as java.lang.String).trim().isEmpty()) WorkspaceText.tr("Общее") else a.category) + (if (available) "" else t(" · окружение не установлено", " · environment not installed")), !importing)
            box.setEnabled(available)
            checks.add(box)
            val preview: TextView = WorkspaceDialogs.action(activity, t("Предпросмотр: ", "Preview: ") + a.name, 0)
            form.addView(preview)
            preview.setOnClickListener(View.OnClickListener { v ->
                val detail: LinearLayout = column()
                info(detail, WorkspaceAliases.label(a.environment))
                info(detail, a.command)
                backupBuilder(activity).setTitle(a.name).setView(scroll(detail)).setPositiveButton(WorkspaceText.tr("Закрыть"), null).show()
            })
        }
        val destination: EditText? = if (importing) null else field(form, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(), t("Каталог экспорта", "Export directory"))
        if (!importing) {
            action(form, t("Выбрать каталог", "Choose directory"), Runnable { browse(File(destination!!.getText().toString()), true, Consumer<String>(destination::setText)) })
        }
        val dialog: AlertDialog = backupBuilder(activity).setTitle(if (importing) t("Импорт Alias", "Import aliases") else t("Экспорт Alias", "Export aliases")).setView(scroll(form)).setPositiveButton(if (importing) t("Импортировать", "Import") else t("Экспортировать", "Export"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                try {
                    val selected: MutableList<WorkspaceStore.Alias> = ArrayList<WorkspaceStore.Alias>()
                    val ids: MutableSet<String?> = HashSet<String?>()
                    var i = 0
                    while (i < aliases.size) {
                        if (checks.get(i).isEnabled() && checks.get(i).isChecked()) {
                            selected.add(aliases.get(i))
                            ids.add(aliases.get(i).id)
                        }
                        i++
                    }
                    if (selected.isEmpty()) {
                        message(t("Выберите хотя бы один Alias.", "Select at least one alias."))
                        return@OnClickListener
                    }
                    if (!importing) {
                        val req: JSONObject = request("alias-export").put("aliasFormat", if (shell) "shell" else "json").put("workspace", WorkspaceAliasImport.export(selected)).put("destination", (destination!!.getText().toString() as java.lang.String).trim())
                        run(req, Consumer<JSONObject?> { r ->
                            dialog.dismiss()
                            message(t("Alias экспортированы: ", "Aliases exported: ") + r!!.optInt("count") + "\n" + r.optString("path"))
                        })
                        return@OnClickListener
                    }
                    val plan: WorkspaceAliasImport.Plan = WorkspaceAliasImport.plan(aliases, ids, store.aliases, WorkspaceText.tr("Общее"))
                    val summary = StringBuilder(t("Импортировать выбранные Alias: ", "Import selected aliases: ") + plan.after.size)
                    if (plan.replacements() > 0) {
                        summary.append(t("\nБудут заменены:\n", "\nWill replace:\n"))
                        var i = 0
                        while (i < plan.before.size) {
                            if (plan.before.get(i) != null) {
                                val a: WorkspaceStore.Alias? = plan.before.get(i)
                                summary.append(a!!.name).append(" · ").append(WorkspaceAliases.label(a.environment)).append('\n')
                            }
                            i++
                        }
                    }
                    summary.append(t("\nКоманды не будут выполнены. ", "\nCommands will not be executed. ")).append(aliasReloadHint(plan))
                    backupBuilder(activity).setTitle(t("Подтвердить импорт Alias", "Confirm alias import")).setMessage(summary).setPositiveButton(t("Импортировать", "Import"), DialogInterface.OnClickListener { q, w ->
                        try {
                            val req: JSONObject = request(if (source!!.optBoolean("aliasShell")) "alias-inspect-shell" else "inspect").put("path", source.getString("path"))
                            if (source.optBoolean("aliasShell")) {
                                req.put("environment", source.getString("environment"))
                            }
                            run(req, Consumer<JSONObject?> { fresh ->
                                if (!fresh!!.optJSONObject("fingerprints").toString().equals(source.optJSONObject("fingerprints").toString())) {
                                    message(errorText("BACKUP_CHANGED_RECHECK"))
                                    return@Consumer
                                }
                                applyAliases(plan, dialog)
                            })
                        } catch (e: Exception) {
                            message(errorText("IMPORT_FAILED"))
                        }
                    }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
                } catch (e: Exception) {
                    message(errorText("IMPORT_FAILED"))
                }
            })
        })
        dialog.show()
    }

    private fun applyAliases(plan: WorkspaceAliasImport.Plan, picker: AlertDialog) {
        if (!store.isWritable() || !WorkspaceAliases.BUSY.compareAndSet(false, true)) {
            message(t("Дождитесь окончания изменений Alias и повторите импорт.", "Wait for pending alias changes, then retry import."))
            return
        }
        try {
            WorkspaceAliasImport.verify(plan, store.aliases)
        } catch (e: Exception) {
            WorkspaceAliases.BUSY.set(false)
            message(errorText("ALIAS_LIBRARY_CHANGED"))
            return
        }
        val wait: AlertDialog = backupBuilder(activity).setTitle(t("Импорт Alias", "Import aliases")).setMessage(t("Сохранение в окружения…", "Saving into environments…")).create()
        wait.setCancelable(false)
        wait.show()
        Thread(Runnable {
            try {
                // Private recovery metadata is written before any managed shell definition changes.
                val recovery = File(activity.getFilesDir(), "workspace-import-recovery")
                if (!recovery.isDirectory() && !recovery.mkdirs()) {
                    throw IOException("PRIVATE_STORAGE")
                }
                val snapshot: JSONObject = WorkspaceAliasImport.export(plan.before.stream().filter(Predicate<WorkspaceStore.Alias?>(Objects::nonNull)).collect(Collectors.toList()))
                (FileOutputStream(File(recovery, "aliases-before-" + UUID.randomUUID() + ".json"))).use { out ->
                    out.write((snapshot.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8))
                }
                WorkspaceAliasImport.install(plan, WorkspaceAliases.Installer { a, delete -> WorkspaceAliases.apply(activity, a, delete, "") })
            } catch (e: Exception) {
                activity.runOnUiThread(Runnable {
                    WorkspaceAliases.BUSY.set(false)
                    wait.dismiss()
                    message(t("Импорт Alias не завершён. Код: ", "Alias import did not complete. Code: ") + aliasError(e))
                })
                return@Runnable
            }
            activity.runOnUiThread(Runnable {
                val target: WorkspaceStore = if (store.isWritable()) store else WorkspaceStore(activity, WorkspaceStore.ErrorListener { error ->  })
                val separate: Boolean = target !== store
                try {
                    WorkspaceAliasImport.commit(plan, target)
                    if (!activity.isDestroyed()) {
                        picker.dismiss()
                        changed.run()
                    }
                } catch (e: Exception) {
                    if (separate) {
                        target.close()
                    }
                    WorkspaceAliases.BUSY.set(false)
                    wait.dismiss()
                    message(t("Определения записаны, но библиотека не обновлена. Сохранён снимок восстановления. Код: ", "Definitions were written, but the library was not updated. A recovery snapshot was retained. Code: ") + aliasError(e))
                    return@Runnable
                }
                Thread(Runnable {
                    var failure: Exception? = null
                    try {
                        target.awaitWrites()
                        val saved = JSONObject(String(readFile(File(activity.getFilesDir(), "workspace-v1.json")), StandardCharsets.UTF_8))
                        if (!WorkspaceAliasImport.persisted(plan, saved)) {
                            throw IOException("LIBRARY_WRITE_FAILED")
                        }
                    } catch (e: Exception) {
                        failure = e
                    } finally {
                        if (separate) {
                            target.close()
                        }
                    }
                    val result: Exception? = failure
                    activity.runOnUiThread(Runnable {
                        WorkspaceAliases.BUSY.set(false)
                        wait.dismiss()
                        message(if (result == null) t("Alias импортированы. ", "Aliases imported. ") + aliasReloadHint(plan) else t("Не удалось подтвердить сохранение библиотеки. Определения могли быть записаны. Снимок восстановления сохранён. Код: ", "Library persistence could not be confirmed. Definitions may have been written. Recovery snapshot retained. Code: ") + aliasError(result))
                    })
                }, "jobs-alias-import-save").start()
            })
        }, "jobs-alias-import").start()
    }

    private fun aliasReloadHint(plan: WorkspaceAliasImport.Plan): String {
        var termux = false
        var guest = false
        for (a in plan.after) {
            if (a.environment.equals("termux")) {
                termux = true
            } else {
                guest = true
            }
        }
        return (if (termux) t("Для Termux перезапустите вкладку. ", "Restart the Termux tab. ") else "") + (if (guest) t("Для остальных выбранных окружений откройте новую терминальную сессию.", "Open a new terminal session for the other selected environments.") else "")
    }

    private fun restoredInUse(alias: String): Boolean {
        if (activity.getTermuxService() == null) {
            return true
        }
        val handles: MutableSet<String> = HashSet<String>()
        for (item in activity.getTermuxService()!!.getTermuxSessions()) {
            val terminal: TerminalSession = item.getTerminalSession()
            if (terminal.isRunning()) {
                handles.add(terminal.mHandle)
            }
        }
        return restoredProfileInUse(store, alias, handles)
    }

    private fun restoredEnvironments() {
        run(request("restored-inventory"), Consumer<JSONObject?> { result ->
            val form: LinearLayout = column()
            info(form, t("Удаляется восстановленная Linux-система и связанные пресеты. Архивы в Download, папки отчётов и исходные окружения сохраняются. Закройте сессии этой копии перед удалением.", "Deletes the restored Linux system and linked presets. Download archives, report folders and original environments are retained. Close this copy's sessions before deletion."))
            val entries: JSONArray? = result!!.optJSONArray("environments")
            if (entries == null || entries.length() == 0) {
                info(form, t("Восстановленных окружений не найдено.", "No restored environments found."))
            }
            val list: AlertDialog = backupBuilder(activity).setTitle(t("Удаление восстановленных окружений", "Delete restored environments")).setView(scroll(form)).setNegativeButton(t("Назад", "Back"), null).create()
            if (entries != null) {
                var i = 0
                while (i < entries.length()) {
                    val entry: JSONObject = entries.optJSONObject(i)
                    val alias: String = entry.optString("alias")
                    val confirmed: Boolean = entry.optBoolean("confirmed")
                    val title: String = (if (entry.optString("source").isEmpty()) alias else WorkspaceEnvironments.label(entry.optString("source")) + " · " + alias) + "\n" + (if (confirmed) size(entry.optLong("bytes")) else t("Происхождение не подтверждено — удаление недоступно", "Origin unconfirmed — deletion unavailable")) + (if (entry.optBoolean("incomplete")) "\n" + t("Незавершённое восстановление", "Incomplete restore") else "")
                    val row: TextView = WorkspaceDialogs.action(activity, title, 0)
                    row.setEnabled(confirmed)
                    if (!confirmed) {
                        row.setAlpha(0.55f)
                    }
                    form.addView(row)
                    row.setOnClickListener(View.OnClickListener { v ->
                        if (restoredInUse(alias)) {
                            message(t("Окружение используется. Сначала закройте его сессии. Принудительная остановка не выполняется.", "Environment is in use. Close its sessions first. No sessions are forcibly stopped."))
                            return@OnClickListener
                        }
                        StorageDeletionConsent.show(activity, t("Удалить окружение?", "Delete environment?"), alias + "\n" + t("Все файлы и установленные пакеты этой восстановленной копии будут удалены без возможности отмены, вместе со связанными пресетами. Исходная система и архив бэкапа сохранятся.", "All files and installed packages in this restored copy will be permanently deleted, together with linked presets. The original system and backup archive are retained."), Runnable {
                            if (restoredInUse(alias)) {
                                message(t("Окружение используется. Закройте его сессии.", "Environment is in use. Close its sessions."))
                                return@Runnable
                            }
                            list.dismiss()
                            try {
                                run(request("remove-restored").put("alias", alias).put("identity", entry.optString("identity")), Consumer<JSONObject?> { removed ->
                                    if (!alias.equals(removed!!.optString("removed"))) {
                                        return@Consumer
                                    }
                                    removeRestoredPresets(store, alias)
                                    store.save()
                                    changed.run()
                                    message(t("Восстановленное окружение удалено. Архив бэкапа сохранён.", "Restored environment deleted. Backup archive retained."))
                                })
                            } catch (error: Exception) {
                                message(errorText("RESTORED_REMOVE_FAILED"))
                            }
                        })
                    })
                    i++
                }
            }
            list.show()
        })
    }

    private fun run(request: JSONObject, success: Consumer<JSONObject?>) {
        val engine = WorkspaceBackupEngine()
        val progress = WorkspaceBackupProgress(activity)
        val removal: Boolean = "remove-restored".equals(request.optString("operation"))
        val builder: AlertDialog.Builder = backupBuilder(activity).setTitle(label(activity)).setView(progress.view)
        if (!removal) {
            builder.setNegativeButton(WorkspaceText.tr("Отмена"), DialogInterface.OnClickListener { d, w -> engine.cancel() })
        }
        val dialog: AlertDialog = builder.create()
        dialog.setCancelable(false)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> progress.close() })
        val started: Boolean = engine.start(activity, request, object : WorkspaceBackupEngine.Callback {
            override fun progress(event: JSONObject) {
                if (dialog.isShowing()) {
                    progress.update(stageName(event.optString("stage")), event)
                }
            }

            override fun done(result: JSONObject?) {
                progress.close()
                dialog.dismiss()
                try {
                    if ("restore".equals(request.optString("operation")) || removal) {
                        BackupInventoryCache.SHARED.clear()
                    }
                    success.accept(result)
                } catch (e: Exception) {
                    message(t("Операция не применена полностью. Проверьте результат и сохранённые исходные данные.", "Operation was not fully applied. Inspect the result; original files were retained."))
                }
            }

            override fun error(code: String) {
                progress.close()
                dialog.dismiss()
                message(errorText(code))
            }
        })
        if (started) {
            dialog.show()
            progress.bindCancellation(dialog, Runnable(engine::cancel))
            progress.start()
        } else {
            progress.close()
            message(t("Другая операция резервного копирования ещё выполняется.", "Another backup operation is still running."))
        }
    }

    private fun stageName(stage: String): String {
        when (stage) {
            "INSTALL_PIGZ" -> {
                return t("Установка pigz", "Installing pigz")
            }
            "DATABASE" -> {
                return t("Копирование базы данных", "Backing up database")
            }
            "CHECKSUM" -> {
                return t("Проверка SHA-256", "Checking SHA-256")
            }
            "SCAN" -> {
                return t("Проверка структуры архива", "Checking archive structure")
            }
            "VERIFY_COMPRESSION" -> {
                return t("Проверка целостности сжатия", "Verifying compressed stream")
            }
            "ARCHIVE" -> {
                return t("Создание архива", "Creating archive")
            }
            "PROOT_DISTRO" -> {
                return t("Работа proot-distro", "Running proot-distro")
            }
            "RESTORE_FILES" -> {
                return t("Извлечение в новую папку", "Extracting to a new folder")
            }
            "PREPARE_UBUNTU" -> {
                return t("Подготовка отдельной Ubuntu", "Preparing separate Ubuntu")
            }
            else -> {
                return t("Подготовка и оценка размера", "Preparing and estimating size")
            }
        }
    }

    private fun errorText(code: String): String {
        when (code) {
            "RESTORED_NOT_CONFIRMED" -> {
                return t("Происхождение копии не подтверждено. Удаление заблокировано.", "Copy origin is unconfirmed. Deletion blocked.")
            }
            "RESTORED_CHANGED_RECHECK" -> {
                return t("Окружение изменилось. Откройте список удаления заново.", "Environment changed. Reopen the deletion list.")
            }
            "RESTORED_LOCKS_REQUIRED" -> {
                return t("Для безопасного удаления нужен современный proot-distro с блокировкой используемых окружений. Обновите proot-distro.", "Safe deletion requires modern proot-distro with active-environment locking. Update proot-distro.")
            }
            "RESTORED_REMOVE_FAILED" -> {
                return t("proot-distro не завершил удаление. Копия может использоваться другим процессом или быть удалена частично. Закройте её сессии и перечитайте список. Исходные окружения не затрагивались.", "proot-distro did not complete deletion. The copy may be in use by another process or partially deleted. Close its sessions and reread the list. Original environments were not targeted.")
            }
            "PIGZ_INSTALL_FAILED" -> {
                return t("Не удалось установить pigz. Проверьте интернет, репозитории и завершение других операций APT. gzip остаётся доступен. При необходимости выполните вручную: pkg install pigz.", "Could not install pigz. Check internet, repositories and other APT operations. gzip remains available. If needed, run manually: pkg install pigz.")
            }
            "PIGZ_REQUIRED" -> {
                return t("pigz больше недоступен. Выберите gzip или установите pigz заново.", "pigz is no longer available. Select gzip or install pigz again.")
            }
            "SOURCE_UNREADABLE", "SOURCE_CHANGED_RETRY" -> {
                return t("Выбранные исходные файлы недоступны или изменились во время копирования. Копия не завершена; текущие окружения не заменялись. Повторите проверку состава после завершения задач, меняющих файлы.", "Selected source files cannot be read or changed while copying. Backup is incomplete; existing environments were not replaced. Recheck the selection after file-writing jobs finish.")
            }
            "PROOT_LINK_UNSAFE" -> {
                return t("Служебная ссылка proot повреждена или ведёт за пределы разрешённого хранилища. Копирование остановлено без изменения источника.", "A proot internal link is broken or escapes the permitted backing store. Copying stopped without changing the source.")
            }
            "PYTHON_REQUIRED" -> {
                return t("Нужен Python в Termux. Установите вручную: pkg install python. Данные не изменены.", "Python is required in Termux. Install manually: pkg install python. Your data was not changed.")
            }
            "INSUFFICIENT_SPACE" -> {
                return t("Недостаточно свободного места. Для восстановления нужен запас на проверенную копию и извлечение.", "Not enough free space. Restore needs room for a verified copy and extraction.")
            }
            "CANCELLED" -> {
                return t("Операция отменена. Незавершённая копия помечена INCOMPLETE, если каталог уже был создан. Не используйте её как готовый бэкап.", "Cancelled. A created partial backup/restore folder is marked INCOMPLETE. Do not treat it as complete.")
            }
            "ARCH_OR_PROOT_UNCONFIRMED" -> {
                return t("Архитектура не подтверждена/несовместима или нужен современный proot-distro. Восстановление Linux-окружения заблокировано.", "Architecture is unconfirmed/incompatible or modern proot-distro is required. Linux environment restore blocked.")
            }
            "UNRECOGNIZED_ARCHIVE" -> {
                return t("Формат архива не распознан. Поддерживаются manifest.json нашего бэкапа, workspace-v1.json и распознаваемые TAR-архивы HOME/PREFIX/proot-distro.", "Archive format not recognized. Supported: our manifest.json, workspace-v1.json and recognized HOME/PREFIX/proot-distro TAR layouts.")
            }
            "UNSAFE_ARCHIVE_PATH", "WRITE_THROUGH_SYMLINK", "UNSAFE_LINK", "DUPLICATE_ARCHIVE_PATH" -> {
                return t("Архив содержит опасные или конфликтующие пути/ссылки. Восстановление заблокировано.", "Archive contains unsafe or conflicting paths/links. Restore blocked.")
            }
            "CHECKSUM_MISMATCH", "BACKUP_CHANGED_RECHECK" -> {
                return t("Контрольная сумма не совпадает или бэкап изменился. Выберите и проверьте его заново.", "Checksum mismatch or backup changed. Select and verify it again.")
            }
            else -> {
                return t("Операция остановлена. Текущие окружения не заменялись. Возможна нехватка прав, повреждённый/неподдерживаемый архив или ошибка инструмента. Код: ", "Operation stopped. Existing environments were not replaced. Check permissions, archive format/integrity and required tools. Code: ") + code
            }
        }
    }

    private fun request(operation: String): JSONObject {
        try {
            return WorkspaceBackupEngine.base(activity, operation)
        } catch (e: Exception) {
            throw IllegalStateException(e)
        }
    }

    private fun inventoryKey(selective: Boolean): String {
        return (if (selective) "selection:" else "full:") + activity.getFilesDir().getAbsolutePath() + ":" + WorkspaceBackupEngine.prefix()
    }

    private fun inventory() {
        backupBuilder(activity).setTitle(t("Режим резервного копирования", "Backup mode")).setItems(arrayOf<String>(t("Полная копия окружения", "Full environment backup"), t("По выбору", "Custom selection")), DialogInterface.OnClickListener { d, w ->
            val entry: BackupInventoryCache.Entry? = BackupInventoryCache.SHARED.get(inventoryKey(w == 1))
            if (w == 0) {
                createForm(if (entry == null) null else entry.inventory)
            } else {
                try {
                    selectiveForm(if (entry == null) null else entry.inventory, if (entry == null) JSONObject() else JSONObject(entry.folders.toString()), LinkedHashMap<String, MutableSet<String>>(), JSONObject(), entry == null, null)
                } catch (e: Exception) {
                    message(errorText("INVENTORY_FAILED"))
                }
            }
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun inlineInventory(dialog: AlertDialog, header: WorkspaceBackupScanHeader, form: LinearLayout, req: JSONObject, success: Consumer<JSONObject?>) {
        if (header.running || header.closed || installingPigz) {
            return
        }
        val engine = WorkspaceBackupEngine()
        val enabled: MutableMap<View, Boolean> = IdentityHashMap<View, Boolean>()
        rememberEnabled(form, enabled)
        for (v in enabled.keys) {
            v.setEnabled(false)
        }
        val positive: Button? = dialog.getButton(-1)
        val wasEnabled: Boolean = positive != null && positive.isEnabled()
        if (positive != null) {
            positive.setEnabled(false)
        }
        header.start(engine, t("Сканирование…", "Scanning…"))
        val restore = Runnable {
            if (!header.ready) {
                return@Runnable
            }
            for (e in enabled.entries) {
                e.key.setEnabled(e.value)
            }
            if (positive != null) {
                positive.setEnabled(wasEnabled)
            }
        }
        val started: Boolean = engine.start(activity, req, object : WorkspaceBackupEngine.Callback {
            override fun progress(event: JSONObject) {
                if (!header.closed && dialog.isShowing()) {
                    header.progress(stageName(event.optString("stage")), event)
                }
            }

            override fun done(result: JSONObject?) {
                if (header.closed || !dialog.isShowing()) {
                    return
                }
                header.ready = true
                header.finish(null)
                restore.run()
                try {
                    success.accept(result)
                } catch (e: Exception) {
                    header.finish(errorText("INVENTORY_FAILED"))
                }
            }

            override fun error(code: String) {
                if (header.closed || !dialog.isShowing()) {
                    return
                }
                header.finish(errorText(code))
                restore.run()
            }
        })
        if (!started) {
            header.finish(t("Другая операция резервного копирования ещё выполняется.", "Another backup operation is still running."))
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

    private fun createForm(inventory: JSONObject?) {
        createForm(inventory, JSONObject())
    }

    private fun createForm(supplied: JSONObject?, state: JSONObject) {
        try {
            val initial: Boolean = supplied == null
            val inventory: JSONObject? = if (initial) JSONObject().put("environments", JSONArray()).put("ubuntuBytes", JSONObject()) else supplied
            val scan = WorkspaceBackupScanHeader(activity, t("Создать резервную копию", "Create backup"))
            scan.ready = !initial
            val form: LinearLayout = column()
            info(form, t("Архивы могут содержать SSH-ключи, токены и авторизацию. Завершите изменяющие файлы задачи перед копированием. Процессы не останавливаются автоматически; копия работающих баз не гарантированно согласована.", "Archives may contain SSH keys, tokens and authorization. Finish file-writing tasks first. Processes are not stopped automatically; live database copies may be inconsistent."))
            val library: CheckBox = check(form, t("Команды, Alias, заметки, пресеты, настройки и файлы истории сессий", "Commands, aliases, notes, presets, settings and shell history files"), state.optBoolean("library", true))
            if (!store.isWritable()) {
                library.setChecked(false)
                library.setEnabled(false)
                info(form, t("Библиотека недоступна для чтения/записи. Её исходный файл нужно сохранить отдельно.", "Library is unavailable. Preserve its original file separately."))
            }
            val home: CheckBox = check(form, "Termux HOME · " + size(inventory!!.optLong("homeBytes")), state.optBoolean("home"))
            val prefix: CheckBox = check(form, "Termux PREFIX · " + size(inventory.optLong("prefixBytes")), state.optBoolean("prefix"))
            info(form, t("PREFIX сохраняется без rootfs/cache proot-distro и временных файлов. Linux-окружения выбираются отдельно. Внешние файлы по символьным ссылкам и общая память Android не копируются.", "PREFIX excludes proot-distro storage/cache and temporary files. Select Linux environments separately. External symlink targets and Android shared storage are not copied."))
            val environments: JSONArray = inventory.getJSONArray("environments")
            val envChecks: MutableList<CheckBox> = ArrayList<CheckBox>()
            var i = 0
            while (i < environments.length()) {
                val name: String = environments.getJSONObject(i).getString("name")
                envChecks.add(check(form, "Linux / proot · " + name + " · " + size(inventory.getJSONObject("ubuntuBytes").optLong(name)), state.optJSONObject("envs") != null && state.optJSONObject("envs").optBoolean(name)))
                i++
            }
            compressionControl(form)
            info(form, t("Папка создания резервной копии", "Backup destination folder"))
            val destination: EditText = field(form, state.optString("destination", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()), t("Путь к папке", "Folder path"))
            action(form, t("Выбрать каталог", "Choose directory"), Runnable {
                browse(File(destination.getText().toString()), true, Consumer<String> { p -> destination.setText(p) })
            })
            val confirmed: CheckBox = check(form, t("Я завершил важные задачи и понимаю, что архив содержит секреты", "I finished important jobs and understand the archive contains secrets"), false)
            val dialog: AlertDialog = backupBuilder(activity).setCustomTitle(scan.view).setView(scroll(form)).setPositiveButton(t("Создать", "Create"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
            dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
                dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                    try {
                        if (installingPigz) {
                            message(t("Дождитесь окончания установки pigz.", "Wait for pigz installation to finish."))
                            return@OnClickListener
                        }
                        if (!confirmed.isChecked()) {
                            confirmed.setError(t("Нужно подтверждение", "Confirmation required"))
                            return@OnClickListener
                        }
                        val selected = JSONArray()
                        val envs = JSONArray()
                        if (library.isChecked()) {
                            selected.put("library")
                        }
                        if (home.isChecked()) {
                            selected.put("home")
                        }
                        if (prefix.isChecked()) {
                            selected.put("prefix")
                        }
                        var i = 0
                        while (i < envChecks.size) {
                            if (envChecks.get(i).isChecked()) {
                                envs.put(environments.getJSONObject(i).getString("name"))
                            }
                            i++
                        }
                        if (selected.length() + envs.length() == 0) {
                            return@OnClickListener
                        }
                        val req: JSONObject = compressedRequest(request("create")).put("destination", (destination.getText().toString() as java.lang.String).trim()).put("components", selected).put("environments", envs).put("workspace", store.encode()).put("settings", WorkspaceBackupImport.settings(activity))
                        dialog.dismiss()
                        run(req, Consumer<JSONObject?>(this::backupCreated))
                    } catch (e: Exception) {
                        message(t("Не удалось подготовить бэкап.", "Could not prepare backup."))
                    }
                })
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> scan.close() })
            dialog.show()
            bindConsent(dialog, confirmed)
            val refresh = Runnable {
                try {
                    val draft: JSONObject = JSONObject().put("library", library.isChecked()).put("home", home.isChecked()).put("prefix", prefix.isChecked()).put("destination", destination.getText().toString())
                    val names = JSONObject()
                    var i = 0
                    while (i < envChecks.size) {
                        names.put(environments.getJSONObject(i).getString("name"), envChecks.get(i).isChecked())
                        i++
                    }
                    draft.put("envs", names)
                    inlineInventory(dialog, scan, form, request("inventory"), Consumer<JSONObject?> { result ->
                        BackupInventoryCache.SHARED.put(inventoryKey(false), result, JSONObject())
                        dialog.dismiss()
                        createForm(result, draft)
                    })
                } catch (e: Exception) {
                    scan.finish(errorText("INVENTORY_FAILED"))
                }
            }
            scan.refresh.setOnClickListener(View.OnClickListener { v -> refresh.run() })
            if (initial) {
                refresh.run()
            }
        } catch (e: Exception) {
            message(errorText("INVENTORY_FAILED"))
        }
    }

    private fun scopeName(id: String): String {
        return if (id.equals("termux")) "Termux" else if (id.startsWith("ubuntu:")) "Linux · " + id.substring(7) else id
    }

    private fun itemName(id: String): String {
        if (id.startsWith("project:")) {
            return t("Проект · ", "Project · ") + id.substring(8)
        }
        if (id.startsWith("folder:")) {
            return t("Папка · ", "Folder · ") + id.substring(7)
        }
        when (id) {
            "codex" -> {
                return t("Codex · программа, авторизация, настройки и история", "Codex · CLI, authorization, settings and history")
            }
            "agents" -> {
                return t("Другие AI-агенты · найденные файлы и настройки", "Other AI agents · detected files and settings")
            }
            "ssh" -> {
                return t("SSH и Git · ключи и настройки", "SSH and Git · keys and settings")
            }
            "shell" -> {
                return t("Оболочка и пользовательские скрипты", "Shell and user scripts")
            }
            "builds" -> {
                return t("Инструменты, кеши и результаты сборок", "Tools, caches and build outputs")
            }
            "other" -> {
                return t("Остальные пользовательские файлы", "Other user files")
            }
            "system" -> {
                return t("Система и установленные зависимости", "System and installed dependencies")
            }
            else -> {
                return id
            }
        }
    }

    private fun selectiveInventory(folders: JSONObject, retained: MutableMap<String, MutableSet<String>>, cached: JSONObject?, state: JSONObject) {
        selectiveForm(cached, folders, retained, state, true, cached)
    }

    private fun selectiveForm(supplied: JSONObject?, folders: JSONObject, retained: MutableMap<String, MutableSet<String>>, state: JSONObject, scanOnOpen: Boolean, cached: JSONObject?) {
        try {
            val initial: Boolean = supplied == null
            val inventory: JSONObject? = if (initial) JSONObject().put("scopes", JSONArray()) else supplied
            val scan = WorkspaceBackupScanHeader(activity, t("По выбору", "Custom selection"))
            scan.ready = !initial
            val destinationField: Array<EditText?> = arrayOf<EditText?>(null)
            val form: LinearLayout = column()
            info(form, t("Выбранные данные. Полная копия остаётся отдельным режимом. Размеры до сжатия; каждая найденная папка учитывается один раз. Внешние цели ссылок не копируются. Архив содержит секреты.", "Selected data. Full backup remains a separate mode. Sizes are before compression; detected folders are counted once. External symlink targets are not copied. Archives contain secrets."))
            val library: CheckBox = check(form, t("Библиотека, настройки и история терминала", "Library, settings and terminal history") + " · " + size(inventory!!.optLong("libraryBytes")), store.isWritable() && state.optBoolean("library", true))
            library.setEnabled(store.isWritable())
            val scopes: JSONArray = inventory.getJSONArray("scopes")
            val checks: MutableMap<String, MutableMap<String, CheckBox>> = LinkedHashMap<String, MutableMap<String, CheckBox>>()
            val holder: Array<AlertDialog?> = arrayOf<AlertDialog?>(null)
            var i = 0
            while (i < scopes.length()) {
                val scope: JSONObject = scopes.getJSONObject(i)
                val sid: String = scope.getString("id")
                val section: LinearLayout = column()
                section.setBackground(WorkspaceDialogs.background(activity, false))
                val sectionParams = LinearLayout.LayoutParams(-1, -2)
                sectionParams.bottomMargin = WorkspaceDialogs.dp(activity, 10.0f)
                form.addView(section, sectionParams)
                val body = LinearLayout(activity)
                body.setOrientation(LinearLayout.VERTICAL)
                val title: String = if (sid.equals("termux")) "Termux" else "Ubuntu · " + sid.substring(7)
                val heading: TextView = WorkspaceDialogs.action(activity, title + " ▾", 0)
                section.addView(heading)
                section.addView(body)
                heading.setOnClickListener(View.OnClickListener { v ->
                    val open: Boolean = body.getVisibility() == View.VISIBLE
                    body.setVisibility(if (open) View.GONE else View.VISIBLE)
                    heading.setText(title + (if (open) " ▸" else " ▾"))
                })
                val rows: MutableMap<String, CheckBox> = LinkedHashMap<String, CheckBox>()
                checks.put(sid, rows)
                val items: JSONArray = scope.getJSONArray("items")
                var j = 0
                while (j < items.length()) {
                    val item: JSONObject = items.getJSONObject(j)
                    val id: String = item.getString("id")
                    if (item.optLong("files") == 0L && item.optInt("unreadable") == 0) {
                        j++
                        continue
                    }
                    val readable: Boolean = item.optInt("unreadable") == 0
                    val b: CheckBox = check(body, itemName(id) + " · " + (if (readable) size(item.optLong("bytes")) else t("размер неполный · недоступные файлы", "incomplete size · unreadable files")), readable && retained.getOrDefault(sid, Collections.emptySet()).contains(id))
                    b.setEnabled(readable)
                    rows.put(id, b)
                    j++
                }
                val issues: JSONArray? = scope.optJSONArray("issues")
                if (issues != null && issues.length() > 0) {
                    info(body, t("Некоторые пути прочитать нельзя. Эти компоненты отключены; остальные можно выбрать. Права исходных файлов не меняются.", "Some paths cannot be read. Affected components are disabled; other items remain selectable. Source permissions are not changed."))
                    var j = 0
                    while (j < Math.min(8, issues.length())) {
                        val issue: JSONObject = issues.optJSONObject(j)
                        info(body, issue.optString("path") + " · " + t("код ОС ", "OS error ") + issue.optInt("errno"))
                        j++
                    }
                }
                action(body, t("Добавить папку…", "Add folder…"), Runnable {
                    browse(File(scope.optString("root"), scope.optString("home")), true, Consumer<String> { p ->
                        try {
                            val root: File = File(scope.getString("root")).getCanonicalFile()
                            val chosen: File = File(p).getCanonicalFile()
                            val base: String = root.getPath() + "/"
                            val user: String = base + scope.getString("home") + "/"
                            if (!chosen.getPath().startsWith(user)) {
                                message(t("Выберите подпапку пользовательского HOME этого окружения.", "Choose a subdirectory of this environment's user HOME."))
                                return@Consumer
                            }
                            val relative: String = chosen.getPath().substring(base.length)
                            var added: JSONArray? = folders.optJSONArray(sid)
                            if (added == null) {
                                added = JSONArray()
                                folders.put(sid, added)
                            }
                            var exists = false
                            var k = 0
                            while (k < added.length()) {
                                if (relative.equals(added.optString(k))) {
                                    exists = true
                                }
                                k++
                            }
                            if (!exists) {
                                added.put(relative)
                            }
                            val keep: MutableMap<String, MutableSet<String>> = selectedItems(checks)
                            keep.computeIfAbsent(sid, Function<String, MutableSet<String>> { key -> HashSet<String>() }).add("folder:" + relative)
                            val draft: JSONObject = JSONObject().put("library", library.isChecked()).put("destination", destinationField[0]!!.getText().toString())
                            holder[0]!!.dismiss()
                            selectiveInventory(folders, keep, inventory, draft)
                        } catch (e: Exception) {
                            message(errorText("INVALID_SELECTION_PATH"))
                        }
                    })
                })
                i++
            }
            compressionControl(form)
            info(form, t("Папка создания резервной копии", "Backup destination folder"))
            val destination: EditText = field(form, state.optString("destination", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath()), t("Путь к папке", "Folder path"))
            action(form, t("Выбрать каталог", "Choose directory"), Runnable { browse(File(destination.getText().toString()), true, Consumer<String>(destination::setText)) })
            destinationField[0] = destination
            info(form, t("История чатов не заменяет файлы проектов. Работающие процессы и Android Keystore не переносятся. Для других AI-агентов точный состав и зависимости требуют отдельной проверки.", "Chat history does not include project files. Live processes and Android Keystore cannot be transferred. Other AI agents need individual file/dependency review."))
            val dialog: AlertDialog = backupBuilder(activity).setCustomTitle(scan.view).setView(scroll(form)).setPositiveButton(t("Проверить состав", "Check selection"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
            holder[0] = dialog
            dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
                dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                    try {
                        if (installingPigz) {
                            message(t("Дождитесь окончания установки pigz.", "Wait for pigz installation to finish."))
                            return@OnClickListener
                        }
                        val selections = JSONArray()
                        var total: Long = if (library.isChecked()) inventory.optLong("libraryBytes") else 0L
                        var i = 0
                        while (i < scopes.length()) {
                            val scope: JSONObject = scopes.getJSONObject(i)
                            val sid: String = scope.getString("id")
                            val ids = JSONArray()
                            val rows: MutableMap<String, CheckBox>? = checks.get(sid)
                            val items: JSONArray = scope.getJSONArray("items")
                            var j = 0
                            while (j < items.length()) {
                                val item: JSONObject = items.getJSONObject(j)
                                val b: CheckBox? = rows!!.get(item.getString("id"))
                                if (b != null && b.isChecked()) {
                                    ids.put(item.getString("id"))
                                    total += item.optLong("bytes")
                                }
                                j++
                            }
                            if (ids.length() > 0) {
                                selections.put(JSONObject().put("scope", sid).put("items", ids).put("folders", if (folders.optJSONArray(sid) == null) JSONArray() else folders.getJSONArray(sid)).put("runtime", scope.optJSONObject("runtime")))
                            }
                            i++
                        }
                        if (selections.length() == 0 && !library.isChecked()) {
                            return@OnClickListener
                        }
                        val req: JSONObject = compressedRequest(request("create")).put("destination", (destination.getText().toString() as java.lang.String).trim()).put("components", if (library.isChecked()) JSONArray().put("library") else JSONArray()).put("environments", JSONArray()).put("selections", selections).put("workspace", store.encode()).put("settings", WorkspaceBackupImport.settings(activity))
                        selectionConfirmation(req, scopes, checks, dialog, total)
                    } catch (e: Exception) {
                        message(errorText("INVALID_SELECTION"))
                    }
                })
            })
            dialog.setOnDismissListener(DialogInterface.OnDismissListener { d -> scan.close() })
            dialog.show()
            val refresh = Runnable {
                try {
                    val keep: MutableMap<String, MutableSet<String>> = if (scanOnOpen) retained else selectedItems(checks)
                    val draft: JSONObject = JSONObject().put("library", library.isChecked()).put("destination", destination.getText().toString())
                    val req: JSONObject = request("selection-inventory").put("folders", folders)
                    if (scanOnOpen && cached != null && (cached.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8).size < 4 * 1024 * 1024) {
                        req.put("inventoryCache", cached)
                    }
                    inlineInventory(dialog, scan, form, req, Consumer<JSONObject?> { result ->
                        BackupInventoryCache.SHARED.put(inventoryKey(true), result, folders)
                        dialog.dismiss()
                        selectiveForm(result, folders, keep, draft, false, null)
                    })
                } catch (e: Exception) {
                    scan.finish(errorText("INVENTORY_FAILED"))
                }
            }
            scan.refresh.setOnClickListener(View.OnClickListener { v -> selectiveRefresh(dialog, scan, form, folders, checks, library, destination) })
            if (scanOnOpen || initial) {
                refresh.run()
            }
        } catch (e: Exception) {
            message(errorText("INVENTORY_FAILED"))
        }
    }

    private fun selectiveRefresh(dialog: AlertDialog, scan: WorkspaceBackupScanHeader, form: LinearLayout, folders: JSONObject, checks: MutableMap<String, MutableMap<String, CheckBox>>, library: CheckBox, destination: EditText) {
        try {
            val keep: MutableMap<String, MutableSet<String>> = selectedItems(checks)
            val draft: JSONObject = JSONObject().put("library", library.isChecked()).put("destination", destination.getText().toString())
            inlineInventory(dialog, scan, form, request("selection-inventory").put("folders", folders), Consumer<JSONObject?> { result ->
                BackupInventoryCache.SHARED.put(inventoryKey(true), result, folders)
                dialog.dismiss()
                selectiveForm(result, folders, keep, draft, false, null)
            })
        } catch (e: Exception) {
            scan.finish(errorText("INVENTORY_FAILED"))
        }
    }

    private fun selectedItems(checks: MutableMap<String, MutableMap<String, CheckBox>>): MutableMap<String, MutableSet<String>> {
        val result: MutableMap<String, MutableSet<String>> = LinkedHashMap<String, MutableSet<String>>()
        for (scope in checks.entries) {
            val ids: MutableSet<String> = HashSet<String>()
            for (row in scope.value.entries) {
                if (row.value.isChecked()) {
                    ids.add(row.key)
                }
            }
            result.put(scope.key, ids)
        }
        return result
    }

    @Throws(JSONException::class)
    private fun selectionConfirmation(req: JSONObject, scopes: JSONArray, checks: MutableMap<String, MutableMap<String, CheckBox>>, parent: AlertDialog, total: Long) {
        val form: LinearLayout = column()
        info(form, t("Выбрано до сжатия: ", "Selected before compression: ") + size(total))
        val plans: JSONArray = req.getJSONArray("selections")
        val reviewHolder: Array<AlertDialog?> = arrayOf<AlertDialog?>(null)
        var i = 0
        while (i < plans.length()) {
            val plan: JSONObject = plans.getJSONObject(i)
            val sid: String = plan.getString("scope")
            val ids: MutableSet<String> = HashSet<String>()
            val selected: JSONArray = plan.getJSONArray("items")
            var j = 0
            while (j < selected.length()) {
                ids.add(selected.getString(j))
                j++
            }
            info(form, if (sid.equals("termux")) "Termux" else "Ubuntu · " + sid.substring(7))
            for (id in ids) {
                info(form, "✓ " + itemName(id))
            }
            if (!ids.contains("system")) {
                info(form, t("Для запуска потребуется готовое окружение. Система и её зависимости не включены: добавьте их сейчас или установите в целевом окружении. Данные будут извлечены отдельно, без автоматической замены файлов.", "A prepared environment is required. The system/dependencies are not included: add them now or install in the target environment. Data will be staged separately, without automatic file replacement."))
                val system: CheckBox? = checks.get(sid)!!.get("system")
                if (system != null && system.isEnabled()) {
                    action(form, t("Добавить систему и зависимости", "Add system and dependencies"), Runnable {
                        system.setChecked(true)
                        if (reviewHolder[0] != null) {
                            reviewHolder[0]!!.dismiss()
                        }
                    })
                }
            }
            if (ids.contains("codex")) {
                val node: Boolean = plan.optJSONObject("runtime") != null && plan.optJSONObject("runtime").optBoolean("node")
                info(form, if (node && ids.contains("system")) t("Node.js найден в выбранной системе. Переносимость CLI проверяется в целевом окружении.", "Node.js found in the selected system. CLI portability is checked in the target environment.") else t("Codex требует совместимый Node.js. Если он не включён в систему, установите его в целевом окружении. Авторизация может потребовать повторного входа.", "Codex requires compatible Node.js. If not included in the system, install it in the target environment. Authorization may require signing in again."))
                if (ids.stream().noneMatch(Predicate<String> { id -> id.startsWith("project:") || id.startsWith("folder:") })) {
                    info(form, t("История сохранится; проекты не выбраны. Добавьте нужные папки, чтобы продолжить работу с файлами.", "History is included; projects are not selected. Add their folders to continue working with files."))
                }
            }
            if (ids.contains("ssh")) {
                info(form, t("Для использования ключей нужны Git и SSH-клиент. ", "Using the keys requires Git and an SSH client. ") + (if (ids.contains("system") && plan.optJSONObject("runtime") != null && plan.optJSONObject("runtime").optBoolean("git") && plan.optJSONObject("runtime").optBoolean("ssh")) t("Оба найдены в выбранной системе; доступ к серверу проверяется отдельно.", "Both found in the selected system; server access must be checked separately.") else t("Если их нет в целевом окружении: Termux — pkg install git openssh; Ubuntu — apt install git openssh-client. Сейчас команды не выполняются.", "If absent in the target environment: Termux — pkg install git openssh; Ubuntu — apt install git openssh-client. No commands run now.")))
            }
            if (ids.contains("shell")) {
                info(form, t("Зависимости пользовательских скриптов не определяются автоматически; проверьте интерпретаторы и нужные команды.", "User-script dependencies are not detected automatically; review interpreters and required commands."))
            }
            if (ids.contains("agents")) {
                info(form, t("Для других агентов зависимости индивидуальны. Наличие файлов не подтверждает готовность к запуску.", "Other agents have individual dependencies. File presence does not establish launch readiness."))
            }
            i++
        }
        info(form, t("Завершите задачи, меняющие файлы. Процессы не останавливаются. Базы Codex SQLite копируются согласованно по отдельности; весь бэкап не является атомарным снимком.", "Finish file-writing jobs. Processes are not stopped. Codex SQLite databases are backed up consistently individually; the complete backup is not an atomic snapshot."))
        val agree: CheckBox = check(form, t("Я завершил важные задачи, понимаю, что архив может содержать секреты, и подтверждаю состав", "I finished important jobs, understand the archive may contain secrets and confirm the selection"), false)
        val review: AlertDialog = backupBuilder(activity).setTitle(t("Проверка выбранного", "Review selection")).setView(scroll(form)).setPositiveButton(t("Создать копию", "Create backup"), null).setNegativeButton(t("Изменить выбор", "Change selection"), null).create()
        reviewHolder[0] = review
        review.setOnShowListener(DialogInterface.OnShowListener { d ->
            review.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                if (!agree.isChecked()) {
                    agree.setError(t("Нужно подтверждение", "Confirmation required"))
                    return@OnClickListener
                }
                try {
                    // Rebuild from live checkboxes, including system selections added in this review.
                    val updated = JSONArray()
                    val now: MutableMap<String, MutableSet<String>> = selectedItems(checks)
                    var i = 0
                    while (i < plans.length()) {
                        val plan: JSONObject = plans.getJSONObject(i)
                        val ids = JSONArray()
                        for (id in now.get(plan.getString("scope"))!!) {
                            ids.put(id)
                        }
                        plan.put("items", ids)
                        updated.put(plan)
                        i++
                    }
                    req.put("selections", updated)
                    review.dismiss()
                    parent.dismiss()
                    run(req, Consumer<JSONObject?>(this::backupCreated))
                } catch (e: Exception) {
                    message(errorText("INVALID_SELECTION"))
                }
            })
        })
        review.show()
        bindConsent(review, agree)
    }

    private fun requirements(form: LinearLayout, component: JSONObject) {
        val selection: JSONObject? = component.optJSONObject("selection")
        val req: JSONObject? = component.optJSONObject("requirements")
        if (selection == null || req == null) {
            return
        }
        val savedItems: JSONArray? = selection.optJSONArray("items")
        if (savedItems != null) {
            var i = 0
            while (i < savedItems.length()) {
                info(form, "✓ " + itemName(savedItems.optString(i)))
                i++
            }
        }
        info(form, if (req.optBoolean("architectureMatches")) t("Архитектура источника совпадает. Это не проверка запуска CLI.", "Source architecture matches. This does not verify CLI launch.") else t("Архитектура отличается или неизвестна: файлы можно извлечь отдельно, но бинарники могут потребовать переустановки.", "Source architecture differs or is unknown: data can be staged, but binaries may need reinstalling."))
        if (req.optBoolean("stagingOnly")) {
            info(form, t("Только отдельное извлечение. Текущие файлы не заменятся; окружение и зависимости нужно подготовить перед ручным переносом.", "Staging only. Existing files are not replaced; prepare the environment and dependencies before manually activating files."))
        }
        val candidates: JSONArray? = req.optJSONArray("candidates")
        if (selection.optBoolean("system") && selection.optString("environment").equals("ubuntu")) {
            info(form, t("Система включена и будет восстановлена под новым именем. Наличие текущей системы не требуется. Версии runtime и работоспособность CLI проверьте после восстановления.", "The system is included and will be restored under a new name. An existing system is not required. Verify runtime versions and CLI operation after restore."))
        } else if (candidates == null || candidates.length() == 0) {
            info(form, t("Подходящее окружение в целевом окружении не найдено. Установите его либо восстановите систему из полного бэкапа.", "No matching environment found in the target environment. Install it or restore the system from a full backup."))
        } else {
            var i = 0
            while (i < candidates.length()) {
                val c: JSONObject = candidates.optJSONObject(i)
                info(form, t("Обнаружено: ", "Detected: ") + scopeName(c.optString("scope")) + (if (req.optBoolean("needsNode")) if (c.optBoolean("node")) t(" · Node.js найден", " · Node.js found") else t(" · Node.js не найден; установите перед запуском", " · Node.js not found; install before launching") else ""))
                i++
            }
        }
        if (selection.optBoolean("historyOnlyWithoutProjects")) {
            info(form, t("Проекты не выбраны: история чатов не содержит их файлы.", "Projects were not selected: chat history does not contain their files."))
        }
        if (req.optBoolean("needsSshGit")) {
            info(form, t("Ключам нужны Git и SSH-клиент. Если отсутствуют: pkg install git openssh в Termux; apt install git openssh-client в Ubuntu. Сейчас установка не выполняется.", "Keys require Git and an SSH client. If absent: pkg install git openssh in Termux; apt install git openssh-client in Ubuntu. Nothing is installed now."))
            if (candidates != null) {
                var i = 0
                while (i < candidates.length()) {
                    val c: JSONObject = candidates.optJSONObject(i)
                    info(form, scopeName(c.optString("scope")) + " · Git: " + (if (c.optBoolean("git")) t("найден", "found") else t("не найден", "not found")) + " · SSH: " + (if (c.optBoolean("ssh")) t("найден", "found") else t("не найден", "not found")))
                    i++
                }
            }
        }
        if (req.optBoolean("scriptsRequireReview")) {
            info(form, t("Зависимости пользовательских скриптов требуют ручной проверки.", "User-script dependencies require manual review."))
        }
        if (req.optBoolean("agentsRequireReview")) {
            info(form, t("Проверьте зависимости и вход каждого AI-агента отдельно.", "Check each AI agent's dependencies and sign-in separately."))
        }
        if (req.optBoolean("needsNode")) {
            info(form, t("Подготовка вручную: Termux — pkg install nodejs; Ubuntu — proot-distro install ubuntu, затем apt update && apt install nodejs npm внутри Ubuntu. Доступная версия Node.js должна соответствовать требованиям сохранённого CLI. Это подсказки; команды сейчас не выполняются.", "Manual preparation: Termux — pkg install nodejs; Ubuntu — proot-distro install ubuntu, then apt update && apt install nodejs npm inside Ubuntu. The Node.js version must meet the saved CLI requirements. These are hints; no commands are run now."))
        }
    }

    private fun size(value: Long): String {
        return Formatter.formatFileSize(activity, value)
    }

    private fun chooseFile(restore: Boolean) {
        val form: LinearLayout = column()
        info(form, t("Выберите manifest.json из каталога бэкапа, workspace-v1.json или TAR-архив. Проверка ничего не распаковывает и не запускает.", "Choose manifest.json from a backup directory, workspace-v1.json or a TAR archive. Inspection never extracts or executes archive contents."))
        val file: EditText = field(form, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(), t("Путь к файлу", "File path"))
        action(form, t("Выбрать файл", "Choose file"), Runnable { browse(File(file.getText().toString()), false, Consumer<String>(file::setText)) })
        backupBuilder(activity).setTitle(if (restore) t("Восстановить из файла", "Restore from file") else t("Проверить резервную копию", "Verify backup")).setView(scroll(form)).setPositiveButton(t("Проверить", "Verify"), DialogInterface.OnClickListener { d, w ->
            try {
                run(request("inspect").put("path", (file.getText().toString() as java.lang.String).trim()), Consumer<JSONObject?> { r -> report(r, restore) })
            } catch (e: Exception) {
                message(errorText("INVALID_PATH"))
            }
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    private fun browse(start: File, folders: Boolean, selected: Consumer<String>) {
        var folder: File? = if (start.isDirectory()) start else start.getParentFile()
        if (folder == null || !folder.isDirectory()) {
            folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        val list: Array<File>? = folder!!.listFiles(FileFilter { f -> !WorkspaceBackupEngine.symlink(f) && (f.isDirectory() || !folders && f.isFile()) })
        if (list == null) {
            message(t("Нет доступа к папке. Проверьте разрешение доступа к файлам Android или введите доступный путь вручную.", "Cannot access this directory. Check Android storage permission or enter an accessible path."))
            return
        }
        Arrays.sort(list, Comparator.comparing(Function<File, Boolean> { f -> !f.isDirectory() }).thenComparing(Function<File, String>(File::getName)))
        if (list.size > 3000) {
            message(t("Слишком много файлов; введите вложенный путь.", "Too many files; enter a nested path."))
            return
        }
        val rows: MutableList<String> = ArrayList<String>()
        rows.add("../")
        for (f in list) {
            rows.add((if (f.isDirectory()) "▸ " else "") + f.getName())
        }
        val current: File? = folder
        val b: AlertDialog.Builder = backupBuilder(activity).setTitle(current!!.getAbsolutePath()).setItems(rows.toTypedArray(), DialogInterface.OnClickListener { d, w ->
            if (w == 0) {
                val parent: File? = current.getParentFile()
                if (parent != null) {
                    browse(parent, folders, selected)
                }
            } else if (list[w - 1].isDirectory()) {
                browse(list[w - 1], folders, selected)
            } else {
                selected.accept(list[w - 1].getAbsolutePath())
            }
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null)
        if (folders) {
            b.setPositiveButton(t("Выбрать эту папку", "Select this directory"), DialogInterface.OnClickListener { d, w -> selected.accept(current.getAbsolutePath()) })
        }
        b.show()
    }

    private fun report(report: JSONObject?, restore: Boolean) {
        try {
            val form: LinearLayout = column()
            info(form, t("Проверка завершена. SHA-256 не подтверждает подлинность чужого бэкапа. Не восстанавливайте недоверенные данные. Совместимость работающих баз и внешних путей автоматически не гарантируется.", "Inspection complete. SHA-256 does not authenticate a foreign backup. Do not restore untrusted data. Live database consistency and external paths are not guaranteed."))
            val source: JSONObject? = report!!.optJSONObject("source")
            if (source != null && source.length() > 0) {
                info(form, t("Источник: ", "Source: ") + source.optString("packageId", "?") + " · " + source.optString("arch", "?") + " · " + source.optString("appVersion", "?") + "\n" + t("Пути из бэкапа: ", "Backup paths: ") + source.optString("home", "?") + " / " + source.optString("prefix", "?"))
            } else {
                info(form, t("Исходные версии приложения и package ID неизвестны. Совместимость путей не подтверждена.", "Source app version and package ID are unknown. Path compatibility is unconfirmed."))
            }
            val components: JSONArray = report.getJSONArray("components")
            var i = 0
            while (i < components.length()) {
                val c: JSONObject = components.getJSONObject(i)
                info(form, (i + 1).toString() + ". " + kindName(c.optString("kind")) + " · " + c.optString("format") + " · " + c.optString("arch") + "\n" + status(c) + " · " + size(c.optLong("unpacked")))
                requirements(form, c)
                i++
            }
            val library: JSONObject? = report.optJSONObject("workspace")
            if (library != null) {
                info(form, t("Библиотека: команды / Alias / заметки / пресеты: ", "Library: commands / aliases / notes / presets: ") + count(library, "commands") + " / " + count(library, "aliases") + " / " + count(library, "notes") + " / " + count(library, "sessions"))
            }
            info(form, t("Свободно: ", "Free: ") + size(report.optLong("free")))
            action(form, t("Пакеты · сравнить версии", "Packages · compare versions"), Runnable { packages(report) })
            action(form, t("Выбрать компоненты восстановления", "Choose restore components"), Runnable { restoreForm(report) })
            backupBuilder(activity).setTitle(t("Проверка бэкапа", "Backup inspection")).setView(scroll(form)).setPositiveButton(WorkspaceText.tr("Закрыть"), null).show()
        } catch (e: Exception) {
            message(errorText("REPORT_FAILED"))
        }
    }

    private fun kindName(kind: String): String {
        when (kind) {
            "home" -> {
                return "Termux HOME"
            }
            "prefix" -> {
                return "Termux PREFIX"
            }
            "ubuntu" -> {
                return "Linux / proot"
            }
            "app-data" -> {
                return t("Файлы истории терминала", "Terminal history files")
            }
            "packages" -> {
                return t("Список пакетов Termux", "Termux package list")
            }
            "data" -> {
                return t("Выбранные данные окружения", "Selected environment data")
            }
            "library" -> {
                return t("Библиотека приложения", "App library")
            }
            else -> {
                return kind
            }
        }
    }

    private fun status(c: JSONObject): String {
        if (c.optString("status").equals("blocked")) {
            return t("ЗАБЛОКИРОВАНО: архитектура или proot-distro не подтверждены", "BLOCKED: architecture or proot-distro unconfirmed")
        }
        if (c.optString("kind").equals("data")) {
            return t("Отдельное извлечение; зависимости и ручная активация", "Staging only; dependencies and manual activation required")
        }
        if (c.optString("kind").equals("prefix")) {
            return t("Только извлечение отдельно; текущий PREFIX не заменяется", "Staging only; current PREFIX is not replaced")
        }
        if (c.optString("kind").equals("ubuntu")) {
            return t("Отдельная среда с новым именем", "Separate environment with a fresh name")
        }
        return t("Перенос в новую папку; пути требуют проверки", "Stage in a new directory; review paths")
    }

    private fun restoreForm(report: JSONObject?) {
        try {
            val form: LinearLayout = column()
            info(form, t("Файлы извлекаются только в новую подпапку выбранного каталога HOME. Текущие HOME/PREFIX/Linux-окружения не заменяются. Для восстановленной Linux-системы создаются минимальные настройки запуска: переменные и команды из OCI-манифеста не переносятся. Файлы системы и настройки пользователя сохраняются. Пресеты после импорта требуют проверки путей и среды перед запуском. Сохранённые пароли SSH Android Keystore перенести нельзя.", "Files are staged only in a new subdirectory under HOME. Current HOME/PREFIX/Linux environments are not replaced. Restored Linux systems get minimal launch metadata; OCI manifest environment variables and commands are not imported. Rootfs files and user settings are preserved. Imported presets require path/environment review before launch. Android Keystore remembered SSH passwords cannot be transferred."))
            val components: JSONArray = report!!.getJSONArray("components")
            val selected: MutableList<CheckBox> = ArrayList<CheckBox>()
            var i = 0
            while (i < components.length()) {
                val c: JSONObject = components.getJSONObject(i)
                val b: CheckBox = check(form, kindName(c.optString("kind")) + " · " + status(c), false)
                b.setEnabled(!c.optString("status").equals("blocked"))
                selected.add(b)
                requirements(form, c)
                i++
            }
            val choice = WorkspaceBackupImport.Choice()
            val library: JSONObject? = report.optJSONObject("workspace")
            val commands: CheckBox = check(form, t("Импортировать команды", "Import commands"), false)
            val notes: CheckBox = check(form, t("Импортировать заметки", "Import notes"), false)
            val presets: CheckBox = check(form, t("Импортировать пресеты запуска", "Import launch presets"), false)
            val history: CheckBox = check(form, t("Перенести файлы истории импортируемых пресетов (нужен app-data)", "Copy history files for imported presets (requires app-data)"), false)
            val settings: CheckBox = check(form, t("Импортировать настройки приложения", "Import app settings"), false)
            val replace: CheckBox = check(form, t("Заменить выбранные библиотеки вместо объединения", "Replace selected libraries instead of merging"), false)
            commands.setEnabled(library != null)
            notes.setEnabled(library != null)
            presets.setEnabled(library != null)
            history.setEnabled(library != null)
            settings.setEnabled(report.optJSONObject("settings") != null)
            action(form, t("Импорт Alias", "Import aliases"), Runnable { aliasImportReport(report) })
            val candidates: JSONArray = report.optJSONArray("candidates")
            val chosen: MutableSet<Int> = HashSet<Int>()
            action(form, t("Aliases и скрипты → добавить в команды", "Aliases and scripts → add to commands"), Runnable { candidatePicker(candidates, chosen) })
            val dest: EditText = field(form, WorkspaceBackupEngine.home(), t("Родительский каталог внутри HOME", "Parent directory inside HOME"))
            action(form, t("Выбрать каталог", "Choose directory"), Runnable { browse(File(dest.getText().toString()), true, Consumer<String>(dest::setText)) })
            val agree: CheckBox = check(form, t("Я доверяю бэкапу и подтверждаю выбранный план", "I trust this backup and confirm the selected plan"), false)
            val dialog: AlertDialog = backupBuilder(activity).setTitle(t("План восстановления", "Restore plan")).setView(scroll(form)).setPositiveButton(t("Подтвердить восстановление", "Confirm restore"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
            dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
                dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                    try {
                        if (!agree.isChecked()) {
                            agree.setError(t("Нужно подтверждение", "Confirmation required"))
                            return@OnClickListener
                        }
                        val indices = JSONArray()
                        var i = 0
                        while (i < selected.size) {
                            if (selected.get(i).isChecked()) {
                                indices.put(i)
                            }
                            i++
                        }
                        choice.commands = commands.isChecked()
                        choice.notes = notes.isChecked()
                        choice.presets = presets.isChecked()
                        choice.history = history.isChecked()
                        choice.settings = settings.isChecked()
                        choice.replace = replace.isChecked()
                        choice.candidates = chosen
                        for (index in chosen) {
                            val c: JSONObject = candidates.getJSONObject(index)
                            if (c.getString("kind").equals("script") && !selected.get(c.getInt("component")).isChecked()) {
                                message(t("Для выбранного скрипта сначала выберите восстановление его HOME/Ubuntu.", "Select the HOME/Ubuntu component containing each chosen script."))
                                return@OnClickListener
                            }
                        }
                        if (indices.length() == 0 && !choice.commands && !choice.notes && !choice.presets && !choice.settings && chosen.isEmpty()) {
                            return@OnClickListener
                        }
                        val req: JSONObject = request("inspect").put("path", report.getString("path"))
                        dialog.dismiss()
                        run(req, Consumer<JSONObject?> { fresh ->
                            try {
                                if (!fresh!!.getJSONObject("fingerprints").toString().equals(report.getJSONObject("fingerprints").toString())) {
                                    message(errorText("BACKUP_CHANGED_RECHECK"))
                                    return@Consumer
                                }
                                if (indices.length() == 0) {
                                    applyImport(fresh, JSONObject(), choice)
                                } else {
                                    run(request("restore").put("path", report.getString("path")).put("fingerprints", report.getJSONObject("fingerprints")).put("selected", indices).put("destination", (dest.getText().toString() as java.lang.String).trim()), Consumer<JSONObject?> { r -> applyImport(fresh, r, choice) })
                                }
                            } catch (e: Exception) {
                                message(errorText("IMPORT_FAILED"))
                            }
                        })
                    } catch (e: Exception) {
                        message(errorText("IMPORT_FAILED"))
                    }
                })
            })
            dialog.show()
        } catch (e: Exception) {
            message(errorText("IMPORT_FAILED"))
        }
    }

    private fun candidatePicker(candidates: JSONArray?, selected: MutableSet<Int>) {
        if (candidates == null || candidates.length() == 0) {
            message(t("Простые aliases и shell-скрипты не найдены.", "No simple aliases or shell scripts found."))
            return
        }
        val labels = arrayOfNulls<String>(candidates.length())
        val values = BooleanArray(labels.size)
        for (i in 0 until (labels.size)) {
            val c: JSONObject = candidates.optJSONObject(i)
            labels[i] = c.optString("kind") + " · " + c.optString("name") + "\n" + c.optString("source")
            values[i] = selected.contains(i)
        }
        backupBuilder(activity).setTitle(t("Добавить в команды", "Add to commands")).setMultiChoiceItems(labels, values, DialogInterface.OnMultiChoiceClickListener { d, w, checked ->
            if (checked) {
                selected.add(w)
            } else {
                selected.remove(w)
            }
        }).setPositiveButton("OK", null).show()
    }

    private fun applyImport(report: JSONObject?, restored: JSONObject?, choice: WorkspaceBackupImport.Choice) {
        val wait: AlertDialog = backupBuilder(activity).setTitle(label(activity)).setMessage(t("Проверка и импорт библиотеки…", "Validating and importing library…")).create()
        wait.setCancelable(false)
        wait.show()
        Thread(Runnable {
            try {
                val prepared: WorkspaceBackupImport.Prepared = WorkspaceBackupImport.prepare(activity, report, restored, choice)
                activity.runOnUiThread(Runnable {
                    try {
                        if (activity.isDestroyed()) {
                            return@Runnable
                        }
                        WorkspaceBackupImport.apply(activity, store, prepared, choice)
                        if (choice.settings) {
                            WorkspaceBackupImport.settings(activity, report!!.optJSONObject("settings"))
                            WorkspaceLanguage.apply(activity)
                        }
                        BackupInventoryCache.SHARED.clear()
                        changed.run()
                        Thread(Runnable {
                            try {
                                store.awaitWrites()
                                val saved = String(readFile(File(activity.getFilesDir(), "workspace-v1.json")), StandardCharsets.UTF_8)
                                if (!WorkspaceBackupImport.persisted(JSONObject(saved), prepared)) {
                                    throw IOException()
                                }
                                activity.runOnUiThread(Runnable {
                                    wait.dismiss()
                                    message(t("Выбранные компоненты восстановлены. Ничего из архива не запускалось. Проверьте пути, настройки и авторизацию. Для части настроек нужно заново открыть приложение.\n", "Selected components restored. No archive content was executed. Review paths, settings and authorization. Some settings require reopening the app.\n") + restored!!.optString("path", t("Импортирована библиотека", "Library imported")) + "\n" + t("Отдельные Linux-окружения добавлены как пресеты запуска. Исходные библиотеки перед импортом сохранены в приватном каталоге приложения.", "Separate Linux environments were added as launch presets. Original libraries were saved in the app's private directory before import."))
                                })
                            } catch (e: Exception) {
                                activity.runOnUiThread(Runnable {
                                    wait.dismiss()
                                    message(errorText("LIBRARY_WRITE_FAILED"))
                                })
                            }
                        }, "jobs-backup-save").start()
                    } catch (e: Exception) {
                        wait.dismiss()
                        message(errorText("IMPORT_FAILED"))
                    }
                })
            } catch (e: Exception) {
                activity.runOnUiThread(Runnable {
                    wait.dismiss()
                    message(t("Файлы могли быть извлечены, но импорт библиотеки не завершён. Проверьте каталог результата.", "Files may have been staged, but library import did not complete. Inspect the result directory."))
                })
            }
        }, "jobs-backup-import").start()
    }

    @Throws(IOException::class)
    private fun readFile(file: File): ByteArray {
        (FileInputStream(file)).use { `in` ->
            (ByteArrayOutputStream()).use { out ->
                val data = ByteArray(8192)
                var n: Int = 0
                while (true) {
                    n = `in`.read(data)
                    if (n == -1) break
                    if (out.size() + n > 16 * 1024 * 1024) {
                        throw IOException()
                    }
                    out.write(data, 0, n)
                }
                return out.toByteArray()
            }
        }
    }

    private fun packages(report: JSONObject?) {
        val source: JSONObject? = report!!.optJSONObject("packages")
        if (source == null || source.length() == 0) {
            message(t("Список пакетов Termux в бэкапе не найден. Пакеты отдельной Ubuntu восстанавливаются с её системой и с текущим Termux не смешиваются.", "No Termux package list found. Separate Ubuntu packages are restored with its rootfs, not mixed into current Termux."))
            return
        }
        try {
            run(request("packages").put("packages", source), Consumer<JSONObject?> { r ->
                val rows: JSONArray? = r!!.optJSONArray("packages")
                if (rows == null || rows.length() == 0) {
                    message(t("Различий в известных версиях не найдено.", "No differences in known package versions."))
                    return@Consumer
                }
                packageSelection(rows)
            })
        } catch (e: Exception) {
            message(errorText("PACKAGE_LIST_FAILED"))
        }
    }

    private fun packageSelection(rows: JSONArray) {
        val selected: MutableSet<Int> = HashSet<Int>()
        val form: LinearLayout = column()
        info(form, t("Сравнение относится к пакетам Termux. Выбирайте только доверенные пакеты. APT может изменить зависимости; план показывается до установки. Текущие репозитории должны содержать выбранные версии.", "Comparison covers Termux packages. Select trusted packages only. APT may change dependencies; review its plan before installing. Selected versions must exist in current repositories."))
        val checks: MutableList<CheckBox> = ArrayList<CheckBox>()
        var i = 0
        while (i < rows.length()) {
            val p: JSONObject = rows.optJSONObject(i)
            val b: CheckBox = check(form, p.optString("name") + "\n" + t("Сейчас: ", "Current: ") + (if (p.optString("current").isEmpty()) t("не установлен", "not installed") else p.optString("current")) + " → " + t("Бэкап: ", "Backup: ") + p.optString("backup"), false)
            b.setEnabled(p.optBoolean("selectable"))
            checks.add(b)
            i++
        }
        action(form, t("Выбрать все доступные различия", "Select all available differences"), Runnable {
            if (checks.stream().filter(Predicate<CheckBox>(View::isEnabled)).count() > 200) {
                message(t("За один план можно выбрать до 200 пакетов. Выберите их вручную несколькими группами.", "Select up to 200 packages per plan. Process larger lists in batches."))
                return@Runnable
            }
            for (b in checks) {
                if (b.isEnabled()) {
                    b.setChecked(true)
                }
            }
        })
        action(form, t("Не заменять ничего", "Do not replace anything"), Runnable {
            for (b in checks) {
                b.setChecked(false)
            }
        })
        backupBuilder(activity).setTitle(t("Пакеты · различия", "Packages · differences")).setView(scroll(form)).setPositiveButton(t("Получить план APT", "Get APT plan"), DialogInterface.OnClickListener { d, w ->
            try {
                val packages = JSONObject()
                var i = 0
                while (i < checks.size) {
                    if (checks.get(i).isChecked()) {
                        val p: JSONObject = rows.getJSONObject(i)
                        packages.put(p.getString("name"), p.getString("backup"))
                    }
                    i++
                }
                if (packages.length() == 0) {
                    return@OnClickListener
                }
                run(request("apt-plan").put("packages", packages), Consumer<JSONObject?> { plan ->
                    val body: LinearLayout = column()
                    info(body, plan!!.optString("plan"))
                    info(body, t("Установка затронет общую среду Termux. Завершите важные задачи. Откроется отдельный терминал; APT дополнительно запросит подтверждение. Старый план может измениться — проверьте его ещё раз в терминале.", "Installation changes the shared Termux environment. Finish important jobs. A separate terminal opens; APT asks for confirmation again. Its plan may have changed; review it again in the terminal."))
                    val prompt: AlertDialog.Builder = backupBuilder(activity).setTitle(t("План APT", "APT plan")).setView(scroll(body)).setNegativeButton(WorkspaceText.tr("Отмена"), null)
                    if (plan.optBoolean("ok")) {
                        prompt.setPositiveButton(t("Подтвердить установку", "Confirm installation"), DialogInterface.OnClickListener { x, y -> packageInstall.accept(plan.optString("command")) })
                    }
                    prompt.show()
                })
            } catch (e: Exception) {
                message(errorText("APT_PLAN_FAILED"))
            }
        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
    }

    companion object {
        @JvmStatic
        fun text(a: Activity, ru: String, en: String): String {
            return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
        }

        @JvmStatic
        fun label(a: Activity): String {
            return text(a, "Резервное копирование", "Backup and restore")
        }

        @JvmStatic
        fun show(a: TermuxActivity, s: WorkspaceStore, changed: Runnable, install: Consumer<String>) {
            WorkspaceBackupUi(a, s, changed, install).menu()
        }

        @JvmStatic
        fun showRestored(a: TermuxActivity, s: WorkspaceStore, changed: Runnable) {
            WorkspaceBackupUi(a, s, changed, Consumer<String> { command ->  }).restoredEnvironments()
        }

        @JvmStatic
        fun backupBuilder(a: Activity): WorkspaceDialogs.Builder {
            return WorkspaceDialogs.builder(a).blockOutsideDismiss()
        }

        @JvmStatic
        private fun aliasError(error: Exception): String {
            val code: String? = error.message
            return if (code != null && (code as java.lang.String).matches("[A-Z_]{1,80}")) code else "ALIAS_IMPORT_FAILED"
        }

        @JvmStatic
        fun restoredProfileInUse(store: WorkspaceStore, alias: String, runningHandles: MutableSet<String>): Boolean {
            for (profile in store.sessions) {
                if (runningHandles.contains(profile!!.handle) && WorkspaceEnvironments.alias(profile).equals(alias)) {
                    return true
                }
            }
            return false
        }

        @JvmStatic
        fun removeRestoredPresets(store: WorkspaceStore, alias: String) {
            if (!(alias as java.lang.String).matches("jobs-[a-f0-9]{12}")) {
                throw IllegalArgumentException("Unconfirmed alias")
            }
            store.sessions.removeIf(Predicate<WorkspaceStore.Session?> { s -> WorkspaceEnvironments.alias(s).equals(alias) })
        }

        @JvmStatic
        fun bindConsent(dialog: AlertDialog, agreement: CheckBox) {
            val create: Button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            create.setEnabled(agreement.isChecked())
            create.setAlpha(if (agreement.isChecked()) 1f else 0.4f)
            agreement.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { button, checked ->
                create.setEnabled(checked)
                create.setAlpha(if (checked) 1f else 0.4f)
            })
        }

        @JvmStatic
        private fun count(v: JSONObject, key: String): Int {
            val a: JSONArray? = v.optJSONArray(key)
            return if (a == null) 0 else a.length()
        }
    }
}
