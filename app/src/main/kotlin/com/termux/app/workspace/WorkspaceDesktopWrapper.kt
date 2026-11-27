package com.termux.app.workspace

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject

/** Separate, target-scoped Bionic driver libraries; never replaces system drivers.*/
class WorkspaceDesktopWrapper : AutoCloseable {
    private val activity: TermuxActivity
    private val target: String
    private val picker: WorkspaceLocalFilePicker
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main: Handler = Handler(Looper.getMainLooper())
    @Volatile private var closed: Boolean = false
    @Volatile private var busy: Boolean = false
    private var cancel: Runnable? = null
    private var window: AlertDialog? = null

    constructor(activity: TermuxActivity) : this(activity, "termux")

    constructor(activity: TermuxActivity, target: String) {
        if (!supported(target) && !bridgeSupported(target)) {
            throw IllegalArgumentException("Bionic target")
        }
        this.activity = activity
        this.target = target
        picker = WorkspaceLocalFilePicker(activity)
    }

    private fun bridge(): Boolean {
        return bridgeSupported(target)
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    fun busy(): Boolean {
        return busy
    }

    fun showDrivers(unavailable: BooleanSupplier, updated: Runnable) {
        if (closed || busy || unavailable.getAsBoolean()) {
            return
        }
        // A fresh installation can choose the virtual preset before host Python exists.
        if (!File(base(activity, target), "drivers").exists() && metadata(activity, target) == null) {
            driverChoices(JSONArray(), unavailable, updated)
            return
        }
        libraryJob("catalog", "-", unavailable, updated)
    }

    private fun driverChoices(drivers: JSONArray, unavailable: BooleanSupplier, updated: Runnable) {
        if (closed) {
            return
        }
        if (unavailable.getAsBoolean()) {
            updated.run()
            return
        }
        val imported = JSONArray()
        var i = 0
        while (i < drivers.length()) {
            val value: JSONObject? = drivers.optJSONObject(i)
            if (value != null && !DEFAULT_DRIVER_ID.equals(value.optString("id"))) {
                imported.put(value)
            }
            i++
        }
        val labels = arrayOfNulls<String>(imported.length() + 1)
        val ids = arrayOfNulls<String>(labels.size)
        val enabled = BooleanArray(labels.size)
        val current: JSONObject? = metadata(activity, target)
        val selected: String = if (current == null) "" else current.optString("id")
        var checked: Int = if (builtinSelected(activity, target)) 0 else -1
        labels[0] = DEFAULT_DRIVER_NAME
        ids[0] = DEFAULT_DRIVER_ID
        enabled[0] = Build.VERSION.SDK_INT >= 28
        for (i in 1 until (labels.size)) {
            val value: JSONObject = imported.optJSONObject(i - 1)
            ids[i] = value.optString("id")
            labels[i] = value.optString("name", "Turnip Bionic")
            enabled[i] = (ids[i]!! as java.lang.String).matches("[a-f0-9]{64}") && value.optInt("minApi", 0) <= Build.VERSION.SDK_INT
            if (!builtinSelected(activity, target) && selected.equals(ids[i])) {
                checked = i
            }
        }
        for (i in 0 until (labels.size)) {
            for (j in i + 1 until (labels.size)) {
                if (labels[i]!!.equals(labels[j])) {
                    val name: String? = labels[i]
                    for (k in i until (labels.size)) {
                        if (labels[k]!!.equals(name)) {
                            labels[k] += " · " + ids[k]!!.substring(0, Math.min(8, ids[k]!!.length))
                        }
                    }
                    break
                }
            }
        }
        val selectedIndex: Int = checked
        val adapter: BaseAdapter = object : BaseAdapter() {
            override fun getCount(): Int {
                return labels.size
            }

            override fun getItem(position: Int): Any? {
                return labels[position]
            }

            override fun getItemId(position: Int): Long {
                return position.toLong()
            }

            override fun getView(position: Int, recycled: View, parent: ViewGroup): View {
                val outer = LinearLayout(activity)
                outer.setGravity(Gravity.CENTER_VERTICAL)
                outer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS)
                outer.setPadding(WorkspaceDialogs.dp(activity, 16.0f), WorkspaceDialogs.dp(activity, 4.0f), WorkspaceDialogs.dp(activity, 16.0f), WorkspaceDialogs.dp(activity, 4.0f))
                val row = LinearLayout(activity)
                row.setGravity(Gravity.CENTER_VERTICAL)
                row.setBackground(WorkspaceDialogs.background(activity, position == selectedIndex))
                val label: TextView = WorkspaceDialogs.action(activity, labels[position], 0)
                label.setBackground(null)
                label.setAlpha(if (enabled[position]) 1f else 0.4f)
                row.addView(label, LinearLayout.LayoutParams(0, -2, 1.0f))
                if (position > 0 && (ids[position]!! as java.lang.String).matches("[a-f0-9]{64}")) {
                    val trash = ImageButton(activity)
                    trash.setImageResource(R.drawable.workspace_delete)
                    trash.setImageTintList(ColorStateList.valueOf(Pal.DANGER))
                    trash.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
                    trash.setPadding(WorkspaceDialogs.dp(activity, 10.0f), WorkspaceDialogs.dp(activity, 10.0f), WorkspaceDialogs.dp(activity, 10.0f), WorkspaceDialogs.dp(activity, 10.0f))
                    trash.setBackground(WorkspaceDialogs.background(activity, false))
                    trash.setFocusable(false)
                    trash.setTag("wrapper-remove-" + ids[position])
                    trash.setContentDescription(tr("Удалить ", "Remove ") + labels[position])
                    trash.setOnClickListener(View.OnClickListener { v ->
                        if (closed || busy || unavailable.getAsBoolean()) {
                            return@OnClickListener
                        }
                        window!!.dismiss()
                        confirmRemove(ids[position], labels[position], unavailable, updated)
                    })
                    row.addView(trash, LinearLayout.LayoutParams(WorkspaceDialogs.dp(activity, 44.0f), WorkspaceDialogs.dp(activity, 44.0f)))
                }
                outer.addView(row, LinearLayout.LayoutParams(-1, -2))
                return outer
            }
        }
        window = WorkspaceDialogs.builder(activity).setTitle(tr("Драйверы Wrapper", "Wrapper drivers")).setAdapter(adapter, DialogInterface.OnClickListener { d, i ->
            if (!enabled[i] || closed) {
                return@OnClickListener
            }
            if (unavailable.getAsBoolean()) {
                updated.run()
                return@OnClickListener
            }
            if (i == 0) {
                chooseBuiltin(activity, target)
                updated.run()
                return@OnClickListener
            }
            if (selected.equals(ids[i])) {
                chooseImported(activity, target)
                updated.run()
                return@OnClickListener
            }
            libraryJob("select", ids[i], unavailable, updated)
        }).setNegativeButton(tr("Назад", "Back"), DialogInterface.OnClickListener { d, w -> updated.run() }).create()
        window!!.show()
    }

