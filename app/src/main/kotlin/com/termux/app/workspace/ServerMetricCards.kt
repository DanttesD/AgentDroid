package com.termux.app.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.termux.R
import java.util.Locale
import org.json.JSONObject

/** Three stable views: refreshing data changes only labels and progress values.*/
class ServerMetricCards : LinearLayout {
    private val values: Array<TextView?> = arrayOfNulls<TextView>(3)
    private val bars: Array<ProgressBar?> = arrayOfNulls<ProgressBar>(3)
    private val ru: Boolean
    private val labels: Array<String>

    constructor(context: Context) : super(context) {
        setOrientation(LinearLayout.HORIZONTAL)
        ru = WorkspaceLanguage.code(context)!!.equals("ru")
        labels = arrayOf<String>("CPU", "RAM", if (ru) "Диск" else "Disk")
        val icons: IntArray = intArrayOf(R.drawable.workspace_server_cpu, R.drawable.workspace_server_ram, R.drawable.workspace_server_disk)
        for (i in 0 until (3)) {
            val card = LinearLayout(context)
            card.setOrientation(LinearLayout.VERTICAL)
            card.setPadding(dp(10), dp(12), dp(10), dp(12))
            card.setBackground(WorkspaceDialogs.background(context, false))
            val p = LinearLayout.LayoutParams(0, -2, 1.0f)
            if (i > 0) {
                p.setMarginStart(dp(8))
            }
            addView(card, p)
            val heading = LinearLayout(context)
            heading.setGravity(Gravity.CENTER_VERTICAL)
            val icon = ImageView(context)
            icon.setImageResource(icons[i])
            icon.setColorFilter(Pal.MUTED)
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER)
            val ip = LinearLayout.LayoutParams(dp(18), dp(18))
            ip.setMarginEnd(dp(6))
            heading.addView(icon, ip)
            val label = TextView(context)
            label.setText(labels[i])
            label.setTextSize(13.0f)
            label.setTextColor(Pal.MUTED)
            label.setSingleLine(true)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(label, 10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
            heading.addView(label, LinearLayout.LayoutParams(0, dp(24), 1.0f))
            card.addView(heading)
            val value = TextView(context)
            value.setTextSize(14.0f)
            value.setTypeface(null, Typeface.BOLD)
            value.setTextColor(Pal.TEXT)
            value.setMaxLines(2)
            value.setGravity(Gravity.CENTER_VERTICAL)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(value, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            card.addView(value, LinearLayout.LayoutParams(-1, dp(42)))
            values[i] = value
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            bar.setMax(1000)
            bar.setIndeterminate(false)
            bar.setProgressTintList(ColorStateList.valueOf(Pal.ACCENT))
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(Pal.TRACK))
            card.addView(bar, LinearLayout.LayoutParams(-1, dp(6)))
            bars[i] = bar
        }
        update(null)
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(getContext(), n.toFloat())
    }

    fun update(metrics: JSONObject?) {
        val cpu: Double = if (metrics == null) java.lang.Double.NaN else metrics.optDouble("cpu", java.lang.Double.NaN)
        val validCpu: Boolean = java.lang.Double.isFinite(cpu) && cpu >= 0
        set(0, if (validCpu) java.lang.String.format(if (ru) Locale("ru") else Locale.US, "%.0f%%", cpu) else "—", if (validCpu) progress(cpu) else 0)
        for (i in 1 until (3)) {
            val prefix: String = if (i == 1) "ram" else "disk"
            val total: Long = if (metrics == null) (-1).toLong() else metrics.optLong(prefix + "Total", (-1).toLong())
            val used: Long = if (metrics == null) (-1).toLong() else metrics.optLong(prefix + "Used", (-1).toLong())
            set(i, usage(used, total, ru), if (used >= 0 && total > 0) progress(100.0 * used / total) else 0)
        }
    }

    private fun set(index: Int, value: String, progress: Int) {
        val view: TextView? = values[index]
        if (!(value as java.lang.String).contentEquals(view!!.getText())) {
            view.setText(value)
            getChildAt(index).setContentDescription(labels[index] + " · " + value)
        }
        if (bars[index]!!.getProgress() != progress) {
            bars[index]!!.setProgress(progress, false)
        }
    }

    companion object {
        @JvmStatic
        fun progress(percent: Double): Int {
            return if (java.lang.Double.isFinite(percent)) Math.round(Math.max(0.0, Math.min(100.0, percent)) * 10).toInt() else 0
        }

        @JvmStatic
        fun usage(used: Long, total: Long, ru: Boolean): String {
            if (used < 0 || total <= 0) {
                return "—"
            }
            var unit: Double = 1.0
            var label: String = if (ru) "Б" else "B"
            if (total >= 1073741824L) {
                unit = 1073741824.0
                label = if (ru) "ГБ" else "GB"
            } else if (total >= 1048576) {
                unit = 1048576.0
                label = if (ru) "МБ" else "MB"
            } else if (total >= 1024) {
                unit = 1024.0
                label = if (ru) "КБ" else "KB"
            }
            return java.lang.String.format(if (ru) Locale("ru") else Locale.US, "%.1f / %.1f %s", used / unit, total / unit, label)
        }
    }
}
