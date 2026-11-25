package com.termux.app.agent

import android.content.Context
import com.agentdroid.exec.ShellQuote
import com.termux.shared.termux.TermuxConstants
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Runs a tunnel as a child process and reports its public address and state.
 * With an ngrok token and a (free, permanent) ngrok address configured it runs ngrok inside the Debian
 * environment (port 443, permanent address); otherwise a cloudflared quick tunnel (random address).
 **/
object TunnelManager {
    private val CF_URL: Pattern = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com")
    private val NGROK_URL: Pattern = Pattern.compile("https://[a-zA-Z0-9.-]+\\.(ngrok-free\\.app|ngrok-free\\.dev|ngrok\\.app|ngrok\\.dev|ngrok\\.io)")
    private const val PREFS: String = "agentdroid_tunnel"
    private const val NGROK_BIN: String = "/opt/ngrok/ngrok"

    private var process: Process? = null
    @Volatile private var url: String? = ""
    @Volatile private var connected: Boolean = false
    @Volatile private var error: String = ""
    @Volatile private var ngrok: Boolean = false
    @Volatile private var forceCloudflare: Boolean = false
    @Volatile private var viaSu: Boolean = false
    @Volatile private var note: String = ""

    @Synchronized
    @JvmStatic
    fun isRunning(): Boolean {
        return process != null && process!!.isAlive()
    }

    @JvmStatic
    fun url(): String? {
        return url
    }

    @JvmStatic
    fun isConnected(): Boolean {
        return connected && isRunning()
    }

    @JvmStatic
    fun error(): String {
        return error
    }

    @JvmStatic
    fun usesNgrok(): Boolean {
        return ngrok
    }

    /** Non-empty when the permanent tunnel failed and the temporary one is used instead.*/
    @JvmStatic
    fun note(): String {
        return note
    }

    @JvmStatic
    fun ngrokDomain(c: Context): String? {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("domain", "")
    }

    @JvmStatic
    fun ngrokConfigured(c: Context): Boolean {
        return !SecretStore(c).get("ngrok_token").isEmpty()
    }

    @JvmStatic
    fun saveNgrok(c: Context, token: String, domain: String) {
        SecretStore(c).put("ngrok_token", (token as java.lang.String).trim())
        var d: String = (((domain as java.lang.String).trim() as java.lang.String).replace("https://", "") as java.lang.String).replace("http://", "")
        while (d.endsWith("/")) {
            d = d.substring(0, d.length - 1)
        }
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("domain", d).apply()
    }

