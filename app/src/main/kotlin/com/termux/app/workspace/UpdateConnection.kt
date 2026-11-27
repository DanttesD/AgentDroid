package com.termux.app.workspace

import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

/** One updater operation owns its connection, including redirects and cancellation.*/
class UpdateConnection {
    fun interface Factory {
        @Throws(IOException::class)
        fun open(url: URL): HttpURLConnection
    }

    private val factory: Factory
    private var cancelled: Boolean = false
    private var active: HttpURLConnection? = null

    constructor() : this(Factory { url -> url.openConnection() as HttpURLConnection })

    constructor(factory: Factory) {
        this.factory = factory
    }

    @Synchronized
    fun isCancelled(): Boolean {
        return cancelled
    }

    fun cancel() {
        var connection: HttpURLConnection?
        synchronized(this) {
            cancelled = true
            connection = active
        }
        if (connection != null) {
            connection!!.disconnect()
        }
    }

    @Throws(InterruptedIOException::class)
    @Synchronized
    fun check() {
        if (cancelled) {
            throw InterruptedIOException("Update cancelled")
        }
    }

    fun release(connection: HttpURLConnection) {
        synchronized(this) {
            if (active === connection) {
                active = null
            }
        }
        connection.disconnect()
    }

    @Throws(IOException::class)
    fun connect(address: String): HttpURLConnection {
        var url = URL(address)
        for (hop in 0 until (6)) {
            check()
            if (!trusted(url)) {
                throw IOException("Untrusted download host")
            }
            val connection: HttpURLConnection = factory.open(url)
            var returned = false
            try {
                synchronized(this) {
                    check()
                    active = connection
                }
                connection.setInstanceFollowRedirects(false)
                connection.setConnectTimeout(15000)
                connection.setReadTimeout(20000)
                connection.setRequestProperty("User-Agent", "Termux-Jobs-Updater")
                connection.setRequestProperty("Accept", if (url.getHost().equals("api.github.com")) "application/vnd.github+json" else "application/octet-stream")
                check()
                val status: Int = connection.getResponseCode()
                check()
                if (status >= 300 && status < 400) {
                    val location: String? = connection.getHeaderField("Location")
                    if (location == null) {
                        throw IOException("Missing redirect")
                    }
                    url = URL(url, location)
                } else {
                    if (status != 200) {
                        throw IOException("GitHub HTTP " + status)
                    }
                    returned = true
                    return connection
                }
            } finally {
                if (!returned) {
                    release(connection)
                }
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        @JvmStatic
        fun trusted(url: URL): Boolean {
            val host: String = url.getHost()
            return url.getProtocol().equals("https") && url.getUserInfo() == null && url.getPort() == -1 && (host.equals("api.github.com") || host.equals("github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"))
        }
    }
}
