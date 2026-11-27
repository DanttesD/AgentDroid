package com.termux.app.workspace

import android.app.Activity
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject

/** Inline inventory status; only the spinner and one status line change during the scan.*/
class WorkspaceBackupScanHeader {
    @JvmField val view: LinearLayout
    @JvmField val refresh: TextView
    @JvmField val status: TextView
    @JvmField val spinner: ProgressBar
    @JvmField var running: Boolean = false
    @JvmField var closed: Boolean = false
    @JvmField var ready: Boolean = true
    private var engine: WorkspaceBackupEngine? = null

    constructor(activity: Activity, title: String) {
        view = LinearLayout(activity)
        view.setOrientation(LinearLayout.VERTICAL)
        val d: Int = WorkspaceDialogs.dp(activity, 16.0f)
        view.setPadding(d, d, d, 0)
        val row = LinearLayout(activity)
        row.setGravity(Gravity.CENTER_VERTICAL)
        val name = TextView(activity)
        name.setText(title)
        name.setTextSize(18.0f)
        name.setTextColor(Pal.TEXT)
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1.0f))
        val control = FrameLayout(activity)
        refresh = WorkspaceDialogs.action(activity, WorkspaceBackupUi.text(activity, "Обновить", "Refresh"), 0)
        refresh.setGravity(Gravity.CENTER)
        control.addView(refresh, FrameLayout.LayoutParams(-1, -1))
        spinner = ProgressBar(activity)
        val spin = FrameLayout.LayoutParams(WorkspaceDialogs.dp(activity, 24.0f), WorkspaceDialogs.dp(activity, 24.0f), Gravity.CENTER)
        control.addView(spinner, spin)
        spinner.setVisibility(View.GONE)
        spinner.setContentDescription(WorkspaceBackupUi.text(activity, "Сканирование", "Scanning"))
        val button = LinearLayout.LayoutParams(WorkspaceDialogs.dp(activity, 100.0f), WorkspaceDialogs.dp(activity, 48.0f))
        button.setMarginStart(WorkspaceDialogs.dp(activity, 8.0f))
        row.addView(control, button)
        view.addView(row)
        status = TextView(activity)
        status.setTextSize(12.0f)
        status.setTextColor(Pal.MUTED)
        status.setSingleLine(true)
        status.setEllipsize(TextUtils.TruncateAt.MIDDLE)
        status.setPadding(0, WorkspaceDialogs.dp(activity, 6.0f), 0, WorkspaceDialogs.dp(activity, 8.0f))
        view.addView(status)
        status.setVisibility(View.GONE)
    }

    fun start(owned: WorkspaceBackupEngine, initial: String) {
        engine = owned
        running = true
        refresh.setEnabled(false)
        refresh.setVisibility(View.INVISIBLE)
        spinner.setVisibility(View.VISIBLE)
        status.setText(initial)
        status.setVisibility(View.VISIBLE)
    }

    fun progress(stage: String, event: JSONObject) {
        if (closed || !running) {
            return
        }
        val count: Long = event.optLong("processed")
        val path: String = event.optString("current")
        status.setText(stage + (if (count > 0) " · " + Formatter.formatFileSize(view.getContext(), count) else "") + (if (path.isEmpty()) "" else " · " + path))
    }

    fun finish(error: String?) {
        running = false
        engine = null
        refresh.setEnabled(true)
        refresh.setVisibility(View.VISIBLE)
        spinner.setVisibility(View.GONE)
        status.setText(if (error == null) "" else error)
        status.setVisibility(if (error == null) View.GONE else View.VISIBLE)
    }

    fun close() {
        closed = true
        val owned: WorkspaceBackupEngine? = engine
        engine = null
        running = false
        if (owned != null) {
            owned.cancel()
        }
        spinner.setVisibility(View.GONE)
    }
}
