package com.termux.app.workspace

import android.content.Context
import android.net.LocalSocket
import com.termux.app.agent.AgentBackends
import com.termux.app.agent.McpHost
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** OpenCode-only adapter; existing Codex profiles continue on their original path.*/
object AiAgents {
    const val DEFAULT_CONNECTION: String = "00000000-0000-4000-8000-000000000002"
    private const val GUEST: String = "/tmp/termux-jobs-ai"

    @JvmStatic
    fun validThread(id: String?): Boolean {
        return id != null && (id as java.lang.String).matches("ses[A-Za-z0-9_-]{1,124}")
    }

    @JvmStatic
    fun validModel(model: String?): Boolean {
        return model != null && model.length <= 256 && (model.isEmpty() || (model as java.lang.String).matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.:/-]+"))
    }

    @JvmStatic
    fun prepareLaunch(s: WorkspaceStore.Session?) {
        s!!.aiSocketId = UUID.randomUUID().toString()
        if (!s.aiSavedThread.isEmpty()) {
            s.aiThread = s.aiSavedThread
            s.aiChat = "resume"
        }
    }

    @JvmStatic
    fun directory(files: File): File {
        return File(files, ".ai-control")
    }

    @JvmStatic
    fun socket(files: File, s: WorkspaceStore.Session?): File {
        val run: String = if (s!!.aiSocketId.isEmpty()) s.id else s.aiSocketId
        UUID.fromString(run)
        return File(directory(files), run + ".sock")
    }

