package com.termux.app.workspace

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.app.TermuxActivity
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.json.JSONObject

/** OpenCode pending requests and native transcript receipt details; never executes terminal text.*/
class AgentReceiptsUi {
    private val activity: TermuxActivity
    private lateinit var current: Supplier<WorkspaceStore.Session?>
    private lateinit var visible: BooleanSupplier

    fun interface Transport {
        @Throws(Exception::class)
        fun request(p: WorkspaceStore.Session, q: JSONObject): JSONObject
    }

    private val transport: Transport
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val reader: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var openCode: GeminiPermissionUi
    private var closed: Boolean = false
    private var details: AlertDialog? = null
    private var detailsOwner: String = ""
    private var lastOwner: String = ""
    private var previewSerial: Long = 0L

    constructor(a: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier) : this(a, current, visible, Transport { p, q ->
        if (p.geminiAgent) GeminiLiveControl.request(a.getFilesDir(), p.geminiSocketId, q) else AiAgents.liveRequest(a, p, q)
    })

    constructor(a: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier, transport: Transport) {
        activity = a
        this.current = current
        this.visible = visible
        this.transport = transport
        openCode = GeminiPermissionUi(a, Supplier<WorkspaceStore.Session?> {
            val p: WorkspaceStore.Session? = current.get()
            return@Supplier if (p != null && p.aiAgent.equals("opencode")) p else null
        }, visible, GeminiPermissionUi.Transport { run, q ->
            val p: WorkspaceStore.Session? = current.get()
            if (p == null || !run.equals(owner(p))) {
                throw IOException("Changed session")
            }
            val snapshot: WorkspaceStore.Session = snapshot(p)
            return@Transport transport.request(snapshot, q)
        }, true)
        handler.post(poll)
    }

    private fun owner(p: WorkspaceStore.Session): String {
        return if (p.aiAgent.equals("opencode")) p.id + ":" + p.aiThread else p.geminiSocketId
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (closed) {
                return
            }
            syncVisibility()
            val p: WorkspaceStore.Session? = current.get()
            if (visible.getAsBoolean() && p != null && p.aiAgent.equals("opencode")) {
                openCode.refresh(p)
            }
            handler.postDelayed(this, 1500L)
        }
    }

    fun syncVisibility() {
        openCode.syncVisibility()
        val p: WorkspaceStore.Session? = current.get()
        val active: String = if (visible.getAsBoolean() && p != null) owner(p) else ""
        if (!active.equals(lastOwner)) {
            lastOwner = active
            previewSerial++
        }
        if (details != null && (!visible.getAsBoolean() || p == null || !owner(p).equals(detailsOwner))) {
            details!!.dismiss()
            details = null
        }
    }

    fun tap(line: String): Boolean {
        syncVisibility()
        val p: WorkspaceStore.Session? = current.get()
        val m: Matcher = TAG.matcher(line)
        if (closed || !visible.getAsBoolean() || p == null || !m.find()) {
            return false
        }
        val gemini: Boolean = m.group(1)!!.equals("G")
        if (if (gemini) !p.geminiAgent else !p.aiAgent.equals("opencode")) {
            return false
        }
        val serial: Long = ++previewSerial
        val token: String? = m.group(2)
        val captured: String = owner(p)
        val snapshot: WorkspaceStore.Session = snapshot(p)
        reader.execute(Runnable {
            var result: JSONObject? = null
            try {
                val q: JSONObject = JSONObject().put("op", "receipt").put("token", token)
                result = transport.request(snapshot, q)
            } catch (ignored: Exception) {
            }
            val response: JSONObject? = result
            handler.post(Runnable {
                val now: WorkspaceStore.Session? = current.get()
                if (closed || serial != previewSerial || !visible.getAsBoolean() || now == null || !captured.equals(owner(now))) {
                    return@Runnable
                }
                val r: JSONObject? = if (response == null) null else response.optJSONObject("receipt")
                if (r == null || !token!!.equals(r.optString("token"))) {
                    Toast.makeText(activity, if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Подробности этой записи недоступны в текущем чате." else "Receipt details are unavailable in this chat.", Toast.LENGTH_SHORT).show()
                    return@Runnable
                }
                val text = TextView(activity)
                text.setTextColor(Pal.TEXT)
                text.setTextSize(13.0f)
                text.setTypeface(Typeface.MONOSPACE)
                text.setTextIsSelectable(true)
                val pad: Int = Math.round(16 * activity.getResources().getDisplayMetrics().density)
                text.setPadding(pad, pad, pad, pad)
                val ru: Boolean = WorkspaceLanguage.code(activity)!!.equals("ru")
                var note: String = if (ru) "Это запись о разрешении, а не подтверждение успешного выполнения." else "This records permission, not successful execution."
                if (gemini && r.optString("outcome").equals("proceed_always")) {
                    note += "\n" + (if (ru) "Сессионное разрешение относилось к тому запуску Gemini. Эта запись не выдаёт его заново." else "Session permission belonged to that Gemini launch. This receipt does not grant it again.")
                }
                text.setText(note + "\n\n" + r.optString("body"))
                val scroll = ScrollView(activity)
                scroll.addView(text)
                if (details != null) {
                    details!!.dismiss()
                }
                detailsOwner = captured
                details = WorkspaceDialogs.builder(activity).setTitle(r.optString("title")).setView(scroll).setPositiveButton(if (ru) "Назад" else "Back", null).create()
                details!!.show()
            })
        })
        return true
    }

    fun close() {
        closed = true
        handler.removeCallbacks(poll)
        openCode.close()
        reader.shutdownNow()
        if (details != null) {
            details!!.dismiss()
        }
    }

    companion object {
        private val TAG: Pattern = Pattern.compile("\\[([GO]):([a-f0-9]{8})\\]")

        @JvmStatic
        private fun snapshot(p: WorkspaceStore.Session): WorkspaceStore.Session {
            val s = WorkspaceStore.Session()
            s.id = p.id
            s.aiAgent = p.aiAgent
            s.aiThread = p.aiThread
            s.aiConnection = p.aiConnection
            s.geminiAgent = p.geminiAgent
            s.geminiSocketId = p.geminiSocketId
            return s
        }
    }
}
