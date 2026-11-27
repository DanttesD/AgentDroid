package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer

/** User-selected documents stream directly into private remote temporaries over SFTP.*/
class WorkspaceServerUploads : AutoCloseable {
    @JvmField val presentation: ServerTransferPresentation = ServerTransferPresentation()
    private val activity: TermuxActivity
    private val completed: Runnable
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main: Handler = Handler(Looper.getMainLooper())
    private val picker: WorkspaceLocalFilePicker
    private val dialogs: MutableList<AlertDialog?> = ArrayList<AlertDialog?>()
    private val documents: MutableList<Document> = ArrayList<Document>()
    private var profile: ServerProfiles.Profile? = null
    private var directory: String? = null
    private var running: Boolean = false
    private var closed: Boolean = false
    @Volatile private var cancelled: Boolean = false
    @Volatile private var connection: ServerConnection? = null
    @Volatile private var sftp: ServerSftp? = null
    @Volatile private var source: InputStream? = null
    private var decision: CompletableFuture<String>? = null
    private var progress: AlertDialog? = null
    private var status: TextView? = null
    private var bar: ProgressBar? = null
    private var index: Int = 0
    private var uploaded: Int = 0
    private var skipped: Int = 0
    private var choiceEpoch: Int = 0
    private var lastUpdate: Long = 0L
    private var startedAt: Long = 0L

    private class Document {
        @JvmField val location: WorkspaceStore.Session
        @JvmField val entry: ProjectFiles.Entry
        @JvmField var name: String
        @JvmField val size: Long

        constructor(s: WorkspaceStore.Session, e: ProjectFiles.Entry) {
            location = s.copy()
            entry = e
            name = e.name
            size = e.size
        }
    }

