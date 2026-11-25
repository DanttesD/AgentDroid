package com.termux.app.workspace

import android.os.SystemClock
import com.termux.BuildConfig
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Bounded, private, structured diagnostics. Never records terminal input, tokens or transcript.*/
object WorkspaceDiagnostics {
    class Run {
        @JvmField var id: String? = null
        @JvmField var handle: String? = null
        @JvmField var agent: String? = null
        @JvmField var environment: String? = null
        @JvmField var extended: Boolean = false
        @JvmField var extendedAtLaunch: Boolean = false
        @JvmField var paused: Boolean = false
        @JvmField var startedAt: Long = 0L
        @JvmField var startedElapsed: Long = 0L
        @JvmField var endedElapsed: Long = 0L
        @JvmField var lastEvent: Long = 0L
        @JvmField var launches: Int = 0
        @JvmField var errorCount: Int = 0
        @JvmField var lastScan: Long = 0L
        @JvmField var lastOutput: Long = 0L
        @JvmField var bytes: Int = 0
        @JvmField var dropped: Int = 0
        @JvmField var lastError: String = ""
        @JvmField val lines: ArrayDeque<String> = ArrayDeque<String>()
    }

    class Snapshot {
        @JvmField val paused: Boolean
        @JvmField val extended: Boolean
        @JvmField val nativeAtLaunch: Boolean
        @JvmField val bytes: Int
        @JvmField val dropped: Int
        @JvmField val launches: Int
        @JvmField val errors: Int
        @JvmField val lastEvent: Long
        @JvmField val uptime: Long
        @JvmField val signal: String
        @JvmField val events: MutableList<String>

        constructor(r: Run?) {
            paused = r!!.paused
            extended = r.extended
            nativeAtLaunch = r.extendedAtLaunch
            bytes = r.bytes
            dropped = r.dropped
            launches = r.launches
            errors = r.errorCount
            lastEvent = r.lastEvent
            uptime = if (r.startedAt == 0L) (-1).toLong() else Math.max(0L, ((if (r.endedElapsed == 0L) SystemClock.elapsedRealtime() else r.endedElapsed) - r.startedElapsed) / 1000)
            signal = r.lastError
            events = ArrayList<String>()
            val i: MutableIterator<String> = r.lines.descendingIterator()
            while (i.hasNext() && events.size < 200) {
                events.add(i.next())
            }
        }
    }

    const val LIMIT: Int = 2 * 1024 * 1024
    private val RUNS: MutableMap<String, Run?> = LinkedHashMap<String, Run?>()
    private val IO: ExecutorService = Executors.newSingleThreadExecutor()
    private val CODES: Pattern = Pattern.compile("\\b(?:RESOURCE_EXHAUSTED|UNAUTHENTICATED|PERMISSION_DENIED|INVALID_ARGUMENT|NOT_FOUND|UNAVAILABLE|DEADLINE_EXCEEDED|UNSUPPORTED_CLIENT|ENOENT|EACCES|ECONNRESET|ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EAI_AGAIN|ENETUNREACH)\\b")
    private val HTTP: Pattern = Pattern.compile("(?i)\\b(?:http(?:/[0-9.]+)?|status(?:code)?|code)[\\s:=\"']{1,12}([45][0-9]{2})\\b")

    @JvmStatic
    private fun agent(s: WorkspaceStore.Session?): String {
        return if (s!!.geminiAgent) "Gemini CLI" else if (s.claudeAgent) "Claude Code" else if (s.aiAgent.equals("opencode")) "OpenCode" else if (s.environment.equals("codex")) "Codex" else "shell"
    }

