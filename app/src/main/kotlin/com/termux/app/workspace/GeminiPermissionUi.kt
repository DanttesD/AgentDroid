package com.termux.app.workspace

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R
import com.termux.app.TermuxActivity
import java.util.Arrays
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import org.json.JSONArray
import org.json.JSONObject

/** Run-scoped native approvals. Details stay in memory, never in workspace/status/history files.*/
class GeminiPermissionUi {
    private val activity: TermuxActivity
    private val current: Supplier<WorkspaceStore.Session?>
    private val visible: BooleanSupplier

    fun interface Transport {
        @Throws(Exception::class)
        fun request(run: String, value: JSONObject): JSONObject
    }

    private val transport: Transport
    private val openCode: Boolean
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var card: LinearLayout? = null
    private var run: String = ""
    private var id: String = ""
    private var requested: String = ""
    private var body: String = ""
    private var title: String = ""
    private var permission: JSONObject? = null
    private var loading: Boolean = false
    private var busy: Boolean = false
    private var closed: Boolean = false
    private var preview: AlertDialog? = null

    constructor(a: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier) : this(a, current, visible, Transport { run, value -> GeminiLiveControl.request(a.getFilesDir(), run, value) })

    constructor(a: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier, transport: Transport) : this(a, current, visible, transport, false)