    @JvmStatic
    fun ngrokInstalled(): Boolean {
        return File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/containers/debian/rootfs" + NGROK_BIN).exists() || File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/proot-distro/installed-rootfs/debian" + NGROK_BIN).exists()
    }

    /** Terminal command that installs proot-distro, Debian, curl and ngrok. Safe to repeat.*/
    @JvmStatic
    fun ngrokInstallScript(): String {
        val inner = "command -v curl >/dev/null 2>&1 || { apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y curl ca-certificates; }; mkdir -p /opt/ngrok && cd /opt/ngrok && curl -fsSL -o n.tgz https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz && tar xzf n.tgz && rm -f n.tgz && ./ngrok version"
        return "{ command -v proot-distro >/dev/null 2>&1 || pkg install -y proot-distro; } && { [ -d \"\$PREFIX/var/lib/proot-distro/containers/debian\" ] || [ -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/debian\" ] || proot-distro install debian; } && proot-distro login debian -- bash -lc " + ShellQuote.quote(inner)
    }

    /** Kills ngrok processes left over from an earlier app process; they would keep the permanent address busy.*/
    @JvmStatic
    private fun killStale() {
        val procs: Array<File>? = File("/proc").listFiles()
        if (procs == null) {
            return
        }
        var killed = false
        for (f in procs) {
            val n: String = f.getName()
            if (!(n as java.lang.String).matches("\\d+")) {
                continue
            }
            try {
                val r = BufferedReader(InputStreamReader(FileInputStream(File(f, "cmdline")), StandardCharsets.UTF_8))
                var primaryException: Throwable? = null
                try {
                    val cmd: String? = r.readLine()
                    if (cmd != null && cmd.contains(NGROK_BIN) && cmd.contains("http")) {
                        android.os.Process.killProcess(Integer.parseInt(n))
                        killed = true
                    }
                } catch (t: Throwable) {
                    primaryException = t
                    throw t
                } finally {
                    if (primaryException != null) {
                        try {
                            r.close()
                        } catch (suppressed: Throwable) {
                            primaryException.addSuppressed(suppressed)
                        }
                    } else {
                        r.close()
                    }
                }
            } catch (ignored: IOException) {
            } catch (ignored: RuntimeException) {
            }
        }
        if (killed) {
            try {
                Thread.sleep(1500L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Synchronized
    @JvmStatic
    fun start(c: Context, port: Int) {
        if (isRunning()) {
            return
        }
        url = ""
        connected = false
        error = ""
        if (!forceCloudflare) {
            note = ""
        }
        val prefix: String = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        val home: String = TermuxConstants.TERMUX_HOME_DIR_PATH
        val token: String = SecretStore(c).get("ngrok_token")
        ngrok = !forceCloudflare && !token.isEmpty() && ngrokInstalled()
        val cmd: MutableList<String> = ArrayList<String>()
        if (ngrok) {
            killStale()
            val domain: String? = ngrokDomain(c)
            val inner: MutableList<String> = ArrayList<String>()
            inner.add(prefix + "/bin/proot-distro")
            inner.add("login")
            inner.add("debian")
            inner.add("--shared-tmp")
            inner.add("--")
            inner.add("env")
            inner.add("NGROK_AUTHTOKEN=" + token)
            inner.add(NGROK_BIN)
            inner.add("http")
            inner.add("127.0.0.1:" + port)
            inner.add("--log=stdout")
            inner.add("--log-format=logfmt")
            if (!domain!!.isEmpty()) {
                inner.add("--url=" + domain)
            }
            if (viaSu) {
                // Android hides network card addresses from normal apps; the same app user started through su sees them.
                val sb = StringBuilder("env -i PATH=" + prefix + "/bin HOME=" + home + " PREFIX=" + prefix + " TERMUX__PREFIX=" + prefix + " TERMUX_APP__PACKAGE_NAME=" + TermuxConstants.TERMUX_PACKAGE_NAME + " TERMUX__HOME=" + home + " TMPDIR=" + prefix + "/tmp LD_LIBRARY_PATH=" + prefix + "/lib")
                for (a in inner) {
                    sb.append(' ').append(ShellQuote.quote(a))
                }
                cmd.add("su")
                cmd.add(java.lang.String.valueOf(android.os.Process.myUid()))
                cmd.add("-c")
                cmd.add(sb.toString())
            } else {
                cmd.addAll(inner)
            }
        } else {
            val bin = File(prefix + "/bin/cloudflared")
            if (!bin.exists()) {
                error = "cloudflared"
                return
            }
            cmd.add(bin.getPath())
            cmd.add("tunnel")
            cmd.add("--no-autoupdate")
            cmd.add("--url")
            cmd.add("http://127.0.0.1:" + port)
        }
        try {
            val pb = ProcessBuilder(cmd)
            pb.environment().put("PATH", prefix + "/bin")
            pb.environment().put("HOME", home)
            pb.environment().put("PREFIX", prefix)
            pb.environment().put("TERMUX__PREFIX", prefix)
            pb.environment().put("TERMUX_APP__PACKAGE_NAME", TermuxConstants.TERMUX_PACKAGE_NAME)
            pb.environment().put("TERMUX__HOME", home)
            pb.environment().put("TMPDIR", prefix + "/tmp")
            pb.environment().put("LD_LIBRARY_PATH", prefix + "/lib")
            pb.redirectErrorStream(true)
            val p: Process = pb.start()
            process = p
            val app: Context = c.getApplicationContext()
            val wasNgrok: Boolean = ngrok
            val wasSu: Boolean = viaSu
            val fport: Int = port
            val t = Thread(Runnable {
                read(p, wasNgrok)
                afterExit(app, fport, wasNgrok, wasSu)
            }, "tunnel-reader")
            t.setDaemon(true)
            t.start()
        } catch (e: IOException) {
            error = java.lang.String.valueOf(e.message)
            if (ngrok) {
                // could not even start (for example no su): use the temporary tunnel
                note = error
                forceCloudflare = true
                try {
                    start(c, port)
                } finally {
                    forceCloudflare = false
                }
            }
        }
    }

    @JvmStatic
    private fun read(p: Process, isNgrok: Boolean) {
        try {
            (BufferedReader(InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))).use { r ->
                var line: String? = null
                while (true) {
                    line = r.readLine()
                    if (line == null) break
                    if (isNgrok) {
                        val m: Matcher = NGROK_URL.matcher(line)
                        if (line.contains("started tunnel") && m.find()) {
                            url = m.group()
                            connected = true
                        } else if (line.contains("lvl=eror") || line.contains("lvl=crit")) {
                            val i: Int = line.indexOf("err=")
                            error = if (i >= 0) line.substring(i + 4) else line
                        }
                    } else {
                        val m: Matcher = CF_URL.matcher(line)
                        if (m.find() && !line.contains("api.trycloudflare")) {
                            url = m.group()
                        }
                        if (line.contains("Registered tunnel connection")) {
                            connected = true
                        }
                    }
                }
            }
        } catch (ignored: IOException) {
        }
        connected = false
    }

    /** ngrok ended without connecting: retry through su when the network card address was hidden, then fall back to Cloudflare.*/
    @JvmStatic
    private fun afterExit(c: Context, port: Int, wasNgrok: Boolean, wasSu: Boolean) {
        if (!wasNgrok || forceCloudflare) {
            return
        }
        var retrySu: Boolean
        synchronized(TunnelManager::class.java) {
            if (process == null) {
                return
            } // stopped by the user
            retrySu = !wasSu && error.contains("hardware address")
            note = error
            process = null
            if (retrySu) {
                viaSu = true
            } else {
                forceCloudflare = true
            }
        }
        try {
            start(c, port)
        } finally {
            forceCloudflare = false
            viaSu = false
        }
    }

    @Synchronized
    @JvmStatic
    fun stop() {
        if (process != null) {
            process!!.destroy()
        }
        if (ngrok) {
            killStale()
        }
        process = null
        connected = false
        url = ""
    }
}
