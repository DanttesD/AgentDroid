package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.app.TermuxActivity
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.x11.VirglInstaller
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.IntPredicate

/** Optional shared driver cache. Selection is per environment, never per terminal profile.*/
object WorkspaceDesktopGpu {
    interface Inline {
        fun started(cancel: Runnable?)

        fun progress(percent: Int, text: String?)

        fun finished(success: Boolean, report: String)
    }

    @JvmStatic
    fun directory(target: String): String {
        return if (target.equals("ubuntu")) "ubuntu-26.04-mesa26.2.5" else if (target.equals("termux")) "termux-mesa26.2.5" else if (target.equals("debian")) "debian-13-mesa26.2.5" else if (target.equals("alpine")) "alpine-3.24-mesa26.2.5" else if (target.equals("fedora")) "fedora-44-mesa26.2.5" else if (target.equals("opensuse")) "opensuse-16-mesa26.2.5" else if (target.equals("void")) "void-mesa26.2.5" else if (target.equals("archlinux")) "archlinux-mesa26.2.5" else ""
    }

    @JvmStatic
    fun supported(target: String): Boolean {
        return !directory(target).isEmpty()
    }

    @JvmStatic
    fun mode(c: Context, target: String): String {
        val value: String? = c.getSharedPreferences("workspace-x11", 0).getString("renderer:" + target, "cpu")
        return if (value!!.equals("bionic-glibc") && WorkspaceDesktopWrapper.bridgeSupported(target)) "bionic-glibc" else if (value.equals("wrapper") && WorkspaceDesktopWrapper.supported(target)) "wrapper" else if (value.equals("turnip")) "turnip" else if (value.equals("virgl") && VirglInstaller.supported(target)) "virgl" else "cpu"
    }

    @JvmStatic
    private fun t(a: TermuxActivity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun failure(a: TermuxActivity, raw: String): String {
        var code = "GPU_SETUP_FAILED"
        for (line in (raw as java.lang.String).split("\\r?\\n")) {
            if ((line as java.lang.String).matches("ERROR:[A-Z0-9_]{1,100}")) {
                code = line.substring(6)
            }
        }
        if (code.startsWith("GPU_DOWNLOAD_HTTP_")) {
            return t(a, "Источник компонента недоступен: HTTP ", "Component source unavailable: HTTP ") + code.substring(18) + t(a, ". Повторите позже или проверьте обновление приложения.", ". Retry later or check for an app update.")
        }
        if (code.equals("GPU_DOWNLOAD_NETWORK")) {
            return t(a, "Не удалось скачать компонент. Проверьте интернет и повторите.", "Could not download the component. Check the connection and retry.")
        }
        if (code.contains("CHECKSUM")) {
            return t(a, "Компонент не прошёл проверку целостности. Повторите загрузку.", "Component integrity check failed. Retry the download.")
        }
        return t(a, "Подготовка не завершена. Код: ", "Setup incomplete. Code: ") + code
    }

    @JvmStatic
    fun installed(a: TermuxActivity, target: String): Boolean {
        return supported(target) && File(File(File(a.getFilesDir(), "workspace-x11-gpu"), directory(target)), "ready.json").isFile()
    }

    @JvmStatic
    fun installed(a: TermuxActivity, target: String, choice: String): Boolean {
        return choice.equals("bionic-glibc") && WorkspaceDesktopWrapper.bridgeSupported(target) && WorkspaceDesktopWrapper.installed(a, target) || choice.equals("wrapper") && WorkspaceDesktopWrapper.supported(target) && WorkspaceDesktopWrapper.installed(a) || choice.equals("cpu") || choice.equals("virgl") && VirglInstaller.installed(a, target) || choice.equals("turnip") && installed(a, target)
    }

    @JvmStatic
    fun turnipLabel(target: String): String {
        return "Turnip + Zink (Mesa 26.2.5)"
    }

    @JvmStatic
    fun label(a: TermuxActivity, target: String): String {
        val mode: String = mode(a, target)
        return if (mode.equals("bionic-glibc")) "Bionic for glibc (libhybris + Jobs bridge)" else if (mode.equals("wrapper")) "Wrapper · Bionic (AdrenoTools)" else if (mode.equals("virgl")) "VirGL (GPU)" else if (mode.equals("turnip")) turnipLabel(target) else t(a, "Программный (CPU)", "Software (CPU)")
    }

    @JvmStatic
    fun choose(a: TermuxActivity, target: String, busy: BooleanSupplier, updated: Runnable) {
        select(a, target, busy, Consumer<String> { choice ->
            if (choice.equals("wrapper") || choice.equals("bionic-glibc")) {
                a.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target, choice).apply()
                updated.run()
            } else {
                prepare(a, target, choice, busy, updated, null)
            }
        }, updated)
    }