    /** Capture before scheduling so a later launch cannot retarget an old action.*/
    @JvmStatic
    fun snapshot(p: WorkspaceStore.Session): WorkspaceStore.Session {
        val s: WorkspaceStore.Session = p.copy()
        s.id = p.id
        s.handle = p.handle
        s.aiSocketId = p.aiSocketId
        s.aiThread = p.aiThread
        s.aiSavedThread = p.aiSavedThread
        return s
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun protect(f: File) {
        if (!f.setReadable(false, false) || !f.setWritable(false, false) || !f.setExecutable(false, false) || !f.setReadable(true, true) || !f.setWritable(true, true) || f.isDirectory() && !f.setExecutable(true, true)) {
            throw IOException("Cannot protect AI controls")
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(context: Context) {
        val dir: File = directory(context.getFilesDir())
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create AI controls")
        }
        protect(dir)
        for (asset in arrayOf<String>("ai-agents-control.js", "ai-agents-bootstrap.sh", "agent-receipts.cjs", "agent-attachments.cjs", "opencode-permissions.cjs", "opencode-chat.cjs", "opencode-session-settings.cjs", "agentdroid-mcp.cjs")) {
            val bridge = File(dir, if (asset.equals("ai-agents-bootstrap.sh")) "bootstrap-v1.sh" else if (asset.equals("ai-agents-control.js")) "bridge-v1.js" else asset)
            if (asset.endsWith(".bundle") && bridge.isFile() && bridge.length() > 0) {
                continue
            }
            val temporary: File = File.createTempFile("bridge-", ".tmp", dir)
            (context.getAssets().open(asset)).use { `in` ->
                (FileOutputStream(temporary)).use { out ->
                    val b = ByteArray(8192)
                    var n: Int = 0
                    while (true) {
                        n = `in`.read(b)
                        if (n == -1) break
                        out.write(b, 0, n)
                    }
                }
            }
            protect(temporary)
            if (!temporary.renameTo(bridge)) {
                temporary.delete()
                throw IOException("Cannot replace AI bridge")
            }
        }
        writeMcpDescriptor(context, dir)
    }

    /** Tells the OpenCode bridge where the phone's MCP server is (loopback only; the guest shares the host network).*/
    @JvmStatic
    private fun writeMcpDescriptor(context: Context, dir: File) {
        try {
            val url: String = "http://127.0.0.1:" + McpHost.port(context) + "/mcp/" + McpHost.token(context)
            val envFile = File(AgentBackends.TERMUX_HOME, ".agentdroid/ENVIRONMENT.md")
            var env = "You are running inside AgentDroid, an Android app, on the user's own phone. The agentdroid MCP tools give the same access as the app: shell, files, screen (accessibility), apps, notifications. Sensitive actions ask the user on the phone."
            if (envFile.isFile() && envFile.length() < 20000) {
                env = String(Files.readAllBytes(envFile.toPath()), Charsets.UTF_8)
            }
            val o: JSONObject = JSONObject().put("url", url).put("environment", env)
            val temporary: File = File.createTempFile("mcp-", ".tmp", dir)
            (FileOutputStream(temporary)).use { out ->
                out.write((o.toString() as java.lang.String).getBytes("UTF-8"))
            }
            protect(temporary)
            val target = File(dir, "agentdroid-mcp.json")
            if (!temporary.renameTo(target)) {
                temporary.delete()
            }
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun command(files: File, s: WorkspaceStore.Session?): String {
        if (!s!!.aiAgent.equals("opencode") || !s.environment.equals("ubuntu") || !AgentEnvironments.ubuntu(s.codexEnvironment) || !validThread(s.aiThread) || !validModel(s.aiModel)) {
            throw IllegalArgumentException("Invalid AI profile")
        }
        return "( set -e; export JOBS_AI_LANGUAGE=" + (if (WorkspaceText.tr("Агент: ")!!.startsWith("Агент")) "ru" else "en") + "; source " + SessionShell.quote(GUEST + "/bootstrap-v1.sh") + "; exec node " + SessionShell.quote(GUEST + "/bridge-v1.js") + " run-chat " + SessionShell.quote(GUEST + "/connections/" + s.aiConnection) + " " + SessionShell.quote(GUEST + "/" + socket(files, s).getName()) + " " + SessionShell.quote(s.aiThread) + " " + SessionShell.quote(s.aiModel) + " " + SessionShell.quote(s.aiMode) + " " + SessionShell.quote(s.aiChat) + " " + SessionShell.quote(s.aiVariant) + " " + SessionShell.quote(s.aiAccess) + " " + SessionShell.quote(if (s.aiProjectOnly) "project" else "unrestricted") + " )"
    }

    @Throws(Exception::class)
    @JvmStatic
    fun liveRequest(c: Context, s: WorkspaceStore.Session, request: JSONObject): JSONObject {
        UUID.fromString(s.id)
        if (!s.aiAgent.equals("opencode")) {
            throw IOException("Not OpenCode")
        }
        (LocalSocket()).use { client ->
            GeminiLiveControl.connect(client, socket(c.getFilesDir(), s).getAbsolutePath())
            client.setSoTimeout(35000)
            val data: ByteArray = (request.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8)
            if (data.size > 65536) {
                throw IOException("Request too large")
            }
            client.getOutputStream().write(data)
            client.getOutputStream().flush()
            client.shutdownOutput()
            val out = ByteArrayOutputStream()
            val b = ByteArray(4096)
            var n: Int = 0
            while (true) {
                n = client.getInputStream().read(b)
                if (n == -1) break
                if (out.size() + n > 524288) {
                    throw IOException("Response too large")
                }
                out.write(b, 0, n)
            }
            val value = JSONObject(out.toString("UTF-8"))
            if (!value.optBoolean("ok")) {
                throw IOException("OpenCode request failed")
            }
            return value
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun call(c: Context, connection: String, live: WorkspaceStore.Session?, request: JSONObject): JSONObject {
        UUID.fromString(connection)
        prepare(c)
        val args: String = if (live == null || !live.aiAgent.equals("opencode")) "request " + SessionShell.quote(GUEST + "/connections/" + connection) else "proxy " + SessionShell.quote(GUEST + "/" + socket(c.getFilesDir(), live).getName())
        val script: String = "exec proot-distro login --bind " + SessionShell.quote(directory(c.getFilesDir()).getAbsolutePath() + ":" + GUEST) + " " + SessionShell.quote(if (live == null) AgentEnvironments.defaultUbuntu() else AgentEnvironments.alias(live)) + " -- /bin/bash --noprofile --norc -c " + SessionShell.quote("export PATH=\"\$HOME/.local/share/termux-jobs/agents/bin:\$HOME/.local/share/termux-jobs/agents/node-v22.22.1-linux-arm64/bin:\$HOME/.local/share/termux-jobs/agents/node_modules/.bin:\$PATH\"; exec node " + SessionShell.quote(GUEST + "/bridge-v1.js") + " " + args)
        val pb = ProcessBuilder(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "--noprofile", "--norc", "-c", script)
        pb.directory(File(TermuxConstants.TERMUX_HOME_DIR_PATH))
        pb.environment().clear()
        for (v in TermuxShellUtils.buildEnvironment(c, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            val i: Int = v.indexOf('=')
            if (i > 0) {
                pb.environment().put(v.substring(0, i), v.substring(i + 1))
            }
        }
        pb.redirectError(File("/dev/null"))
        val p: Process = pb.start()
        val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
        timer.schedule(Runnable(p::destroy), 65L, TimeUnit.SECONDS)
        try {
            (p.getOutputStream()).use { stdin ->
                stdin.write((request.toString() as java.lang.String).getBytes(StandardCharsets.UTF_8))
            }
            val out = ByteArrayOutputStream()
            (p.getInputStream()).use { `in` ->
                val b = ByteArray(8192)
                var n: Int = 0
                while (true) {
                    n = `in`.read(b)
                    if (n == -1) break
                    if (out.size() + n > 2 * 1024 * 1024) {
                        throw IOException("AI response too large")
                    }
                    out.write(b, 0, n)
                }
            }
            val result = JSONObject(out.toString("UTF-8"))
            if (!result.optBoolean("ok")) {
                throw IOException("OpenCode unavailable")
            }
            return result
        } finally {
            p.destroy()
            timer.shutdownNow()
        }
    }
}