    constructor(a: TermuxActivity, done: Runnable) {
        activity = a
        completed = done
        picker = WorkspaceLocalFilePicker(a)
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun show(d: AlertDialog?) {
        dialogs.add(d)
        d!!.setCanceledOnTouchOutside(false)
        d.setOnDismissListener(DialogInterface.OnDismissListener { x -> dialogs.remove(d) })
        d.show()
    }

    private fun error(message: String) {
        if (!closed) {
            show(WorkspaceDialogs.builder(activity).setTitle(t("Отправка файлов", "Upload files")).setMessage(message).setPositiveButton("OK", null).create())
        }
    }

    fun disconnect(id: String) {
        if (profile != null && profile!!.id.equals(id)) {
            choiceEpoch++
            picker.cancelSelection()
            cancel()
            for (d in ArrayList<AlertDialog?>(dialogs)) {
                if (d !== progress) {
                    d!!.dismiss()
                }
            }
        }
    }

    fun active(): Boolean {
        return running
    }

    fun pick(p: ServerProfiles.Profile?, path: String?) {
        if (closed || ServerConnection.disconnected(activity, p!!.id)) {
            return
        }
        if (active()) {
            presentation.foreground()
            return
        }
        if (path == null || !path.startsWith("/")) {
            error(t("Сначала откройте каталог сервера.", "Open a server directory first."))
            return
        }
        val epoch: Int = ++choiceEpoch
        picker.cancelSelection()
        for (d in ArrayList<AlertDialog?>(dialogs)) {
            if (d !== progress) {
                d!!.dismiss()
            }
        }
        documents.clear()
        profile = ServerProfiles.connectionSnapshot(p)
        directory = path
        val serverId: String = profile!!.id
        picker.chooseMultiple(t("Выбрать файлы для загрузки", "Choose files to upload"), BiConsumer<WorkspaceStore.Session, MutableList<ProjectFiles.Entry>> { location, entries ->
            if (closed || epoch != choiceEpoch || ServerConnection.disconnected(activity, serverId)) {
                return@BiConsumer
            }
            if (entries.isEmpty() || entries.size > 50) {
                error(t("Выберите от 1 до 50 файлов.", "Select 1 to 50 files."))
                return@BiConsumer
            }
            val selected: MutableList<Document> = ArrayList<Document>()
            for (entry in entries) {
                if (entry.directory || !ServerSftp.validName(entry.name) || entry.size < 0 || entry.size > ServerSftp.MAX_BYTES) {
                    error(t("Недопустимый файл. Лимит — 2 ГБ на файл.", "Invalid file. Limit: 2 GB per file."))
                    return@BiConsumer
                }
                selected.add(Document(location, entry))
            }
            documents.clear()
            documents.addAll(selected)
            confirm()
        })
    }

    private fun confirm() {
        val epoch: Int = choiceEpoch
        val body = StringBuilder(profile!!.name + "\n" + profile!!.user + "@" + profile!!.host + ":" + profile!!.port + "\n" + directory + "\n\n")
        for (d in documents) {
            body.append(d.name).append(" · ").append(if (d.size < 0) t("размер неизвестен", "unknown size") else ServerFileInfo.size(d.size, WorkspaceLanguage.code(activity)!!.equals("ru"))).append('\n')
        }
        body.append(t("\nСовпавшие имена будут запрошены отдельно. Передача по SFTP в правах выбранной SSH-учётной записи.", "\nConflicting names are reviewed separately. SFTP uses the selected SSH account's permissions."))
        val dialog: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Отправить на сервер?", "Upload to server?")).setMessage(body).setPositiveButton(t("Отправить", "Upload"), DialogInterface.OnClickListener { d, w ->
            if (epoch == choiceEpoch) {
                start()
            }
        }).setNegativeButton(t("Отмена", "Cancel"), null).create()
        show(dialog)
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { d ->
            dialogs.remove(dialog)
            if (choiceEpoch == epoch) {
                choiceEpoch++
            }
        })
    }

    private fun start() {
        if (closed || running || ServerConnection.disconnected(activity, profile!!.id)) {
            return
        }
        choiceEpoch++
        running = true
        cancelled = false
        skipped = 0
        uploaded = skipped
        index = uploaded
        val body = LinearLayout(activity)
        body.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = WorkspaceDialogs.dp(activity, 16.0f)
        body.setPadding(pad, pad, pad, pad)
        status = TextView(activity)
        status!!.setTextColor(Pal.TEXT)
        body.addView(status)
        bar = ProgressBar(activity, null, R.attr.progressBarStyleHorizontal)
        bar!!.setMax(1000)
        body.addView(bar, LinearLayout.LayoutParams(-1, -2))
        progress = WorkspaceDialogs.builder(activity).setTitle(t("Отправка файлов", "Uploading files")).setView(body).setNeutralButton(t("В фон", "Background"), null).setNegativeButton(t("Остановить", "Stop"), null).create()
        progress!!.setCancelable(false)
        show(progress)
        presentation.begin(profile!!.id, progress, t("Подключение…", "Connecting…"))
        progress!!.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(View.OnClickListener { v -> presentation.background() })
        progress!!.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(View.OnClickListener { v -> cancel() })
        worker.execute(Runnable(this::transfer))
    }

    private fun cancel() {
        cancelled = true
        if (status != null) {
            status!!.setText(t("Остановка и очистка временного файла…", "Stopping and cleaning temporary file…"))
        }
        if (progress != null) {
            progress!!.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false)
        }
        val choice: CompletableFuture<String>? = decision
        if (choice != null) {
            choice.completeExceptionally(IOException("CANCELLED"))
        }
        val channel: ServerSftp? = sftp
        if (channel != null) {
            channel.close()
        }
        val c: ServerConnection? = connection
        if (c != null) {
            c.close()
        }
        val input: InputStream? = source
        if (input != null) {
            try {
                input.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun transfer() {
        var failed = false
        var cleanupFailed = false
        var leftover: String? = null
        try {
            connection = ServerConnection(activity, profile)
            if (cancelled) {
                throw IOException("CANCELLED")
            }
            sftp = connection!!.sftp()
            val target: String = sftp!!.canonical(directory)
            index = 0
            while (index < documents.size) {
                if (cancelled) {
                    throw IOException("CANCELLED")
                }
                val doc: Document = documents.get(index)
                var expected: ServerSftp.Info
                while (true) {
                    expected = sftp!!.stat(target + (if (target.equals("/")) "" else "/") + doc.name)
                    if (!expected.exists) {
                        break
                    }
                    val answer: String = choose(doc, expected, sftp!!.canReplace())
                    if (answer.equals("skip")) {
                        skipped++
                        break
                    }
                    if (answer.equals("replace")) {
                        break
                    }
                    doc.name = answer.substring(5)
                }
                // A skip decision is handled before any local input or remote output is opened.
                if (skipCurrent) {
                    skipCurrent = false
                    index++
                    continue
                }
                val current: Document = doc
                lastUpdate = 0L
                startedAt = SystemClock.uptimeMillis()
                postProgress(current, 0L)
                val local = ProjectFiles(activity, doc.location)
                try {
                    source = local.openUpload(doc.entry)
                } catch (e: Exception) {
                    local.close()
                    throw e
                }
                val trace: ServerDiagnostics.Trace = ServerDiagnostics.begin(profile, ServerDiagnostics.Op.UPLOAD)
                val acknowledged: LongArray = longArrayOf(0L)
                var failure: Exception? = null
                try {
                    (source).use { input ->
                        sftp!!.upload(input, target, doc.name, expected, ServerSftp.Progress { bytes ->
                            acknowledged[0] = bytes
                            if (cancelled) {
                                throw CancellationException()
                            }
                            postProgress(current, bytes)
                        })
                    }
                } catch (e: Exception) {
                    failure = if (cancelled) CancellationException() else e
                    throw e
                } finally {
                    source = null
                    trace.finish(trace.elapsed(), 0L, acknowledged[0], failure)
                }
                uploaded++
                index++
            }
        } catch (e: Exception) {
            failed = !cancelled
        } finally {
            val channel: ServerSftp? = sftp
            if (channel != null) {
                leftover = channel.pendingTemp()
            }
            if (channel != null) {
                channel.close()
            }
            val c: ServerConnection? = connection
            if (c != null) {
                c.close()
            }
            sftp = null
            connection = null
            if (leftover != null && ServerConnection.disconnected(activity, profile!!.id)) {
                cleanupFailed = true
            } else if (leftover != null) {
                try {
                    (ServerConnection(activity, profile)).use { cleanup ->
                        (cleanup.sftp()).use { clean ->
                            clean.removeTemporary(leftover)
                        }
                    }
                } catch (e: Exception) {
                    cleanupFailed = true
                }
            }
            val failure: Boolean = failed
            val dirty: Boolean = cleanupFailed
            val remaining: String? = leftover
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
                var summary: String = t("Отправлено: ", "Uploaded: ") + uploaded + t(" · Пропущено: ", " · Skipped: ") + skipped
                if (cancelled) {
                    summary += "\n" + t("Передача остановлена. Если остановка совпала с публикацией, проверьте итоговый каталог.", "Transfer stopped. If stopped during publication, check the destination directory.")
                }
                if (failure) {
                    summary += "\n" + t("Передача не завершена. Проверьте сеть, права, свободное место и поддержку SFTP. При потере связи в момент публикации файл уже мог быть отправлен — проверьте каталог.", "Transfer incomplete. Check network, permissions, free space and SFTP support. If connectivity was lost during publication, the file may already have been uploaded: check the directory.")
                }
                if (dirty) {
                    summary += "\n" + t("Не удалось удалить временный файл: ", "Could not remove temporary file: ") + remaining
                }
                if (hidden && !failure && !dirty && !cancelled) {
                    activity.showToast(t("Загрузка на сервер завершена", "Upload complete"), false)
                } else {
                    error(summary)
                }
                completed.run()
            })
        }
    }

    private var skipCurrent: Boolean = false

    @Throws(Exception::class)
    private fun choose(doc: Document, info: ServerSftp.Info, replace: Boolean): String {
        val choice = CompletableFuture<String>()
        decision = choice
        main.post(Runnable {
            if (closed || cancelled) {
                choice.completeExceptionally(IOException("CANCELLED"))
                return@Runnable
            }
            val body = LinearLayout(activity)
            body.setOrientation(LinearLayout.VERTICAL)
            val label = TextView(activity)
            label.setTextColor(Pal.TEXT)
            label.setText(t("Файл уже существует. Новое имя или замена обычного файла. Замена атомарная, но не блокирует внешних редакторов; владелец нового файла — SSH-пользователь.", "File exists. Choose a new name or replace a regular file. Replacement is atomic, but does not lock external editors; the new file belongs to the SSH user."))
            body.addView(label)
            val name = EditText(activity)
            name.setSingleLine(true)
            name.setText(doc.name)
            body.addView(name)
            val stop = Button(activity)
            stop.setText(t("Остановить передачу", "Stop upload"))
            body.addView(stop)
            val d: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(doc.name).setView(body).setPositiveButton(t("Переименовать", "Rename"), null).setNeutralButton(t("Заменить", "Replace"), DialogInterface.OnClickListener { x, w -> choice.complete("replace") }).setNegativeButton(t("Пропустить", "Skip"), DialogInterface.OnClickListener { x, w -> choice.complete("skip") }).create()
            d.setCancelable(false)
            show(d)
            stop.setOnClickListener(View.OnClickListener { v ->
                d.dismiss()
                cancel()
            })
            d.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(info.regular() && replace)
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                val n: String = name.getText().toString()
                if (!ServerSftp.validName(n) || n.equals(doc.name)) {
                    name.setError(t("Введите другое имя", "Enter a different name"))
                    return@OnClickListener
                }
                d.dismiss()
                choice.complete("name:" + n)
            })
        })
        var answer: String
        try {
            answer = choice.get()
        } finally {
            decision = null
        }
        skipCurrent = answer.equals("skip")
        return answer
    }

    private fun postProgress(doc: Document, bytes: Long) {
        val now: Long = SystemClock.uptimeMillis()
        if (bytes != 0L && now - lastUpdate < 150) {
            return
        }
        lastUpdate = now
        val number: Int = index + 1
        main.post(Runnable {
            if (closed || cancelled || progress == null) {
                return@Runnable
            }
            status!!.setText(number.toString() + "/" + documents.size + " · " + doc.name + "\n" + ServerTransferProgress.describe(bytes, doc.size, Math.max(0L, now - startedAt), WorkspaceLanguage.code(activity)!!.equals("ru")))
            bar!!.setIndeterminate(doc.size <= 0)
            presentation.update(status!!.getText().toString(), bytes, if (doc.size <= 0) (-1).toLong() else doc.size)
            if (doc.size > 0) {
                bar!!.setProgress(Math.min(1000L, bytes * 1000 / doc.size).toInt())
            }
        })
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        presentation.finish()
        picker.close()
        cancel()
        for (d in ArrayList<AlertDialog?>(dialogs)) {
            d!!.dismiss()
        }
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
