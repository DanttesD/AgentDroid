package com.agentdroid.agent

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** POST JSON and read a server-sent-events stream. */
internal object Http {
    fun interface EventSink {
        @Throws(IOException::class)
        fun onData(data: String)
    }

    @Throws(IOException::class)
    fun postSse(url: String, headers: Map<String, String>, body: String, sink: EventSink) {
        val target = URL(url)
        val via = NetConfig.proxyFor(target)
        val c = (if (via == null) target.openConnection() else target.openConnection(via)) as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val payload = body.toByteArray(Charsets.UTF_8)
        c.outputStream.use { it.write(payload) }
        val code = c.responseCode
        if (code / 100 != 2) {
            val es = c.errorStream
            val err = if (es == null) "" else readAll(es)
            throw IOException("HTTP $code: $err")
        }
        try {
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("data:")) {
                        var d = line.substring(5)
                        if (d.startsWith(" ")) d = d.substring(1)
                        sink.onData(d)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun readAll(input: InputStream): String = String(input.readBytes(), Charsets.UTF_8)
}
