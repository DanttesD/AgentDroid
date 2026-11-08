package com.agentdroid.agent

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/** Optional outbound proxy for the agent's own model requests. Local and private addresses always go direct. */
object NetConfig {
    @Volatile
    private var proxy: Proxy? = null

    @Volatile
    private var auth: PasswordAuthentication? = null

    private var authInstalled = false

    private val PRIVATE_172 = Regex("172\\.(1[6-9]|2\\d|3[01])\\..*")

    /** @param spec host:port, http://host:port or socks5://host:port; empty clears the proxy. */
    @JvmStatic
    @Synchronized
    fun set(spec: String?, user: String?, pass: String?) {
        proxy = parse(spec)
        auth = if (proxy != null && user != null && user.isNotEmpty()) {
            PasswordAuthentication(user, (pass ?: "").toCharArray())
        } else {
            null
        }
        if (!authInstalled) {
            authInstalled = true
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? =
                    if (requestorType == Authenticator.RequestorType.PROXY) auth else null
            })
        }
    }

    @JvmStatic
    fun active(): Boolean = proxy != null

    @JvmStatic
    fun parse(spec: String?): Proxy? {
        var s = (spec ?: "").jtrim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "http://$s"
        return try {
            val u = URI(s)
            if (u.host == null || u.port < 1) return null
            val scheme = u.scheme.lowercase()
            val type = if (scheme.startsWith("socks")) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress.createUnresolved(u.host, u.port))
        } catch (e: URISyntaxException) {
            null
        }
    }

    @JvmStatic
    fun isLocal(host: String?): Boolean {
        if (host == null) return true
        val h = host.lowercase()
        return h == "localhost" || h.startsWith("127.") || h == "::1" || h == "[::1]" || h.startsWith("10.") ||
            h.startsWith("192.168.") || PRIVATE_172.matches(h) || h.endsWith(".local")
    }

    /** The proxy to use for this URL, or null for a direct connection. */
    @JvmStatic
    fun proxyFor(url: URL): Proxy? {
        val p = proxy
        return if (p == null || isLocal(url.host)) null else p
    }
}
