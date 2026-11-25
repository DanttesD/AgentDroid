package com.termux.app.workspace

import com.termux.BuildConfig
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Arrays
import java.util.concurrent.CancellationException

/** Local completion telemetry. Accepts only typed operations/numbers and allowlisted errors. No RPCs.*/
object ServerDiagnostics {
    enum class Op {
        METRICS,
        DIRECTORY,
        SEARCH,
        PROBE,
        COMPONENTS,
        PROPERTIES,
        INSTALL,
        SCHEDULE,
        TASK_START,
        TASK_CANCEL,
        TASK_LOG,
        TASK_REMOVE,
        FILE_RENAME,
        FILE_DELETE,
        STREAM,
        SERVICES,
        SERVICE_LOG,
        SERVICE_RESTART,
        SFTP_CONNECT,
        UPLOAD,
        DOWNLOAD,
        RPC,
        FILE_MOVE
    }

    class Event {
        @JvmField val time: Long
        @JvmField val waitMs: Long
        @JvmField val networkMs: Long
        @JvmField val parseMs: Long
        @JvmField val bytes: Long
        @JvmField val operation: Op
        @JvmField val code: String?

        constructor(op: Op, wait: Long, net: Long, parse: Long, size: Long, error: String?) {
            time = System.currentTimeMillis()
            operation = op
            waitMs = wait
            networkMs = Math.max(0L, net)
            parseMs = Math.max(0L, parse)
            bytes = size
            code = error
        }

        fun line(): String {
            return time.toString() + " " + operation.name + " queue_ms=" + waitMs + " request_ms=" + networkMs + " parse_ms=" + parseMs + " bytes=" + bytes + " result=" + code + "\n"
        }
    }

    class Run {
        @JvmField var paused: Boolean = false
        @JvmField var epoch: Int = 0
        @JvmField var bytes: Int = 0
        @JvmField var dropped: Int = 0
        @JvmField val events: ArrayDeque<Event> = ArrayDeque<Event>()

        constructor(p: Boolean) {
            paused = p
        }
    }

    class Trace {
        @JvmField val id: String
        @JvmField val op: Op
        @JvmField val epoch: Int
        @JvmField val enabled: Boolean
        @JvmField val waitMs: Long
        @JvmField val started: Long = System.nanoTime()
        private var done: Boolean = false

        constructor(id: String, op: Op, epoch: Int, enabled: Boolean, wait: Long) {
            this.id = id
            this.op = op
            this.epoch = epoch
            this.enabled = enabled
            waitMs = wait
        }

        fun elapsed(): Long {
            return Math.max(0L, (System.nanoTime() - started) / 1000000)
        }

        @Synchronized
        fun finish(network: Long, parse: Long, size: Long, failure: Exception?) {
            if (done) {
                return
            }
            done = true
            if (enabled) {
                record(this, Event(op, waitMs, network, parse, size, error(failure)))
            }
        }
    }

    class Snapshot {
        @JvmField val paused: Boolean
        @JvmField val bytes: Int
        @JvmField val dropped: Int
        @JvmField val events: MutableList<Event>

        constructor(r: Run?) {
            paused = r!!.paused
            bytes = r.bytes
            dropped = r.dropped
            val last: MutableList<Event> = ArrayList<Event>()
            val i: MutableIterator<Event> = r.events.descendingIterator()
            while (i.hasNext() && last.size < 200) {
                last.add(i.next())
            }
            events = last
        }
    }

    const val LIMIT: Int = 2 * 1024 * 1024
    const val TOTAL: Int = 32 * 1024 * 1024
    private val runs: LinkedHashMap<String, Run> = LinkedHashMap<String, Run>()
    private val queued: ThreadLocal<Long> = ThreadLocal<Long>()

    @JvmStatic
    fun queueStarted(elapsedMs: Long) {
        queued.set(Math.max(0L, elapsedMs))
    }

    @JvmStatic
    fun queueFinished() {
        queued.remove()
    }

    private val ERRORS: MutableSet<String> = HashSet<String>(Arrays.asList("SSH_FAILED", "TRUST_REQUIRED", "PASSWORD_REQUIRED", "OPENSSH_REQUIRED", "CLOSED", "CANCELLED", "RESPONSE_LIMIT", "PYTHON_OR_AGENT_UNAVAILABLE", "AGENT_ERROR", "SEARCH_FAILED", "SCHEDULE_ERROR", "SFTP_FAILED", "SFTP_WRITE_FAILED", "SFTP_ID", "SFTP_PACKET", "SFTP_OPEN", "SFTP_STAT", "SFTP_REPLACE_UNSUPPORTED", "REMOTE_CHANGED", "FILE_LIMIT", "INVALID_PATH", "ACTIVE_TASKS", "TASK_NOT_FOUND", "TASK_NOT_MANAGED", "QUEUE_FULL", "PERMISSION_DENIED", "SERVICE_PERMISSION_OR_UNAVAILABLE", "DOWNLOAD_FAILED", "NOT_DIRECTORY", "TIMEOUT", "SFTP_READ", "SFTP_SHORT_READ", "SFTP_READDIR", "SFTP_DIRECTORY", "TREE_LIMIT", "DOWNLOAD_SIZE_LIMIT", "DESTINATION_EXISTS", "MOVE_INTO_SELF", "UNSUPPORTED_TYPE", "REMOTE_SYMLINK_OR_CHANGED", "DUPLICATE_OR_UNSAFE_NAME", "DELETE_FAILED", "DELETE_MOUNT"))

