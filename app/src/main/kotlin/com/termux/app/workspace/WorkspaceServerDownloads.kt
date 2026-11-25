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
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import org.json.JSONObject

/** One queued batch and an independent owned SFTP connection. Monitoring and terminals are untouched.*/
class WorkspaceServerDownloads : AutoCloseable {
    @JvmField val presentation: ServerTransferPresentation = ServerTransferPresentation()
    private val activity: TermuxActivity
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main: Handler = Handler(Looper.getMainLooper())
    @Volatile private var connection: ServerConnection? = null
    @Volatile private var channel: ServerSftp? = null
    @Volatile private var cancelled: Boolean = false
    @Volatile private var closed: Boolean = false
    @Volatile private var destination: LocalDownloadFile? = null
    private val picker: WorkspaceLocalFilePicker
    private var ownerProfile: String = ""
    private var choiceEpoch: Int = 0
    private var running: Boolean = false
    private var progress: AlertDialog? = null
    private var result: AlertDialog? = null
    private var started: Long = 0L
    private var lastUpdate: Long = 0L

    constructor(a: TermuxActivity) {
        activity = a
        picker = WorkspaceLocalFilePicker(a)
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    fun start(profile: ServerProfiles.Profile?, entry: JSONObject) {
        choose(profile, Collections.singletonList(entry), false)
    }

    fun quick(profile: ServerProfiles.Profile?, entry: JSONObject) {
        choose(profile, Collections.singletonList(entry), true)
    }

    fun selected(profile: ServerProfiles.Profile?, entries: MutableList<JSONObject>) {
        choose(profile, entries, false)
    }

    fun disconnect(id: String) {
        if (ownerProfile.equals(id)) {
            choiceEpoch++
            picker.cancelSelection()
            if (result != null) {
                result!!.dismiss()
            }
            cancel()
        }
    }

    private fun beginChoice(id: String): Int {
        ownerProfile = id
        val epoch: Int = ++choiceEpoch
        picker.cancelSelection()
        if (result != null) {
            result!!.dismiss()
        }
        return epoch
    }

    private fun trackChoice(dialog: AlertDialog?, epoch: Int) {
        dialog!!.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            if (choiceEpoch == epoch) {
                choiceEpoch++
            }
        })
    }

    private fun choose(profile: ServerProfiles.Profile?, entries: MutableList<JSONObject>, quick: Boolean) {
        if (closed || entries.isEmpty() || ServerConnection.disconnected(activity, profile!!.id)) {
            return
        }
        if (running) {
            presentation.foreground()
            return
        }
        val epoch: Int = beginChoice(profile.id)
        val snapshot: ServerProfiles.Profile = ServerProfiles.connectionSnapshot(profile)
        val paths: MutableList<String> = ArrayList<String>()
        for (e in entries) {
            if (!e.optBoolean("directory") && e.optLong("size", 0L) > ServerSftp.MAX_DOWNLOAD_BYTES) {
                result = WorkspaceDialogs.builder(activity).setMessage(t("Лимит скачивания — 10 ГБ на файл.", "Download limit: 10 GB per file.")).setPositiveButton("OK", null).show()
                return
            }
            paths.add(e.optString("path"))
        }
        val root: String = "download-" + UUID.randomUUID()
        if (quick) {
            activity.requestWorkspaceStorageAccess(Runnable {
                if (closed || epoch != choiceEpoch) {
                    return@Runnable
                }
                val local = WorkspaceStore.Session()
                local.environment = "termux"
                local.name = t("Память устройства", "Device storage")
                confirm(snapshot, paths, local, "/sdcard/Download", root, epoch)
            })
        } else {
            picker.chooseDirectory(t("Куда скачать?", "Choose download destination"), BiConsumer<WorkspaceStore.Session, String?> { location, path -> confirm(snapshot, paths, location, path, root, epoch) })
        }
    }

    private fun confirm(profile: ServerProfiles.Profile, paths: MutableList<String>, location: WorkspaceStore.Session, folder: String?, root: String, epoch: Int) {
        if (closed || epoch != choiceEpoch || ServerConnection.disconnected(activity, profile.id)) {
            return
        }
        result = WorkspaceDialogs.builder(activity).setTitle(t("Скачать выбранное?", "Download selection?")).setMessage(location.name + "\n" + folder + "/" + root + "\n\n" + t("Выбрано: ", "Selected: ") + paths.size + t("\nВ отдельную папку, с исходными именами и вложенностью. Существующие файлы не заменяются. Ссылки и специальные файлы пропускаются. До 10 ГБ на файл, 20 000 записей и 256 ГБ суммарно; соединение до 6 часов. Не закрывайте приложение.", "\nInto a separate folder with original names and hierarchy. Existing files are not replaced. Links and special files are skipped. Up to 10 GB per file, 20,000 entries and 256 GB total; connection up to 6 hours. Keep the app open.")).setPositiveButton(t("Скачать", "Download"), DialogInterface.OnClickListener { d, w -> transfer(profile, paths, location, folder, root, epoch) }).setNegativeButton(t("Отмена", "Cancel"), null).show()
        trackChoice(result, epoch)
    }

    private fun transfer(profile: ServerProfiles.Profile, paths: MutableList<String>, location: WorkspaceStore.Session, folder: String?, root: String, epoch: Int) {
        if (closed || running || epoch != choiceEpoch || ServerConnection.disconnected(activity, profile.id)) {
            return
        }
        choiceEpoch++
        ownerProfile = profile.id
        running = true
        cancelled = false
        started = SystemClock.uptimeMillis()
        lastUpdate = 0L
        val body = LinearLayout(activity)
        body.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = WorkspaceDialogs.dp(activity, 16.0f)
        body.setPadding(pad, pad, pad, pad)
        val status = TextView(activity)
        status.setTextColor(Pal.TEXT)
        status.setText(t("Проверка выбранных файлов…", "Checking selected files…"))
        body.addView(status)
        val bar = ProgressBar(activity, null, R.attr.progressBarStyleHorizontal)
        bar.setMax(1000)
        bar.setIndeterminate(true)
        body.addView(bar)
        progress = WorkspaceDialogs.builder(activity).setTitle(t("Скачивание", "Download")).setView(body).setNeutralButton(t("В фон", "Background"), null).setNegativeButton(t("Остановить", "Stop"), null).create()
        progress!!.setCancelable(false)
        progress!!.setCanceledOnTouchOutside(false)
        progress!!.show()
        presentation.begin(profile.id, progress, t("Проверка выбранных файлов…", "Checking selected files…"))
        progress!!.getButton(-3).setOnClickListener(View.OnClickListener { v -> presentation.background() })
        progress!!.getButton(-2).setOnClickListener(View.OnClickListener { v ->
            status.setText(t("Остановка…", "Stopping…"))
            progress!!.getButton(-2).setEnabled(false)
            cancel()
        })
        worker.execute(Runnable {
            val base: String = folder + (if (folder!!.equals("/")) "" else "/") + root
            val received: LongArray = longArrayOf(0L)
            var completed = 0
            var skipped = 0
            var success = false
            var failure: Exception? = null
            val trace: ServerDiagnostics.Trace = ServerDiagnostics.begin(profile, ServerDiagnostics.Op.DOWNLOAD)
            try {
                check()
                val owned = ServerConnection(activity, profile)
                connection = owned
                check()
                val sftp: ServerSftp = owned.sftp(360L)
                channel = sftp
                check()
                val plan: ServerDownloadPlan = ServerDownloadPlan.scan(object : ServerDownloadPlan.Source {
                    @Throws(Exception::class)
                    override fun stat(p: String): ServerSftp.Info {
                        return sftp.stat(p)
                    }

                    @Throws(Exception::class)
                    override fun list(p: String): MutableList<ServerSftp.Entry> {
                        return sftp.list(p)
                    }

                    @Throws(Exception::class)
                    override fun canonical(p: String): String {
                        return sftp.canonical(p)
                    }
                }, paths, ServerDownloadPlan.Progress { count, path ->
                    check()
                    update(status, bar, t("Проверка: ", "Scanning: ") + count + "\n" + path, 0L, (-1).toLong())
                })
                skipped = plan.skipped
                check()
                (ProjectFiles(activity, location)).use { local ->
                    // Exclusive batch root: no user-supplied existing tree is merged or overwritten.
                    local.localOperation("umask 077; mkdir -- " + ProjectFiles.target(base))
                    for (dir in plan.directories) {
                        check()
                        ServerDownloadPlan.checkedRelative(dir)
                        local.localOperation("mkdir -- " + ProjectFiles.target(base + "/" + dir))
                    }
                }
                var index = 0
                for (item in plan.files) {
                    check()
                    ServerDownloadPlan.checkedRelative(item.relative)
                    val current: Int = ++index
                    val before: Long = received[0]
                    val target: String = base + "/" + item.relative
                    val slash: Int = target.lastIndexOf('/')
                    try {
                        (LocalDownloadFile(activity, location, target.substring(0, slash), target.substring(slash + 1))).use { local ->
                            destination = local
                            check()
                            sftp.download(item.remote, local.stream, item.info, ServerSftp.Progress { bytes ->
                                check()
                                received[0] = before + bytes
                                val line: String = ServerTransferProgress.describe(received[0], plan.bytes, SystemClock.uptimeMillis() - started, WorkspaceLanguage.code(activity)!!.equals("ru"))
                                update(status, bar, current.toString() + " / " + plan.files.size + " · " + item.relative + "\n" + line, received[0], plan.bytes)
                            })
                            check()
                            local.finish()
                            completed++
                        }
                    } finally {
                        destination = null
                    }
                }
                check()
                success = true
            } catch (error: Exception) {
                failure = if (cancelled) CancellationException() else error
            } finally {
                trace.finish(trace.elapsed(), 0L, received[0], failure)
                val sftp: ServerSftp? = channel
                channel = null
                if (sftp != null) {
                    sftp.close()
                }
                val owned: ServerConnection? = connection
                connection = null
                if (owned != null) {
                    owned.close()
                }
            }
            val done: Boolean = success
            val count: Int = completed
            val omitted: Int = skipped
            val error: String? = if (failure == null) "" else failure.message
            main.post(Runnable {
                running = false
                val hidden: Boolean = presentation.finish()
                if (progress != null) {
                    progress!!.dismiss()
                }
                progress = null
                if (closed) {
                    return@Runnable
                }
                var message: String = (if (done) t("Скачано и завершено.", "Download complete.") else if (cancelled) t("Передача остановлена. Завершённые файлы сохранены.", "Transfer stopped. Completed files were kept.") else t("Передача не завершена. Завершённые файлы сохранены. Проверьте сеть, права и свободное место.", "Download incomplete. Completed files were kept. Check network, permissions and free space.")) + "\n" + base + "\n" + t("Готово файлов: ", "Completed files: ") + count + "\n" + t("Пропущено ссылок / специальных файлов: ", "Skipped links / special files: ") + omitted
                if (!done && error != null) {
                    message += "\n" + error.substring(0, Math.min(180, error.length))
                }
                result = WorkspaceDialogs.builder(activity).setTitle(t("Скачивание", "Download")).setMessage(message).setPositiveButton("OK", null).create()
                result!!.setCanceledOnTouchOutside(false)
                if (hidden && done) {
                    activity.showToast(t("Скачивание завершено", "Download complete"), false)
                } else {
                    result!!.show()
                }
            })
        })
    }

    fun move(selectedProfile: ServerProfiles.Profile?, entries: MutableList<JSONObject>, folder: String, finished: Runnable) {
        if (closed || running || entries.isEmpty() || ServerConnection.disconnected(activity, selectedProfile!!.id)) {
            return
        }
        val profile: ServerProfiles.Profile = ServerProfiles.connectionSnapshot(selectedProfile)
        val epoch: Int = beginChoice(profile.id)
        val paths: MutableList<String> = ArrayList<String>()
        for (entry in entries) {
            paths.add(entry.optString("path"))
        }
        result = WorkspaceDialogs.builder(activity).setTitle(t("Переместить сюда?", "Move here?")).setMessage(t("Выбрано: ", "Selected: ") + paths.size + "\n" + folder + t("\nТолько внутри этого сервера, без перезаписи. Между дисками операция может быть недоступна. При ошибке уже перемещённые объекты остаются в папке назначения.", "\nWithin this server only, without overwriting. Moving between filesystems may be unavailable. If interrupted, already moved entries remain at the destination.")).setNegativeButton(t("Отмена", "Cancel"), null).setPositiveButton(t("Переместить", "Move"), DialogInterface.OnClickListener { dialog, which ->
            if (closed || running || epoch != choiceEpoch || ServerConnection.disconnected(activity, profile.id)) {
                return@OnClickListener
            }
            choiceEpoch++
            ownerProfile = profile.id
            running = true
            cancelled = false
            val state = TextView(activity)
            state.setText(t("Перемещение…", "Moving…"))
            state.setPadding(24, 24, 24, 24)
            progress = WorkspaceDialogs.builder(activity).setTitle(t("Перемещение", "Move")).setView(state).setNegativeButton(t("Остановить", "Stop"), DialogInterface.OnClickListener { d, w -> cancel() }).create()
            progress!!.setCancelable(false)
            progress!!.setCanceledOnTouchOutside(false)
            progress!!.show()
            worker.execute(Runnable {
                var moved = 0
                var failure: Exception? = null
                val trace: ServerDiagnostics.Trace = ServerDiagnostics.begin(profile, ServerDiagnostics.Op.FILE_MOVE)
                try {
                    check()
                    val owned = ServerConnection(activity, profile)
                    connection = owned
                    check()
                    (owned.sftp()).use { sftp ->
                        channel = sftp
                        val target: String = sftp.canonical(folder)
                        for (path in paths) {
                            check()
                            val info: ServerSftp.Info = sftp.stat(path)
                            sftp.move(path, target, info)
                            moved++
                        }
                    }
                } catch (e: Exception) {
                    failure = e
                } finally {
                    channel = null
                    val owned: ServerConnection? = connection
                    connection = null
                    if (owned != null) {
                        owned.close()
                    }
                    trace.finish(trace.elapsed(), 0L, 0L, failure)
                }
                val count: Int = moved
                val error: String? = if (failure == null) "" else failure.message
                main.post(Runnable {
                    running = false
                    if (progress != null) {
                        progress!!.dismiss()
                    }
                    progress = null
                    if (closed) {
                        return@Runnable
                    }
                    result = WorkspaceDialogs.builder(activity).setTitle(t("Перемещение", "Move")).setMessage(t("Перемещено: ", "Moved: ") + count + " / " + paths.size + "\n" + folder + (if (error == null || error.isEmpty()) "" else "\n" + error)).setPositiveButton("OK", null).show()
                    finished.run()
                })
            })
        }).show()
        trackChoice(result, epoch)
    }

    private fun check() {
        if (cancelled || closed) {
            throw CancellationException()
        }
    }

    private fun update(status: TextView, bar: ProgressBar, text: String, bytes: Long, total: Long) {
        val now: Long = SystemClock.uptimeMillis()
        if (now - lastUpdate < 150 && bytes != total) {
            return
        }
        lastUpdate = now
        main.post(Runnable {
            if (closed || cancelled) {
                return@Runnable
            }
            status.setText(text)
            presentation.update(text, bytes, total)
            bar.setIndeterminate(total < 0)
            if (total >= 0) {
                bar.setProgress(if (total == 0L) 1000 else Math.min(1000.0, bytes * 1000.0 / total).toInt())
            }
        })
    }

    private fun cancel() {
        cancelled = true
        val local: LocalDownloadFile? = destination
        if (local != null) {
            local.cancel()
        }
        val owned: ServerConnection? = connection
        if (owned != null) {
            owned.close()
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        presentation.finish()
        picker.close()
        cancel()
        if (progress != null) {
            progress!!.dismiss()
        }
        if (result != null) {
            result!!.dismiss()
        }
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
    }
}
