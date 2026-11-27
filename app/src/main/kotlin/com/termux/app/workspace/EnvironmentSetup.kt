package com.termux.app.workspace

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/** Explicit local environment checks/setup, independent of all AI transports.*/
class EnvironmentSetup : AutoCloseable {
    class Result {
        @JvmField val report: String
        @JvmField val code: Int

        constructor(text: String, status: Int) {
            report = text
            code = status
        }

        fun ready(): Boolean {
            return code == 0 && report.contains("CHECK:done") && report.contains("READY:environment") && report.contains("READY:shell") && !report.contains("MISSING:")
        }
    }

    private val activity: TermuxActivity
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main: Handler = Handler(Looper.getMainLooper())
    private var closed: Boolean = false
    private var running: Boolean = false
    private var progress: AlertDialog? = null

    fun interface InstallationRunner {
        @Throws(Exception::class)
        fun run(alias: String, update: Consumer<String>, cancel: File): Result
    }

    private val installationRunner: InstallationRunner?
    private val inventory: Supplier<MutableList<WorkspaceEnvironments.Choice>>

    constructor(a: TermuxActivity) : this(a, Supplier<MutableList<WorkspaceEnvironments.Choice>>(WorkspaceEnvironments::choices))

    constructor(a: TermuxActivity, choices: Supplier<MutableList<WorkspaceEnvironments.Choice>>) : this(a, choices, null)