    @JvmStatic
    fun select(a: TermuxActivity, target: String, busy: BooleanSupplier, selected: Consumer<String>, back: Runnable) {
        if (busy.getAsBoolean()) {
            return
        }
        val bionic: Boolean = WorkspaceDesktopWrapper.supported(target) || WorkspaceDesktopWrapper.bridgeSupported(target)
        val labels: Array<String?> = if (bionic) arrayOf<String?>(t(a, "Программный (CPU)", "Software (CPU)"), turnipLabel(target), "VirGL (GPU)", if (WorkspaceDesktopWrapper.bridgeSupported(target)) "Bionic for glibc (libhybris + Jobs bridge)" else "Wrapper · Bionic (AdrenoTools)") else arrayOf<String?>(t(a, "Программный (CPU)", "Software (CPU)"), turnipLabel(target), "VirGL (GPU)")
        WorkspaceDialogs.builder(a).setTitle(t(a, "Рендеринг · ", "Rendering · ") + WorkspaceEnvironments.label(target)).setAdapter(WorkspaceDialogs.choiceAdapter(a, labels, if (mode(a, target).equals("wrapper") || mode(a, target).equals("bionic-glibc")) 3 else if (mode(a, target).equals("turnip")) 1 else if (mode(a, target).equals("virgl")) 2 else 0, IntPredicate { i ->
            i == 0 || (if (i == 1) supported(target) else if (i == 2) VirglInstaller.supported(target) else bionic)
        }), DialogInterface.OnClickListener { d, index ->
            if (!busy.getAsBoolean() && (index == 0 || (if (index == 1) supported(target) else if (index == 2) VirglInstaller.supported(target) else bionic))) {
                selected.accept(if (index == 0) "cpu" else if (index == 1) "turnip" else if (index == 2) "virgl" else if (WorkspaceDesktopWrapper.bridgeSupported(target)) "bionic-glibc" else "wrapper")
            }
        }).setNegativeButton(t(a, "Назад", "Back"), DialogInterface.OnClickListener { d, w -> back.run() }).show()
    }

    @JvmStatic
    fun prepare(a: TermuxActivity, target: String, choice: String, busy: BooleanSupplier, updated: Runnable, inline: Inline?) {
        prepare(a, target, choice, busy, updated, inline, false)
    }