    private fun confirmRemove(identity: String?, label: String?, unavailable: BooleanSupplier, updated: Runnable) {
        window = WorkspaceDialogs.builder(activity).setTitle(tr("Удалить драйвер?", "Remove driver?")).setMessage(label + "\n\n" + tr("Удалится только копия из выбранного окружения. Оригинальный ZIP в памяти телефона сохранится. Если удалён выбранный драйвер, будет выбран a6xx-a7xx-a8xx; загрузка произойдёт при следующем запуске стола.", "Removes only this environment’s copy. The original phone ZIP is preserved. Removing the selected driver selects a6xx-a7xx-a8xx; it downloads at the next desktop launch.")).setPositiveButton(tr("Удалить", "Remove"), DialogInterface.OnClickListener { d, w -> libraryJob("remove", identity, unavailable, updated) }).setNegativeButton(tr("Отмена", "Cancel"), DialogInterface.OnClickListener { d, w -> showDrivers(unavailable, updated) }).create()
        window!!.show()
    }

    private fun libraryJob(operation: String, identity: String?, unavailable: BooleanSupplier, updated: Runnable) {
        if (closed || busy || unavailable.getAsBoolean()) {
            return
        }
        busy = true
        val cancelled = AtomicBoolean()
        val cancellation = AtomicReference<File>()
        window = WorkspaceDialogs.builder(activity).setTitle(tr("Драйверы Wrapper", "Wrapper drivers")).setMessage(if (operation.equals("catalog")) tr("Чтение списка драйверов…", "Reading driver library…") else if (operation.equals("remove")) tr("Удаление драйвера…", "Removing driver…") else tr("Проверка и выбор драйвера…", "Verifying and selecting driver…")).setNegativeButton(tr("Отмена", "Cancel"), null).create()
        window!!.setCancelable(false)
        window!!.setCanceledOnTouchOutside(false)
        window!!.show()
        val progress: AlertDialog? = window
        cancel = Runnable {
            cancelled.set(true)
            val marker: File? = cancellation.get()
            if (marker != null) {
                try {
                    marker.createNewFile()
                } catch (ignored: IOException) {
                }
            }
            progress!!.getButton(-2).setEnabled(false)
        }
        progress!!.getButton(-2).setOnClickListener(View.OnClickListener { v -> cancel!!.run() })
        worker.execute(Runnable {
            var job: File? = null
            var result = Result(false, "ERROR:WRAPPER_LIBRARY_FAILED")
            try {
                job = jobDirectory()
                val marker = File(job, "cancel")
                cancellation.set(marker)
                if (cancelled.get()) {
                    marker.createNewFile()
                }
                result = run(job, operation, identity, marker, Consumer<String> { line ->  })
            } catch (ignored: Exception) {
            } finally {
                cleanup(job)
            }
            val answer: Result = result
            main.post(Runnable {
                busy = false
                cancel = null
                progress.dismiss()
                if (closed) {
                    return@Runnable
                }
                if (operation.equals("remove") && answer.success && metadata(activity, target) == null) {
                    chooseBuiltin(activity, target)
                }
                if (cancelled.get()) {
                    updated.run()
                    return@Runnable
                }
                if (!answer.success) {
                    message(if (answer.report.contains("WRAPPER_BUSY")) tr("Сначала остановите рабочий стол выбранного окружения. Выбор драйвера сохранён.", "Stop the selected environment’s desktop first. The selected driver is preserved.") else (if (operation.equals("remove")) tr("Не удалось удалить драйвер. Подробности:\n", "Could not remove the driver. Details:\n") else tr("Не удалось прочитать или выбрать драйвер. Предыдущий выбор сохранён.\n", "Could not read or select driver. The previous selection is preserved.\n")) + answer.report, updated)
                    return@Runnable
                }
                if (operation.equals("select")) {
                    chooseImported(activity, target)
                    updated.run()
                    return@Runnable
                }
                if (operation.equals("remove")) {
                    showDrivers(unavailable, updated)
                    return@Runnable
                }
                try {
                    for (line in (answer.report as java.lang.String).split("\n")) {
                        if (line.startsWith("WRAPPER_DRIVERS:")) {
                            driverChoices(JSONArray(line.substring("WRAPPER_DRIVERS:".length)), unavailable, updated)
                            return@Runnable
                        }
                    }
                    throw IOException("Missing library")
                } catch (ignored: Exception) {
                    message(tr("Не удалось прочитать список драйверов.", "Could not read the driver library."), updated)
                }
            })
        })
    }

