package com.agentdroid.adb

import android.content.Context
import android.os.Build
import androidx.lifecycle.Observer
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * High-level entry point of the own ADB client. No Shizuku involved.
 *
 * Typical flow:
 *  1. [pair] once (Android 11+, wireless debugging pairing code), or the user
 *     runs `adb tcpip` from a computer on older versions.
 *  2. [connectPort] / mDNS discovery gives the current wireless-debugging port.
 *  3. [switchToTcp] moves adbd to a fixed TCP port; after that
 *     [shell] on 127.0.0.1:<port> works without Wi-Fi until the next reboot.
 */
class AgentAdb(context: Context) {

    private val appContext = context.applicationContext

    private val key: AdbKey by lazy {
        val prefs = appContext.getSharedPreferences("agentdroid_adb", Context.MODE_PRIVATE)
        AdbKey(PreferenceAdbKeyStore(prefs), "agentdroid")
    }

    data class ShellResult(val exitCode: Int, val output: String)

    /** Pairing with a code shown in Developer options > Wireless debugging. */
    fun pair(host: String, pairingPort: Int, code: String): Boolean =
        AdbPairingClient(host, pairingPort, code, key).use { it.start() }

    /** Quick TCP reachability check of an adbd endpoint. */
    fun isReachable(host: String, port: Int, timeoutMs: Int = 1500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    private fun connected(host: String, port: Int, attempts: Int = 4): AdbClient {
        var last: Exception? = null
        for (i in 1..attempts) {
            val client = AdbClient(host, port, key)
            try {
                client.connect()
                return client
            } catch (e: Exception) {
                runCatching { client.close() }
                last = e
                if (i < attempts) Thread.sleep(700L * i)
            }
        }
        throw last ?: IllegalStateException("adb connect failed")
    }

    /** Runs a shell command, returns merged output and the exit code. */
    fun shell(host: String, port: Int, command: String): ShellResult {
        val out = ByteArrayOutputStream()
        connected(host, port).use { client ->
            client.command("shell:$command\necho \"$RC_MARKER\$?\"") { out.write(it) }
        }
        val text = out.toString("UTF-8")
        val idx = text.lastIndexOf(RC_MARKER)
        if (idx < 0) return ShellResult(-1, text)
        val rc = text.substring(idx + RC_MARKER.length).trim().toIntOrNull() ?: -1
        return ShellResult(rc, text.substring(0, idx))
    }

    /** Moves adbd to a fixed TCP port. Works only until the device reboots. */
    fun switchToTcp(host: String, currentPort: Int, tcpPort: Int) {
        connected(host, currentPort).use { client ->
            runCatching { client.command("tcpip:$tcpPort") }
                .onFailure { if (it !is EOFException && it !is SocketException) throw it } // adbd restarts: expected
        }
    }

    /** Returns adbd to USB mode (drops the TCP listener). */
    fun stopTcp(host: String, port: Int) {
        connected(host, port).use { client ->
            runCatching { client.command("usb:") }
                .onFailure { if (it !is EOFException && it !is SocketException) throw it }
        }
    }

    /** Finds the current wireless-debugging connect port through mDNS (Android 11+). */
    fun discoverConnectPort(timeoutMs: Long = 8000): Pair<String, Int>? =
        discover("_adb-tls-connect._tcp", timeoutMs)

    /** Finds the pairing port while the "Pair with code" dialog is open (Android 11+). */
    fun discoverPairingPort(timeoutMs: Long = 30000): Pair<String, Int>? =
        discover("_adb-tls-pairing._tcp", timeoutMs)

    private fun discover(serviceType: String, timeoutMs: Long): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        var found: Pair<String, Int>? = null
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(appContext, serviceType, Observer { value ->
            if (value.second in 1..65535) {
                found = value
                latch.countDown()
            }
        })
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return found
    }

    private companion object {
        const val RC_MARKER = "__AGENTDROID_RC__:"
    }
}
