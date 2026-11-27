package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.drawable.Drawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Objects
import java.util.function.Consumer

/** Shared stable dialog; refreshing it never starts a network request.*/
object DiagnosticsPanel {
    interface Source {
        fun refresh() {
        }

        fun hasX11(): Boolean {
            return false
        }

        fun x11(): String {
            return ""
        }

        fun prepareX11(done: Runnable) {
            done.run()
        }

        fun hasJournalControl(): Boolean {
            return true
        }

        fun capacity(): Int {
            return WorkspaceDiagnostics.LIMIT
        }

        fun capacitySummary(): String {
            return ""
        }

        fun prepareExport(action: Runnable) {
            action.run()
        }

        fun actionLabel(): String {
            return ""
        }

        fun action() {
        }

        fun pauseLabel(): String {
            return ""
        }

        fun canPause(): Boolean {
            return true
        }

        fun privacy(): String {
            return ""
        }

        fun title(): String

        fun summary(): String

        fun events(): String

        fun report(): String

        fun id(): String

        fun bytes(): Int

        fun dropped(): Int

        fun paused(): Boolean

        @Throws(Exception::class)
        fun paused(value: Boolean)

        fun hasMode(): Boolean {
            return false
        }

        fun extended(): Boolean {
            return false
        }

        fun extended(value: Boolean) {
        }
    }

