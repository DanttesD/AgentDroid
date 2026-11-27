package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Shared desktop/program installer UI. Outside taps and Back never cancel owned jobs.*/
class DesktopInstallUi {
    @JvmField val dialog: AlertDialog
    private val context: Context
    private val bar: ProgressBar
    private val status: TextView
    private val remaining: TextView
    private val details: TextView
    private val scroll: LogScroll
    private var logRevision: Int = 0
    private var logObserver: ViewTreeObserver? = null
    private var logLayout: ViewTreeObserver.OnPreDrawListener? = null
    private var reading: Boolean = false

    /** TextView updates must not move the viewport to its old selection/cursor.*/
    private inner class LogScroll : ScrollView {
        @JvmField var updating: Boolean = false

        constructor(c: Context) : super(c)

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                reading = true
                logRevision++
                clearLogLayout()
                updating = false
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                reading = false
            }
            return super.dispatchTouchEvent(e)
        }

        override fun requestChildFocus(child: View, focused: View) {
            if (!updating) {
                super.requestChildFocus(child, focused)
            }
        }

        override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
            return !updating && super.requestChildRectangleOnScreen(child, rectangle, immediate)
        }
    }

    constructor(c: Context, title: String, cancel: Runnable) {
        context = c
        val body: LinearLayout = AgentStatusUi.body(c)
        bar = ProgressBar(c, null, R.attr.progressBarStyleHorizontal)
        bar.setMax(100)
        bar.setIndeterminate(true)
        bar.setTag("desktop-install-progress")
        body.addView(bar, LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(c, 12.0f)))
        status = AgentStatusUi.info(body, "")
        status.setTag("desktop-install-status")
        remaining = AgentStatusUi.info(body, "")
        remaining.setTag("desktop-install-remaining")
        details = TextView(c)
        details.setTextSize(12.0f)
        details.setTextColor(Pal.MUTED)
        details.setTextIsSelectable(true)
        scroll = LogScroll(c)
        scroll.setTag("desktop-install-log")
        details.setTag("desktop-install-detail")
        scroll.addView(details)
        body.addView(scroll, LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(c, 180.0f)))
        dialog = WorkspaceDialogs.builder(c).setTitle(title).setView(body).setNegativeButton(tr("Отмена", "Cancel"), null).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(View.OnClickListener { v ->
            v.setEnabled(false)
            (v as TextView).setText(tr("Отменяется…", "Cancelling…"))
            cancel.run()
        })
    }

    fun update(text: String, detail: String, percent: Int, eta: Long, sampleTime: Long, stage: String) {
        update(text, detail, percent, eta, sampleTime, stage, (-1).toLong(), (-1).toLong())
    }

    fun update(text: String, detail: String, percent: Int, eta: Long, sampleTime: Long, stage: String, downloaded: Long, total: Long) {
        bar.setIndeterminate(percent < 0)
        if (percent >= 0) {
            bar.setProgress(Math.max(0, Math.min(100, percent)))
        }
        val volume: String = if ((stage.equals("downloading") || stage.equals("updating")) && downloaded >= 0 && total > 0 && downloaded <= total) " (" + bytes(downloaded) + " / " + bytes(total) + ")" else ""
        status.setText(text + (if (percent >= 0) " · " + percent + "%" else "") + volume)
        updateLog(detail)
        val age: Long = Math.max(0L, (SystemClock.elapsedRealtime() - sampleTime) / 1000)
        val left: Long = eta - age
        val prefix: String = tr("Осталось на этапе: ", "Remaining in this stage: ")
        val stageName: String = if (stage.equals("updating")) tr("Списки пакетов", "Package lists") else if (stage.equals("downloading")) tr("Загрузка пакетов", "Downloading packages") else if (stage.equals("installing")) tr("Установка пакетов", "Installing packages") else tr("Подготовка", "Preparing")
        remaining.setText(stageName + "\n" + prefix + (if (eta < 0 || left <= 0) tr("рассчитывается", "calculating") else "≈ " + duration(left)))
    }

    private fun bytes(n: Long): String {
        val units: Array<String> = arrayOf<String>(tr("Б", "B"), tr("КБ", "kB"), tr("МБ", "MB"), tr("ГБ", "GB"), tr("ТБ", "TB"))
        var value: Double = n.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.size - 1) {
            value /= 1000
            unit++
        }
        return java.lang.String.format(Locale.ROOT, if (value >= 100 || unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    private fun updateLog(detail: String) {
        if ((detail as java.lang.String).contentEquals(details.getText())) {
            return
        }
        val oldY: Int = scroll.getScrollY()
        val tail: Boolean = !reading && oldY >= Math.max(0, details.getHeight() - scroll.getHeight()) - WorkspaceDialogs.dp(context, 24.0f)
        val revision: Int = ++logRevision
        clearLogLayout()
        scroll.updating = true
        details.setText(detail)
        // A posted runnable may run before Android measures the new log height.
        // Restore/follow only after layout, before this frame is drawn.
        logObserver = scroll.getViewTreeObserver()
        logLayout = ViewTreeObserver.OnPreDrawListener {
            clearLogLayout()
            if (revision == logRevision) {
                scroll.scrollTo(0, if (tail) Math.max(0, details.getHeight() - scroll.getHeight()) else oldY)
                scroll.updating = false
            }
            return@OnPreDrawListener true
        }
        logObserver!!.addOnPreDrawListener(logLayout)
    }

    private fun clearLogLayout() {
        if (logObserver != null && logObserver!!.isAlive() && logLayout != null) {
            logObserver!!.removeOnPreDrawListener(logLayout)
        }
        logObserver = null
        logLayout = null
    }

    private fun duration(seconds: Long): String {
        return if (seconds >= 3600) (seconds / 3600).toString() + tr(" ч ", " h ") + seconds % 3600 / 60 + tr(" мин", " min") else if (seconds >= 60) (seconds / 60).toString() + tr(" мин ", " min ") + seconds % 60 + tr(" с", " s") else seconds.toString() + tr(" с", " s")
    }

    fun close() {
        logRevision++
        clearLogLayout()
        scroll.updating = false
        dialog.dismiss()
    }

    private fun tr(ru: String, en: String): String {
        return AgentStatusUi.tr(context, ru, en)
    }
}