    constructor(a: TermuxActivity, current: Supplier<WorkspaceStore.Session?>, visible: BooleanSupplier, transport: Transport, openCode: Boolean) {
        activity = a
        this.current = current
        this.visible = visible
        this.transport = transport
        this.openCode = openCode
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun dp(n: Int): Int {
        return Math.round(n * activity.getResources().getDisplayMetrics().density)
    }

    private fun key(p: WorkspaceStore.Session): String {
        return if (openCode) p.id + ":" + p.aiThread else p.geminiSocketId
    }

    private fun owns(value: String): Boolean {
        val p: WorkspaceStore.Session? = current.get()
        return !closed && visible.getAsBoolean() && p != null && value.equals(key(p))
    }

    fun syncVisibility() {
        if (!owns(run)) {
            hide()
        }
    }

    private fun hide() {
        if (card != null) {
            card!!.setVisibility(View.GONE)
        }
        if (preview != null) {
            preview!!.dismiss()
            preview = null
        }
        if (!owns(run)) {
            run = ""
            id = ""
            requested = ""
            permission = null
            body = ""
            title = ""
            busy = false
        }
    }

    fun update(p: WorkspaceStore.Session, status: GeminiLiveStatus?) {
        if (!owns(key(p))) {
            hide()
            return
        }
        if (!run.equals(key(p))) {
            hide()
            run = key(p)
        }
        val wanted: String = if (status == null || !status.available) "" else status.permissionId
        if (wanted.isEmpty()) {
            if (!busy && permission != null) {
                permission = null
                id = ""
                body = ""
                title = ""
                if (card != null) {
                    card!!.setVisibility(View.GONE)
                }
            }
            return
        }
        if (permission != null && !wanted.equals(id) && !busy) {
            permission = null
            id = ""
            if (card != null) {
                card!!.setVisibility(View.GONE)
            }
        }
        if (wanted.equals(id) || wanted.equals(requested) || loading || busy) {
            return
        }
        val captured: String = run
        requested = wanted
        loading = true
        worker.execute(Runnable {
            var response: JSONObject? = null
            try {
                response = transport.request(captured, JSONObject().put("op", "permission"))
            } catch (ignored: Exception) {
            }
            val result: JSONObject? = response
            activity.runOnUiThread(Runnable {
                loading = false
                if (!owns(captured) || !requested.equals(wanted)) {
                    return@Runnable
                }
                requested = ""
                val value: JSONObject? = if (result == null) null else result.optJSONObject("permission")
                if (value == null || !wanted.equals(value.optString("id")) || !valid(value)) {
                    return@Runnable
                }
                run = captured
                id = wanted
                permission = value
                body = value.optString("body")
                title = value.optString("title")
                render()
            })
        })
    }

    fun refresh(p: WorkspaceStore.Session) {
        if (!owns(key(p))) {
            hide()
            return
        }
        if (loading || busy) {
            return
        }
        val captured: String = key(p)
        run = captured
        loading = true
        worker.execute(Runnable {
            var result: JSONObject? = null
            try {
                result = transport.request(captured, JSONObject().put("op", "permission"))
            } catch (ignored: Exception) {
            }
            val response: JSONObject? = result
            activity.runOnUiThread(Runnable {
                loading = false
                if (!owns(captured)) {
                    return@Runnable
                }
                val value: JSONObject? = if (response == null) null else response.optJSONObject("permission")
                if (value == null) {
                    if (response != null) {
                        permission = null
                        id = ""
                        if (card != null) {
                            card!!.setVisibility(View.GONE)
                        }
                    }
                    return@Runnable
                }
                if (!valid(value) || value.optString("id").equals(id)) {
                    return@Runnable
                }
                id = value.optString("id")
                permission = value
                body = value.optString("body")
                title = value.optString("title")
                render()
            })
        })
    }

    private fun text(value: String, size: Int): TextView {
        val t = TextView(activity)
        t.setText(value)
        t.setTextSize(size.toFloat())
        t.setTextColor(Pal.TEXT)
        return t
    }

    private fun background(color: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.setCornerRadius(dp(radius).toFloat())
        d.setStroke(dp(1), Pal.TINT_BORDER)
        return d
    }

    private fun ensureCard() {
        if (card != null) {
            return
        }
        val host: FrameLayout? = activity.findViewById(R.id.workspace_terminal_surface)
        if (host == null) {
            return
        }
        card = LinearLayout(activity)
        card!!.setOrientation(LinearLayout.VERTICAL)
        card!!.setPadding(dp(12), dp(10), dp(12), dp(10))
        card!!.setBackground(background(Pal.PANEL, 14))
        card!!.setTag("gemini-permission-card")
        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        lp.setMargins(dp(12), dp(8), dp(12), dp(8))
        host.addView(card, lp)
    }

    private fun render() {
        if (!owns(run) || permission == null) {
            return
        }
        ensureCard()
        if (card == null) {
            return
        }
        card!!.removeAllViews()
        card!!.setVisibility(View.VISIBLE)
        card!!.addView(text(tr((if (openCode) "OpenCode" else "Gemini") + " · Разрешить действие?", (if (openCode) "OpenCode" else "Gemini") + " · Allow action?"), 14))
        val command: TextView = text(title + "\n" + body, 12)
        command.setTypeface(Typeface.MONOSPACE)
        command.setMaxLines(3)
        command.setEllipsize(TextUtils.TruncateAt.END)
        command.setPadding(0, dp(6), 0, dp(8))
        command.setOnClickListener(View.OnClickListener { v -> preview() })
        card!!.addView(command)
        val full: TextView = text(tr("Посмотреть полностью ›", "View full request ›"), 12)
        full.setPadding(0, 0, 0, dp(8))
        full.setOnClickListener(View.OnClickListener { v -> preview() })
        card!!.addView(full)
        val actions = LinearLayout(activity)
        actions.setGravity(Gravity.CENTER)
        val options: JSONArray = permission!!.optJSONArray("options")
        for (outcome in arrayOf<String>("proceed_once", "proceed_always", "cancel")) {
            var allowed = false
            var i = 0
            while (i < options.length()) {
                allowed = allowed or outcome.equals(options.optString(i))
                i++
            }
            if (!allowed) {
                continue
            }
            val b: TextView = text(if (outcome.equals("cancel")) tr("Нет", "No") else if (outcome.equals("proceed_always")) tr("На эту сессию", "This session") else tr("Разрешить разово", "Allow once"), 13)
            b.setGravity(Gravity.CENTER)
            b.setMinHeight(dp(44))
            b.setBackground(background(if (outcome.equals("cancel")) Pal.CARD2 else Pal.TINT, 10))
            b.setOnClickListener(View.OnClickListener { v -> answer(outcome) })
            val lp = LinearLayout.LayoutParams(0, -2, 1.0f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            actions.addView(b, lp)
        }
        card!!.addView(actions)
        val hint: TextView = text(if (openCode) tr("Разово — только текущий запрос. Нет — отклонить его. Правила для проекта можно выбрать в штатном запросе OpenCode.", "Once — only this request. No — reject it. Project rules remain available in the native OpenCode prompt.") else tr("Разово — только это действие. На эту сессию — без повторного запроса по правилам Gemini; разрешение может охватывать похожие команды или действия. Сбрасывается после завершения Gemini.", "Once — only this action. This session — no repeat prompt under Gemini's rules; the permission may cover similar commands or actions. It resets when Gemini exits."), 11)
        hint.setPadding(0, dp(7), 0, 0)
        card!!.addView(hint)
    }

    private fun preview() {
        if (permission == null || !owns(run)) {
            return
        }
        val t: TextView = text(body, 13)
        t.setTypeface(Typeface.MONOSPACE)
        t.setTextIsSelectable(true)
        t.setPadding(dp(16), dp(12), dp(16), dp(12))
        val scroll = ScrollView(activity)
        scroll.addView(t)
        preview = WorkspaceDialogs.builder(activity).setTitle(title).setView(scroll).setPositiveButton(tr("Назад", "Back"), null).create()
        preview!!.show()
    }

    private fun answer(outcome: String) {
        if (busy || permission == null || !owns(run)) {
            return
        }
        val captured: String = run
        val token: String = id
        busy = true
        if (preview != null) {
            preview!!.dismiss()
            preview = null
        }
        var i = 0
        while (i < card!!.getChildCount()) {
            card!!.getChildAt(i).setEnabled(false)
            i++
        }
        // Disable descendants too, avoiding repeated taps while the callback is in flight.
        setEnabled(card, false)
        worker.execute(Runnable {
            var response: JSONObject? = null
            try {
                response = transport.request(captured, JSONObject().put("op", "answer").put("id", token).put("outcome", outcome))
            } catch (ignored: Exception) {
            }
            val result: JSONObject? = response
            activity.runOnUiThread(Runnable {
                if (!owns(captured) || !token.equals(id)) {
                    return@Runnable
                }
                busy = false
                val ack: JSONObject? = if (result == null) null else result.optJSONObject("resolved")
                val confirmed: Boolean = ack != null && token.equals(ack.optString("id")) && outcome.equals(ack.optString("outcome"))
                permission = null
                id = ""
                body = ""
                title = ""
                card!!.removeAllViews()
                setEnabled(card, true)
                if (confirmed) {
                    card!!.setVisibility(View.GONE)
                    return@Runnable
                }
                val done: TextView = text(if (confirmed) if (outcome.equals("cancel")) tr("Отклонено", "Denied") else if (outcome.equals("proceed_always")) tr("Разрешено на эту сессию без повторного запроса", "Allowed for this session without repeat prompts") else tr("Разрешено один раз", "Allowed once") else tr("Ответ не подтверждён. Проверьте запрос в терминале.", "Response unconfirmed. Check the terminal prompt."), 12)
                card!!.addView(done)
                done.setOnClickListener(View.OnClickListener { v -> card!!.setVisibility(View.GONE) })
            })
        })
    }

    private fun setEnabled(view: View?, value: Boolean) {
        view!!.setEnabled(value)
        if (view is ViewGroup) {
            val g: ViewGroup = view as ViewGroup
            var i = 0
            while (i < g.getChildCount()) {
                setEnabled(g.getChildAt(i), value)
                i++
            }
        }
    }

    fun close() {
        closed = true
        hide()
        worker.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun valid(value: JSONObject): Boolean {
            if (!GeminiCli.validThread(value.optString("id")) || !Arrays.asList("exec", "edit", "info").contains(value.optString("type"))) {
                return false
            }
            if (value.optString("body").isEmpty() || value.optString("body").length > 40000 || value.optString("title").length > 80) {
                return false
            }
            val options: JSONArray? = value.optJSONArray("options")
            if (options == null || options.length() > 3) {
                return false
            }
            var yes = false
            var no = false
            var i = 0
            while (i < options.length()) {
                val s: String = options.optString(i)
                if (!Arrays.asList("proceed_once", "proceed_always", "cancel").contains(s)) {
                    return false
                }
                yes = yes or s.equals("proceed_once")
                no = no or s.equals("cancel")
                i++
            }
            return yes && no
        }
    }
}
