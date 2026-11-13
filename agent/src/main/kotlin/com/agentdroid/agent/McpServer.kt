package com.agentdroid.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Minimal MCP server (Streamable HTTP, JSON responses) exposing the same tools as the built-in agent.
 * Plain sockets only, so it runs on Android and on a desktop JVM without extra libraries.
 *
 * Access control: the endpoint is `/mcp/<token>` (claude.ai custom connectors cannot send a
 * static header), and a `Authorization: Bearer <token>` header is accepted as well. Every tool that
 * needs approval still goes through the [Approver] (a dialog on the phone).
 */
class McpServer(
    toolList: List<Tool>,
    private val approver: Approver,
    private val token: String,
    private val requestedPort: Int,
) {
    private val tools = LinkedHashMap<String, Tool>()

    @Volatile
    private var guardRef: Guard? = null

    @Volatile
    private var instructionsText: String = ""

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var active = false

    init {
        for (t in toolList) tools[t.spec().name] = t
    }

    fun setGuard(g: Guard?) {
        guardRef = g
    }

    fun setInstructions(s: String?) {
        instructionsText = s ?: ""
    }

    @Synchronized
    @Throws(IOException::class)
    fun start(): Int {
        val current = server
        if (active && current != null) return current.localPort
        val ss = ServerSocket(requestedPort, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        active = true
        val acceptor = Thread({ acceptLoop() }, "mcp-accept")
        acceptor.isDaemon = true
        acceptor.start()
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        active = false
        try {
            server?.close()
        } catch (ignored: IOException) {
        }
    }

    fun isRunning(): Boolean = active

    fun port(): Int = server?.localPort ?: -1

    /** Path part of the public URL. */
    fun path(): String = "/mcp/$token"

    private fun acceptLoop() {
        while (active) {
            try {
                val s = server!!.accept()
                val t = Thread({ handle(s) }, "mcp-conn")
                t.isDaemon = true
                t.start()
            } catch (e: SocketException) {
                return
            } catch (e: IOException) {
                // keep serving
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 300_000
            val input = s.getInputStream()
            val out = s.getOutputStream()
            while (active) {
                val requestLine = readLine(input)
                if (requestLine == null || requestLine.isEmpty()) return
                val parts = requestLine.jsplit(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input)
                    if (line == null || line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).jtrim().lowercase()] = line.substring(i + 1).jtrim()
                }
                var len = 0
                try {
                    len = (headers["content-length"] ?: "0").toInt()
                } catch (ignored: NumberFormatException) {
                }
                if (len > MAX_BODY) {
                    respond(out, 413, "text/plain", "too large")
                    return
                }
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = input.read(body, off, len - off)
                    if (r < 0) return
                    off += r
                }
                val authorized = target == path() || target.startsWith(path() + "?") ||
                    (target == "/mcp" && ("Bearer $token") == headers["authorization"])
                if (!authorized) {
                    respond(out, 404, "text/plain", "not found")
                    continue
                }
                if (method != "POST") {
                    respond(out, 405, "text/plain", "POST only")
                    continue
                }
                val reply: String? = try {
                    dispatch(String(body, Charsets.UTF_8))
                } catch (e: RuntimeException) {
                    respond(out, 400, "text/plain", "bad request: " + e.message)
                    continue
                }
                if (reply == null) respond(out, 202, "text/plain", "") else respond(out, 200, "application/json", reply)
                if ("close".equals(headers["connection"], ignoreCase = true)) return
            }
        } catch (ignored: IOException) {
        } finally {
            try {
                s.close()
            } catch (ignored: IOException) {
            }
        }
    }

    /** Returns the JSON-RPC reply, or null for notifications. */
    @Suppress("UNCHECKED_CAST")
    fun dispatch(json: String): String? {
        val parsed = Json.parse(json)
        if (parsed is List<*>) {
            val replies = ArrayList<Any?>()
            for (o in parsed) {
                val r = one(o as Map<String, Any?>)
                if (r != null) replies.add(Json.parseObject(r))
            }
            return if (replies.isEmpty()) null else Json.stringify(replies)
        }
        return one(parsed as Map<String, Any?>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun one(req: Map<String, Any?>): String? {
        val id = req["id"]
        val method = req["method"] as String? ?: return null // a response from the client: ignore
        if (id == null) return null // notification
        val params: Map<String, Any?> =
            if (req["params"] is Map<*, *>) req["params"] as Map<String, Any?> else LinkedHashMap()
        try {
            val result: Any?
            when (method) {
                "initialize" -> {
                    val r = Json.obj(
                        "protocolVersion", PROTOCOL_VERSION,
                        "capabilities", Json.obj("tools", Json.obj()),
                        "serverInfo", Json.obj("name", "agentdroid", "version", "0.2"),
                    )
                    val instructions = instructionsText
                    if (instructions.isNotEmpty()) r["instructions"] = instructions
                    result = r
                }
                "ping" -> result = Json.obj()
                "tools/list" -> {
                    val l = ArrayList<Any?>()
                    for (t in tools.values) {
                        val sp = t.spec()
                        l.add(Json.obj("name", sp.name, "description", sp.description, "inputSchema", sp.schema))
                    }
                    result = Json.obj("tools", l)
                }
                "tools/call" -> result = call(params)
                else -> return Json.stringify(
                    Json.obj(
                        "jsonrpc", "2.0", "id", id,
                        "error", Json.obj("code", -32601L, "message", "method not found: $method"),
                    ),
                )
            }
            return Json.stringify(Json.obj("jsonrpc", "2.0", "id", id, "result", result))
        } catch (e: RuntimeException) {
            return Json.stringify(
                Json.obj(
                    "jsonrpc", "2.0", "id", id,
                    "error", Json.obj("code", -32602L, "message", e.message.toString()),
                ),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun call(params: Map<String, Any?>): Any? {
        val name = params["name"] as String? ?: throw IllegalArgumentException("unknown tool: null")
        val tool = tools[name] ?: throw IllegalArgumentException("unknown tool: $name")
        val args: Map<String, Any?> =
            if (params["arguments"] is Map<*, *>) params["arguments"] as Map<String, Any?> else LinkedHashMap()
        var text = ""
        var png: ByteArray? = null
        var error = false
        val g = guardRef
        var refused = false
        if (g != null) {
            try {
                g.before(name, args, tool.describe(args))
            } catch (e: SecurityException) {
                text = "refused: " + e.message
                error = true
                refused = true
            } catch (ignored: RuntimeException) {
            }
        }
        if (refused) {
            // text already set
        } else if ((tool.needsApproval(args) || tool.sensitive(args)) &&
            !approver.approve(ToolCall("mcp", name, args), (if (tool.sensitive(args)) "!" else "") + tool.describe(args))
        ) {
            text = "the user denied this action"
            error = true
        } else {
            try {
                val tr = tool.run(args)
                text = tr.text
                png = tr.png
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                text = "error: " + e.message
                error = true
            }
        }
        if (g != null) {
            try {
                g.after(name, args, text, error)
            } catch (ignored: RuntimeException) {
            }
        }
        if (text.length > 50_000) text = text.substring(0, 50_000) + "\n...[truncated]"
        val content = ArrayList<Any?>()
        content.add(Json.obj("type", "text", "text", text))
        if (png != null) content.add(Json.obj("type", "image", "data", UiTools.base64(png), "mimeType", "image/png"))
        return Json.obj("content", content, "isError", error)
    }

    @Throws(IOException::class)
    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Bad Request"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: " +
            b.size + "\r\nConnection: keep-alive\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(b)
        out.flush()
    }

    @Throws(IOException::class)
    private fun readLine(input: InputStream): String? {
        val bo = ByteArrayOutputStream()
        var c = 0
        while (true) {
            c = input.read()
            if (c < 0) break
            if (c == '\n'.code) break
            if (c != '\r'.code) bo.write(c)
            if (bo.size() > 16 * 1024) throw IOException("line too long")
        }
        if (c < 0 && bo.size() == 0) return null
        return String(bo.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        private const val MAX_BODY = 4 * 1024 * 1024
    }
}
