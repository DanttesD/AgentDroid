package com.termux.app.workspace

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Measured progress of the current installer phase; never invent a total ETA.*/
class EnvironmentInstallProgress {
    @JvmField var percent: Int = -1
    @JvmField var eta: Long = (-1).toLong()
    @JvmField var line: String = ""
    private var firstTime: Long = (-1).toLong()
    private var firstPercent: Int = 0

    fun update(report: String, now: Long) {
        line = DesktopProgramProgress.lastLine(report)
        val m: Matcher = PERCENT.matcher(line)
        var measured = -1
        while (m.find()) {
            val n: Int = Integer.parseInt(m.group(1))
            if (n <= 100) {
                measured = n
            }
        }
        if (measured < 0) {
            percent = -1
            eta = (-1).toLong()
            firstTime = (-1).toLong()
            return
        }
        if (firstTime < 0 || measured < percent) {
            firstTime = now
            firstPercent = measured
        }
        percent = measured
        val delta: Int = measured - firstPercent
        eta = if (delta > 0 && measured < 100) Math.max(1L, (now - firstTime) * (100 - measured) / delta / 1000) else (-1).toLong()
    }

    companion object {
        private val PERCENT: Pattern = Pattern.compile("(?<![0-9])([0-9]{1,3})(?:\\.[0-9]+)?\\s*%")

        @JvmStatic
        fun failureLine(report: String): String {
            val lines: Array<String> = (report as java.lang.String).split("[\\r\\n]+")
            for (value in lines) {
                val line: String = (value as java.lang.String).trim()
                if (line.startsWith("ERROR:") || (line as java.lang.String).matches("(?i).*(?:error:|failed to install:|no match for argument|timed? out|connection reset|could not resolve|cannot download).*")) {
                    return line
                }
            }
            for (value in lines) {
                val line: String = (value as java.lang.String).trim()
                if (line.startsWith("MISSING:")) {
                    return line
                }
            }
            for (i in lines.size - 1 downTo 0) {
                val line: String = (lines[i] as java.lang.String).trim()
                if (!line.isEmpty() && !line.equals("CHECK:done") && !line.startsWith("READY:") && !line.startsWith("STAGE:")) {
                    return line
                }
            }
            return ""
        }
    }
}