    fun choose(unavailable: BooleanSupplier, updated: Runnable) {
        if (closed || busy || unavailable.getAsBoolean()) {
            return
        }
        picker.choosePhoneFile(WorkspaceFileBrowser.FileChoice { source, entry ->
            if (closed || busy || unavailable.getAsBoolean()) {
                return@FileChoice
            }
            if (!(entry.name as java.lang.String).toLowerCase(Locale.ROOT).endsWith(".zip") || entry.size > 64L * 1024 * 1024) {
                message(tr("Выберите ZIP драйвера Turnip Bionic до 64 МБ с meta.json и библиотекой ARM64.", "Choose a Bionic ZIP up to 64 MB with meta.json and an ARM64 library."), updated)
                return@FileChoice
            }
            window = WorkspaceDialogs.builder(activity).setTitle(tr("Загрузить драйвер Bionic?", "Import Bionic driver?")).setMessage((if (bridge()) tr("Для выбранного glibc-окружения, через отдельный мост. Совместимость ZIP с конкретным телефоном не гарантируется.\n", "For the selected glibc environment, through its separate bridge. ZIP/device compatibility is not guaranteed.\n") else "") + tr("Драйвер рабочего стола. ZIP будет скопирован и проверен в приватной папке приложения. Драйвер — исполняемый код с доступом к данным приложения: выбирайте только доверенный источник. Проверка формата и SHA-256 не подтверждает безопасность или совместимость. После импорта драйвер добавится в список и будет выбран; остальные загруженные драйверы и оригинал в памяти телефона сохранятся.", "Desktop driver. The ZIP will be copied and checked in private app storage. A driver is executable code with access to app data: use a trusted source. Format and SHA-256 checks do not establish safety or compatibility. The imported driver is added to the library and selected; other imported drivers and the original phone file are preserved.")).setPositiveButton(tr("Загрузить", "Import"), DialogInterface.OnClickListener { d, w ->
                if (!closed && !unavailable.getAsBoolean()) {
                    importDriver(source, entry, unavailable, updated)
                }
            }).setNegativeButton(tr("Назад", "Back"), DialogInterface.OnClickListener { d, w -> updated.run() }).create()
            window!!.show()
        })
    }

