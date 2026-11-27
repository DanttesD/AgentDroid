package com.termux.app.workspace

import android.content.Context
import android.net.LocalSocket
import android.util.Base64
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Bounded JSON-RPC over stdio for browsing, and RFC6455 over a private Unix socket for live status.*/
class CodexRpc : AutoCloseable {
    private var `in`: InputStream? = null
    private var out: OutputStream? = null
    private var socket: LocalSocket? = null
    private var process: Process? = null
    private val websocket: Boolean
    private var serial: Int = 0
    @Volatile private var failureKind: String? = ""
    private var stage: String = "launch"
    private var errors: Thread? = null

    private class ControlFailure : IOException {
        constructor(message: String) : super(message)
    }

    private fun drainErrors() {
        errors = Thread(Runnable {
            try {
                (process!!.getErrorStream()).use { input ->
                    val line = ByteArrayOutputStream()
                    var b: Int = 0
                    while (true) {
                        b = input.read()
                        if (b == -1) break
                        if (b == '\n'.code) {
                            val classified: String? = classify(String(line.toByteArray(), StandardCharsets.UTF_8))
                            if (!classified!!.isEmpty()) {
                                failureKind = classified
                            }
                            line.reset()
                        } else if (line.size() < 4096) {
                            line.write(b)
                        }
                    }
                    val classified: String? = classify(String(line.toByteArray(), StandardCharsets.UTF_8))
                    if (!classified!!.isEmpty()) {
                        failureKind = classified
                    }
                }
            } catch (ignored: IOException) {
            }
        }, "codex-control-errors")
        errors!!.setDaemon(true)
        errors!!.start()
    }