    @JvmStatic
    private fun stamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())
    }

    @JvmStatic
    private fun append(r: Run?, message: String) {
        r!!.lastEvent = System.currentTimeMillis()
        val line: String = stamp() + " " + message + "\n"
        r.lines.addLast(line)
        r.bytes += line.length
        while (r.bytes > LIMIT && !r.lines.isEmpty()) {
            r.bytes -= r.lines.removeFirst().length
            r.dropped++
        }
        var total = 0
        for (known in RUNS.values) {
            total += known!!.bytes
        }
        // Bound total retained data too, including closed profiles.
        while (total > 16 * LIMIT) {
            var removed = false
            for (known in RUNS.values) {
                if (known!!.lines.size > 1) {
                    val n: Int = known.lines.removeFirst().length
                    known.bytes -= n
                    known.dropped++
                    total -= n
                    removed = true
                    break
                }
            }
            if (!removed) {
                break
            }
        }
    }

    @Synchronized
    @JvmStatic
    fun register(s: WorkspaceStore.Session?, launched: Boolean) {
        var r: Run? = RUNS.get(s!!.id)
        if (r == null) {
            r = Run()
            r.id = s.id
            r.agent = agent(s)
            r.environment = s.environment
            RUNS.put(s.id, r)
            append(r, "COLLECTOR attached; previous process output is not reconstructed")
        }
        r.handle = s.handle
        if (launched) {
            r.agent = agent(s)
            r.environment = s.environment
            r.extended = s.extendedDebug
            r.extendedAtLaunch = s.extendedDebug
            r.startedAt = System.currentTimeMillis()
            r.startedElapsed = SystemClock.elapsedRealtime()
            r.endedElapsed = 0L
            r.launches++
        }
        if (launched) {
            r.paused = false
            append(r, "START environment=" + r.environment + " agent=" + r.agent + " extended=" + r.extended + "; custom commands and credentials omitted")
        }
        while (RUNS.size > 64) {
            RUNS.remove(RUNS.keys.iterator().next())
        }
    }

    @Synchronized
    @JvmStatic
    fun failure(s: WorkspaceStore.Session?, error: Exception) {
        register(s, false)
        val r: Run? = RUNS.get(s!!.id)
        if (!r!!.paused) {
            append(r, "LAUNCH_FAILED category=" + (if (error is IOException) "IO" else "runtime") + "; details omitted to protect credentials")
        }
    }

    @Synchronized
    @JvmStatic
    fun finished(session: TerminalSession?) {
        val r: Run? = find(session)
        if (r != null) {
            r.endedElapsed = SystemClock.elapsedRealtime()
            if (!r.paused) {
                append(r, "PROCESS_EXIT status=" + session!!.getExitStatus())
            }
        }
    }

    @JvmStatic
    private fun find(s: TerminalSession?): Run? {
        for (r in RUNS.values) {
            if (s!!.mHandle.equals(r!!.handle)) {
                return r
            }
        }
        return null
    }

    /** Called regardless of diagnostics dialog visibility. Work is capped to 100 terminal rows / 2s.*/
    @Synchronized
    @JvmStatic
    fun onOutput(session: TerminalSession?) {
        val r: Run? = find(session)
        if (r == null || r.paused) {
            return
        }
        val now: Long = SystemClock.elapsedRealtime()
        if (now - r.lastScan < 2000) {
            return
        }
        r.lastScan = now
        val e: TerminalEmulator? = session!!.getEmulator()
        if (e == null) {
            return
        }
        val tail: String = e.getScreen().getSelectedText(0, -Math.min(60, e.getScreen().getActiveTranscriptRows()), Math.min(400, e.mColumns), Math.min(40, e.mRows), true)
        val errors: String = classify(tail)
        if (!errors.equals(r.lastError)) {
            if (!errors.isEmpty()) {
                r.errorCount++
                append(r, "TERMINAL_SIGNAL " + errors + " (observed text; not proof of a failed request)")
            }
            r.lastError = errors
        }
        if (r.extended && now - r.lastOutput >= 15000) {
            append(r, "OUTPUT_ACTIVITY pid=" + session.getPid())
            r.lastOutput = now
        }
    }

    /** Only allowlisted error categories/codes leave the terminal buffer. Unknown text never enters a report.*/
    @JvmStatic
    fun classify(raw: String): String {
        val values: MutableSet<String?> = LinkedHashSet<String?>()
        val code: Matcher = CODES.matcher(raw)
        while (code.find()) {
            values.add(code.group())
        }
        val http: Matcher = HTTP.matcher(raw)
        while (http.find()) {
            values.add("HTTP_" + http.group(1))
        }
        val lower: String = (raw as java.lang.String).toLowerCase(Locale.ROOT)
        if (lower.contains("quota") || lower.contains("rate limit")) {
            values.add("QUOTA_OR_RATE_LIMIT_TEXT")
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            values.add("TIMEOUT_TEXT")
        }
        if (lower.contains("this client is no longer supported")) {
            values.add("UNSUPPORTED_CLIENT_TEXT")
        }
        if (lower.contains("skipping system settings file")) {
            values.add("SYSTEM_SETTINGS_REJECTED_TEXT")
        }
        if (lower.contains("taking a bit longer")) {
            values.add("LONG_WAIT_TEXT")
        }
        if (lower.contains("failed to sign in") || lower.contains("not logged in")) {
            values.add("AUTH_FAILURE_TEXT")
        }
        if (lower.contains("error") || lower.contains("exception")) {
            values.add("ERROR_TEXT")
        }
        val summary = StringBuilder()
        for (value in values) {
            if (summary.length > 0) {
                summary.append(' ')
            }
            summary.append(value)
        }
        return summary.toString()
    }

    @Synchronized
    @JvmStatic
    fun paused(id: String): Boolean {
        val r: Run? = RUNS.get(id)
        return r != null && r.paused
    }

    @Synchronized
    @JvmStatic
    fun pause(id: String, value: Boolean) {
        val r: Run? = RUNS.get(id)
        if (r != null && r.paused != value) {
            r.paused = value
            append(r, if (value) "COLLECTION_PAUSED" else "COLLECTION_RESUMED; paused interval unavailable")
        }
    }

    @Synchronized
    @JvmStatic
    fun report(profile: WorkspaceStore.Session?, terminal: TerminalSession?): String {
        val r: Run? = RUNS.get(profile!!.id)
        val b: StringBuilder = StringBuilder("AgentDroid diagnostics\nApp: " + BuildConfig.VERSION_NAME + "\nFormat: 2\nGenerated: ").append(stamp()).append('\n')
        b.append("Session: ").append(profile.id).append("\nEnvironment: ").append(profile.environment).append("\nAgent: ").append(agent(profile))
        b.append("\nExtended requested: ").append(profile.extendedDebug).append("\nExtended active at launch: ").append(r != null && r.extendedAtLaunch)
        b.append("\nDetailed collection now: ").append(r != null && r.extended).append("\nLaunches observed: ").append(if (r == null) 0 else r.launches).append("\nLast event timestamp: ").append(if (r == null) 0L else r.lastEvent)
        b.append("\nCollector paused: ").append(r != null && r.paused).append("\nProcess: ").append(if (terminal == null) "unavailable" else if (terminal.isRunning()) "running pid=" + terminal.getPid() else "exited status=" + terminal.getExitStatus())
        b.append("\nLimit: 2 MiB per session, 32 MiB total; dropped old entries: ").append(if (r == null) 0 else r.dropped)
        b.append("\nPrivacy: structured events/codes; X11 may include redacted installer transfer failures. No terminal transcript is exported. URL, credential and path redaction is best-effort; review the report before sharing.\nHTTP/internal details depend on native agent output; this is not a network trace.\nNative verbose mode currently supported for Gemini only, enabled before launch. Other agents: extended activity timeline and observed error codes.\nRuntime events retained while app process lives; saved report files survive restart.\n\n")
        if (r == null) {
            b.append("No collector attached for this session.\n")
        } else {
            for (line in r.lines) {
                b.append(line)
            }
        }
        return b.toString()
    }

    @Synchronized
    @JvmStatic
    fun extended(id: String): Boolean {
        val r: Run? = RUNS.get(id)
        return r != null && r.extended
    }

    @Synchronized
    @JvmStatic
    fun extended(id: String, value: Boolean) {
        val r: Run? = RUNS.get(id)
        if (r != null && r.extended != value) {
            r.extended = value
            if (!r.paused) {
                append(r, if (value) "DETAILS_ENABLED; future activity only; native launch flags unchanged" else "DETAILS_DISABLED")
            }
        }
    }

    @Synchronized
    @JvmStatic
    fun snapshot(s: WorkspaceStore.Session?): Snapshot {
        register(s, false)
        return Snapshot(RUNS.get(s!!.id))
    }

    @JvmStatic
    fun friendly(line: String, ru: Boolean): String {
        val time: String = if (line.length > 23) line.substring(11, 19) else ""
        val event: String = if (line.length > 29) (line.substring(29) as java.lang.String).trim() else line
        var title: String
        if (event.startsWith("COLLECTOR")) {
            title = if (ru) "Сбор диагностики подключён. Предыдущие события недоступны." else "Collector attached. Earlier events are unavailable."
        } else if (event.startsWith("START")) {
            title = if (ru) "Процесс запущен" else "Process launched"
        } else if (event.startsWith("PROCESS_EXIT")) {
            title = (if (ru) "Процесс завершился · " else "Process exited · ") + event.substring("PROCESS_EXIT ".length)
        } else if (event.startsWith("LAUNCH_FAILED")) {
            title = if (ru) "Не удалось запустить процесс. Проверьте окружение и доступность программы." else "Process launch failed. Check the environment and executable."
        } else if (event.startsWith("TERMINAL_SIGNAL")) {
            title = (if (ru) "В выводе замечен сигнал: " else "Observed in output: ") + (event.substring("TERMINAL_SIGNAL ".length) as java.lang.String).replace(" (observed text; not proof of a failed request)", "") + (if (ru) " · проверьте контекст, это не подтверждение сбоя запроса." else " · check context; this does not prove a request failed.")
        } else if (event.startsWith("OUTPUT_ACTIVITY")) {
            title = if (ru) "Получен вывод процесса" else "Process output activity"
        } else if (event.startsWith("COLLECTION_PAUSED")) {
            title = if (ru) "Журнал остановлен" else "Collection paused"
        } else if (event.startsWith("COLLECTION_RESUMED")) {
            title = if (ru) "Журнал возобновлён. Пропущенные события недоступны." else "Collection resumed. Paused events are unavailable."
        } else if (event.startsWith("DETAILS_ENABLED")) {
            title = if (ru) "Включён подробный сбор новых событий. Нативные флаги не менялись." else "Detailed collection enabled for future events. Native flags unchanged."
        } else if (event.startsWith("DETAILS_DISABLED")) {
            title = if (ru) "Включён обычный сбор" else "Normal collection enabled"
        } else {
            title = if (ru) "Событие диагностики" else "Diagnostic event"
        }
        return time + "  " + title + "\n    [" + (event as java.lang.String).split("[ =]", 2)[0] + "]"
    }

    @JvmStatic
    fun save(files: File, id: String, report: String, success: Consumer<File>, failure: Consumer<Exception>) {
        IO.execute(Runnable {
            try {
                val dir = File(files, "workspace-diagnostics")
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw IOException("Cannot create diagnostics directory")
                }
                val out = File(dir, id + ".txt")
                val temp: File = File.createTempFile("report-", ".tmp", dir)
                try {
                    (FileOutputStream(temp)).use { stream ->
                        stream.write((report as java.lang.String).getBytes(StandardCharsets.UTF_8))
                    }
                    if (!temp.setReadable(false, false) || !temp.setWritable(false, false) || !temp.setReadable(true, true) || !temp.setWritable(true, true)) {
                        throw IOException("Cannot protect report")
                    }
                    if (!temp.renameTo(out)) {
                        throw IOException("Cannot save report")
                    }
                } finally {
                    temp.delete()
                }
                success.accept(out)
            } catch (e: Exception) {
                failure.accept(e)
            }
        })
    }
}