    private fun importDriver(source: WorkspaceStore.Session, entry: ProjectFiles.Entry, unavailable: BooleanSupplier, updated: Runnable) {
        busy = true
        val cancelled = AtomicBoolean()
        val body: LinearLayout = AgentStatusUi.body(activity)
        val bar = ProgressBar(activity)
        body.addView(bar)
        val text: TextView = AgentStatusUi.info(body, tr("Копирование и проверка драйвера…", "Copying and checking driver…"))
        window = WorkspaceDialogs.builder(activity).setTitle("Wrapper · Bionic").setView(body).setNegativeButton(tr("Отмена", "Cancel"), null).create()
        window!!.setCancelable(false)
        window!!.setCanceledOnTouchOutside(false)
        window!!.show()
        val progress: AlertDialog? = window
        val cancellation = AtomicReference<File>()
        cancel = Runnable {
            cancelled.set(true)
            val marker: File? = cancellation.get()
            if (marker != null) {
                try {
                    marker.createNewFile()
                } catch (ignored: IOException) {
                }
            }
            text.setText(tr("Отмена…", "Cancelling…"))
            progress!!.getButton(-2).setEnabled(false)
        }
        progress!!.getButton(-2).setOnClickListener(View.OnClickListener { v -> cancel!!.run() })
        worker.execute(Runnable {
            var success = false
            var report = "ERROR:WRAPPER_IMPORT_FAILED"
            var job: File? = null
            try {
                job = jobDirectory()
                val archive = File(job, "driver.zip")
                val marker = File(job, "cancel")
                cancellation.set(marker)
                if (cancelled.get()) {
                    marker.createNewFile()
                }
                (ProjectFiles(activity, source)).use { files ->
                    files.export(entry, archive, 64L * 1024 * 1024)
                }
                if (!cancelled.get()) {
                    val result: Result = run(job, "import", archive.getPath(), marker, Consumer<String> { chunk ->  })
                    success = result.success && result.report.contains("READY:wrapper-driver")
                    report = result.report
                }
            } catch (ignored: Exception) {
            } finally {
                cleanup(job)
            }
            val ok: Boolean = success
            val detail: String = report
            main.post(Runnable {
                busy = false
                cancel = null
                progress.dismiss()
                if (closed) {
                    return@Runnable
                }
                if (ok) {
                    chooseImported(activity, target)
                    updated.run()
                    return@Runnable
                }
                if (cancelled.get()) {
                    updated.run()
                    return@Runnable
                }
                message(if (detail.contains("WRAPPER_BUSY")) tr("Остановите выбранный рабочий стол перед заменой драйвера. Предыдущий выбор сохранён.", "Stop the selected desktop before replacing its driver. The previous selection is preserved.") else tr("Не удалось импортировать драйвер. Нужен ZIP Bionic ARM64 с meta.json, подходящий версии Android. Предыдущий драйвер сохранён.\n", "Could not import driver. Choose a Bionic ARM64 ZIP with meta.json matching this Android version. The previous driver is preserved.\n") + detail, updated)
            })
        })
    }

