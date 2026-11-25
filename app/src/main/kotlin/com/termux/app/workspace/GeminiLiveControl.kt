package com.termux.app.workspace

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.json.JSONArray
import org.json.JSONObject

/** Small private Unix protocol; no subprocess, credentials, terminal key injection or cloud polling.*/
object GeminiLiveControl {
    class Failure : IOException {
        @JvmField val code: String

        constructor(code: String) : super(code) {
            this.code = code
        }
    }

    @JvmStatic
    fun validModel(s: String?): Boolean {
        return s != null && (s as java.lang.String).matches("(?:auto|flash|pro|gemini-[A-Za-z0-9][A-Za-z0-9._-]{0,120})")
    }

    // Android LocalSocketImpl.setOption requires an existing fd; connect creates it.
    @Throws(IOException::class)
    @JvmStatic
    fun connect(socket: LocalSocket, path: String) {
        socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.setSoTimeout(15000)
    }

    @Throws(Exception::class)
    @JvmStatic
    fun request(files: File, run: String, value: JSONObject): JSONObject {
        if (!GeminiCli.validThread(run)) {
            throw Failure("INVALID_RUN")
        }
        val socket = File(GeminiCli.directory(files), run + ".sock")
        var stage = "CONNECT_FAILED"
        try {
            val client = LocalSocket()
            var primaryException: Throwable? = null
            try {
                connect(client, socket.getAbsolutePath())
                stage = "PROTOCOL_FAILED"
                val chat: Boolean = value.optString("op").startsWith("chat-") || value.optString("op").startsWith("terminal-")
                val data: ByteArray = ((value.toString() + "\n") as java.lang.String).getBytes(StandardCharsets.UTF_8)
                if (data.size > (if (chat) 65536 else 4096)) {
                    throw Failure("REQUEST_TOO_LARGE")
                }
                client.getOutputStream().write(data)
                client.getOutputStream().flush()
                val response = JSONObject(readReply(client.getInputStream(), if (chat) 524288 else 65536))
                if (!response.optBoolean("ok")) {
                    val error: String = response.optString("error")
                    throw Failure(if (Arrays.asList("NOT_READY", "CONTROL_TIMEOUT", "RESTARTED", "ENDED", "CONTROL_FAILED", "INVALID_MODEL", "INVALID_MODE", "THINKING_UNAVAILABLE", "THINKING_BUSY", "MODE_UNAVAILABLE", "MODE_BUSY", "STALE_PERMISSION", "PERMISSION_FAILED", "INVALID_PERMISSION").contains(error)) error else "CONTROL_FAILED")
                }
                return response
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                if (primaryException != null) {
                    try {
                        client.close()
                    } catch (suppressed: Throwable) {
                        primaryException.addSuppressed(suppressed)
                    }
                } else {
                    client.close()
                }
            }
        } catch (e: Failure) {
            throw e
        } catch (e: InterruptedIOException) {
            throw Failure("CONTROL_TIMEOUT")
        } catch (e: Exception) {
            throw Failure(stage)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun readReply(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var n: Int = 0
        while (true) {
            n = input.read(buffer)
            if (n == -1) break
            var length: Int = n
            for (i in 0 until (n)) {
                if (buffer[i].toInt() == '\n'.code) {
                    length = i
                    break
                }
            }
            if (out.size() + length > limit) {
                throw Failure("RESPONSE_TOO_LARGE")
            }
            out.write(buffer, 0, length)
            if (length < n) {
                break
            }
        }
        return out.toString("UTF-8")
    }

    @Throws(Exception::class)
    @JvmStatic
    fun models(response: JSONObject): MutableList<String> {
        val array: JSONArray = response.getJSONArray("models")
        if (array.length() > 150) {
            throw IOException("CATALOG_TOO_LARGE")
        }
        val out: MutableList<String> = ArrayList<String>()
        var i = 0
        while (i < array.length()) {
            val id: String = array.getJSONObject(i).getString("id")
            if (validModel(id) && !out.contains(id)) {
                out.add(id)
            }
            i++
        }
        return out
    }
}
