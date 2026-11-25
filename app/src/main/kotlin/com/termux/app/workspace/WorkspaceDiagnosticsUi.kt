package com.termux.app.workspace

import com.termux.BuildConfig
import com.termux.app.TermuxActivity
import com.termux.terminal.TerminalSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.function.Consumer
import java.util.function.Supplier

/** Same structured collector for Termux/Ubuntu/SSH/AI; no process control or new requests.*/
object WorkspaceDiagnosticsUi {
    @JvmStatic
    fun show(activity: TermuxActivity, profile: WorkspaceStore.Session, terminal: Supplier<TerminalSession?>) {
        show(activity, profile, terminal, Supplier<String> { "" }, Consumer<Runnable>(Runnable::run))
    }

    @JvmStatic
    fun show(activity: TermuxActivity, profile: WorkspaceStore.Session?, terminal: Supplier<TerminalSession?>, x11: Supplier<String>, prepare: Consumer<Runnable>) {
        DiagnosticsPanel.show(activity, object : DiagnosticsPanel.Source {
            override fun hasX11(): Boolean {
                return WorkspaceDesktops.diagnosticsEligible(profile)
            }

            override fun x11(): String {
                val value: String = x11.get()
                return if (value.isEmpty()) t("X11 · Нет сведений о запуске", "X11 · No startup snapshot") else value
            }

            override fun prepareX11(done: Runnable) {
                prepare.accept(done)
            }

            override fun prepareExport(done: Runnable) {
                prepare.accept(done)
            }

            @JvmField var data: WorkspaceDiagnostics.Snapshot = WorkspaceDiagnostics.snapshot(profile)
            @JvmField var live: TerminalSession? = null

            fun ru(): Boolean {
                return WorkspaceLanguage.code(activity)!!.equals("ru")
            }

            fun t(a: String, b: String): String {
                return if (ru()) a else b
            }

            override fun refresh() {
                live = terminal.get()
                data = WorkspaceDiagnostics.snapshot(profile)
            }

            override fun title(): String {
                return t("Диагностика сессии", "Session diagnostics")
            }

            override fun id(): String {
                return profile!!.id
            }

            override fun summary(): String {
                val state: String = if (live == null) t("Нет сведений о процессе", "Process unavailable") else if (live!!.isRunning()) t("Работает · PID ", "Running · PID ") + live!!.getPid() else t("Завершён · код ", "Exited · status ") + live!!.getExitStatus()
                return t("Приложение: ", "App: ") + BuildConfig.VERSION_NAME + "\n" + t("Среда: ", "Environment: ") + profile!!.environment + "\n" + state + "\n" + t("Время работы: ", "Uptime: ") + (if (data.uptime < 0) t("неизвестно, процесс подключён ранее", "unknown; process attached earlier") else (data.uptime / 60).toString() + t(" мин ", " min ") + data.uptime % 60 + t(" с", " s")) + "\n" + t("Журнал: ", "Journal: ") + (if (data.extended) t("подробный", "detailed") else t("обычный", "normal")) + " · " + (if (data.paused) t("остановлен", "paused") else t("сбор включён", "collecting")) + "\n" + t("Последнее событие: ", "Last event: ") + (if (data.lastEvent == 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(data.lastEvent))) + "\n" + t("Запусков в этом журнале: ", "Launches observed: ") + data.launches + "\n" + t("Наблюдений ошибок: ", "Error observations: ") + data.errors + "\n\n" + t("Состав зависит от среды и агента. Работающий процесс не доказывает успешный вход. Пауза событий не означает зависание.", "Available details depend on the environment and agent. A running process does not prove authentication. Event silence does not imply a hang.") + "\n" + t("Нативный verbose Gemini задаётся при запуске. Переключение здесь меняет сбор новых событий, без перезапуска и изменения прав.", "Gemini native verbose is set at launch. This switch changes future event collection, without restarting or changing permissions.")
            }

            override fun events(): String {
                val text = StringBuilder()
                for (line in data.events) {
                    text.append(WorkspaceDiagnostics.friendly(line, ru())).append('\n')
                }
                return text.toString()
            }

            override fun report(): String {
                return WorkspaceDiagnostics.report(profile, terminal.get()) + (if (hasX11()) "\n\n" + x11() else "")
            }

            override fun bytes(): Int {
                return data.bytes
            }

            override fun dropped(): Int {
                return data.dropped
            }

            override fun paused(): Boolean {
                return WorkspaceDiagnostics.paused(profile!!.id)
            }

            override fun paused(value: Boolean) {
                WorkspaceDiagnostics.pause(profile!!.id, value)
            }

            override fun hasMode(): Boolean {
                return true
            }

            override fun extended(): Boolean {
                return WorkspaceDiagnostics.extended(profile!!.id)
            }

            override fun extended(value: Boolean) {
                WorkspaceDiagnostics.extended(profile!!.id, value)
            }
        })
    }
}
