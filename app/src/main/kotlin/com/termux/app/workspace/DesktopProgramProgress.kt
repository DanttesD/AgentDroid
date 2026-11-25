package com.termux.app.workspace

import android.R
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import java.util.function.IntPredicate

/** Updates only the selected program card; closing its UI never owns/cancels the job.*/
class DesktopProgramProgress {
    @JvmField val view: LinearLayout
    @JvmField val bar: ProgressBar
    @JvmField val remaining: TextView
    @JvmField val file: TextView

    constructor(context: Context) {
        view = LinearLayout(context)
        view.setOrientation(LinearLayout.VERTICAL)
        view.setVisibility(View.GONE)
        view.setTag("desktop-program-progress")
        bar = ProgressBar(context, null, R.attr.progressBarStyleHorizontal)
        bar.setMax(100)
        view.addView(bar, LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(context, 10.0f)))
        remaining = AgentStatusUi.info(view, "")
        remaining.setTextSize(11.0f)
        remaining.setTag("desktop-program-remaining")
        file = AgentStatusUi.info(view, "")
        file.setTextSize(11.0f)
        file.setSingleLine(true)
        file.setEllipsize(TextUtils.TruncateAt.MARQUEE)
        file.setMarqueeRepeatLimit(-1)
        file.setTag("desktop-program-file")
    }

    fun update(state: Bundle, visible: Boolean) {
        view.setVisibility(if (visible) View.VISIBLE else View.GONE)
        file.setSelected(visible)
        if (!visible) {
            return
        }
        val percent: Int = state.getInt("percent", -1)
        bar.setIndeterminate(percent < 0)
        if (percent >= 0) {
            bar.setProgress(Math.min(100, Math.max(0, percent)))
        }
        val c: Context = view.getContext()
        val ru: Boolean = WorkspaceLanguage.code(c)!!.equals("ru")
        val eta: Long = state.getLong("eta", (-1).toLong())
        val sample: Long = state.getLong("sampleTime", 0L)
        val left: Long = eta - Math.max(0L, (SystemClock.elapsedRealtime() - sample) / 1000)
        var text: String = (if (ru) "Осталось: " else "Remaining: ") + (if (eta < 0 || left <= 0) if (ru) "рассчитывается" else "calculating" else "≈ " + (if (left >= 60) (left / 60).toString() + (if (ru) " мин " else " min ") else "") + left % 60 + (if (ru) " с" else " s"))
        val done: Long = state.getLong("downloadBytes", (-1).toLong())
        val total: Long = state.getLong("downloadTotal", (-1).toLong())
        val stage: String = state.getString("stage", "")
        if (!state.getString("item", "").isEmpty()) {
            text = (if (stage.equals("installing")) if (ru) "Установка пакетов" else "Installing packages" else if (ru) "Загрузка пакетов" else "Downloading packages") + "\n" + text
        }
        if ((stage.equals("downloading") || stage.equals("updating")) && done >= 0 && total > 0 && done <= total) {
            text += " · " + bytes(done) + " / " + bytes(total)
        }
        set(remaining, text)
        set(file, if (state.getString("item", "").isEmpty()) lastLine(state.getString("detail", "")) else state.getString("item", ""))
    }

    companion object {
        @JvmStatic
        fun lastLine(detail: String): String {
            val lines: Array<String> = ((detail as java.lang.String).replaceAll("\u001b\\[[0-9;?]*[ -/]*[@-~]", "") as java.lang.String).split("[\\r\\n]")
            for (i in lines.size - 1 downTo 0) {
                val line: String = (lines[i] as java.lang.String).trim()
                if (line.startsWith("pmstatus:") || line.startsWith("dlstatus:")) {
                    val parts: Array<String> = (line as java.lang.String).split(":", 4)
                    if (parts.size == 4 && !(parts[3] as java.lang.String).trim().isEmpty()) {
                        return (parts[3] as java.lang.String).trim()
                    }
                    continue
                }
                if (!line.isEmpty() && !spinner(line) && !line.startsWith("JOBS_") && !(line as java.lang.String).matches("^\\[.*\\] Bytes:.*") && !line.startsWith("PROGRAM:") && !line.startsWith("READY:")) {
                    return line
                }
            }
            return ""
        }

        @JvmStatic
        private fun spinner(line: String): Boolean {
            return (line as java.lang.String).chars().allMatch(IntPredicate { c ->
                c == '.'.code || c == '\\'.code || c == '|'.code || c == '/'.code || c == '-'.code || c == '='.code || c == '['.code || c == ']'.code || c == '—'.code || c == '−'.code || c == '–'.code || Character.isWhitespace(c)
            })
        }

        @JvmStatic
        private fun bytes(n: Long): String {
            var v: Double = n.toDouble()
            val units: Array<String> = arrayOf<String>("B", "kB", "MB", "GB", "TB")
            var i = 0
            while (v >= 1000 && i < 4) {
                v /= 1000
                i++
            }
            return java.lang.String.format(Locale.ROOT, if (i == 0) "%.0f %s" else "%.1f %s", v, units[i])
        }

        @JvmStatic
        private fun set(v: TextView, s: String) {
            if (!(s as java.lang.String).contentEquals(v.getText())) {
                v.setText(s)
            }
        }
    }
}