    @JvmStatic
    fun prepare(a: TermuxActivity, target: String, choice: String, busy: BooleanSupplier, updated: Runnable, inline: Inline?, verified: Boolean) {
        if (!choice.equals("cpu") && !choice.equals("turnip") && !choice.equals("virgl")) {
            throw IllegalArgumentException("Invalid renderer")
        }
        if (busy.getAsBoolean()) {
            if (inline != null) {
                inline.finished(false, "ERROR:GPU_BUSY")
            }
            updated.run()
            return
        }
        if (choice.equals("virgl")) {
            WorkspaceDesktopVirgl.prepare(a, target, busy, updated, inline, verified)
            return
        }
        if (choice.equals("cpu") || verified && installed(a, target)) {
            a.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target, choice).apply()
            updated.run()
            return
        }
        if (!supported(target)) {
            if (inline != null) {
                inline.finished(false, "ERROR:GPU_TARGET_UNSUPPORTED")
            }
            updated.run()
            return
        }
        val confirm: AlertDialog = WorkspaceDialogs.builder(a).setTitle(t(a, "Установить Turnip + Zink?", "Install Turnip + Zink?")).setMessage(t(a, "Экспериментальный режим для Adreno 7xx, 830 и 840. Совместимость конкретного устройства проверяется при запуске. Компоненты скачиваются с проверкой SHA-256 в общую папку приложения; системная Mesa не заменяется. Повреждённая копия компонентов приложения будет восстановлена. Для Termux нужно около 1,8 МБ загрузки, для Ubuntu 26.04, Debian 13, Alpine 3.24 Fedora 44, openSUSE Leap 16.0 и Void Linux — около 8–10 МБ. Arch Linux — около 10 МБ, нужны mesa, vulkan-icd-loader и llvm22-libs. Нужны библиотеки Vulkan и EGL: libvulkan1/libegl1 в Ubuntu и Debian, vulkan-loader/mesa-egl в Alpine, vulkan-loader/libglvnd-egl в Fedora, libvulkan1/Mesa-libEGL1/Mesa-dri в openSUSE, vulkan-loader/libglvnd/libllvm18 в Void. Настройка меняется только при остановленном рабочем столе. Остальные сессии не переключаются.", "Experimental mode for Adreno 7xx, 830 and 840. Actual device compatibility must be tested. Components are downloaded with SHA-256 verification into shared app storage; system Mesa is not replaced. Damaged application-owned components will be repaired. Termux download is about 1.8 MB; Ubuntu 26.04, Debian 13, Alpine 3.24 Fedora 44, openSUSE Leap 16.0 and Void Linux about 8–10 MB. Arch Linux download is about 10 MB and requires mesa, vulkan-icd-loader and llvm22-libs. Vulkan/EGL libraries are required: libvulkan1/libegl1 on Ubuntu and Debian, vulkan-loader/mesa-egl on Alpine, vulkan-loader/libglvnd-egl on Fedora, libvulkan1/Mesa-libEGL1/Mesa-dri on openSUSE, vulkan-loader/libglvnd/libllvm18 on Void. Change modes only with the desktop stopped. Other sessions are not switched.")).setNegativeButton(t(a, "Назад", "Back"), DialogInterface.OnClickListener { x, w ->
            if (inline != null) {
                inline.finished(false, "ERROR:GPU_CANCELLED")
            }
            updated.run()
        }).setPositiveButton(t(a, "Установить", "Install"), DialogInterface.OnClickListener { x, w ->
            if (!busy.getAsBoolean()) {
                install(a, target, busy, updated, inline)
            } else {
                if (inline != null) {
                    inline.finished(false, "ERROR:GPU_BUSY")
                }
                updated.run()
            }
        }).create()
        confirm.setCancelable(false)
        confirm.setCanceledOnTouchOutside(false)
        confirm.show()
    }

    @JvmStatic
    private fun install(a: TermuxActivity, target: String, busy: BooleanSupplier, updated: Runnable, inline: Inline?) {
        val body: LinearLayout = AgentStatusUi.body(a)
        val bar = ProgressBar(a, null, R.attr.progressBarStyleHorizontal)
        bar.setMax(100)
        bar.setIndeterminate(true)
        body.addView(bar)
        val detail: TextView = AgentStatusUi.info(body, t(a, "Проверка совместимости…", "Checking compatibility…"))
        val cancel = File(File(a.getFilesDir(), ".gpu-control"), "cancel-" + UUID.randomUUID())
        val cancelRequested = AtomicBoolean()
        val wait: AlertDialog = WorkspaceDialogs.builder(a).setTitle("Turnip + Zink").setView(body).setNegativeButton(t(a, "Отмена", "Cancel"), null).setPositiveButton(t(a, "Назад", "Back"), null).create()
        wait.setCancelable(false)
        wait.setCanceledOnTouchOutside(false)
        val stop = Runnable {
            cancelRequested.set(true)
            try {
                if (cancel.getParentFile()!!.isDirectory()) {
                    cancel.createNewFile()
                }
            } catch (ignored: IOException) {
            }
            if (inline == null) {
                wait.getButton(-2).setEnabled(false)
            } else {
                inline.progress(-1, t(a, "Остановка загрузки…", "Stopping download…"))
            }
            detail.setText(t(a, "Остановка загрузки…", "Stopping download…"))
        }
        if (inline == null) {
            wait.show()
            wait.getButton(-1).setEnabled(false)
            wait.getButton(-2).setOnClickListener(View.OnClickListener { v -> stop.run() })
        } else {
            inline.started(stop)
        }
        val main = Handler(Looper.getMainLooper())
        val worker: ExecutorService = Executors.newSingleThreadExecutor()
        worker.execute(Runnable {
            var ready = false
            var result = ""
            try {
                val control = File(a.getFilesDir(), ".gpu-control")
                if (Files.isSymbolicLink(control.toPath())) {
                    throw IOException("GPU_CONTROL_LINK")
                }
                if (!control.isDirectory() && !control.mkdirs()) {
                    throw IOException("GPU_CONTROL_DIRECTORY")
                }
                if (cancelRequested.get()) {
                    cancel.createNewFile()
                }
                val script = File(control, "x11-gpu.py")
                if (Files.isSymbolicLink(script.toPath())) {
                    throw IOException("GPU_CONTROL_LINK")
                }
                (a.getAssets().open("x11-gpu.py")).use { `in` ->
                    (FileOutputStream(script)).use { out ->
                        val bytes = ByteArray(8192)
                        var n: Int = 0
                        while (true) {
                            n = `in`.read(bytes)
                            if (n == -1) break
                            out.write(bytes, 0, n)
                        }
                    }
                }
                if (!script.setReadable(false, false) || !script.setWritable(false, false) || !script.setReadable(true, true) || !script.setWritable(true, true)) {
                    throw IOException("GPU_CONTROL_PERMISSIONS")
                }
                val bin: String = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                val builder = ProcessBuilder(bin + "/python3", "-B", script.getPath(), "repair", target, File(a.getFilesDir(), "workspace-x11-gpu").getPath(), cancel.getPath())
                builder.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
                builder.environment().clear()
                for (item in TermuxShellUtils.buildEnvironment(a, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                    val i: Int = item.indexOf('=')
                    if (i > 0) {
                        builder.environment().put(item.substring(0, i), item.substring(i + 1))
                    }
                }
                builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
                builder.redirectErrorStream(true)
                val process: Process = builder.start()
                process.getOutputStream().close()
                val report = StringBuilder()
                val started: Long = SystemClock.elapsedRealtime()
                var last: Long = 0L
                val reader = BufferedReader(InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                var primaryException: Throwable? = null
                try {
                    var line: String? = null
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        report.append(line).append('\n')
                        if (report.length > 8000) {
                            report.delete(0, report.length - 8000)
                        }
                        if (line.startsWith("GPU_PROGRESS:")) {
                            val parts: Array<String> = (line as java.lang.String).split(":")
                            val done: Long = java.lang.Long.parseLong(parts[1])
                            val total: Long = java.lang.Long.parseLong(parts[2])
                            val now: Long = SystemClock.elapsedRealtime()
                            if (now - last < 200 && done < total) {
                                continue
                            }
                            last = now
                            val percent: Int = Math.min(100L, done * 100 / Math.max(1L, total)).toInt()
                            val seconds: Long = if (done > 0) (now - started) * (total - done) / done / 1000 else (-1).toLong()
                            val text: String = t(a, "Загружено: ", "Downloaded: ") + done / 1000000.0 + " / " + total / 1000000.0 + " MB\n" + t(a, "Осталось на загрузку: ", "Download remaining: ") + (if (seconds < 0) t(a, "рассчитывается", "calculating") else "≈ " + seconds + t(a, " с", " s"))
                            main.post(Runnable {
                                if (inline != null) {
                                    inline.progress(percent, text)
                                } else if (wait.isShowing()) {
                                    bar.setIndeterminate(false)
                                    bar.setProgress(percent)
                                    detail.setText(text)
                                }
                            })
                        } else if (line.equals("GPU_STAGE:extract")) {
                            main.post(Runnable {
                                if (inline != null) {
                                    inline.progress(-1, t(a, "Проверка и распаковка компонентов…", "Verifying and extracting components…"))
                                } else if (wait.isShowing()) {
                                    bar.setIndeterminate(true)
                                    detail.setText(t(a, "Проверка и распаковка компонентов…", "Verifying and extracting components…"))
                                }
                            })
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
                ready = process.waitFor() == 0 && report.toString().contains("READY:gpu")
                result = report.toString()
            } catch (error: Exception) {
                result = "ERROR:GPU_SETUP_FAILED"
            }
            cancel.delete()
            val success: Boolean = ready && !cancelRequested.get()
            val report: String = if (cancelRequested.get()) "ERROR:GPU_CANCELLED" else if (success) result else failure(a, result)
            main.post(Runnable {
                if (a.isFinishing() || a.isDestroyed()) {
                    return@Runnable
                }
                if (inline != null) {
                    val applied: Boolean = success && !busy.getAsBoolean()
                    if (applied) {
                        a.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target, "turnip").apply()
                    }
                    inline.finished(applied, report)
                    updated.run()
                    return@Runnable
                }
                bar.setVisibility(View.GONE)
                wait.getButton(-2).setVisibility(View.GONE)
                if (success) {
                    if (!busy.getAsBoolean()) {
                        a.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target, "turnip").apply()
                    }
                    detail.setText(if (busy.getAsBoolean()) t(a, "Компоненты установлены. Остановите рабочий стол и выберите Turnip + Zink.", "Components installed. Stop the desktop and select Turnip + Zink.") else t(a, "Компоненты установлены. Выбран Turnip + Zink. Проверьте рабочий стол и glxinfo -B; программный режим остаётся доступным.", "Components installed. Turnip + Zink selected. Test the desktop and glxinfo -B; software mode remains available."))
                } else {
                    detail.setText(t(a, "Не удалось подготовить GPU. Выбор рендеринга не изменён.\n", "Could not prepare GPU. Rendering selection is unchanged.\n") + report)
                }
                wait.getButton(-1).setEnabled(true)
                wait.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                    wait.dismiss()
                    updated.run()
                })
            })
            worker.shutdown()
        })
    }
}