    fun prepare(unavailable: BooleanSupplier, updated: Runnable, inline: WorkspaceDesktopGpu.Inline) {
        if (closed || busy || unavailable.getAsBoolean()) {
            finish(inline, false, "ERROR:GPU_BUSY", updated)
            return
        }
        if (!builtinSelected(activity, target) && metadata(activity, target) == null) {
            finish(inline, false, tr("Выберите ZIP Bionic кнопкой папки рядом с рендером.", "Choose a Bionic ZIP using the folder button next to the renderer."), updated)
            return
        }
        if (builtinSelected(activity, target)) {
            install(unavailable, updated, inline)
            return
        }
        if (bridge()) {
            install(unavailable, updated, inline)
            return
        }
        if (installed(activity, target)) {
            updated.run()
            return
        }
        window = WorkspaceDialogs.builder(activity).setTitle(tr("Подготовить Wrapper?", "Prepare Wrapper?")).setMessage(tr("Будет загружено около 3,5 МБ: Wrapper Lee Gao и Vulkan Loader для Termux. Компоненты хранятся отдельно, системные драйверы не заменяются. Выбранный Bionic-драйвер будет выполняться только в процессе рабочего стола Termux. Режим экспериментальный.", "Downloads about 3.5 MB: Lee Gao’s Wrapper and the Termux Vulkan Loader. Components are stored separately; system drivers are not replaced. The selected Bionic driver will run in the Termux desktop process tree only. Experimental mode.")).setNegativeButton(tr("Назад", "Back"), DialogInterface.OnClickListener { d, w -> finish(inline, false, "ERROR:GPU_CANCELLED", updated) }).setPositiveButton(tr("Подготовить", "Prepare"), DialogInterface.OnClickListener { d, w -> install(unavailable, updated, inline) }).create()
        window!!.setCancelable(false)
        window!!.setCanceledOnTouchOutside(false)
        window!!.show()
    }

    private fun install(unavailable: BooleanSupplier, updated: Runnable, inline: WorkspaceDesktopGpu.Inline?) {
        if (closed || busy || unavailable.getAsBoolean()) {
            finish(inline, false, "ERROR:GPU_BUSY", updated)
            return
        }
        if (inline == null) {
            message(tr("Запустите рабочий стол для подготовки Wrapper.", "Start the desktop to prepare Wrapper."), updated)
            return
        }
        busy = true
        val cancelled = AtomicBoolean()
        val cancellation = AtomicReference<File>()
        cancel = Runnable {
            cancelled.set(true)
            val marker: File? = cancellation.get()
            if (marker != null) {
                try {
                    marker.createNewFile()
                } catch (ignored: IOException) {
                }
            }
            inline.progress(-1, tr("Остановка загрузки…", "Stopping download…"))
        }
        inline.started(cancel)
        worker.execute(Runnable {
            var job: File? = null
            var success = false
            var report = "ERROR:WRAPPER_SETUP_FAILED"
            try {
                job = jobDirectory()
                val marker = File(job, "cancel")
                cancellation.set(marker)
                if (cancelled.get()) {
                    marker.createNewFile()
                }
                val began: Long = SystemClock.elapsedRealtime()
                val last: LongArray = longArrayOf(0L)
                val result: Result = run(job, if (builtinSelected(activity, target)) "install-default" else "install", "-", marker, Consumer<String> { line ->
                    if (!line.startsWith("GPU_PROGRESS:")) {
                        return@Consumer
                    }
                    try {
                        val parts: Array<String> = (line as java.lang.String).split(":")
                        val done: Long = java.lang.Long.parseLong(parts[1])
                        val total: Long = java.lang.Long.parseLong(parts[2])
                        val now: Long = SystemClock.elapsedRealtime()
                        if (now - last[0] < 200 && done < total) {
                            return@Consumer
                        }
                        last[0] = now
                        val percent: Int = Math.min(100L, done * 100 / Math.max(1L, total)).toInt()
                        val eta: Long = if (done == 0L) (-1).toLong() else (now - began) * (total - done) / done / 1000
                        val text: String = java.lang.String.format(Locale.ROOT, "%.1f / %.1f MB", done / 1000000.0, total / 1000000.0) + (if (eta < 0) "" else " · ≈ " + eta + tr(" с", " s"))
                        main.post(Runnable {
                            if (!closed && !cancelled.get()) {
                                inline.progress(percent, text)
                            }
                        })
                    } catch (ignored: Exception) {
                    }
                })
                success = result.success && result.report.contains("READY:wrapper")
                report = result.report
            } catch (ignored: Exception) {
            } finally {
                cleanup(job)
            }
            val ok: Boolean = success && !cancelled.get()
            val detail: String = if (cancelled.get()) "ERROR:GPU_CANCELLED" else if (ok) report else WorkspaceDesktopGpu.failure(activity, report)
            main.post(Runnable {
                busy = false
                cancel = null
                if (!closed) {
                    finish(inline, ok, detail, updated)
                }
            })
        })
    }