    private fun failed(failure: Exception): ControlFailure {
        if (failure is ControlFailure) {
            return failure as ControlFailure
        }
        var exit = ""
        if (process != null) {
            try {
                exit = ", exit=" + process!!.exitValue()
                if (errors != null) {
                    errors!!.join(100L)
                }
            } catch (ignored: IllegalThreadStateException) {
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return ControlFailure(WorkspaceText.tr("Статус Codex недоступен: ") + (if (failureKind!!.isEmpty()) failure.javaClass.getSimpleName() else failureKind) + " (" + stage + exit + WorkspaceText.tr("). Чат и авторизация не изменены."))
    }

    private val random: SecureRandom = SecureRandom()
    private val timeout: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private constructor(websocket: Boolean) {
        this.websocket = websocket
    }

    @Throws(Exception::class)
    private fun initialize() {
        call("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "termux_jobs").put("version", "0.2.19")).put("capabilities", JSONObject().put("experimentalApi", true)))
        send(JSONObject().put("method", "initialized"))
    }

    @Throws(Exception::class)
    fun call(method: String, params: JSONObject): JSONObject {
        stage = method
        try {
            val id: Int = ++serial
            send(JSONObject().put("id", id).put("method", method).put("params", params))
            val deadline: Long = System.currentTimeMillis() + 12000
            while (System.currentTimeMillis() < deadline) {
                val message: JSONObject = receive()
                if (message.has("id") && message.optInt("id", -1) == id && !message.has("method")) {
                    if (message.has("error")) {
                        throw IOException("Codex rejected " + method)
                    }
                    return message.getJSONObject("result")
                }
                // This status connection never handles approvals or tools. The TUI remains their owner.
            }
            throw IOException("Codex request timed out")
        } catch (e: Exception) {
            throw failed(e)
        }
    }

    @Throws(IOException::class)
    private fun line(max: Int): String {
        val bytes = ByteArrayOutputStream()
        var b: Int = 0
        while (`in`!!.read().also { b = it } != -1 && b != '\n'.code) {
            if (bytes.size() >= max) {
                throw IOException("Message too large")
            }
            bytes.write(b)
        }
        if (b == -1) {
            throw EOFException()
        }
        return (String(bytes.toByteArray(), StandardCharsets.UTF_8) as java.lang.String).replace("\r", "")
    }

    @Throws(Exception::class)
    private fun upgrade() {
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        val key: String = Base64.encodeToString(nonce, Base64.NO_WRAP)
        out!!.write((("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n") as java.lang.String).getBytes(StandardCharsets.US_ASCII))
        out!!.flush()
        if (!line(4096).startsWith("HTTP/1.1 101")) {
            throw IOException("WebSocket upgrade rejected")
        }
        var accept = ""
        var finished = false
        for (count in 0 until (64)) {
            val h: String = line(4096)
            if (h.isEmpty()) {
                finished = true
                break
            }
            if ((h as java.lang.String).toLowerCase(Locale.ROOT).startsWith("sec-websocket-accept:")) {
                accept = (h.substring(h.indexOf(':') + 1) as java.lang.String).trim()
            }
        }
        val expected: String = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11") as java.lang.String).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP)
        if (!finished || !expected.equals(accept)) {
            throw IOException("Invalid WebSocket handshake")
        }
    }

    @Throws(IOException::class)
    private fun frame(opcode: Int, bytes: ByteArray) {
        out!!.write(0x80 or opcode)
        val length: Int = bytes.size
        if (length < 126) {
            out!!.write(0x80 or length)
        } else if (length < 65536) {
            out!!.write(0x80 or 126)
            out!!.write(length ushr 8)
            out!!.write(length)
        } else {
            out!!.write(0x80 or 127)
            for (shift in (56 downTo 0) step 8) {
                out!!.write((length.toLong() ushr shift).toInt())
            }
        }
        val mask = ByteArray(4)
        random.nextBytes(mask)
        out!!.write(mask)
        for (i in 0 until (bytes.size)) {
            out!!.write(bytes[i].toInt() xor mask[i % 4].toInt())
        }
        out!!.flush()
    }

    @Throws(IOException::class)
    private fun byteValue(): Int {
        val b: Int = `in`!!.read()
        if (b < 0) {
            throw EOFException()
        }
        return b
    }

    @Throws(Exception::class)
    private fun receive(): JSONObject {
        if (!websocket) {
            return JSONObject(line(MAX))
        }
        val message = ByteArrayOutputStream()
        while (true) {
            val first: Int = byteValue()
            val second: Int = byteValue()
            val opcode: Int = first and 15
            var length: Long = (second and 127).toLong()
            if (first and 0x70 != 0 || second and 0x80 != 0) {
                throw IOException("Invalid server frame")
            }
            if (length == 126L) {
                length = (byteValue() shl 8 or byteValue()).toLong()
            } else if (length == 127L) {
                length = 0L
                for (i in 0 until (8)) {
                    length = length shl 8 or byteValue().toLong()
                }
            }
            if (length < 0 || length > MAX || message.size() + length > MAX) {
                throw IOException("Message too large")
            }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read: Int = `in`!!.read(bytes, offset, bytes.size - offset)
                if (read < 0) {
                    throw EOFException()
                }
                offset += read
            }
            if (opcode == 8) {
                throw EOFException()
            }
            if (opcode == 9) {
                frame(10, bytes)
                continue
            }
            if (opcode == 10) {
                continue
            }
            if (opcode != 0 && opcode != 1) {
                throw IOException("Expected text frame")
            }
            message.write(bytes)
            if (first and 0x80 != 0) {
                return JSONObject(String(message.toByteArray(), StandardCharsets.UTF_8))
            }
        }
    }

    @Throws(IOException::class)
    private fun send(data: JSONObject) {
        val bytes: ByteArray = (data.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8)
        if (websocket) {
            frame(1, bytes)
        } else {
            out!!.write(bytes)
            out!!.write('\n'.code)
            out!!.flush()
        }
    }

    override fun close() {
        try {
            if (socket != null) {
                socket!!.close()
            }
        } catch (ignored: IOException) {
        }
        try {
            if (out != null) {
                out!!.close()
            }
        } catch (ignored: IOException) {
        }
        try {
            if (`in` != null) {
                `in`!!.close()
            }
        } catch (ignored: IOException) {
        }
        if (process != null) {
            process!!.destroy()
        }
        timeout.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun diagnostic(failure: Exception): String? {
            return if (failure is ControlFailure) failure.message else WorkspaceText.tr("Не удалось прочитать статус Codex (") + failure.javaClass.getSimpleName() + WorkspaceText.tr("). Чат может продолжать работать.")
        }

        @JvmStatic
        fun classify(line: String): String? {
            if (line.contains("JOBS_CONTROL:NO_NODE")) {
                return WorkspaceText.tr("В выбранной среде не найден Node.js")
            }
            if (line.contains("JOBS_CONTROL:NO_BRIDGE")) {
                return WorkspaceText.tr("Выбранная среда не видит файл адаптера")
            }
            if (line.contains("JOBS_CONTROL:NO_SOCKET")) {
                return WorkspaceText.tr("Выбранная среда не видит сокет текущей сессии")
            }
            if (line.contains("ENOENT")) {
                return WorkspaceText.tr("Не найден файл или сокет адаптера")
            }
            if (line.contains("ECONNREFUSED")) {
                return WorkspaceText.tr("Сервер Codex не принимает подключение")
            }
            if (line.contains("EACCES") || line.contains("Permission denied")) {
                return WorkspaceText.tr("Отказ в доступе к адаптеру")
            }
            if (line.contains("not found")) {
                return WorkspaceText.tr("Не найдена программа для запуска адаптера")
            }
            if (line.contains("WebSocket")) {
                return WorkspaceText.tr("Ошибка согласования локального протокола")
            }
            return ""
        }

        private const val MAX: Int = 2 * 1024 * 1024

        @Throws(Exception::class)
        @JvmStatic
        fun live(context: Context, profile: WorkspaceStore.Session): CodexRpc {
            CodexSessionShell.prepareBridge(context) // New immutable helper, without touching the active server.
            return process(context, CodexSessionShell.proxyCommand(context.getFilesDir(), profile))
        }

        @Throws(Exception::class)
        @JvmStatic
        fun history(context: Context, profile: WorkspaceStore.Session): CodexRpc {
            return process(context, CodexSessionShell.historyCommand(profile))
        }

        @Throws(Exception::class)
        @JvmStatic
        private fun process(context: Context, command: String): CodexRpc {
            val rpc = CodexRpc(false)
            try {
                val builder = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", command)
                builder.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
                builder.environment().clear()
                for (item in TermuxShellUtils.buildEnvironment(context, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
                    val equal: Int = item.indexOf('=')
                    if (equal > 0) {
                        builder.environment().put(item.substring(0, equal), item.substring(equal + 1))
                    }
                }
                rpc.process = builder.start()
                rpc.drainErrors()
                rpc.`in` = rpc.process!!.getInputStream()
                rpc.out = BufferedOutputStream(rpc.process!!.getOutputStream())
                rpc.timeout.schedule(Runnable(rpc::close), 25L, TimeUnit.SECONDS)
                rpc.initialize()
                return rpc
            } catch (e: Exception) {
                val failure: ControlFailure = rpc.failed(e)
                rpc.close()
                throw failure
            }
        }

        @JvmStatic
        fun stopOwnedServer(files: File, profile: WorkspaceStore.Session) {
            ownedStop(files, profile).run()
        }

        /** Resolve the owner before enqueueing: a mutable profile may already have relaunched.*/
        @JvmStatic
        fun ownedStop(files: File, profile: WorkspaceStore.Session): Runnable {
            val socket: File = CodexSessionShell.socket(files, profile)
            return Runnable { stopOwnedSocket(socket) }
        }

        @JvmStatic
        private fun stopOwnedSocket(socket: File) {
            val marker = File(socket.getAbsolutePath() + ".stop")
            val stopped = File(socket.getAbsolutePath() + ".stopped")
            try {
                (FileOutputStream(marker)).use { out ->
                    out.write(1)
                }
                val until: Long = System.currentTimeMillis() + 1500
                while (!stopped.exists() && System.currentTimeMillis() < until) {
                    Thread.sleep(50L)
                }
                // Only the launcher's watcher knows and signals the PID it created. No PID inference across proot.
            } catch (unavailable: Exception) {
                /* Finishing the terminal must still work.*/
            }
        }
    }
}
