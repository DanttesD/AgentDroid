package com.termux.app.workspace

import android.app.Activity
import android.util.Base64
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.regex.Matcher
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** All commands run through independent OpenSSH processes, pinned host keys, bounded I/O.*/
class ServerConnection : AutoCloseable {
    private val activity: Activity
    @JvmField val profile: ServerProfiles.Profile?
    @Volatile private var process: Process? = null
    @Volatile private var tunnel: Process? = null
    @Volatile private var closed: Boolean = false
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    constructor(a: Activity, p: ServerProfiles.Profile?) {
        activity = a
        profile = p
        ServerDiagnostics.configure(p)
        synchronized(ServerConnection::class.java) {
            LIVE.add(this)
            if (disconnected(a, p!!.id)) {
                close()
            }
        }
    }

    fun knownHosts(): File {
        return File(activity.getFilesDir(), "server-host-" + profile!!.id)
    }

    fun trusted(): Boolean {
        return knownHosts().isFile()
    }

    private fun bin(name: String): String {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + name
    }

    @Throws(Exception::class)
    private fun builder(command: MutableList<String>): ProcessBuilder {
        if (closed || disconnected(activity, profile!!.id)) {
            throw IOException("CLOSED")
        }
        val b = ProcessBuilder(command)
        b.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        b.environment().clear()
        for (item in TermuxShellUtils.buildEnvironment(activity, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val i: Int = item.indexOf('=')
            if (i > 0) {
                b.environment().put(item.substring(0, i), item.substring(i + 1))
            }
        }
        b.environment().put("LC_ALL", "C")
        b.redirectError(File("/dev/null"))
        return b
    }

    @Throws(Exception::class)
    private fun password(b: ProcessBuilder): SshPasswordBroker? {
        if (!profile!!.auth.equals("password") && !(profile.auth.equals("key") && !profile.encrypted.isEmpty())) {
            return null
        }
        if (profile.encrypted.isEmpty()) {
            throw IOException("PASSWORD_REQUIRED")
        }
        val secret: CharArray = SshPasswordVault.decrypt(profile.session())
        var broker: SshPasswordBroker
        try {
            broker = SshPasswordBroker(activity, secret, if (profile.auth.equals("key")) identity() else null)
        } finally {
            Arrays.fill(secret, '\u0000')
        }
        try {
            val askpass: File = SessionShell.prepareAskpass(activity.getFilesDir(), profile.session(), activity.getApplicationInfo().sourceDir)
            b.environment().put("SSH_ASKPASS", askpass.getAbsolutePath())
            b.environment().put("SSH_ASKPASS_REQUIRE", "force")
            b.environment().put("DISPLAY", ":0")
            b.environment().put("TERMUX_JOBS_SSH_SOCKET", broker.address)
            return broker
        } catch (e: Exception) {
            broker.close()
            throw e
        }
    }

    fun ssh(): MutableList<String> {
        if (!ServerProfiles.valid(profile)) {
            throw IllegalArgumentException("INVALID_PROFILE")
        }
        val c: MutableList<String> = ArrayList<String>(Arrays.asList(bin("ssh"), "-F", "/dev/null", "-T", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=2", "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + knownHosts(), "-o", "GlobalKnownHostsFile=/dev/null", "-o", "ControlMaster=no", "-o", "ControlPath=none", "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes", "-o", "PermitLocalCommand=no"))
        c.addAll(Arrays.asList("-o", if (profile!!.auth.equals("password") || profile.auth.equals("key") && !profile.encrypted.isEmpty()) "BatchMode=no" else "BatchMode=yes"))
        if (profile.auth.equals("password")) {
            c.addAll(Arrays.asList("-o", "PreferredAuthentications=password", "-o", "PubkeyAuthentication=no", "-o", "KbdInteractiveAuthentication=no", "-o", "NumberOfPasswordPrompts=1"))
        }
        if (profile.auth.equals("key")) {
            c.addAll(Arrays.asList("-o", "IdentitiesOnly=yes", "-i", identity(), "-o", "PreferredAuthentications=publickey", "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no", "-o", "NumberOfPasswordPrompts=1"))
        }
        c.addAll(Arrays.asList("-p", Integer.toString(profile.port), "-l", profile.user))
        return c
    }

    private fun identity(): String {
        return if (profile!!.identity.startsWith("~/")) TermuxConstants.TERMUX_HOME_DIR_PATH + profile.identity.substring(1) else profile.identity
    }

    @Throws(Exception::class)
    private fun execute(args: MutableList<String>, input: ByteArray?, limit: Int, timeout: Int): ByteArray {
        val b: ProcessBuilder = builder(args)
        val broker: SshPasswordBroker? = password(b)
        var owned: Process? = null
        var timer: ScheduledFuture<*>? = null
        try {
            owned = b.start()
            process = owned
            val child: Process? = owned
            timer = watchdog.schedule(Runnable(child!!::destroy), timeout.toLong(), TimeUnit.SECONDS)
            if (closed) {
                owned!!.destroy()
            }
            (owned!!.getOutputStream()).use { out ->
                if (input != null) {
                    out.write(input)
                }
            }
            val out = ByteArrayOutputStream()
            (owned.getInputStream()).use { `in` ->
                val buf = ByteArray(16384)
                var n: Int = 0
                while (true) {
                    n = `in`.read(buf)
                    if (n == -1) break
                    if (out.size() + n > limit) {
                        throw IOException("RESPONSE_LIMIT")
                    }
                    out.write(buf, 0, n)
                }
            }
            val code: Int = owned.waitFor()
            val result: ByteArray = out.toByteArray()
            if (code != 0 && result.size == 0) {
                throw IOException("SSH_FAILED")
            }
            return result
        } finally {
            if (timer != null) {
                timer.cancel(false)
            }
            if (owned != null) {
                owned.destroy()
            }
            process = null
            if (broker != null) {
                broker.close()
            }
        }
    }

    @Throws(Exception::class)
    fun scan(): Array<String> {
        if (!File(bin("ssh-keyscan")).canExecute()) {
            throw IOException("OPENSSH_REQUIRED")
        }
        val cmd: MutableList<String> = Arrays.asList(bin("ssh-keyscan"), "-T", "8", "-t", "ed25519", "-p", Integer.toString(profile!!.port), profile.host)
        val bytes: ByteArray = execute(cmd, null, 16384, 15)
        val keys = StringBuilder()
        val prints = StringBuilder()
        for (line in (String(bytes, StandardCharsets.UTF_8) as java.lang.String).split("\n")) {
            if (line.startsWith("#") || (line as java.lang.String).trim().isEmpty()) {
                continue
            }
            val a: Array<String> = ((line as java.lang.String).trim() as java.lang.String).split("\\s+")
            if (a.size != 3 || !a[1].equals("ssh-ed25519")) {
                continue
            }
            val key: ByteArray = Base64.decode(a[2], Base64.DEFAULT)
            val digest: String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key), Base64.NO_WRAP or Base64.NO_PADDING)
            keys.append(a[0]).append(' ').append(a[1]).append(' ').append(a[2]).append('\n')
            prints.append("ED25519 SHA256:").append(digest).append('\n')
        }
        if (keys.length == 0) {
            throw IOException("HOST_KEY_UNAVAILABLE")
        }
        return arrayOf<String>(keys.toString(), prints.toString())
    }

    @Throws(Exception::class)
    fun trust(keys: String) {
        (FileOutputStream(knownHosts())).use { out ->
            out.write((keys as java.lang.String).getBytes(StandardCharsets.UTF_8))
        }
        knownHosts().setReadable(false, false)
        knownHosts().setReadable(true, true)
        knownHosts().setWritable(false, false)
        knownHosts().setWritable(true, true)
    }

    @Throws(Exception::class)
    fun remote(op: ServerDiagnostics.Op, command: String, input: ByteArray?, limit: Int): JSONObject {
        val trace: ServerDiagnostics.Trace = ServerDiagnostics.begin(profile, op)
        var network: Long = 0L
        var parse: Long = 0L
        var size: Long = (-1).toLong()
        var networkStart: Long = 0L
        var parseStart: Long = 0L
        var failure: Exception? = null
        try {
            if (!trusted()) {
                throw IOException("TRUST_REQUIRED")
            }
            val args: MutableList<String> = ssh()
            args.add("--")
            args.add(profile!!.host)
            args.add(command)
            networkStart = System.nanoTime()
            val data: ByteArray = execute(args, input, limit, 40)
            network = (System.nanoTime() - networkStart) / 1000000
            networkStart = 0L
            size = data.size.toLong()
            parseStart = System.nanoTime()
            var result: JSONObject
            try {
                result = JSONObject(String(data, StandardCharsets.UTF_8))
            } catch (e: JSONException) {
                throw IOException("PYTHON_OR_AGENT_UNAVAILABLE")
            }
            if (result.has("ok") && !result.optBoolean("ok")) {
                throw IOException(result.optString("error", "AGENT_ERROR"))
            }
            if (op === ServerDiagnostics.Op.FILE_DELETE && result.optJSONObject("result") != null) {
                val code: String = result.getJSONObject("result").optString("error")
                if (!code.isEmpty()) {
                    failure = IOException(code)
                }
            }
            return result
        } catch (e: Exception) {
            failure = e
            throw e
        } finally {
            if (networkStart != 0L) {
                network = (System.nanoTime() - networkStart) / 1000000
            }
            if (parseStart != 0L) {
                parse = (System.nanoTime() - parseStart) / 1000000
            }
            trace.finish(network, parse, size, if (closed) CancellationException() else failure)
        }
    }

    @Throws(Exception::class)
    fun diagnose(): JSONObject {
        val result = JSONObject()
        try {
            (Socket()).use { socket ->
                socket.connect(InetSocketAddress(profile!!.host, profile.port), 8000)
                result.put("network", true)
            }
        } catch (e: Exception) {
            return result.put("network", false)
        }
        if (!trusted()) {
            return result.put("trusted", false)
        }
        result.put("trusted", true)
        try {
            return result.put("probe", probe())
        } catch (e: Exception) {
            val code: String? = e.message
            return result.put("error", if (code != null && (code as java.lang.String).matches("[A-Z_]{1,80}")) code else "SSH_OR_PYTHON_FAILED")
        }
    }

    @Throws(Exception::class)
    fun probe(): JSONObject {
        val script = "import json,os,platform,shutil,sys; print(json.dumps({'supported':sys.version_info >= (3,8),'python':platform.python_version(),'home':os.path.expanduser('~'),'user':os.getuid(),'agent':os.path.isfile(os.path.expanduser('~/.local/share/termux-jobs-server/agent.py'))}))"
        return remote(ServerDiagnostics.Op.PROBE, "python3 -I -c " + SessionShell.quote(script), null, 16384)
    }

    @Throws(Exception::class)
    fun rpc(request: JSONObject): JSONObject {
        val r: JSONObject = remote(ServerDiagnostics.operation(request.optString("op")), "python3 -I \"\$HOME/.local/share/termux-jobs-server/agent.py\" --rpc", (request.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8), 2 * 1024 * 1024)
        if (!r.optBoolean("ok")) {
            throw IOException(r.optString("error", "AGENT_ERROR"))
        }
        return r.getJSONObject("result")
    }

    @Throws(Exception::class)
    fun schedules(request: JSONObject): JSONObject {
        var code: String
        val `in` = activity.getAssets().open("workspace-server-schedules.py")
        var primaryException: Throwable? = null
        try {
            val out = ByteArrayOutputStream()
            val b = ByteArray(8192)
            var n: Int = 0
            while (true) {
                n = `in`.read(b)
                if (n == -1) break
                out.write(b, 0, n)
            }
            code = out.toString("UTF-8")
        } catch (t: Throwable) {
            primaryException = t
            throw t
        } finally {
            if (primaryException != null) {
                try {
                    `in`.close()
                } catch (suppressed: Throwable) {
                    primaryException.addSuppressed(suppressed)
                }
            } else {
                `in`.close()
            }
        }
        val reply: JSONObject = remote(ServerDiagnostics.Op.SCHEDULE, "python3 -I -c " + SessionShell.quote(code), (request.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8), 2 * 1024 * 1024)
        if (!reply.optBoolean("ok")) {
            throw IOException(reply.optString("error", "SCHEDULE_ERROR"))
        }
        return reply.getJSONObject("result")
    }

    @Throws(Exception::class)
    fun deleteFiles(entries: JSONArray): JSONObject {
        val groups: MutableMap<String, JSONArray?> = deletionGroups(entries)
        val deleted = JSONArray()
        var nodes: Long = 0L
        var error = ""
        for (group in groups.entries) {
            val result: JSONObject = deleteFiles(group.key, group.value)
            val done: JSONArray = result.getJSONArray("deleted")
            var i = 0
            while (i < done.length()) {
                deleted.put(done.get(i))
                i++
            }
            nodes += result.optLong("removedNodes")
            error = result.optString("error")
            if (!error.isEmpty()) {
                break
            }
        }
        return JSONObject().put("deleted", deleted).put("removedNodes", nodes).put("error", error)
    }

    @Throws(Exception::class)
    fun deleteFiles(directory: String, entries: JSONArray?): JSONObject {
        var code: String
        val `in` = activity.getAssets().open("workspace-server-delete.py")
        var primaryException1: Throwable? = null
        try {
            val out = ByteArrayOutputStream()
            val b = ByteArray(8192)
            var n: Int = 0
            while (true) {
                n = `in`.read(b)
                if (n == -1) break
                out.write(b, 0, n)
            }
            code = out.toString("UTF-8")
        } catch (t: Throwable) {
            primaryException1 = t
            throw t
        } finally {
            if (primaryException1 != null) {
                try {
                    `in`.close()
                } catch (suppressed: Throwable) {
                    primaryException1.addSuppressed(suppressed)
                }
            } else {
                `in`.close()
            }
        }
        val request: JSONObject = JSONObject().put("directory", directory).put("entries", entries)
        val reply: JSONObject = remote(ServerDiagnostics.Op.FILE_DELETE, "python3 -I -c " + SessionShell.quote(code), (request.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8), 131072)
        return reply.getJSONObject("result")
    }

    @Throws(Exception::class)
    fun search(path: String, query: String): JSONObject {
        var code: String
        val `in` = activity.getAssets().open("workspace-server-search.py")
        var primaryException2: Throwable? = null
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var n: Int = 0
            while (true) {
                n = `in`.read(buffer)
                if (n == -1) break
                out.write(buffer, 0, n)
            }
            code = out.toString("UTF-8")
        } catch (t: Throwable) {
            primaryException2 = t
            throw t
        } finally {
            if (primaryException2 != null) {
                try {
                    `in`.close()
                } catch (suppressed: Throwable) {
                    primaryException2.addSuppressed(suppressed)
                }
            } else {
                `in`.close()
            }
        }
        val reply: JSONObject = remote(ServerDiagnostics.Op.SEARCH, "python3 -I -c " + SessionShell.quote(code), (JSONObject().put("path", path).put("query", query).toString() as java.lang.String).getBytes(StandardCharsets.UTF_8), 2 * 1024 * 1024)
        if (!reply.optBoolean("ok")) {
            throw IOException("SEARCH_FAILED")
        }
        return reply.getJSONObject("result")
    }

    @Throws(Exception::class)
    fun components(): JSONObject {
        val code = "import json,os,shutil; print(json.dumps({'ffmpeg':bool(shutil.which('ffmpeg')),'ffprobe':bool(shutil.which('ffprobe')),'apt':bool(shutil.which('apt-get')),'sudo':bool(shutil.which('sudo')),'uid':os.getuid(),'home':os.path.expanduser('~'),'agent':os.path.isfile(os.path.expanduser('~/.local/share/termux-jobs-server/agent.py'))}))"
        return remote(ServerDiagnostics.Op.COMPONENTS, "python3 -I -c " + SessionShell.quote(code), null, 16384)
    }

    @Throws(Exception::class)
    fun properties(path: String): JSONObject {
        if (ServerFileInfo.media(path)) {
            val result: JSONObject = rpc(JSONObject().put("op", "properties").put("path", path))
            result.put("components", components())
            return result
        }
        val code = "import json,os,sys; p=os.path.realpath(os.path.expanduser(sys.argv[1])); s=os.stat(p); print(json.dumps({'path':p,'size':s.st_size,'modified':s.st_mtime}))"
        return remote(ServerDiagnostics.Op.PROPERTIES, "python3 -I -c " + SessionShell.quote(code) + " " + SessionShell.quote(path), null, 16384)
    }

    fun tunnelActive(): Boolean {
        val owned: Process? = tunnel
        if (owned == null) {
            return false
        }
        try {
            owned.exitValue()
            return false
        } catch (e: IllegalThreadStateException) {
            return true
        }
    }

    @Throws(Exception::class)
    fun install() {
        val setup = "import os,pathlib,sys,json,hashlib; assert sys.version_info >= (3,8); os.umask(0o077); root=pathlib.Path.home()/'.local/share/termux-jobs-server'; parents=[pathlib.Path.home()/'.local',pathlib.Path.home()/'.local/share',root]; exec(\"for p in parents:\\n if p.is_symlink(): raise RuntimeError('SYMLINK_DIRECTORY')\\n p.mkdir(mode=0o700,exist_ok=True)\\n if p.stat().st_uid!=os.getuid(): raise RuntimeError('DIRECTORY_OWNER')\"); data=sys.stdin.buffer.read(262145); assert len(data)==int(sys.argv[1]) and hashlib.sha256(data).hexdigest()==sys.argv[2]; compile(data,'agent.py','exec'); target=root/'agent.py'; tmp=root/('.install-'+os.urandom(8).hex()); fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600); exec(\"try:\\n with os.fdopen(fd,'wb') as f: f.write(data); f.flush(); os.fsync(f.fileno())\\n os.link(tmp,target)\\nfinally:\\n tmp.unlink()\"); print(json.dumps({'installed':True}))"
        var data: ByteArray
        val `in` = activity.getAssets().open("workspace-server-agent.py")
        var primaryException3: Throwable? = null
        try {
            val out = ByteArrayOutputStream()
            val b = ByteArray(8192)
            var n: Int = 0
            while (true) {
                n = `in`.read(b)
                if (n == -1) break
                out.write(b, 0, n)
            }
            data = out.toByteArray()
        } catch (t: Throwable) {
            primaryException3 = t
            throw t
        } finally {
            if (primaryException3 != null) {
                try {
                    `in`.close()
                } catch (suppressed: Throwable) {
                    primaryException3.addSuppressed(suppressed)
                }
            } else {
                `in`.close()
            }
        }
        val digest = StringBuilder()
        for (b in MessageDigest.getInstance("SHA-256").digest(data)) {
            digest.append(java.lang.String.format(Locale.ROOT, "%02x", b.toInt() and 255))
        }
        remote(ServerDiagnostics.Op.INSTALL, "python3 -I -c " + SessionShell.quote(setup) + " " + data.size + " " + digest, data, 16384)
        rpc(JSONObject().put("op", "status"))
    }

    @Throws(Exception::class)
    fun openTunnel(remotePort: Int, localPort: IntArray): Process? {
        if (remotePort < 1 || remotePort > 65535) {
            throw IOException("INVALID_PORT")
        }
        stopTunnel()
        (ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))).use { socket ->
            localPort[0] = socket.getLocalPort()
        }
        val args: MutableList<String> = ssh()
        val index: Int = args.indexOf("ClearAllForwardings=yes")
        args.set(index, "ClearAllForwardings=no")
        args.addAll(Arrays.asList("-N", "-o", "ExitOnForwardFailure=yes", "-L", "127.0.0.1:" + localPort[0] + ":127.0.0.1:" + remotePort, "--", profile!!.host))
        val b: ProcessBuilder = builder(args)
        val broker: SshPasswordBroker? = password(b)
        try {
            tunnel = b.start()
            val child: Process? = tunnel
            if (closed) {
                child!!.destroy()
                throw IOException("CLOSED")
            }
            watchdog.schedule(Runnable(child!!::destroy), 30L, TimeUnit.MINUTES)
            for (i in 0 until (100)) {
                try {
                    child.exitValue()
                    throw IOException("TUNNEL_FAILED")
                } catch (expected: IllegalThreadStateException) {
                }
                try {
                    (Socket()).use { check ->
                        check.connect(InetSocketAddress("127.0.0.1", localPort[0]), 100)
                        return child
                    }
                } catch (notReady: IOException) {
                    Thread.sleep(100L)
                }
            }
            child.destroy()
            throw IOException("TUNNEL_FAILED")
        } catch (e: Exception) {
            stopTunnel()
            throw e
        } finally {
            if (broker != null) {
                broker.close()
            }
        }
    }

    fun stopTunnel() {
        val owned: Process? = tunnel
        if (owned != null) {
            owned.destroy()
        }
        tunnel = null
    }

    fun terminalCommand(directory: String?): String {
        val s: WorkspaceStore.Session = profile!!.session()
        s.directory = if (directory == null || directory.isEmpty()) "~" else directory
        val cmd: String = SshSessionShell.command(s)
        return (cmd as java.lang.String).replaceFirst("ssh -t", Matcher.quoteReplacement("ssh -t -o StrictHostKeyChecking=yes -o " + SessionShell.quote("UserKnownHostsFile=" + knownHosts()) + " -o GlobalKnownHostsFile=/dev/null -o ForwardAgent=no"))
    }

    fun interface DownloadProgress {
        fun received(bytes: Long)
    }

    @Throws(Exception::class)
    fun download(path: String, target: File, max: Long) {
        download(path, target, max, DownloadProgress { bytes ->  })
    }

    @Throws(Exception::class)
    fun download(path: String, target: File, max: Long, progress: DownloadProgress) {
        try {
            (FileOutputStream(target)).use { out ->
                download(path, out, max, progress)
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    @Throws(Exception::class)
    fun download(path: String, out: OutputStream, max: Long, progress: DownloadProgress) {
        if (path.indexOf('\u0000') >= 0 || path.indexOf('\n') >= 0) {
            throw IOException("INVALID_PATH")
        }
        val args: MutableList<String> = ssh()
        args.addAll(Arrays.asList("--", profile!!.host, "exec cat -- " + SessionShell.quote(path)))
        val b: ProcessBuilder = builder(args)
        val broker: SshPasswordBroker? = password(b)
        var owned: Process? = null
        var timer: ScheduledFuture<*>? = null
        try {
            owned = b.start()
            process = owned
            val child: Process? = owned
            timer = watchdog.schedule(Runnable(child!!::destroy), 10L, TimeUnit.MINUTES)
            if (closed) {
                owned!!.destroy()
            }
            var total: Long = 0L
            (owned!!.getOutputStream()).use { unused ->
                (owned.getInputStream()).use { `in` ->
                    unused.close()
                    val buf = ByteArray(65536)
                    var n: Int = 0
                    while (true) {
                        n = `in`.read(buf)
                        if (n == -1) break
                        if (closed) {
                            throw IOException("CANCELLED")
                        }
                        total += n
                        if (total > max) {
                            throw IOException("FILE_LIMIT")
                        }
                        out.write(buf, 0, n)
                        progress.received(total)
                    }
                }
            }
            if (owned.waitFor() != 0 || closed) {
                throw IOException("DOWNLOAD_FAILED")
            }
        } finally {
            if (timer != null) {
                timer.cancel(false)
            }
            if (owned != null) {
                owned.destroy()
            }
            process = null
            if (broker != null) {
                broker.close()
            }
        }
    }

    @Throws(Exception::class)
    fun sftp(): ServerSftp {
        return sftp(30L)
    }

    @Throws(Exception::class)
    fun sftp(timeoutMinutes: Long): ServerSftp {
        if (timeoutMinutes < 1 || timeoutMinutes > 360) {
            throw IOException("INVALID_TIMEOUT")
        }
        val trace: ServerDiagnostics.Trace = ServerDiagnostics.begin(profile, ServerDiagnostics.Op.SFTP_CONNECT)
        var failure: Exception? = null
        try {
            if (!trusted()) {
                throw IOException("TRUST_REQUIRED")
            }
            val args: MutableList<String> = ssh()
            args.addAll(Arrays.asList("-s", "--", profile!!.host, "sftp"))
            val b: ProcessBuilder = builder(args)
            val broker: SshPasswordBroker? = password(b)
            var child: Process? = null
            try {
                child = b.start()
                process = child
                if (closed) {
                    child!!.destroy()
                    throw IOException("CLOSED")
                }
                val owned: Process? = child
                val timer: ScheduledFuture<*> = watchdog.schedule(Runnable(owned!!::destroy), timeoutMinutes, TimeUnit.MINUTES)
                return ServerSftp(owned.getInputStream(), owned.getOutputStream(), AutoCloseable {
                    timer.cancel(false)
                    owned.destroy()
                    if (broker != null) {
                        broker.close()
                    }
                })
            } catch (e: Exception) {
                if (child != null) {
                    child.destroy()
                }
                if (broker != null) {
                    broker.close()
                }
                throw e
            }
        } catch (e: Exception) {
            failure = e
            throw e
        } finally {
            trace.finish(trace.elapsed(), 0L, (-1).toLong(), if (closed) CancellationException() else failure)
        }
    }

    override fun close() {
        closed = true
        LIVE.remove(this)
        val owned: Process? = process
        if (owned != null) {
            owned.destroy()
        }
        stopTunnel()
        watchdog.shutdownNow()
    }

    companion object {
        private val LIVE: MutableSet<ServerConnection> = ConcurrentHashMap.newKeySet()
        private val DISCONNECTED: MutableMap<Activity, MutableSet<String>> = WeakHashMap<Activity, MutableSet<String>>()

        @Synchronized
        @JvmStatic
        fun disconnected(activity: Activity, id: String): Boolean {
            val ids: MutableSet<String>? = DISCONNECTED.get(activity)
            return ids != null && ids.contains(id)
        }

        @Synchronized
        @JvmStatic
        fun allow(activity: Activity, id: String) {
            val ids: MutableSet<String>? = DISCONNECTED.get(activity)
            if (ids != null) {
                ids.remove(id)
            }
        }

        @JvmStatic
        fun disconnectAll(activity: Activity, id: String) {
            synchronized(ServerConnection::class.java) {
                DISCONNECTED.computeIfAbsent(activity, Function<Activity, MutableSet<String>> { a -> HashSet<String>() }).add(id)
            }
            for (c in LIVE) {
                if (c.activity === activity && c.profile!!.id.equals(id)) {
                    c.close()
                }
            }
        }

        @Throws(Exception::class)
        @JvmStatic
        fun deletionGroups(entries: JSONArray): MutableMap<String, JSONArray?> {
            if (entries.length() < 1 || entries.length() > 500) {
                throw IOException("INVALID_SELECTION")
            }
            val groups: MutableMap<String, JSONArray?> = LinkedHashMap<String, JSONArray?>()
            val seen: MutableSet<String> = HashSet<String>()
            var i = 0
            while (i < entries.length()) {
                val e: JSONObject = entries.getJSONObject(i)
                val path: String = e.getString("path")
                val slash: Int = path.lastIndexOf('/')
                if (!path.startsWith("/") || path.contains("\u0000") || path.endsWith("/") || path.contains("//") || Arrays.asList(*(path as java.lang.String).split("/")).contains("..") || Arrays.asList(*(path as java.lang.String).split("/")).contains(".") || !seen.add(path)) {
                    throw IOException("INVALID_PATH")
                }
                val parent: String = if (slash == 0) "/" else path.substring(0, slash)
                var group: JSONArray? = groups.get(parent)
                if (group == null) {
                    group = JSONArray()
                    groups.put(parent, group)
                }
                group.put(e)
                i++
            }
            return groups
        }
    }
}