    private fun message(text: String, updated: Runnable) {
        if (closed) {
            return
        }
        window = WorkspaceDialogs.builder(activity).setMessage(text).setPositiveButton(tr("Назад", "Back"), DialogInterface.OnClickListener { d, w -> updated.run() }).create()
        window!!.show()
    }

    @Throws(Exception::class)
    private fun jobDirectory(): File {
        val root: File = base(activity, target)
        if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory() && !root.mkdirs()) {
            throw IOException("Wrapper directory")
        }
        return Files.createTempDirectory(root.toPath(), ".job-").toFile()
    }

    private class Result {
        @JvmField val success: Boolean
        @JvmField val report: String

        constructor(ok: Boolean, text: String) {
            success = ok
            report = text
        }
    }

    @Throws(Exception::class)
    private fun run(job: File?, operation: String, source: String?, cancel: File, output: Consumer<String>): Result {
        for (name in (if (bridge()) arrayOf<String>("x11-wrapper.py", "x11-bionic-glibc.py", "x11-gpu.py", "x11-extras.py", "x11-default-driver.py") else arrayOf<String>("x11-wrapper.py", "x11-gpu.py", "x11-extras.py", "x11-default-driver.py"))) {
            (activity.getAssets().open(name)).use { `in` ->
                (FileOutputStream(File(job, name))).use { out ->
                    val data = ByteArray(8192)
                    var count: Int = 0
                    while (true) {
                        count = `in`.read(data)
                        if (count == -1) break
                        out.write(data, 0, count)
                    }
                }
            }
        }
        val builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/python3", "-B", File(job, if (operation.equals("install-default")) "x11-default-driver.py" else if (bridge()) "x11-bionic-glibc.py" else "x11-wrapper.py").getPath(), operation, target, base(activity, target).getPath(), source, Integer.toString(Build.VERSION.SDK_INT), cancel.getPath())
        builder.directory(job)
        builder.environment().clear()
        for (value in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val eq: Int = value.indexOf('=')
            if (eq > 0) {
                builder.environment().put(value.substring(0, eq), value.substring(eq + 1))
            }
        }
        builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        builder.redirectErrorStream(true)
        val process: Process = builder.start()
        process.getOutputStream().close()
        val tail = StringBuilder()
        (BufferedReader(InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))).use { reader ->
            var line: String? = null
            while (true) {
                line = reader.readLine()
                if (line == null) break
                tail.append(line).append('\n')
                if (tail.length > 262144) {
                    tail.delete(0, tail.length - 262144)
                }
                output.accept(line)
            }
        }
        return Result(process.waitFor() == 0, tail.toString())
    }

    override fun close() {
        closed = true
        picker.close()
        if (cancel != null) {
            cancel!!.run()
        }
        if (window != null) {
            window!!.dismiss()
        }
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        const val DEFAULT_DRIVER_ID: String = "ce35642c5d93ecdfa08b730f80fd738ad71b473cdd7aa3dc58c37d2e6b1e984f"
        const val DEFAULT_DRIVER_NAME: String = "a6xx-a7xx-a8xx"

        @JvmStatic
        fun bridgeSupported(target: String): Boolean {
            return "ubuntu".equals(target) || "debian".equals(target) || "fedora".equals(target) || "archlinux".equals(target) || "opensuse".equals(target) || "void".equals(target)
        }

        @JvmStatic
        fun supported(target: String): Boolean {
            return "termux".equals(target)
        }

        @JvmStatic
        fun base(context: Context): File {
            return base(context, "termux")
        }

        @JvmStatic
        fun base(context: Context, target: String): File {
            return File(context.getFilesDir(), if (bridgeSupported(target)) "workspace-x11-bionic-glibc" + (if ("ubuntu".equals(target)) "" else "-" + target) else "workspace-x11-wrapper")
        }

        @JvmStatic
        fun metadata(context: Context): JSONObject? {
            return metadata(context, "termux")
        }

        @JvmStatic
        fun metadata(context: Context, target: String): JSONObject? {
            try {
                val root: File = base(context, target)
                val file = File(root, "current.json")
                if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length() > 32768) {
                    return null
                }
                val value = JSONObject(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                if (!(value.optString("id") as java.lang.String).matches("[a-f0-9]{64}") || !(value.optString("library") as java.lang.String).matches("[A-Za-z0-9_][A-Za-z0-9_.+-]{0,127}\\.so")) {
                    return null
                }
                val parent = File(root, "drivers")
                val driver = File(parent, value.getString("id"))
                val library = File(driver, value.getString("library"))
                if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(driver.toPath()) || Files.isSymbolicLink(library.toPath()) || !library.isFile()) {
                    return null
                }
                return value
            } catch (error: Exception) {
                return null
            }
        }

        @JvmStatic
        fun builtinSelected(context: Context, target: String): Boolean {
            return metadata(context, target) == null || context.getSharedPreferences("workspace-x11", 0).getBoolean("builtin-driver:" + target, false)
        }

        @JvmStatic
        fun chooseBuiltin(context: Context, target: String) {
            context.getSharedPreferences("workspace-x11", 0).edit().putBoolean("builtin-driver:" + target, true).apply()
        }

        @JvmStatic
        fun chooseImported(context: Context, target: String) {
            context.getSharedPreferences("workspace-x11", 0).edit().putBoolean("builtin-driver:" + target, false).apply()
        }

        @JvmStatic
        fun installed(context: Context): Boolean {
            return installed(context, "termux")
        }

        @JvmStatic
        fun installed(context: Context, target: String): Boolean {
            val runtime = File(base(context, target), "runtime")
            val ready = File(runtime, "ready.json")
            val selected: JSONObject? = metadata(context, target)
            return selected != null && (!builtinSelected(context, target) || DEFAULT_DRIVER_ID.equals(selected.optString("id"))) && !Files.isSymbolicLink(runtime.toPath()) && !Files.isSymbolicLink(ready.toPath()) && ready.isFile()
        }

        @JvmStatic
        fun driverLabel(context: Context): String {
            return driverLabel(context, "termux")
        }

        @JvmStatic
        fun driverLabel(context: Context, target: String): String {
            if (builtinSelected(context, target)) {
                return DEFAULT_DRIVER_NAME
            }
            val value: JSONObject? = metadata(context, target)
            return if (value == null) "" else value.optString("name", "Turnip Bionic")
        }

        @JvmStatic
        private fun finish(inline: WorkspaceDesktopGpu.Inline?, success: Boolean, report: String, updated: Runnable) {
            if (inline != null) {
                inline.finished(success, report)
            }
            updated.run()
        }

        @JvmStatic
        private fun cleanup(job: File?) {
            if (job == null) {
                return
            }
            for (name in arrayOf<String>("driver.zip", "cancel", "x11-wrapper.py", "x11-gpu.py", "x11-bionic-glibc.py", "x11-bionic-glibc.zip", "x11-extras.py", "x11-default-driver.py")) {
                File(job, name).delete()
            }
            job.delete()
        }
    }
}
