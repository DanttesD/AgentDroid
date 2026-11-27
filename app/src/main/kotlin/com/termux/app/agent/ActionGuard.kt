package com.termux.app.agent

import android.content.Context
import android.content.SharedPreferences
import com.agentdroid.agent.Guard
import com.agentdroid.agent.ScreenBridge
import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ExecRequest
import com.agentdroid.exec.ExecResult
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale

/** Emergency stop, apps the agent must not touch, and a log of what it did.*/
class ActionGuard : Guard {

    private val app: Context
    private val backend: ExecBackend

    constructor(c: Context, backend: ExecBackend) {
        this.app = c.getApplicationContext()
        this.backend = backend
    }

    override fun before(tool: String, args: Map<String, Any?>, description: String) {
        if (paused) {
            throw SecurityException("the user pressed the emergency stop; ask them to resume the agent")
        }
        if (!ACTIONS.contains(tool)) {
            return
        }
        val blocked: MutableSet<String> = HashSet<String>()
        for (l in (blocked(app)!! as java.lang.String).split("\n")) {
            if (!(l as java.lang.String).trim().isEmpty()) {
                blocked.add((l as java.lang.String).trim())
            }
        }
        if (blocked.isEmpty()) {
            return
        }
        val pk: Any? = args.get("package")
        if (pk is String && blocked.contains(pk)) {
            throw SecurityException("app " + pk + " is on the user's blocked list")
        }
        val fg: String = foreground()
        if (blocked.contains(fg)) {
            throw SecurityException("the app in the foreground (" + fg + ") is on the user's blocked list; leave it first or ask the user to change the list")
        }
    }

    override fun after(tool: String, args: Map<String, Any?>, result: String, error: Boolean) {
        try {
            val f: File = log(app)
            if (f.length() > 512 * 1024) {
                f.delete()
            }
            val a: String = java.lang.String.valueOf(args)
            val r: String = if (result == null) "" else (result as java.lang.String).replace('\n', ' ')
            val line: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + tool + " " + (if (a.length > 160) a.substring(0, 160) + "…" else a) + (if (error) " ERROR " else " -> ") + (if (r.length > 100) r.substring(0, 100) + "…" else r) + "\n"
            (FileOutputStream(f, true)).use { o ->
                o.write((line as java.lang.String).getBytes("UTF-8"))
            }
        } catch (ignored: Exception) {
        }
    }

    private fun foreground(): String {
        try {
            val b: ScreenBridge? = ScreenBridge.Holder.current
            if (b != null) {
                val p: String = b.foregroundPackage()
                if (!p.isEmpty()) {
                    return p
                }
            }
            val r: ExecResult = backend.exec(ExecRequest.builder("dumpsys window | grep mCurrentFocus | head -1").timeoutMs(8000L).build())
            val s: String = r.stdout()
            val slash: Int = s.indexOf('/')
            if (slash > 0) {
                val st: Int = s.lastIndexOf(' ', slash) + 1
                return s.substring(st, slash)
            }
        } catch (ignored: Exception) {
        }
        return ""
    }

    companion object {
        private const val PREFS: String = "agentdroid_guard"
        private const val DEFAULT_BLOCKED: String = "ru.sberbankmobile\ncom.idamob.tinkoff.android\nru.vtb24.mobilebanking.android\nru.alfabank.mobile.android\ncom.google.android.apps.walletnfcrel\ncom.paypal.android.p2pmobile"
        private val ACTIONS: MutableSet<String> = HashSet<String>(Arrays.asList("tap", "swipe", "type_text", "key", "open_app", "click", "long_click", "set_text", "scroll", "global_action", "intent", "toggle", "settings", "app_install", "app_uninstall", "find_and_tap", "submit_message"))

        @Volatile private var paused: Boolean = false

        @JvmStatic
        fun isPaused(): Boolean {
            return paused
        }

        @JvmStatic
        fun setPaused(p: Boolean) {
            paused = p
        }

        @JvmStatic
        private fun prefs(c: Context): SharedPreferences {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }

        /** One package name per line.*/
        @JvmStatic
        fun blocked(c: Context): String? {
            return prefs(c).getString("blocked", DEFAULT_BLOCKED)
        }

        @JvmStatic
        fun setBlocked(c: Context, lines: String) {
            prefs(c).edit().putString("blocked", lines).apply()
        }

        @JvmStatic
        private fun log(c: Context): File {
            return File(c.getApplicationContext().getFilesDir(), "agent-actions.log")
        }

        @JvmStatic
        fun readLog(c: Context): String {
            try {
                val f: File = log(c)
                if (!f.exists()) {
                    return ""
                }
                val all: ByteArray = Files.readAllBytes(f.toPath())
                val from: Int = Math.max(0, all.size - 20_000)
                return String(all, from, all.size - from, Charsets.UTF_8)
            } catch (e: Exception) {
                return ""
            }
        }

        @JvmStatic
        fun clearLog(c: Context) {
            log(c).delete()
        }
    }
}