    @JvmStatic
    fun error(e: Exception?): String? {
        return if (e == null) "OK" else if (e is CancellationException || e is InterruptedException) "CANCELLED" else if (ERRORS.contains(e.message)) e.message else "OTHER_ERROR"
    }

    @Synchronized
    @JvmStatic
    fun configure(p: ServerProfiles.Profile?) {
        if (!runs.containsKey(p!!.id)) {
            runs.put(p.id, Run(p.diagnosticsPaused))
        }
        while (runs.size > 64) {
            runs.remove(runs.keys.iterator().next())
        }
    }

    @Synchronized
    @JvmStatic
    fun pause(p: ServerProfiles.Profile?, paused: Boolean) {
        configure(p)
        val r: Run? = runs.get(p!!.id)
        r!!.paused = paused
        r.epoch++
    }

    @Synchronized
    @JvmStatic
    fun paused(p: ServerProfiles.Profile?): Boolean {
        configure(p)
        return runs.get(p!!.id)!!.paused
    }

    @Synchronized
    @JvmStatic
    fun begin(p: ServerProfiles.Profile?, operation: Op): Trace {
        configure(p)
        val r: Run? = runs.get(p!!.id)
        val wait: Long? = queued.get()
        queued.remove()
        return Trace(p.id, operation, r!!.epoch, !r.paused, if (wait == null) (-1).toLong() else wait)
    }

    @Synchronized
    @JvmStatic
    private fun record(trace: Trace, event: Event) {
        val r: Run? = runs.get(trace.id)
        if (r == null || r.paused || r.epoch != trace.epoch) {
            return
        }
        r.events.addLast(event)
        r.bytes += (event.line() as java.lang.String).getBytes(StandardCharsets.UTF_8).size
        while (r.bytes > LIMIT && !r.events.isEmpty()) {
            drop(r)
        }
        var total = 0
        for (run in runs.values) {
            total += run.bytes
        }
        while (total > TOTAL) {
            var changed = false
            for (run in runs.values) {
                if (!run.events.isEmpty()) {
                    val n: Int = run.bytes
                    drop(run)
                    total -= n - run.bytes
                    changed = true
                    break
                }
            }
            if (!changed) {
                break
            }
        }
    }

    @JvmStatic
    private fun drop(r: Run) {
        r.bytes -= (r.events.removeFirst().line() as java.lang.String).getBytes(StandardCharsets.UTF_8).size
        r.dropped++
    }

    @Synchronized
    @JvmStatic
    fun snapshot(p: ServerProfiles.Profile?): Snapshot {
        configure(p)
        return Snapshot(runs.get(p!!.id))
    }

    @Synchronized
    @JvmStatic
    fun report(p: ServerProfiles.Profile?): String {
        configure(p)
        val r: Run? = runs.get(p!!.id)
        val b = StringBuilder("Termux Job's server diagnostics\nApp: " + BuildConfig.VERSION_NAME + "\nFormat: 1\nCollection paused: " + r!!.paused + "\nDropped: " + r.dropped + "\nQueue -1 / bytes -1 mean unavailable. Completed operations only.\nNo addresses, paths, commands, payloads or secrets. Memory journal resets with app process; exported files persist.\n\n")
        for (e in r.events) {
            b.append(e.line())
        }
        return b.toString()
    }

    @JvmStatic
    fun operation(op: String): Op {
        when (op) {
            "status" -> {
                return Op.METRICS
            }
            "list" -> {
                return Op.DIRECTORY
            }
            "start" -> {
                return Op.TASK_START
            }
            "cancel" -> {
                return Op.TASK_CANCEL
            }
            "log" -> {
                return Op.TASK_LOG
            }
            "forget" -> {
                return Op.TASK_REMOVE
            }
            "rename" -> {
                return Op.FILE_RENAME
            }
            "delete" -> {
                return Op.FILE_DELETE
            }
            "stream" -> {
                return Op.STREAM
            }
            "services" -> {
                return Op.SERVICES
            }
            "service-log" -> {
                return Op.SERVICE_LOG
            }
            "service-restart" -> {
                return Op.SERVICE_RESTART
            }
            else -> {
                return Op.RPC
            }
        }
    }

    @JvmStatic
    fun title(op: Op, ru: Boolean): String {
        val ruNames: Array<String> = arrayOf<String>("Метрики", "Чтение каталога", "Поиск имён", "Проверка подключения", "Компоненты", "Свойства файла", "Установка агента", "Расписания", "Запуск задачи", "Остановка задачи", "Журнал задачи", "Удаление записи", "Переименование", "Удаление файлов", "Открытие медиа", "Службы", "Журнал службы", "Перезапуск службы", "Подключение SFTP", "Отправка файла", "Скачивание файла", "Запрос", "Перемещение файлов")
        return if (ru) ruNames[op.ordinal] else (op.name as java.lang.String).replace('_', ' ')
    }
}