    constructor(a: TermuxActivity, choices: Supplier<MutableList<WorkspaceEnvironments.Choice>>, runner: InstallationRunner?) {
        activity = a
        inventory = choices
        installationRunner = runner
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    @JvmField var removal: BiConsumer<WorkspaceEnvironments.Choice?, Runnable>? = null

    private class InstallJob {
        @JvmField val alias: String
        @JvmField val cancel: File
        @JvmField val progress: EnvironmentInstallProgress = EnvironmentInstallProgress()
        @JvmField var active: Boolean = true
        @JvmField var stopping: Boolean = false
        @JvmField var success: Boolean = false
        @JvmField var resuming: Boolean = false
        @JvmField var report: String = ""
        @JvmField var log: String = ""

        constructor(alias: String, cancel: File) {
            this.alias = alias
            this.cancel = cancel
        }
    }

    private val installations: MutableMap<String, InstallJob> = HashMap<String, InstallJob>()
    private val cardUpdates: MutableMap<String, MutableList<Runnable>> = HashMap<String, MutableList<Runnable>>()

    private fun resumable(alias: String): Boolean {
        if (!WorkspaceLocalFilePicker.validAlias(alias)) {
            return false
        }
        val p: SharedPreferences = activity.getSharedPreferences("environment-install-attempts", 0)
        return p.getBoolean("pending." + alias, false) || !p.getBoolean("completed." + alias, false) && File(activity.getFilesDir(), ".environment-control/downloads/" + alias + "/state.json").isFile()
    }

    private fun recordAttempt(alias: String, complete: Boolean) {
        activity.getSharedPreferences("environment-install-attempts", 0).edit().putBoolean("pending." + alias, !complete).putBoolean("completed." + alias, complete).apply()
        if (complete) {
            try {
                val base = File(activity.getFilesDir().getCanonicalFile(), ".environment-control")
                if (!base.getCanonicalPath().equals(base.getAbsolutePath())) {
                    return
                }
                val state = File(base, "downloads/" + alias + "/state.json")
                if (state.getCanonicalPath().equals(state.getAbsolutePath())) {
                    state.delete()
                }
            } catch (ignored: IOException) {
            }
        }
    }

    private fun installed(choice: WorkspaceEnvironments.Choice?): Boolean {
        val job: InstallJob? = installations.get(choice!!.alias)
        return if (job == null) choice.installed && !resumable(choice.alias) else job.success
    }

    private fun refreshCard(alias: String) {
        for (update in ArrayList<Runnable>(cardUpdates.getOrDefault(alias, Collections.emptyList()))) {
            update.run()
        }
    }

    private fun stopInstall(job: InstallJob) {
        if (!job.active || job.stopping) {
            return
        }
        try {
            if (!job.cancel.createNewFile() && !job.cancel.isFile()) {
                throw IOException()
            }
            job.stopping = true
            refreshCard(job.alias)
        } catch (error: IOException) {
            activity.showToast(t("Не удалось запросить остановку", "Could not request stop"), true)
        }
    }

    private fun storeInstallLog(alias: String, answer: Result): String {
        val log: String = if (answer.ready()) "" else EnvironmentInstallLog.report(alias, answer.code, answer.report)
        try {
            if (answer.ready()) {
                EnvironmentInstallLog.clear(activity.getFilesDir(), alias)
            } else {
                EnvironmentInstallLog.save(activity.getFilesDir(), alias, log)
            }
        } catch (ignored: IOException) {
            /* The current report remains available in memory.*/
        }
        return log
    }

    private fun startInstall(choice: WorkspaceEnvironments.Choice?) {
        if (closed) {
            return
        }
        if (running) {
            activity.showToast(t("Дождитесь текущей операции", "Wait for the current operation"), true)
            return
        }
        val directory = File(activity.getFilesDir(), ".environment-control")
        if (!directory.isDirectory() && !directory.mkdirs()) {
            activity.showToast(t("Нет доступа к папке установки", "Cannot access installation directory"), true)
            return
        }
        var jobDirectory: File
        try {
            jobDirectory = executionDirectory(directory)
        } catch (error: IOException) {
            activity.showToast(t("Нет доступа к папке установки", "Cannot access installation directory"), true)
            return
        }
        val job = InstallJob(choice!!.alias, File(jobDirectory, "cancel-" + UUID.randomUUID()))
        job.resuming = resumable(choice.alias)
        recordAttempt(choice.alias, false)
        installations.put(choice.alias, job)
        running = true
        refreshCard(choice.alias)
        worker.execute(Runnable {
            var result: Result
            try {
                val update = Consumer<String> { report ->
                    main.post(Runnable {
                        job.report = report
                        job.progress.update(report, SystemClock.elapsedRealtime())
                        if (!closed) {
                            refreshCard(choice.alias)
                        }
                    })
                }
                result = if (installationRunner == null) execute(choice.alias, true, update, job) else installationRunner.run(choice.alias, update, job.cancel)
            } catch (error: Exception) {
                result = Result("ERROR:SETUP_FAILED", 1)
            } finally {
                cleanExecution(job.cancel.getParentFile(), job.cancel)
            }
            val answer: Result = result
            val log: String = storeInstallLog(choice.alias, answer)
            main.post(Runnable {
                job.active = false
                job.cancel.delete()
                job.success = answer.ready()
                recordAttempt(choice.alias, job.success)
                job.report = EnvironmentInstallLog.clean(answer.report)
                job.log = log
                running = false
                if (!closed) {
                    refreshCard(choice.alias)
                }
            })
        })
    }

    private fun offerInstall(choice: WorkspaceEnvironments.Choice?) {
        val job: InstallJob? = installations.get(choice!!.alias)
        if (job != null && job.active) {
            stopInstall(job)
            return
        }
        WorkspaceDialogs.builder(activity).setTitle((if (resumable(choice.alias)) t("Продолжить установку ", "Resume installing ") else t("Установить ", "Install ")) + choice.label + "?").setMessage(t("Система будет скачана и установлена как отдельное окружение внутри AgentDroid. Другое приложение Termux устанавливать не нужно. Нужны интернет и свободное место. Установку выполняет штатный proot-distro. Он повторно использует целиком скачанные и проверенные слои; оборванный слой может скачиваться заново. Если proot-distro отсутствует, установим его. Уже установленные системы не заменяются. Поддержка зависит от версии proot-distro и архитектуры; недоступный образ будет отклонён.", "Download and install a separate environment inside AgentDroid. No separate Termux app is needed. Internet and free storage are required. Installation uses the native proot-distro CLI. It can reuse complete verified layers; an interrupted layer may download again. Missing proot-distro will be installed. Existing systems are never replaced. Availability depends on proot-distro and architecture; unavailable images are rejected.") + (if (choice.alias.equals("archlinux")) t("\nНа ARM64 устанавливается Arch Linux ARM из архива Termux, с проверкой SHA-256. Это отдельный проект от Arch Linux для x86-64.", "\nOn ARM64, install Arch Linux ARM from a Termux archive with SHA-256 verification. It is a separate project from x86-64 Arch Linux.") else "")).setPositiveButton(if (resumable(choice.alias)) t("Продолжить", "Resume") else t("Установить", "Install"), DialogInterface.OnClickListener { d, w -> startInstall(choice) }).setNegativeButton(t("Назад", "Back"), null).show()
    }

    private fun showInstallLog(choice: WorkspaceEnvironments.Choice?) {
        val job: InstallJob? = installations.get(choice!!.alias)
        if (closed || job != null && job.active) {
            return
        }
        if (job != null && !job.log.isEmpty()) {
            EnvironmentInstallLogUi.show(activity, choice.label, job.log)
            return
        }
        worker.execute(Runnable {
            var report: String
            try {
                report = EnvironmentInstallLog.read(activity.getFilesDir(), choice.alias)
            } catch (e: IOException) {
                report = ""
            }
            val snapshot: String = report
            main.post(Runnable {
                if (closed) {
                    return@Runnable
                }
                if (snapshot.isEmpty()) {
                    activity.showToast(t("Журнал недоступен", "Log unavailable"), true)
                } else {
                    EnvironmentInstallLogUi.show(activity, choice.label, snapshot)
                }
            })
        })
    }

    fun choose(selected: Consumer<WorkspaceEnvironments.Choice>) {
        val choices: MutableList<WorkspaceEnvironments.Choice> = ArrayList<WorkspaceEnvironments.Choice>(inventory.get())
        val bindings: MutableMap<String, MutableList<Runnable>> = HashMap<String, MutableList<Runnable>>()
        val chooserOpen: BooleanArray = booleanArrayOf(true)
        val rows: ArrayAdapter<WorkspaceEnvironments.Choice> = object : ArrayAdapter<WorkspaceEnvironments.Choice>(activity, 0, choices) {
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val choice: WorkspaceEnvironments.Choice? = getItem(position)
                val gap: Int = WorkspaceDialogs.dp(activity, 8.0f)
                val outer = FrameLayout(activity)
                outer.setPadding(gap * 2, gap / 2, gap * 2, gap / 2)
                val card = LinearLayout(activity)
                card.setOrientation(LinearLayout.VERTICAL)
                card.setPadding(gap * 2, gap, gap, gap)
                card.setBackground(WorkspaceDialogs.background(activity, false))
                val row = LinearLayout(activity)
                row.setGravity(Gravity.CENTER_VERTICAL)
                val label = TextView(activity)
                label.setText(choice!!.label)
                label.setTextSize(15.0f)
                label.setTextColor(Pal.TEXT)
                label.setMaxLines(2)
                val labels = LinearLayout(activity)
                labels.setOrientation(LinearLayout.VERTICAL)
                labels.addView(label)
                if (choice.alias.equals("ubuntu-ai")) {
                    val subtitle = TextView(activity)
                    subtitle.setText(t("Используется для AI-агентов", "Used for AI agents"))
                    subtitle.setTextSize(12.0f)
                    subtitle.setTextColor(Pal.MUTED)
                    labels.addView(subtitle)
                }
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1.0f))
                val more: TextView = WorkspaceDialogs.action(activity, t("Подробнее", "Details"), 0)
                more.setTextSize(12.0f)
                more.setGravity(Gravity.CENTER)
                more.setMinHeight(WorkspaceDialogs.dp(activity, 44.0f))
                more.setPadding(gap / 2, gap, gap / 2, gap)
                more.setTag("environment-install-details-" + choice.alias)
                row.addView(more, LinearLayout.LayoutParams(-2, -2))
                more.setOnClickListener(View.OnClickListener { v -> showInstallLog(choice) })
                val badge: TextView = WorkspaceDialogs.action(activity, "", 0)
                badge.setTextSize(12.0f)
                badge.setGravity(Gravity.CENTER)
                badge.setMinHeight(WorkspaceDialogs.dp(activity, 44.0f))
                badge.setPadding(gap, gap, gap, gap)
                badge.setTag("environment-install-" + choice.alias)
                val badgeParams = LinearLayout.LayoutParams(-2, -2)
                badgeParams.leftMargin = gap
                row.addView(badge, badgeParams)
                badge.setOnClickListener(View.OnClickListener { v -> offerInstall(choice) })
                val trash = WorkspaceIconButton(activity)
                trash.setIcon(activity.getDrawable(R.drawable.workspace_delete)!!.mutate(), WorkspaceDialogs.dp(activity, 20.0f))
                trash.setTextColor(Pal.DANGER)
                trash.setFocusable(false)
                trash.setFocusableInTouchMode(false)
                trash.setContentDescription(t("Удалить ", "Delete ") + choice.label)
                row.addView(trash, LinearLayout.LayoutParams(gap * 6, gap * 6))
                trash.setOnClickListener(View.OnClickListener { v ->
                    if (removal != null) {
                        removal!!.accept(choice, Runnable {
                            installations.remove(choice.alias)
                            choices.clear()
                            choices.addAll(inventory.get())
                            notifyDataSetChanged()
                        })
                    }
                })
                card.addView(row)
                val detail = LinearLayout(activity)
                detail.setOrientation(LinearLayout.VERTICAL)
                detail.setTag("environment-progress-" + choice.alias)
                val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
                bar.setMax(100)
                detail.addView(bar, LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(activity, 10.0f)))
                val remaining = TextView(activity)
                remaining.setTextColor(Pal.MUTED)
                remaining.setTextSize(12.0f)
                detail.addView(remaining)
                val line = TextView(activity)
                line.setTextSize(11.0f)
                line.setTextColor(Pal.MUTED)
                line.setSingleLine(true)
                line.setEllipsize(TextUtils.TruncateAt.MARQUEE)
                line.setMarqueeRepeatLimit(-1)
                detail.addView(line)
                card.addView(detail)
                val update = Runnable {
                    val job: InstallJob? = installations.get(choice.alias)
                    val active: Boolean = job != null && job.active
                    val ready: Boolean = installed(choice)
                    more.setVisibility(if (!active && !ready && (job != null && !job.log.isEmpty() || EnvironmentInstallLog.exists(activity.getFilesDir(), choice.alias))) View.VISIBLE else View.GONE)
                    badge.setText(if (active) if (job!!.stopping) t("Остановка…", "Stopping…") else t("Стоп", "Stop") else if (ready) t("Установлено", "Installed") else if (resumable(choice.alias)) t("Продолжить", "Resume") else t("Установить", "Install"))
                    badge.setEnabled(!ready && (!active || !job!!.stopping))
                    badge.setTextColor(if (ready) Pal.MUTED else Pal.ACCENT)
                    badge.setAlpha(if (ready) 0.6f else 1f)
                    trash.setVisibility(if ((ready || choice.installed) && !active && !choice.alias.isEmpty() && removal != null) View.VISIBLE else View.GONE)
                    detail.setVisibility(if (job != null && !job.success) View.VISIBLE else View.GONE)
                    bar.setVisibility(if (active) View.VISIBLE else View.GONE)
                    line.setSelected(active)
                    if (job != null) {
                        val percent: Int = job.progress.percent
                        bar.setIndeterminate(percent < 0)
                        if (percent >= 0) {
                            bar.setProgress(percent)
                        }
                        remaining.setText(if (active) if (job.stopping) t("Остановка установщика…", "Stopping installer…") else t("Осталось на этапе: ", "Remaining in this phase: ") + (if (job.progress.eta < 0) t("рассчитывается", "calculating") else "≈ " + job.progress.eta + t(" с", " s")) else if (job.report.contains("INSTALL_CANCELLED")) t("Установка остановлена", "Installation stopped") else t("Установка не завершена", "Installation did not finish"))
                        var failure: String = EnvironmentInstallProgress.failureLine(job.report)
                        if (failure.equals("ERROR:INCOMPLETE_ROOTFS_REQUIRES_REMOVAL")) {
                            failure = t("Осталась незавершённая распаковка. Удалите это неполное окружение перед продолжением; скачанные данные сохранятся.", "An incomplete extraction remains. Remove this incomplete environment before resuming; downloaded data will be retained.")
                        } else if (failure.equals("MISSING:shell")) {
                            failure = t("Папка системы найдена, но её оболочка отсутствует. Возможно, установка была прервана.", "System directory found, but its shell is missing. Installation may have been interrupted.")
                        } else if (failure.equals("MISSING:environment")) {
                            failure = t("Папка установленной системы не найдена.", "Installed system directory not found.")
                        } else if (failure.equals("MISSING:proot-distro")) {
                            failure = t("Не найден proot-distro.", "proot-distro not found.")
                        }
                        line.setText(if (active) job.progress.line else if (failure.isEmpty()) t("Причина не указана установщиком", "Installer did not report the cause") else failure)
                    }
                }
                // ListView also asks for unattached rows during measurement. Those must
                // never steal the subscription from a card actually visible on screen.
                outer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        if (closed || !chooserOpen[0]) {
                            return
                        }
                        val local: MutableList<Runnable> = bindings.computeIfAbsent(choice.alias, Function<String, MutableList<Runnable>> { k -> ArrayList<Runnable>() })
                        val global: MutableList<Runnable> = cardUpdates.computeIfAbsent(choice.alias, Function<String, MutableList<Runnable>> { k -> ArrayList<Runnable>() })
                        if (!local.contains(update)) {
                            local.add(update)
                        }
                        if (!global.contains(update)) {
                            global.add(update)
                        }
                        update.run()
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        val local: MutableList<Runnable>? = bindings.get(choice.alias)
                        if (local != null) {
                            local.remove(update)
                        }
                        val global: MutableList<Runnable>? = cardUpdates.get(choice.alias)
                        if (global != null) {
                            global.remove(update)
                            if (global.isEmpty()) {
                                cardUpdates.remove(choice.alias)
                            }
                        }
                    }
                })
                update.run()
                outer.addView(card, FrameLayout.LayoutParams(-1, -2))
                return outer
            }
        }
        val chooser: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Окружение", "Environment")).setAdapter(rows, null).setNegativeButton(t("Назад", "Back"), null).create()
        chooser.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            chooserOpen[0] = false
            for (entry in bindings.entries) {
                val updates: MutableList<Runnable>? = cardUpdates.get(entry.key)
                if (updates != null) {
                    updates.removeAll(entry.value)
                    if (updates.isEmpty()) {
                        cardUpdates.remove(entry.key)
                    }
                }
            }
        })
        chooser.show()
        chooser.getListView().setOnItemClickListener(AdapterView.OnItemClickListener { parent, view, index, id ->
            val choice: WorkspaceEnvironments.Choice = choices.get(index)
            val job: InstallJob? = installations.get(choice.alias)
            if (job != null && job.active) {
                return@OnItemClickListener
            }
            if (installed(choice)) {
                chooser.dismiss()
                selected.accept(WorkspaceEnvironments.Choice(choice.environment, choice.alias, choice.label, choice.home, true))
            } else {
                offerInstall(choice)
            }
        })
    }

    fun prepareMigrationTools(done: Consumer<Boolean?>) {
        WorkspaceDialogs.builder(activity).setTitle(t("Подготовить миграцию?", "Prepare migration?")).setMessage(t("Проверим Python и proot-distro с поддержкой блокировок в Termux. Установим недостающие пакеты после подтверждения. При старой версии proot-distro потребуется доступная в репозитории новая версия. Ubuntu не копируется, сессии не останавливаются.", "Check Python and proot-distro locking support in Termux. Install missing packages after confirmation. An outdated proot-distro requires a newer repository version. Ubuntu is not copied and sessions are not stopped.")).setNegativeButton(t("Назад", "Back"), null).setPositiveButton(t("Подготовить", "Prepare"), DialogInterface.OnClickListener { d, w -> run("agent-migration-tools", true, Consumer<Result> { r -> done.accept(r.ready()) }) }).show()
    }

    fun installAgentUbuntu(completed: Consumer<Boolean?>) {
        WorkspaceDialogs.builder(activity).setTitle(t("Установить Ubuntu AI?", "Install Ubuntu AI?")).setMessage(t("Будет скачана отдельная чистая Ubuntu с именем ubuntu-ai. Нужны интернет и свободное место. Агенты установят свои компоненты при первом запуске после подтверждения. Другие системы не заменяются.", "Download a separate clean Ubuntu named ubuntu-ai. Internet and free storage are required. Agents install their components on first launch after confirmation. Other systems are never replaced.")).setNegativeButton(t("Назад", "Back"), null).setPositiveButton(t("Установить", "Install"), DialogInterface.OnClickListener { d, w -> run("ubuntu-ai", true, Consumer<Result> { r -> completed.accept(r.ready()) }) }).show()
    }

    fun check(environment: String, completed: Consumer<Result>) {
        val alias: String = if (environment.equals("termux")) "termux" else if (environment.equals("ubuntu")) "ubuntu" else if (environment.startsWith("proot:")) environment.substring(6) else ""
        if (!alias.isEmpty()) {
            run(alias, false, completed)
        }
    }

    private fun run(alias: String, install: Boolean, completed: Consumer<Result>) {
        if (closed || running) {
            return
        }
        running = true
        val box = LinearLayout(activity)
        box.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = Math.round(16 * activity.getResources().getDisplayMetrics().density)
        box.setPadding(pad, pad, pad, pad)
        val spinner = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        spinner.setIndeterminate(true)
        box.addView(spinner)
        val text = TextView(activity)
        text.setTextColor(Pal.TEXT2)
        text.setTextSize(13.0f)
        text.setText(t("Проверка компонентов…", "Checking components…"))
        box.addView(text)
        val wait: AlertDialog = WorkspaceDialogs.builder(activity).setTitle((if (install) t("Установка · ", "Installing · ") else t("Проверка среды запуска · ", "Launch environment check · ")) + (if (alias.equals("agent-migration-tools")) t("Компоненты миграции", "Migration components") else WorkspaceEnvironments.label(alias))).setView(box).setPositiveButton(t("Назад", "Back"), null).create()
        progress = wait
        wait.setCancelable(false)
        wait.setCanceledOnTouchOutside(false)
        wait.show()
        wait.getButton(-1).setEnabled(false)
        worker.execute(Runnable {
            var result: Result
            try {
                result = execute(alias, install, Consumer<String> { line ->
                    main.post(Runnable {
                        if (!closed && progress === wait && wait.isShowing()) {
                            text.setText(line)
                        }
                    })
                })
            } catch (failure: Exception) {
                result = Result("ERROR:SETUP_FAILED", 1)
            }
            val answer: Result = result
            val environmentInstall: Boolean = install && !alias.equals("agent-migration-tools")
            if (environmentInstall) {
                storeInstallLog(alias, answer)
            }
            main.post(Runnable {
                running = false
                if (environmentInstall) {
                    installations.remove(alias)
                    recordAttempt(alias, answer.ready())
                    if (!closed) {
                        refreshCard(alias)
                    }
                }
                if (closed) {
                    return@Runnable
                }
                spinner.setVisibility(View.GONE)
                text.setText(if (answer.ready()) t("Окружение и оболочка найдены. Это не проверка всех пакетов или доступа к моделям.", "Environment and shell found. This does not verify every package or model access.") else t("Проверка или установка не завершена. Существующие системы не заменялись.\n", "Check or installation did not finish. Existing systems were not replaced.\n") + answer.report.substring(Math.max(0, answer.report.length - 12000)))
                wait.getButton(-1).setEnabled(true)
                completed.accept(answer)
            })
        })
    }

    @Throws(Exception::class)
    private fun execute(alias: String, install: Boolean, update: Consumer<String>): Result {
        return execute(alias, install, update, null)
    }

    @Throws(Exception::class)
    private fun execute(alias: String, install: Boolean, update: Consumer<String>, job: InstallJob?): Result {
        if (!WorkspaceLocalFilePicker.validAlias(alias)) {
            throw IOException("INVALID_ALIAS")
        }
        var directory: File? = File(activity.getFilesDir(), ".environment-control")
        if (!directory!!.isDirectory() && !directory.mkdirs()) {
            throw IOException("SETUP_DIRECTORY")
        }
        val control: File? = directory
        directory = if (job == null) executionDirectory(control) else job.cancel.getParentFile()
        try {
            val script = File(directory, "runtime.sh")
            (activity.getAssets().open("environment-runtime.sh")).use { `in` ->
                (FileOutputStream(script)).use { out ->
                    val bytes = ByteArray(8192)
                    var count: Int = 0
                    while (true) {
                        count = `in`.read(bytes)
                        if (count == -1) break
                        out.write(bytes, 0, count)
                    }
                }
            }
            if (!script.setReadable(false, false) || !script.setWritable(false, false) || !script.setReadable(true, true) || !script.setWritable(true, true)) {
                throw IOException("SETUP_PERMISSIONS")
            }
            var builder: ProcessBuilder
            if (job != null) {
                val supervisor = File(directory, "environment-install.py")
                (activity.getAssets().open("environment-install.py")).use { `in` ->
                    (FileOutputStream(supervisor)).use { out ->
                        val b = ByteArray(8192)
                        var n: Int = 0
                        while (true) {
                            n = `in`.read(b)
                            if (n == -1) break
                            out.write(b, 0, n)
                        }
                    }
                }
                if (!supervisor.setReadable(false, false) || !supervisor.setWritable(false, false) || !supervisor.setReadable(true, true) || !supervisor.setWritable(true, true)) {
                    throw IOException("SETUP_PERMISSIONS")
                }
                val bootstrap = "if [ ! -x \"\$PREFIX/bin/python3\" ];then printf 'STAGE:install-python\\n'; pkg install -y python || exit \$?;fi;exec \"\$PREFIX/bin/python3\" -B \"\$1\" \"\$2\" \"\$3\" \"\$4\""
                builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", bootstrap, "environment-install", supervisor.getAbsolutePath(), script.getAbsolutePath(), alias, job.cancel.getAbsolutePath())
            } else {
                builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", script.getAbsolutePath(), if (install) "install" else "check", alias)
            }
            builder.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
            builder.environment().clear()
            for (item in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                val eq: Int = item.indexOf('=')
                if (eq > 0) {
                    builder.environment().put(item.substring(0, eq), item.substring(eq + 1))
                }
            }
            builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
            builder.environment().put("PD_FORCE_NO_COLORS", "1")
            builder.environment().put("JOBS_ENVIRONMENT_DOWNLOAD_DIR", control!!.getCanonicalPath())
            builder.environment().put("JOBS_ENVIRONMENT_RETRY", if (job != null && job.resuming) "1" else "0")
            builder.redirectErrorStream(true)
            val process: Process = builder.start()
            return collectProcess(process, update, if (job == null) null else job.cancel)
        } finally {
            if (job == null) {
                cleanExecution(directory, null)
            }
        }
    }

    override fun close() {
        closed = true
        cardUpdates.clear()
        if (progress != null) {
            progress!!.dismiss()
        }
        worker.shutdown()
    }

    companion object {
        @Throws(IOException::class)
        @JvmStatic
        fun executionDirectory(control: File?): File {
            return Files.createTempDirectory(control!!.toPath(), "job-").toFile()
        }

        @JvmStatic
        fun cleanExecution(directory: File?, cancel: File?) {
            // Only fixed files from this invocation; never recurse into downloads/rootfs.
            if (cancel != null) {
                cancel.delete()
            }
            File(directory, "runtime.sh").delete()
            File(directory, "environment-install.py").delete()
            directory!!.delete()
        }

        @Throws(Exception::class)
        @JvmStatic
        fun collectProcess(process: Process, update: Consumer<String>, cancel: File?): Result {
            var exited = false
            var interrupted = false
            try {
                process.getOutputStream().close()
                val report = EnvironmentInstallLog.Capture()
                val bytes = CharArray(1024)
                var last: Long = 0L
                val reader = InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                var primaryException: Throwable? = null
                try {
                    var count: Int = 0
                    while (true) {
                        count = reader.read(bytes)
                        if (count == -1) break
                        report.append(bytes, count)
                        val now: Long = SystemClock.elapsedRealtime()
                        if (now - last > 200) {
                            last = now
                            update.accept(EnvironmentInstallLog.clean(report.recent()))
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
                update.accept(EnvironmentInstallLog.clean(report.recent()))
                val code: Int = process.waitFor()
                exited = true
                return Result(EnvironmentInstallLog.clean(report.snapshot()), code)
            } catch (error: InterruptedException) {
                interrupted = true
                throw error
            } finally {
                if (!exited) {
                    // Only this job's private marker requests group cleanup. Never signal
                    // a PID taken from output, another session or a package-manager lock.
                    if (cancel != null) {
                        try {
                            cancel.createNewFile()
                        } catch (ignored: IOException) {
                        }
                    }
                    try {
                        process.getInputStream().close()
                    } catch (ignored: Exception) {
                    }
                    try {
                        process.getErrorStream().close()
                    } catch (ignored: Exception) {
                    }
                    try {
                        process.getOutputStream().close()
                    } catch (ignored: Exception) {
                    }
                    // Retain the operation slot until the supervisor (or prerequisite
                    // preparation) really exits, even if output/callbacks/waits failed.
                    while (true) {
                        try {
                            process.waitFor()
                            break
                        } catch (error: InterruptedException) {
                            interrupted = true
                        }
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}
