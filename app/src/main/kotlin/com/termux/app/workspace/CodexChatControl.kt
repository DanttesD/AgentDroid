package com.termux.app.workspace

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** New-run Codex chat transport; existing terminal sessions retain their immutable bridge.*/
object CodexChatControl {
    @JvmStatic
    fun marker(files: File, p: WorkspaceStore.Session?): File {
        return File(CodexSessionShell.socketDirectory(files), p!!.codexSocketId + ".android-chat")
    }

    @JvmStatic
    fun enabled(files: File, p: WorkspaceStore.Session?): Boolean {
        return p != null && p.environment.equals("codex") && AgentEnvironments.ubuntu(p.codexEnvironment) && CodexSessionShell.validThread(p.codexSocketId) && marker(files, p).isFile()
    }

    @Throws(IOException::class)
    @JvmStatic
    fun prepare(c: Context, p: WorkspaceStore.Session?) {
        if (!p!!.environment.equals("codex") || !AgentEnvironments.ubuntu(p.codexEnvironment)) {
            return
        }
        if (!CodexSessionShell.validThread(p.codexSocketId)) {
            throw IOException("Invalid Codex run")
        }
        val dir: File = CodexSessionShell.socketDirectory(c.getFilesDir())
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw IOException("Cannot create Codex controls")
        }
        for (asset in arrayOf<String>("codex-chat.cjs", "codex-control.js", "agent-receipts.cjs")) {
            val name: String = if (asset.equals("codex-control.js")) "codex-wire-v0.2.22.js" else if (asset.equals("codex-chat.cjs")) "codex-chat-v1.cjs" else asset
            val temp: File = File.createTempFile("codex-chat-", ".tmp", dir)
            try {
                (c.getAssets().open(asset)).use { `in` ->
                    (FileOutputStream(temp)).use { out ->
                        val b = ByteArray(8192)
                        var n: Int = 0
                        while (true) {
                            n = `in`.read(b)
                            if (n == -1) break
                            out.write(b, 0, n)
                        }
                    }
                }
                protect(temp)
                if (!temp.renameTo(File(dir, name))) {
                    throw IOException("Cannot publish Codex chat helper")
                }
            } finally {
                temp.delete()
            }
        }
        (FileOutputStream(marker(c.getFilesDir(), p))).use { out ->
            out.write(1)
        }
        protect(marker(c.getFilesDir(), p))
    }

    @Throws(IOException::class)
    @JvmStatic
    fun protect(f: File?) {
        if (!f!!.setReadable(false, false) || !f.setWritable(false, false) || !f.setReadable(true, true) || !f.setWritable(true, true)) {
            throw IOException("Cannot protect Codex chat file")
        }
    }

    @JvmStatic
    fun command(files: File, p: WorkspaceStore.Session?, endpoint: String): String {
        val home: String = if (AgentEnvironments.ubuntu(p!!.codexEnvironment)) "/root" else TermuxConstants.TERMUX_HOME_DIR_PATH
        var cwd: String? = if (p.directory!!.isEmpty() || p.directory!!.equals("~")) home else p.directory
        if (cwd!!.startsWith("~/")) {
            cwd = home + cwd.substring(1)
        }
        return "node " + SessionShell.quote("/tmp/termux-jobs-codex/codex-chat-v1.cjs") + " run " + SessionShell.quote(endpoint) + " " + SessionShell.quote(p.codexAccess) + " " + SessionShell.quote(p.codexChat) + " " + SessionShell.quote(p.codexThread) + " " + SessionShell.quote(cwd) + " " + SessionShell.quote(p.codexSourceDirectory) + " " + SessionShell.quote(CodexSessionShell.approval(p)) + " " + SessionShell.quote(if (WorkspaceText.tr("Агент: ")!!.startsWith("Агент")) "ru" else "en")
    }

    @Throws(Exception::class)
    @JvmStatic
    fun request(files: File, p: WorkspaceStore.Session?, q: JSONObject): JSONObject {
        if (!enabled(files, p)) {
            return JSONObject().put("ok", true).put("nativeChat", false)
        }
        val socket = File(CodexSessionShell.socket(files, p).getAbsolutePath() + ".chat")
        (LocalSocket()).use { client ->
            client.connect(LocalSocketAddress(socket.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM))
            client.setSoTimeout(15000)
            val bytes: ByteArray = ((q.toString() + "\n") as java.lang.String).getBytes(StandardCharsets.UTF_8)
            if (bytes.size > 65536) {
                throw IOException("REQUEST_TOO_LARGE")
            }
            client.getOutputStream().write(bytes)
            client.getOutputStream().flush()
            val r = JSONObject(ClaudeLiveControl.readReply(client.getInputStream(), 524288))
            if (!r.optBoolean("ok")) {
                throw IOException("Codex chat did not confirm action")
            }
            return r
        }
    }
}
