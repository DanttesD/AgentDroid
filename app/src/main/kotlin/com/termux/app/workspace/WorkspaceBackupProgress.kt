package com.termux.app.workspace

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import java.util.function.LongSupplier
import org.json.JSONObject

/** Bounded, replace-in-place progress UI. The worker owns all cancellation and I/O.*/
class WorkspaceBackupProgress {
    @JvmField val view: LinearLayout
    @JvmField val bar: ProgressBar
    @JvmField val stage: TextView
    @JvmField val current: TextView
    @JvmField val amount: TextView
    @JvmField val elapsed: TextView
    private lateinit var activity: Activity
    private val clock: LongSupplier
    private val started: Long
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var closed: Boolean = false
    private var stopping: Boolean = false
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (closed) {
                return
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                close()
                return
            }
            updateElapsed()
            handler.postDelayed(this, 1000L)
        }
    }

    constructor(a: Activity) : this(a, LongSupplier(SystemClock::elapsedRealtime))

    constructor(a: Activity, time: LongSupplier) {
        activity = a
        clock = time
        started = time.getAsLong()
        view = LinearLayout(a)
        view.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = WorkspaceDialogs.dp(a, 20.0f)
        view.setPadding(pad, pad, pad, pad)
        stage = line(15, Pal.TEXT)
        bar = ProgressBar(a, null, R.attr.progressBarStyleHorizontal)
        bar.setMax(100)
        bar.setIndeterminate(true)
        val tint: ColorStateList = ColorStateList.valueOf(Pal.ACCENT)
        bar.setProgressTintList(tint)
        bar.setIndeterminateTintList(tint)
        val params = LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(a, 6.0f))
        params.topMargin = WorkspaceDialogs.dp(a, 14.0f)
        params.bottomMargin = WorkspaceDialogs.dp(a, 14.0f)
        view.addView(bar, params)
        current = line(12, Pal.MUTED)
        current.setSingleLine(true)
        current.setEllipsize(TextUtils.TruncateAt.MIDDLE)
        amount = line(13, Pal.TEXT)
        elapsed = line(13, Pal.MUTED)
        stage.setText(WorkspaceBackupUi.text(a, "Подготовка…", "Preparing…"))
        current.setText(" ")
        updateElapsed()
    }

    private fun line(size: Int, color: Int): TextView {
        val v = TextView(activity)
        v.setTextSize(size.toFloat())
        v.setTextColor(color)
        view.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    fun start() {
        if (!closed) {
            handler.removeCallbacks(tick)
            tick.run()
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacks(tick)
    }

    fun isStopping(): Boolean {
        return stopping
    }

    /** Bind after show: cancellation requests worker shutdown, it does not dismiss completion UI.*/
    fun bindCancellation(dialog: AlertDialog, cancel: Runnable) {
        val button: Button? = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        if (button == null) {
            return
        }
        button.setOnClickListener(View.OnClickListener { v ->
            if (stopping || closed) {
                return@OnClickListener
            }
            stopping = true
            button.setEnabled(false)
            button.setText(WorkspaceBackupUi.text(activity, "Отменяется…", "Cancelling…"))
            stage.setText(WorkspaceBackupUi.text(activity, "Остановка операции…", "Stopping operation…"))
            current.setText(" ")
            amount.setText(WorkspaceBackupUi.text(activity, "Дождитесь завершения процесса", "Waiting for the process to exit"))
            bar.setIndeterminate(true)
            cancel.run()
        })
    }

    fun update(label: String, event: JSONObject) {
        if (closed || stopping) {
            return
        }
        stage.setText(label)
        val filename: String = event.optString("current", "")
        current.setText(if (filename.isEmpty()) " " else filename)
        val done: Long = Math.max(0L, event.optLong("processed"))
        val total: Long = Math.max(0L, event.optLong("total"))
        if (total > 0) {
            val percent: Int = (Math.min(done, total) * 100.0 / total).toInt()
            bar.setIndeterminate(false)
            bar.setProgress(percent)
            amount.setText(percent.toString() + "%" + (if (event.optString("unit", "bytes").equals("bytes")) " · " + Formatter.formatFileSize(activity, done) + " / " + Formatter.formatFileSize(activity, total) else ""))
        } else {
            bar.setIndeterminate(true)
            amount.setText(if (done > 0) Formatter.formatFileSize(activity, done) else WorkspaceBackupUi.text(activity, "Выполняется…", "Working…"))
        }
        updateElapsed()
    }

    fun updateElapsed() {
        elapsed.setText(WorkspaceBackupUi.text(activity, "Прошло: ", "Elapsed: ") + duration(Math.max(0L, clock.getAsLong() - started)))
    }

    companion object {
        @JvmStatic
        fun duration(milliseconds: Long): String {
            val seconds: Long = Math.max(0L, milliseconds) / 1000
            val hours: Long = seconds / 3600
            return if (hours > 0) java.lang.String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60) else java.lang.String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}
