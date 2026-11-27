package com.termux.app.workspace

/** Presentation only: average throughput for the current file, no network probes.*/
object ServerTransferProgress {
    @JvmStatic
    fun describe(bytes: Long, total: Long, elapsedMs: Long, ru: Boolean): String {
        var text: String = ServerFileInfo.size(bytes, ru) + (if (total < 0) "" else " / " + ServerFileInfo.size(total, ru))
        if (bytes <= 0 || elapsedMs < 500) {
            return text
        }
        val rate: Long = (bytes * 1000.0 / elapsedMs).toLong()
        if (rate <= 0) {
            return text
        }
        text += " · " + ServerFileInfo.size(rate, ru) + (if (ru) "/с" else "/s")
        if (total > bytes) {
            val seconds: Long = Math.ceil((total - bytes) / rate.toDouble()).toLong()
            text += (if (ru) " · осталось ≈ " else " · remaining ≈ ") + (if (seconds < 60) seconds.toString() + (if (ru) " с" else " s") else ((seconds + 59) / 60).toString() + (if (ru) " мин" else " min"))
        }
        return text
    }
}
