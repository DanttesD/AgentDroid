package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.app.TermuxActivity
import com.termux.x11.VirglInstaller
import com.termux.x11.X11InstallProgress
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Renderer setup uses a pipe-owned process group; cancellation never adopts host PIDs.*/
object WorkspaceDesktopVirgl {
    @JvmStatic
    private fun t(a: TermuxActivity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun prepare(a: TermuxActivity, target: String, busy: BooleanSupplier, updated: Runnable, inline: WorkspaceDesktopGpu.Inline?, verified: Boolean) {
        if (!VirglInstaller.supported(target)) {
            if (inline != null) {
                inline.finished(false, "ERROR:VIRGL_TARGET")
            }
            updated.run()
            return
        }
        if (verified && VirglInstaller.installed(a, target)) {
            a.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target, "virgl").apply()
            updated.run()
            return
        }
        val confirm: AlertDialog = WorkspaceDialogs.builder(a).setTitle(t(a, "Установить VirGL?", "Install VirGL?")).setMessage(t(a, "Экспериментальный рендер через ANGLE на стороне Android. В Termux будут установлены virglrenderer-android, angle-android и Mesa из его репозиториев. В Linux-окружениях библиотеки Mesa устанавливаются только внутри выбранной системы. В Arch их установка также обновит пакеты этого Arch (pacman -Syu). Системные пакеты Android не меняются. Для каждого рабочего стола запускается собственный сервер с отдельным сокетом; он и его дочерние процессы останавливаются вместе со столом. Alpine скачивает отдельный клиент Mesa с проверкой SHA-256 и сохраняет его для работы без интернета, без замены системной Mesa. Termux, Debian, Fedora, Void и Arch используют ANGLE Vulkan. Совместимость и скорость зависят от устройства.", "Experimental rendering through ANGLE on Android. Installs virglrenderer-android, angle-android and Mesa from Termux repositories. Linux environments also require Mesa libraries installed only inside the selected system. On Arch their installation also upgrades this Arch’s packages (pacman -Syu). Android system packages are unchanged. Each desktop owns its own server and socket; it and its child processes stop with the desktop. Alpine downloads a separate SHA-256-verified Mesa client and caches it for offline reuse without replacing system Mesa. Termux, Debian, Fedora, Void and Arch use ANGLE Vulkan. Compatibility and speed depend on the device.")).setNegativeButton(t(a, "Назад", "Back"), DialogInterface.OnClickListener { d, w ->
            if (inline != null) {
                inline.finished(false, "ERROR:GPU_CANCELLED")
            }
            updated.run()
        }).setPositiveButton(t(a, "Установить", "Install"), DialogInterface.OnClickListener { d, w ->
            if (busy.getAsBoolean()) {
                if (inline != null) {
                    inline.finished(false, "ERROR:GPU_BUSY")
                }
                updated.run()
                return@OnClickListener
            }
            install(a, target, busy, updated, inline)
        }).create()
        confirm.setCancelable(false)
        confirm.setCanceledOnTouchOutside(false)
        confirm.show()
    }