    @JvmStatic
    fun tr(a: TermuxActivity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    private fun dp(a: TermuxActivity, n: Int): Int {
        return WorkspaceDialogs.dp(a, n.toFloat())
    }

    @JvmStatic
    private fun label(a: TermuxActivity, text: String, size: Int): TextView {
        val v = TextView(a)
        v.setText(text)
        v.setTextColor(Pal.TEXT)
        v.setTextSize(size.toFloat())
        return v
    }

    @JvmStatic
    private fun button(a: TermuxActivity, text: String, click: Runnable): TextView {
        val v: TextView = label(a, text, 13)
        v.setGravity(Gravity.CENTER)
        v.setMinHeight(dp(a, 48))
        v.setPadding(dp(a, 8), dp(a, 4), dp(a, 8), dp(a, 4))
        v.setBackground(WorkspaceDialogs.background(a, false))
        v.setTextColor(Pal.ACCENT)
        v.setOnClickListener(View.OnClickListener { w -> click.run() })
        return v
    }

    @JvmStatic
    private fun selected(a: TermuxActivity, button: TextView, value: Boolean) {
        if (button.getTag() == null || button.isSelected() != value) {
            button.setTag(java.lang.Boolean.TRUE)
            button.setSelected(value)
            button.setBackground(WorkspaceDialogs.background(a, value))
        }
    }

    @JvmStatic
    fun clipboardText(report: String): String {
        return if (report.length <= 96000) report else report.substring(0, 1200) + "\n[Clipboard excerpt; save full report to Download]\n" + report.substring(report.length - 94000)
    }

    @JvmStatic
    fun show(a: TermuxActivity, source: Source): AlertDialog {
        val main = Handler(Looper.getMainLooper())
        val closed: BooleanArray = booleanArrayOf(false)
        val saving: BooleanArray = booleanArrayOf(false)
        val tab: IntArray = intArrayOf(0)
        val body = LinearLayout(a)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 8))
        val tabs = LinearLayout(a)
        val overview: TextView = button(a, tr(a, "Сводка", "Overview"), Runnable {  })
        val events: TextView = button(a, tr(a, "События", "Events"), Runnable {  })
        tabs.addView(overview, LinearLayout.LayoutParams(0, dp(a, 44), 1.0f))
        val right = LinearLayout.LayoutParams(0, dp(a, 44), 1.0f)
        right.setMarginStart(dp(a, 8))
        tabs.addView(events, right)
        body.addView(tabs)
        if (!source.actionLabel().isEmpty()) {
            body.addView(button(a, source.actionLabel(), Runnable(source::action)))
        }
        val modes = LinearLayout(a)
        val normal: TextView = button(a, tr(a, "Обычный", "Normal"), Runnable {  })
        val detail: TextView = button(a, tr(a, "Подробный", "Detailed"), Runnable {  })
        val x11: TextView = button(a, "X11", Runnable {  })
        normal.setTag("diagnostics-normal")
        detail.setTag("diagnostics-detailed")
        x11.setTag("diagnostics-x11")
        val modePair = LinearLayout(a)
        modePair.addView(normal, LinearLayout.LayoutParams(0, dp(a, 44), 1.0f))
        modePair.addView(detail, LinearLayout.LayoutParams(0, dp(a, 44), 1.0f))
        modes.addView(modePair, LinearLayout.LayoutParams(0, dp(a, 44), 1.0f))
        if (source.hasX11()) {
            normal.setTextSize(11.0f)
            detail.setTextSize(11.0f)
            normal.setPadding(0, 0, 0, 0)
            detail.setPadding(0, 0, 0, 0)
            val xp = LinearLayout.LayoutParams(0, dp(a, 44), 1.0f)
            xp.setMarginStart(dp(a, 8))
            modes.addView(x11, xp)
        }
        if (source.hasMode()) {
            body.addView(modes)
        }
        val scroll = ScrollView(a)
        val content = LinearLayout(a)
        content.setOrientation(LinearLayout.VERTICAL)
        content.setPadding(0, dp(a, 10), 0, dp(a, 10))
        val summary: TextView = label(a, "", 14)
        summary.setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12))
        summary.setBackground(WorkspaceDialogs.background(a, false))
        content.addView(summary)
        val lines: TextView = label(a, "", 12)
        lines.setTextIsSelectable(true)
        lines.setPadding(dp(a, 6), dp(a, 12), dp(a, 6), dp(a, 12))
        content.addView(lines)
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.0f))
        val capacity: TextView = label(a, "", 11)
        body.addView(capacity)
        val progress = ProgressBar(a, null, R.attr.progressBarStyleHorizontal)
        progress.setMax(source.capacity())
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(a, 6)))
        val status: TextView = label(a, "", 11)
        body.addView(status)
        val pause: TextView = button(a, "", Runnable {  })
        val copy: TextView = button(a, tr(a, "Скопировать отчёт", "Copy report"), Runnable {  })
        val path: TextView = button(a, tr(a, "Скопировать путь", "Copy path"), Runnable {  })
        val download: TextView = button(a, tr(a, "В Download", "To Download"), Runnable {  })
        pause.setVisibility(if (source.hasJournalControl()) View.VISIBLE else View.GONE)
        download.setTag("diagnostics-download")
        path.setTag("diagnostics-copy-path")
        copy.setTag("diagnostics-copy")
        val iconButtons: Array<TextView> = arrayOf<TextView>(pause, copy, path, download)
        val icons: IntArray = intArrayOf(com.termux.R.drawable.workspace_diag_pause, com.termux.R.drawable.workspace_copy, com.termux.R.drawable.workspace_folder, com.termux.R.drawable.workspace_diag_download)
        for (index in 0 until (icons.size)) {
            val icon: Drawable = a.getDrawable(icons[index])!!.mutate()
            icon.setTint(Pal.ACCENT)
            icon.setBounds(0, 0, dp(a, 18), dp(a, 18))
            iconButtons[index].setCompoundDrawables(icon, null, null, null)
            iconButtons[index].setCompoundDrawablePadding(dp(a, 6))
        }
        for (pair in arrayOf<Array<TextView>>(arrayOf<TextView>(pause, copy), arrayOf<TextView>(path, download))) {
            val row = LinearLayout(a)
            val rowParams = LinearLayout.LayoutParams(-1, -2)
            rowParams.topMargin = dp(a, 6)
            body.addView(row, rowParams)
            row.addView(pair[0], LinearLayout.LayoutParams(0, dp(a, 48), 1.0f))
            val p = LinearLayout.LayoutParams(0, dp(a, 48), 1.0f)
            p.setMarginStart(dp(a, 8))
            row.addView(pair[1], p)
        }
        val privacy: TextView = label(a, if (source.privacy().isEmpty()) tr(a, "Без команд, переписки, адресов, путей и секретов. Журнал в памяти; экспорт сохраняет отчёт.", "No commands, chats, addresses, paths or secrets. In-memory journal; export saves a report.") else source.privacy(), 11)
        privacy.setTextColor(Pal.MUTED)
        privacy.setPadding(0, dp(a, 6), 0, 0)
        body.addView(privacy)
        val holder = FrameLayout(a)
        holder.addView(body, FrameLayout.LayoutParams(-1, (a.getResources().getDisplayMetrics().heightPixels * 0.67).toInt()))
        val dialog: AlertDialog = WorkspaceDialogs.builder(a).setTitle(source.title()).setView(holder).setPositiveButton(WorkspaceText.tr("Закрыть"), null).create()
        dialog.setCanceledOnTouchOutside(false)
        val refresh = Runnable {
            if (closed[0]) {
                return@Runnable
            }
            try {
                source.refresh()
                val info: String = if (tab[0] == 2) source.x11() else source.summary()
                if (!(info as java.lang.String).contentEquals(summary.getText())) {
                    summary.setText(info)
                }
                summary.setVisibility(if (tab[0] != 1) View.VISIBLE else View.GONE)
                lines.setVisibility(if (tab[0] == 2) View.GONE else View.VISIBLE)
                normal.setEnabled(tab[0] != 2)
                detail.setEnabled(tab[0] != 2)
                normal.setAlpha(if (tab[0] == 2) 0.4f else 1f)
                detail.setAlpha(if (tab[0] == 2) 0.4f else 1f)
                var text: String = source.events()
                if (tab[0] == 0) {
                    val entries: Array<String> = (text as java.lang.String).split("\n")
                    val recent = StringBuilder()
                    var i = 0
                    while (i < Math.min(8, entries.size)) {
                        recent.append(entries[i]).append('\n')
                        i++
                    }
                    text = recent.toString()
                }
                if (!(text as java.lang.String).contentEquals(lines.getText())) {
                    val y: Int = scroll.getScrollY()
                    lines.setText(text)
                    scroll.post(Runnable {
                        if (!closed[0]) {
                            scroll.scrollTo(0, y)
                        }
                    })
                }
                pause.setEnabled(source.canPause())
                pause.setText(if (source.pauseLabel().isEmpty()) tr(a, if (source.paused()) "Возобновить журнал" else "Остановить журнал", if (source.paused()) "Resume journal" else "Stop journal") else source.pauseLabel())
                if (!Objects.equals(pause.getTag(), source.paused())) {
                    pause.setTag(source.paused())
                    val icon: Drawable = a.getDrawable(if (source.paused()) com.termux.R.drawable.workspace_play else com.termux.R.drawable.workspace_diag_pause)!!.mutate()
                    icon.setTint(Pal.ACCENT)
                    icon.setBounds(0, 0, dp(a, 18), dp(a, 18))
                    pause.setCompoundDrawables(icon, null, null, null)
                }
                capacity.setText(if (source.capacitySummary().isEmpty()) ServerFileInfo.size(source.bytes().toLong(), WorkspaceLanguage.code(a)!!.equals("ru")) + " / 2 MiB · " + tr(a, "удалено старых: ", "old entries dropped: ") + source.dropped() else source.capacitySummary())
                progress.setProgress(source.bytes())
                selected(a, x11, tab[0] == 2)
                selected(a, overview, tab[0] == 0)
                selected(a, events, tab[0] == 1)
                selected(a, normal, !source.extended())
                selected(a, detail, source.extended())
            } catch (e: Exception) {
                status.setText(tr(a, "Не удалось обновить диагностику", "Could not refresh diagnostics"))
            }
        }
        x11.setOnClickListener(View.OnClickListener { v ->
            tab[0] = 2
            refresh.run()
            source.prepareX11(Runnable {
                if (!closed[0]) {
                    refresh.run()
                }
            })
        })
        overview.setOnClickListener(View.OnClickListener { v ->
            tab[0] = 0
            refresh.run()
        })
        events.setOnClickListener(View.OnClickListener { v ->
            tab[0] = 1
            refresh.run()
        })
        normal.setOnClickListener(View.OnClickListener { v ->
            if (tab[0] == 2) {
                return@OnClickListener
            }
            source.extended(false)
            refresh.run()
        })
        detail.setOnClickListener(View.OnClickListener { v ->
            if (tab[0] == 2) {
                return@OnClickListener
            }
            source.extended(true)
            refresh.run()
            status.setText(tr(a, "Собираются новые события; прошлые не восстанавливаются. Нативный verbose — только при запуске.", "New events only; earlier events cannot be recovered. Native verbose requires launch flags."))
        })
        pause.setOnClickListener(View.OnClickListener { v ->
            try {
                source.paused(!source.paused())
                status.setText("")
                refresh.run()
            } catch (error: Exception) {
                status.setText(tr(a, "Не удалось сохранить выбор; состояние не изменилось", "Could not save choice; state unchanged"))
            }
        })
        copy.setOnClickListener(View.OnClickListener { v ->
            try {
                val full: String = source.report()
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostics", clipboardText(full)))
                status.setText(if (full.length > 96000) tr(a, "Скопирована выдержка. Полный отчёт — в Download.", "Excerpt copied. Save full report to Download.") else tr(a, "Отчёт скопирован", "Report copied"))
            } catch (e: Exception) {
                status.setText(tr(a, "Не удалось скопировать; сохраните в Download", "Could not copy; save to Download"))
            }
        })
        val export = Consumer<Boolean?> { toDownload ->
            if (saving[0]) {
                return@Consumer
            }
            saving[0] = true
            val report: String = source.report()
            WorkspaceDiagnostics.save(a.getFilesDir(), source.id(), report, Consumer<File> { file ->
                var dest: File? = null
                var saved: String = file.getAbsolutePath()
                var failure: Exception? = null
                try {
                    if (toDownload!!) {
                        val folder: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!folder.isDirectory() && !folder.mkdirs()) {
                            throw IOException()
                        }
                        dest = File.createTempFile("Termux-Jobs-diagnostics-", ".txt", folder)
                        (FileInputStream(file)).use { `in` ->
                            (FileOutputStream(dest)).use { out ->
                                val b = ByteArray(16384)
                                var n: Int = 0
                                while (true) {
                                    n = `in`.read(b)
                                    if (n == -1) break
                                    out.write(b, 0, n)
                                }
                            }
                        }
                        saved = dest!!.getAbsolutePath()
                    }
                } catch (e: Exception) {
                    failure = e
                    if (dest != null) {
                        dest.delete()
                    }
                }
                val result: String = saved
                val error: Exception? = failure
                main.post(Runnable {
                    saving[0] = false
                    if (closed[0]) {
                        return@Runnable
                    }
                    if (error != null) {
                        status.setText(tr(a, "Download недоступен; проверьте разрешение хранения", "Download unavailable; check storage permission"))
                        return@Runnable
                    }
                    if (!toDownload!!) {
                        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostics", result))
                    }
                    status.setText(if (toDownload) tr(a, "Сохранено: ", "Saved: ") + result else tr(a, "Путь скопирован", "Path copied"))
                })
            }, Consumer<Exception> { error ->
                main.post(Runnable {
                    saving[0] = false
                    if (!closed[0]) {
                        status.setText(tr(a, "Не удалось сохранить отчёт", "Could not save report"))
                    }
                })
            })
        }
        path.setOnClickListener(View.OnClickListener { v -> export.accept(false) })
        download.setOnClickListener(View.OnClickListener { v ->
            source.prepareExport(Runnable {
                if (!closed[0]) {
                    export.accept(true)
                }
            })
        })
        val tick: Runnable = object : Runnable {
            override fun run() {
                if (closed[0]) {
                    return
                }
                if (a.isFinishing() || a.isDestroyed() || !dialog.isShowing()) {
                    dialog.dismiss()
                    return
                }
                refresh.run()
                main.postDelayed(this, 2000L)
            }
        }
        dialog.setOnDismissListener(DialogInterface.OnDismissListener { v ->
            closed[0] = true
            main.removeCallbacksAndMessages(null)
        })
        dialog.setOnShowListener(DialogInterface.OnShowListener { v -> tick.run() })
        refresh.run()
        dialog.show()
        return dialog
    }
}
