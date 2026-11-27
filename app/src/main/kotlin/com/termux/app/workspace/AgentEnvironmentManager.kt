package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.BuildConfig
import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.Predicate
import org.json.JSONObject

/** User-triggered rootfs operations. No live-session switching or shell injection.*/
class AgentEnvironmentManager : AutoCloseable {
    private val activity: TermuxActivity
    private val store: WorkspaceStore
    private val live: Predicate<String>
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var closed: Boolean = false
    private var progress: AlertDialog? = null
    @Volatile private var cancelFile: File? = null

    constructor(a: TermuxActivity, s: WorkspaceStore, running: Predicate<String>) {
        activity = a
        store = s
        live = running
    }

    fun t(r: String, e: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) r else e
    }

    fun references(alias: String): MutableList<String?> {
        val names: MutableList<String?> = ArrayList<String?>()
        for (p in store.sessions) {
            if ((p!!.saved || !p.handle.isEmpty()) && alias.equals(AgentEnvironments.scope(p))) {
                names.add(p.name)
            }
        }
        return names
    }

    private fun running(alias: String): Boolean {
        return live.test(alias)
    }

    private fun message(value: String) {
        if (!closed) {
            WorkspaceDialogs.builder(activity).setMessage(value).setPositiveButton(t("Назад", "Back"), null).show()
        }
    }

    @JvmField var freshInstall: Consumer<Consumer<Boolean?>>? = null
    @JvmField var prepareTools: Consumer<Consumer<Boolean?>>? = null

    fun choose(current: String, selected: Consumer<String>) {
        WorkspaceDialogs.builder(activity).setTitle(t("Окружение агента", "Agent environment")).setSingleChoiceItems(arrayOf<String>("Ubuntu", t("Ubuntu AI · для агентов", "Ubuntu AI · for agents")), if (current.equals("ubuntu-ai")) 1 else 0, DialogInterface.OnClickListener { d, w ->
            val alias: String = if (w == 0) "ubuntu" else "ubuntu-ai"
            d.dismiss()
            selected.accept(alias)
        }).setNegativeButton(t("Назад", "Back"), null).show()
    }

    fun migrate(selected: Consumer<String>) {
        val proot = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro")
        if (WorkspaceEnvironments.root(proot, "ubuntu") == null && WorkspaceEnvironments.root(proot, "ubuntu-ai") == null && freshInstall != null) {
            freshInstall!!.accept(Consumer<Boolean?> { ok ->
                if (ok!!) {
                    selected.accept("ubuntu-ai")
                }
            })
            return
        }
        if (running("ubuntu") || running("ubuntu-ai")) {
            message(t("Сначала закройте все сессии и рабочий стол исходной Ubuntu. Активная среда не копируется. Работающие сессии автоматически не останавливаются.", "Close all sessions and the desktop using the source Ubuntu first. A live environment is not copied. Running sessions are never stopped automatically."))
            return
        }
        if (WorkspaceEnvironments.root(File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/lib/proot-distro"), "ubuntu-ai") != null) {
            message(t("Ubuntu AI уже существует. Её файлы не будут перезаписаны. Выберите её в окружении агента.", "Ubuntu AI already exists. Its files will not be overwritten. Select it in the agent environment."))
            return
        }
        run("plan", "ubuntu", "prepare-migration", true, Consumer<JSONObject> { plan ->
            val bytes: Long = plan.optLong("bytes")
            val free: Long = plan.optLong("free")
            val token: String = plan.optString("token")
            var body: String = t("Ubuntu → Ubuntu AI\nБудет скопирована вся файловая система Ubuntu: проекты, файлы, инструменты, история, ключи, пароли и настройки. Секреты останутся в обеих системах. Общая память телефона и другие подключённые извне каталоги не копируются. Android-каталоги /apex, /data/app, /data/dalvik-cache и другие служебные точки подключения исключены заранее; в копии сохраняются пустые каталоги. Служебные подключения сессий и агентов в /tmp также не копируются; пресеты и история в приложении сохраняются. Системы не синхронизируются. Жёстко прописанные пути в скриптах могут потребовать корректировки.\n\n", "Ubuntu → Ubuntu AI\nCopy the entire Ubuntu filesystem: projects, files, tools, history, keys, passwords and settings. Secrets remain in both systems. Phone shared storage and other external mounts are not copied. Android /apex, /data/app, /data/dalvik-cache and other host mount points are excluded in advance; directory placeholders are retained. Session and agent service mounts in /tmp are also excluded; app presets and history are retained. The systems do not synchronize. Hardcoded paths in scripts may need adjustment.\n\n") + t("Объём: ", "Size: ") + size(bytes) + t("\nСвободно: ", "\nFree: ") + size(free) + t("\nИсходная Ubuntu сохраняется. Нужен запас 256 МиБ. Во время копирования не запускайте её вручную.", "\nThe source Ubuntu is retained. An extra 256 MiB is required. Do not start it manually during copying.")
            if (plan.optBoolean("emptyWorkspaceRemoved")) {
                body += "\n\n" + t("Подготовка: удалён только пустой /workspace с правами 000. Файлы не удалялись.", "Preparation removed only an empty mode000 /workspace directory. No files were deleted.")
            }
            val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Миграция среды агента", "Agent environment migration")).setMessage(body).setNegativeButton(t("Назад", "Back"), null).setPositiveButton(t("Копировать", "Copy"), DialogInterface.OnClickListener { d, w ->
                if (running("ubuntu") || running("ubuntu-ai")) {
                    message(error("ENVIRONMENT_BUSY"))
                    return@OnClickListener
                }
                run("clone", "ubuntu", token, true, Consumer<JSONObject> { result ->
                    WorkspaceDialogs.builder(activity).setTitle(t("Ubuntu AI создана и проверена", "Ubuntu AI created and verified")).setMessage(t("Исходная Ubuntu сохранена. Выберите, какие настройки переключить. Текущая форма сохраняется обычной кнопкой сохранения/запуска. Другие файлы приложения и данные общей памяти не переносились.", "The source Ubuntu is retained. Choose which settings to switch. Save the current form with the usual save/launch button. Other app files and shared storage were not moved.")).setPositiveButton(t("Эту сессию / пресет", "This session / preset"), DialogInterface.OnClickListener { x, y -> selected.accept("ubuntu-ai") }).setNeutralButton(t("Все агентские пресеты", "All agent presets"), DialogInterface.OnClickListener { x, y ->
                        if (!store.isWritable()) {
                            message(t("Библиотека недоступна для записи", "Library is not writable"))
                            return@OnClickListener
                        }
                        for (p in store.sessions) {
                            if (!AgentPreparation.agent(p).isEmpty() && p!!.codexEnvironment.equals("ubuntu") && !running(AgentEnvironments.scope(p))) {
                                p.codexEnvironment = "ubuntu-ai"
                            }
                        }
                        store.save()
                        selected.accept("ubuntu-ai")
                    }).setNegativeButton(t("Позже", "Later"), null).show()
                })
            }).create()
            confirm.setCanceledOnTouchOutside(false)
            confirm.show()
            confirm.getButton(-1).setEnabled(free >= bytes + 256L * 1024 * 1024)
        })
    }

    fun remove(choice: WorkspaceEnvironments.Choice?, refresh: Runnable) {
        val alias: String = choice!!.alias
        if (alias.isEmpty() || !WorkspaceLocalFilePicker.validAlias(alias) || !choice.installed) {
            return
        }
        if (running(alias)) {
            message(error("ENVIRONMENT_BUSY"))
            return
        }
        val refs: MutableList<String?> = references(alias)
        if (!refs.isEmpty()) {
            message(t("Окружение используется сессиями или пресетами. Сначала переключите их на другую среду либо удалите пресеты:\n• ", "The environment is referenced by sessions or presets. Switch them to another environment or remove those presets first:\n• ") + java.lang.String.join("\n• ", refs))
            return
        }
        run("plan", alias, "", true, Consumer<JSONObject> { plan ->
            val confirm: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Удалить ", "Delete ") + choice.label + "?").setMessage(t("Навсегда удалятся все файлы, проекты, пакеты, ключи и настройки этого окружения.\n", "Permanently delete all files, projects, packages, keys and settings in this environment.\n") + choice.alias + " · " + size(plan.optLong("bytes")) + t("\nАрхивы резервных копий и общая память телефона сохраняются. Произвольные команды со вписанным вручную именем системы автоматически не распознаются. Отменить начавшееся удаление нельзя.", "\nBackup archives and phone shared storage are retained. Arbitrary commands containing a manually typed system name are not detected automatically. Deletion cannot be cancelled once started.")).setNegativeButton(t("Назад", "Back"), null).setPositiveButton(t("Удалить навсегда", "Delete permanently"), DialogInterface.OnClickListener { d, w ->
                if (running(alias) || !references(alias).isEmpty()) {
                    message(error("ENVIRONMENT_BUSY"))
                    return@OnClickListener
                }
                run("remove", alias, plan.optString("token"), false, Consumer<JSONObject> { result -> refresh.run() })
            }).create()
            confirm.setCanceledOnTouchOutside(false)
            confirm.show()
        })
    }

    private fun size(b: Long): String {
        return Formatter.formatShortFileSize(activity, b)
    }

    private fun problem(code: String, retry: Runnable) {
        if ((code.equals("MIGRATION_TOOLS_REQUIRED") || code.equals("NATIVE_LOCKS_REQUIRED")) && prepareTools != null) {
            WorkspaceDialogs.builder(activity).setMessage(error(code)).setNegativeButton(t("Назад", "Back"), null).setPositiveButton(t("Подготовить автоматически", "Prepare automatically"), DialogInterface.OnClickListener { d, w ->
                prepareTools!!.accept(Consumer<Boolean?> { ok ->
                    if (ok!! && !closed) {
                        retry.run()
                    }
                })
            }).show()
        } else {
            message(error(code))
        }
    }

    private fun run(op: String, alias: String, token: String, cancellable: Boolean, done: Consumer<JSONObject>) {
        if (closed) {
            return
        }
        if (op.equals("remove") && (running(alias) || !references(alias).isEmpty())) {
            message(error("ENVIRONMENT_BUSY"))
            return
        }
        if (!File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "python3").canExecute() || !File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro").canExecute()) {
            problem("MIGRATION_TOOLS_REQUIRED", Runnable { run(op, alias, token, cancellable, done) })
            return
        }
        val scopes: MutableSet<String> = HashSet<String>()
        scopes.add(alias)
        if (op.equals("clone")) {
            scopes.add("ubuntu-ai")
        }
        synchronized(operating) {
            for (s in scopes) {
                if (operating.contains(s)) {
                    message(error("ENVIRONMENT_BUSY"))
                    return
                }
            }
            operating.addAll(scopes)
        }
        val body = LinearLayout(activity)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(24, 16, 24, 16)
        val bar = ProgressBar(activity, null, R.attr.progressBarStyleHorizontal)
        bar.setIndeterminate(true)
        body.addView(bar)
        val status = TextView(activity)
        status.setTextColor(Pal.TEXT)
        status.setText(if (token.equals("prepare-migration")) t("Подготовка и проверка. Только пустой /workspace с правами 000 может быть удалён автоматически; файлы не удаляются.", "Preparing and checking. Only an empty mode000 /workspace directory may be removed automatically; no files are deleted.") else t("Проверка…", "Checking…"))
        body.addView(status)
        val wait: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(if (op.equals("remove")) t("Удаление окружения", "Deleting environment") else if (op.equals("plan")) t("Сканирование окружения", "Scanning environment") else t("Миграция среды агента", "Agent environment migration")).setView(body).setNegativeButton(if (cancellable) t("Отмена", "Cancel") else t("Назад", "Back"), null).create()
        progress = wait
        wait.setCancelable(false)
        wait.setCanceledOnTouchOutside(false)
        wait.show()
        wait.getButton(-2).setEnabled(cancellable)
        val stop = AtomicBoolean()
        wait.getButton(-2).setOnClickListener(View.OnClickListener { v ->
            stop.set(true)
            val f: File? = cancelFile
            if (f != null) {
                try {
                    f.createNewFile()
                } catch (ignored: IOException) {
                }
            }
            wait.getButton(-2).setEnabled(false)
            status.setText(t("Отмена и очистка незавершённой копии…", "Cancelling and cleaning the incomplete copy…"))
        })
        worker.execute(Runnable {
            var result: JSONObject? = null
            var failure = "OPERATION_FAILED"
            var location = ""
            var marker: File? = null
            var child: Process? = null
            try {
                val dir = File(activity.getFilesDir(), ".agent-migration")
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw IOException()
                }
                if (!dir.getCanonicalFile().equals(File(activity.getFilesDir().getCanonicalFile(), ".agent-migration"))) {
                    throw IOException()
                }
                protect(dir)
                val script = File(dir, "runtime.py")
                val temp: File = File.createTempFile("runtime-", ".tmp", dir)
                try {
                    (activity.getAssets().open("agent-environment.py")).use { `in` ->
                        (FileOutputStream(temp)).use { out ->
                            val b = ByteArray(8192)
                            var n: Int = 0
                            while (true) {
                                n = `in`.read(b)
                                if (n == -1) break
                                out.write(b, 0, n)
                            }
                        }
                    }
                    protect(temp)
                    if (!temp.renameTo(script)) {
                        throw IOException()
                    }
                } finally {
                    temp.delete()
                }
                marker = File(dir, "cancel-" + UUID.randomUUID())
                cancelFile = marker
                if (stop.get() || closed) {
                    marker.createNewFile()
                }
                val pb = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/python3", script.getAbsolutePath(), op, alias, token, marker.getAbsolutePath())
                pb.environment().clear()
                for (v in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                    val eq: Int = v.indexOf('=')
                    if (eq > 0) {
                        pb.environment().put(v.substring(0, eq), v.substring(eq + 1))
                    }
                }
                pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
                pb.environment().put("PYTHONDONTWRITEBYTECODE", "1")
                pb.redirectError(File("/dev/null"))
                val p: Process = pb.start()
                child = p
                p.getOutputStream().close()
                val reader = BufferedReader(InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                var primaryException: Throwable? = null
                try {
                    var line: String? = null
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        if (line.length > 8192) {
                            throw IOException()
                        }
                        val event = JSONObject(line)
                        val kind: String = event.optString("event")
                        if (kind.equals("progress")) {
                            activity.runOnUiThread(Runnable {
                                if (closed || progress !== wait || !wait.isShowing()) {
                                    return@Runnable
                                }
                                val total: Long = event.optLong("total")
                                val value: Long = event.optLong("done")
                                bar.setIndeterminate(total <= 0)
                                if (total > 0) {
                                    bar.setMax(1000)
                                    bar.setProgress(Math.min(1000L, value * 1000 / total).toInt())
                                }
                                val phase: String = event.optString("phase")
                                status.setText((if (phase.equals("copy")) t("Копирование", "Copying") else if (phase.equals("verify")) t("Проверка файлов", "Verifying files") else if (phase.equals("publish")) t("Завершение", "Finishing") else t("Сканирование", "Scanning")) + "\n" + size(value) + (if (total > 0) " / " + size(total) else ""))
                            })
                        } else if (kind.equals("error")) {
                            failure = event.optString("code", "OPERATION_FAILED") + (if (event.has("errno")) "_ERRNO_" + event.optInt("errno") else "")
                            location = scanDetails(event)
                        } else if (kind.equals("plan") || kind.equals("done")) {
                            result = event
                        }
                    }
                } catch (t: Throwable) {
                    primaryException = t
                    throw t
                } finally {
                    if (primaryException != null) {
                        try {
                            reader.close()
                        } catch (suppressed: Throwable) {
                            primaryException.addSuppressed(suppressed)
                        }
                    } else {
                        reader.close()
                    }
                }
                if (p.waitFor() != 0) {
                    result = null
                }
            } catch (ignored: Exception) {
            } finally {
                if (child != null && child.isAlive()) {
                    if (marker != null) {
                        try {
                            marker.createNewFile()
                        } catch (ignored: IOException) {
                        }
                    }
                    try {
                        child.waitFor()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (marker != null) {
                    marker.delete()
                }
                cancelFile = null
                synchronized(operating) {
                    operating.removeAll(scopes)
                }
            }
            val answer: JSONObject? = result
            val reason: String = failure
            val detail: String = location
            activity.runOnUiThread(Runnable {
                if (progress === wait) {
                    progress = null
                }
                wait.dismiss()
                if (closed) {
                    return@Runnable
                }
                if (stop.get() && op.equals("plan")) {
                    message(error("CANCELLED"))
                    return@Runnable
                }
                if (answer == null) {
                    if (detail.isEmpty()) {
                        problem(reason, Runnable { run(op, alias, token, cancellable, done) })
                    } else {
                        message(error(reason) + detail)
                    }
                } else {
                    done.accept(answer)
                }
            })
        })
    }

    fun scanDetails(event: JSONObject): String {
        if (!event.optString("code").equals("SCAN_ACCESS_DENIED") || !event.has("scanPath")) {
            return ""
        }
        val path: String = event.optString("scanPath")
        val op: String = event.optString("scanOperation")
        if (path.startsWith("/") || Arrays.asList(*(path as java.lang.String).split("/", -1)).contains("..") || !Arrays.asList("list", "stat", "open-directory").contains(op)) {
            return ""
        }
        val safe = StringBuilder()
        var i = 0
        while (i < Math.min(path.length, 2048)) {
            val c: Char = path[i]
            if (Character.isISOControl(c) || c.code >= 0x202a && c.code <= 0x202e || c.code >= 0x2066 && c.code <= 0x2069) {
                safe.append(java.lang.String.format(Locale.ROOT, "\\u%04x", c.code))
            } else {
                safe.append(c)
            }
            i++
        }
        if (path.length > 2048) {
            safe.append("…")
        }
        val action: String = if (op.equals("list")) t("чтение списка каталога", "listing directory") else if (op.equals("stat")) t("чтение атрибутов", "reading metadata") else t("открытие каталога", "opening directory")
        return "\n\n" + t("Операция: ", "Operation: ") + action + "\n" + t("Путь внутри исходного контейнера: ", "Path inside source container: ") + (if (path.isEmpty()) t("корень контейнера", "container root") else safe.toString()) + "\n" + t("Версия проверки: ", "Diagnostic version: ") + BuildConfig.VERSION_NAME
    }

    fun error(code: String): String {
        if (code.contains("_ERRNO_")) {
            val parts: Array<String> = (code as java.lang.String).split("_ERRNO_", 2)
            return error(parts[0]) + "\n" + t("Системный код: ", "System code: ") + parts[1]
        }
        when (code) {
            "MIGRATION_TOOLS_REQUIRED" -> {
                return t("В Termux отсутствует Python или proot-distro. Можно подготовить необходимые компоненты автоматически.", "Python or proot-distro is missing in Termux. Required components can be prepared automatically.")
            }
            "STORAGE_ACCESS_DENIED" -> {
                return t("Android отказал в доступе к пути хранилища proot-distro. Это ошибка доступа, а не признак отсутствия пакетов. Код: STORAGE_ACCESS_DENIED", "Android denied access to the proot-distro storage path. This is an access failure, not evidence of missing packages. Code: STORAGE_ACCESS_DENIED")
            }
            "PROCESS_CHECK_DENIED" -> {
                return t("Android не разрешил проверить процессы. Без проверки занятости миграция не запускается. Код: PROCESS_CHECK_DENIED", "Android denied process inspection. Migration cannot start without the busy check. Code: PROCESS_CHECK_DENIED")
            }
            "ENVIRONMENT_PATH_MISSING" -> {
                return t("Не найден путь исходного окружения в containers либо обязательный файл системы. Нужна проверка структуры среды. Код: ENVIRONMENT_PATH_MISSING", "The source containers environment path or a required system file is missing. Check the environment layout. Code: ENVIRONMENT_PATH_MISSING")
            }
            "LOCKS_ACCESS_DENIED" -> {
                return t("Нет доступа к каталогу или файлу блокировки proot-distro. Файлы Ubuntu ещё не сканировались. Код: LOCKS_ACCESS_DENIED", "Access to the proot-distro lock directory or file was denied. Ubuntu files have not been scanned. Code: LOCKS_ACCESS_DENIED")
            }
            "SCAN_ACCESS_DENIED" -> {
                return t("Отказ при сканировании каталогов или атрибутов файлов Ubuntu. Содержимое ключей на этом этапе не читается. Код: SCAN_ACCESS_DENIED", "Access denied while scanning Ubuntu directories or file metadata. Key contents are not read at this stage. Code: SCAN_ACCESS_DENIED")
            }
            "ENVIRONMENT_ACCESS_DENIED" -> {
                return t("Нет доступа к структуре каталогов окружения. Код: ENVIRONMENT_ACCESS_DENIED", "Access to the environment directory structure was denied. Code: ENVIRONMENT_ACCESS_DENIED")
            }
            "COPY_ACCESS_DENIED" -> {
                return t("Отказ в доступе при копировании или проверке файлов. Исходная Ubuntu сохранена. Код: COPY_ACCESS_DENIED", "Access denied during file copying or verification. The source Ubuntu is retained. Code: COPY_ACCESS_DENIED")
            }
            "FILES_ACCESS_DENIED" -> {
                return t("Нет доступа к одному из файлов или каталогов окружения. Установка пакетов не предлагается, так как причина в доступе. Код: FILES_ACCESS_DENIED", "An environment file or directory is inaccessible. Package installation is not suggested for an access failure. Code: FILES_ACCESS_DENIED")
            }
            "FILESYSTEM_ERROR" -> {
                return t("Файловая операция завершилась ошибкой; системный код приведён ниже. Код: FILESYSTEM_ERROR", "Filesystem operation failed; see the system code below. Code: FILESYSTEM_ERROR")
            }
            "CANCELLED" -> {
                return t("Отменено. Исходная среда сохранена; незавершённая копия удалена.", "Cancelled. The source is retained; the incomplete copy was removed.")
            }
            "ENVIRONMENT_BUSY" -> {
                return t("Среда занята. Закройте её сессии, рабочий стол и пакетные операции, затем повторите. Процессы автоматически не останавливаются.", "Environment busy. Close its sessions, desktop and package operations, then retry. Processes are not stopped automatically.")
            }
            "SOURCE_CHANGED" -> {
                return t("Файлы среды изменились. Повторите сканирование при закрытых сессиях.", "Environment files changed. Scan again with sessions closed.")
            }
            "DESTINATION_EXISTS" -> {
                return t("Ubuntu AI уже существует; перезапись запрещена.", "Ubuntu AI already exists; overwriting is refused.")
            }
            "NATIVE_LOCKS_REQUIRED" -> {
                return t("Нужен proot-distro 5 с блокировками и хранилищем containers. Обновите пакет proot-distro в Termux и повторите. Старые среды не изменены.", "Requires proot-distro 5 with locks and containers storage. Update proot-distro in Termux and retry. Existing systems are unchanged.")
            }
            "DELETE_INCOMPLETE" -> {
                return t("Удаление завершилось ошибкой и могло удалить часть файлов. Остаток находится в скрытом служебном каталоге .jobs-delete-* внутри хранилища proot-distro. Другие окружения не удалялись.", "Deletion failed and may have removed some files. The remainder is in a hidden .jobs-delete-* service directory in proot-distro storage. Other systems were not removed.")
            }
            "UNSUPPORTED_SPECIAL_FILE" -> {
                return t("В среде есть специальный файл, который нельзя безопасно скопировать. Перенос остановлен; исходная система сохранена.", "The system contains a special file which cannot be safely copied. Migration stopped; the source is retained.")
            }
            "NOT_ENOUGH_SPACE" -> {
                return t("Недостаточно свободного места для полной копии и запаса 256 МиБ.", "Not enough free storage for the full copy plus 256 MiB reserve.")
            }
            else -> {
                return t("Операция не завершена. Исходная Ubuntu не удаляется при миграции. Не удалось определить точный этап сбоя. Код: ", "Operation incomplete. Migration never deletes the source Ubuntu. The exact failing stage could not be determined. Code: ") + code
            }
        }
    }

    override fun close() {
        closed = true
        val f: File? = cancelFile
        if (f != null) {
            try {
                f.createNewFile()
            } catch (ignored: IOException) {
            }
        }
        if (progress != null) {
            progress!!.dismiss()
        }
        worker.shutdown()
    }

    companion object {
        @JvmField val operating: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @JvmStatic
        fun busy(p: WorkspaceStore.Session?): Boolean {
            return operating.contains(AgentEnvironments.scope(p))
        }

        @Throws(IOException::class)
        @JvmStatic
        private fun protect(f: File) {
            if (!f.setReadable(false, false) || !f.setWritable(false, false) || !f.setExecutable(false, false) || !f.setReadable(true, true) || !f.setWritable(true, true) || f.isDirectory() && !f.setExecutable(true, true)) {
                throw IOException("Private permissions unavailable")
            }
        }
    }
}