    @JvmStatic
    private fun install(a: TermuxActivity, target: String, busy: BooleanSupplier, updated: Runnable, inline: WorkspaceDesktopGpu.Inline?) {
        val main = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean()
        val owned = AtomicReference<VirglInstaller?>()
        val body: LinearLayout = AgentStatusUi.body(a)
        val bar = ProgressBar(a, null, R.attr.progressBarStyleHorizontal)
        bar.setIndeterminate(true)
        bar.setMax(100)
        body.addView(bar)
        val detail: TextView = AgentStatusUi.info(body, t(a, "Подготовка компонентов VirGL…", "Preparing VirGL components…"))
        val wait: AlertDialog = WorkspaceDialogs.builder(a).setTitle("VirGL").setView(body).setNegativeButton(t(a, "Отмена", "Cancel"), null).setPositiveButton(t(a, "Назад", "Back"), null).create()
        wait.setCancelable(false)
        wait.setCanceledOnTouchOutside(false)
        val stop = Runnable {
            cancelled.set(true)
            val job: VirglInstaller? = owned.get()
            if (job != null) {
                job.close()
            }
            if (inline != null) {
                inline.progress(-1, t(a, "Остановка установщика…", "Stopping installer…"))
            } else {
                detail.setText(t(a, "Остановка установщика…", "Stopping installer…"))
                wait.getButton(-2).setEnabled(false)
            }
        }
        if (inline != null) {
            inline.started(stop)
        } else {
            wait.show()
            wait.getButton(-1).setEnabled(false)
            wait.getButton(-2).setOnClickListener(View.OnClickListener { v -> stop.run() })
        }
        val worker: ExecutorService = Executors.newSingleThreadExecutor()
        worker.execute(Runnable {
            var success = false
            val report = StringBuilder()
            var job: VirglInstaller? = null
            try {
                if (cancelled.get()) {
                    throw IOException("Cancelled")
                }
                job = VirglInstaller.start(a.getApplicationContext(), target)
                owned.set(job)
                if (cancelled.get()) {
                    job!!.close()
                }
                val progress = X11InstallProgress()
                val tail = StringBuilder()
                val ready = AtomicBoolean()
                val exited = AtomicBoolean()
                val last: LongArray = longArrayOf(0L)
                job!!.read(Consumer<String> { chunk ->
                    report.append(chunk)
                    if (report.length > 8192) {
                        report.delete(0, report.length - 8192)
                    }
                    tail.append(chunk)
                    var end: Int = 0
                    while (true) {
                        end = tail.indexOf("\n")
                        if (end < 0) break
                        val line: String = (tail.substring(0, end) as java.lang.String).trim()
                        tail.delete(0, end + 1)
                        if (line.equals("READY:virgl")) {
                            ready.set(true)
                        }
                        if (line.equals("DESKTOP_CHILD_EXIT=0")) {
                            exited.set(true)
                        }
                    }
                    if (tail.length > 8192) {
                        tail.delete(0, tail.length - 8192)
                    }
                    val now: Long = SystemClock.elapsedRealtime()
                    progress.feed(chunk, now)
                    if (now - last[0] < 200) {
                        return@Consumer
                    }
                    last[0] = now
                    val percent: Int = progress.percent()
                    val eta: Long = progress.etaSeconds(now)
                    var text: String? = progress.currentItem()
                    if (text!!.isEmpty()) {
                        text = t(a, "Установка компонентов VirGL…", "Installing VirGL components…")
                    }
                    if (eta >= 0) {
                        text += "\n" + t(a, "Осталось: ≈ ", "Remaining: ≈ ") + eta + t(a, " с", " s")
                    }
                    if (progress.totalBytes() > 0) {
                        text += "\n" + java.lang.String.format(Locale.ROOT, "%.1f / %.1f MB", progress.downloadedBytes() / 1000000.0, progress.totalBytes() / 1000000.0)
                    }
                    val status: String? = text
                    main.post(Runnable {
                        if (cancelled.get() || a.isFinishing() || a.isDestroyed()) {
                            return@Runnable
                        }
                        if (inline != null) {
                            inline.progress(percent, status)
                        } else {
                            bar.setIndeterminate(percent < 0)
                            if (percent >= 0) {
                                bar.setProgress(percent)
                            }
                            detail.setText(status)
                        }
                    })
                })
                val exit: Int = job.waitFor()
                success = (exit == 0 || exit == 137) && ready.get() && exited.get() && !cancelled.get()
                if (success) {
                    job.markReady()
                }
            } catch (error: Exception) {
                success = false
                report.append("\nERROR:VIRGL_SETUP_FAILED ").append(error.javaClass.getSimpleName())
            } finally {
                owned.set(null)
                if (job != null) {
                    job.close()
                    try {
                        job.waitFor()
                        job.cleanup()
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            val result: Boolean = success
            val diagnostic: String = if (cancelled.get()) "ERROR:GPU_CANCELLED" else report.toString()
            main.post(Runnable {
                if (a.isFinishing() || a.isDestroyed()) {
                    return@Runnable
                }
                val applied: Boolean = result && !busy.getAsBoolean()
                if (applied) {
                    a.getSharedPreferences("workspace-x11", 0).edit().putString("renderer:" + target, "virgl").apply()
                }
                if (inline != null) {
                    inline.finished(applied, diagnostic)
                    updated.run()
                } else {
                    bar.setVisibility(View.GONE)
                    wait.getButton(-2).setVisibility(View.GONE)
                    detail.setText(if (applied) t(a, "VirGL установлен и выбран.", "VirGL installed and selected.") else diagnostic)
                    wait.getButton(-1).setEnabled(true)
                    wait.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                        wait.dismiss()
                        updated.run()
                    })
                }
            })
            worker.shutdown()
        })
    }
}
