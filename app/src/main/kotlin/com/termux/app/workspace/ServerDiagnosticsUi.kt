package com.termux.app.workspace

import android.app.AlertDialog
import com.termux.BuildConfig
import com.termux.app.TermuxActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerDiagnosticsUi {
    @JvmStatic
    fun show(activity: TermuxActivity, profile: ServerProfiles.Profile?, store: ServerProfiles): AlertDialog {
        ServerDiagnostics.configure(profile)
        return DiagnosticsPanel.show(activity, object : DiagnosticsPanel.Source {
            @JvmField var data: ServerDiagnostics.Snapshot = ServerDiagnostics.snapshot(profile)

            fun ru(): Boolean {
                return WorkspaceLanguage.code(activity)!!.equals("ru")
            }

            fun t(a: String, b: String): String {
                return if (ru()) a else b
            }

            fun ms(n: Long): String {
                return if (n < 0) "—" else n.toString() + t(" мс", " ms")
            }

            override fun refresh() {
                data = ServerDiagnostics.snapshot(profile)
            }

            override fun title(): String {
                return t("Диагностика сервера", "Server diagnostics")
            }

            override fun id(): String {
                return "server-" + profile!!.id
            }

            override fun summary(): String {
                val text: String = t("Приложение: ", "App: ") + BuildConfig.VERSION_NAME + "\n" + (if (data.paused) t("Журнал остановлен", "Collection paused") else t("Сбор включён", "Collecting"))
                if (data.events.isEmpty()) {
                    return text + "\n\n" + t("Событий пока нет. Записываются завершённые операции. Открытие диагностики не подключается к серверу.", "No events yet. Completed operations are recorded. Opening diagnostics does not connect to the server.")
                }
                val last: ServerDiagnostics.Event = data.events.get(0)
                val transfer: Boolean = last.operation === ServerDiagnostics.Op.UPLOAD || last.operation === ServerDiagnostics.Op.DOWNLOAD
                return text + "\n" + t("Последняя операция: ", "Last operation: ") + ServerDiagnostics.title(last.operation, ru()) + "\n" + t("Очередь: ", "Queue: ") + ms(last.waitMs) + "\n" + (if (transfer) t("Передача: ", "Transfer: ") else t("SSH-запрос: ", "SSH request: ")) + ms(last.networkMs) + "\n" + t("Разбор ответа: ", "Response parse: ") + ms(last.parseMs) + "\n" + (if (transfer) t("Передано: ", "Transferred: ") else t("Ответ: ", "Response: ")) + (if (last.bytes < 0) "—" else ServerFileInfo.size(last.bytes, ru())) + "\n" + t("Результат запроса: ", "Request result: ") + result(last.code, ru()) + "\n\n" + t("Без дополнительных запросов. Успех запроса не означает завершение задачи. Неизвестные значения — прочерк. Журнал в памяти, экспорт — в файл.", "No extra requests. Request success does not mean task completion. Unavailable values use a dash. In-memory journal; export saves a file.")
            }

            override fun events(): String {
                val b = StringBuilder()
                val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                for (e in data.events) {
                    b.append(time.format(Date(e.time))).append("  ").append(ServerDiagnostics.title(e.operation, ru())).append(" · ").append(result(e.code, ru())).append('\n').append("    ").append(ms(e.networkMs))
                    if (e.waitMs >= 0) {
                        b.append(t(" · очередь ", " · queue ")).append(ms(e.waitMs))
                    }
                    if (e.bytes >= 0) {
                        b.append(" · ").append(ServerFileInfo.size(e.bytes, ru()))
                    }
                    if ((e.operation === ServerDiagnostics.Op.UPLOAD || e.operation === ServerDiagnostics.Op.DOWNLOAD) && e.networkMs >= 500 && e.bytes > 0) {
                        b.append(" · ").append(ServerFileInfo.size((e.bytes * 1000.0 / e.networkMs).toLong(), ru())).append(if (ru()) "/с" else "/s")
                    }
                    b.append('\n')
                }
                return b.toString()
            }

            override fun report(): String {
                return ServerDiagnostics.report(profile)
            }

            override fun bytes(): Int {
                return data.bytes
            }

            override fun dropped(): Int {
                return data.dropped
            }

            override fun paused(): Boolean {
                return ServerDiagnostics.paused(profile)
            }

            @Throws(Exception::class)
            override fun paused(value: Boolean) {
                val previous: Boolean = profile!!.diagnosticsPaused
                profile.diagnosticsPaused = value
                try {
                    store.save()
                } catch (e: Exception) {
                    profile.diagnosticsPaused = previous
                    throw e
                }
                ServerDiagnostics.pause(profile, value)
            }
        })
    }

    @JvmStatic
    fun result(code: String?, ru: Boolean): String {
        when (code!!) {
            "OK" -> {
                return if (ru) "Успешно" else "Success"
            }
            "CANCELLED" -> {
                return if (ru) "Остановлено" else "Cancelled"
            }
            "SSH_FAILED" -> {
                return if (ru) "SSH недоступен · SSH_FAILED" else "SSH unavailable · SSH_FAILED"
            }
            "TRUST_REQUIRED" -> {
                return if (ru) "Подтвердите ключ сервера · TRUST_REQUIRED" else "Verify host key · TRUST_REQUIRED"
            }
            "REMOTE_CHANGED" -> {
                return if (ru) "Файл изменился · REMOTE_CHANGED" else "File changed · REMOTE_CHANGED"
            }
            "PASSWORD_REQUIRED" -> {
                return if (ru) "Нужен пароль · PASSWORD_REQUIRED" else "Password required · PASSWORD_REQUIRED"
            }
            else -> {
                return (if (ru) "Операция не завершена · " else "Operation incomplete · ") + code
            }
        }
    }
}
