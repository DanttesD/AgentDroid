package com.termux.app.workspace

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.format.Formatter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R
import io.noties.markwon.Markwon
import java.util.function.BiConsumer
import java.util.function.Consumer
import org.json.JSONObject

/** Agent-neutral Android transcript. Updates rows by stable identity, never rebuilds the terminal.*/
class AgentChatView : LinearLayout {
    @JvmField val scroll: ScrollView
    @JvmField val timeline: LinearLayout
    @JvmField val requests: LinearLayout
    @JvmField val keysHost: LinearLayout
    @JvmField val attachmentDrafts: LinearLayout
    @JvmField val folder: WorkspaceIconButton
    private val shellLabel: TextView
    @JvmField var thumbnail: BiConsumer<JSONObject, ImageView> = BiConsumer<JSONObject, ImageView> { a, v ->  }
    @JvmField var apkDetails: BiConsumer<JSONObject, TextView> = BiConsumer<JSONObject, TextView> { a, v ->  }
    @JvmField val status: TextView
    @JvmField val older: TextView
    @JvmField val connection: TextView
    @JvmField val shellInput: EditText
    private var shellSend: WorkspaceIconButton
    private var shellRow: LinearLayout? = null
    private var assistant: String = "AI"
    private var codexActions: Boolean = false
    private var groupAllTools: Boolean = false

    fun actionCards(allTools: Boolean) {
        codexActions = true
        groupAllTools = allTools
    }

    fun codexActions(enabled: Boolean) {
        codexActions = enabled
    }

    fun assistantName(name: String) {
        assistant = name
    }

    fun shellEnabled(enabled: Boolean) {
        shellInput.setEnabled(enabled)
        shellInput.setFocusable(enabled)
        shellInput.setFocusableInTouchMode(enabled)
        shellInput.setClickable(enabled)
        shellSend.setEnabled(enabled)
        shellSend.setAlpha(if (enabled) 1f else 0.4f)
        if (!enabled) {
            shellInput.clearFocus()
        }
    }

    private val views: MutableMap<String, View?> = LinkedHashMap<String, View?>()
    private val values: MutableMap<String, String> = HashMap<String, String>()
    private val data: MutableMap<String, JSONObject?> = HashMap<String, JSONObject?>()
    private val open: Consumer<JSONObject>
    private var markdown: Markwon? = null
    private var touchingTimeline: Boolean = false
    private var scalePercent: Int = 100

    fun scalePercent(): Int {
        return scalePercent
    }

    fun scale(percent: Int) {
        var percent = percent
        percent = AgentChatScale.clamp(percent)
        if (scalePercent == percent) {
            return
        }
        val held: Position? = position()
        scalePercent = percent
        scaleContent()
        queuePosition(held)
    }

    fun scaleContent() {
        AgentChatScale.apply(timeline, scalePercent)
        AgentChatScale.apply(attachmentDrafts, scalePercent)
        if (shellRow != null) {
            AgentChatScale.apply(shellRow, scalePercent)
        }
    }

    private var followNextRows: Boolean = false
    private var followLayout: Boolean = false

    class Position {
        @JvmField val anchor: String?
        @JvmField val offset: Int
        @JvmField val y: Int
        @JvmField val bottom: Boolean

        constructor(anchor: String?, offset: Int, y: Int, bottom: Boolean) {
            this.anchor = anchor
            this.offset = offset
            this.y = y
            this.bottom = bottom
        }
    }

    private var restoring: Position? = null
    private var restoreObserver: ViewTreeObserver? = null
    private var restoreListener: ViewTreeObserver.OnPreDrawListener? = null

    private fun cancelRestore() {
        restoring = null
        if (restoreObserver != null && restoreObserver!!.isAlive() && restoreListener != null) {
            restoreObserver!!.removeOnPreDrawListener(restoreListener)
        }
        restoreObserver = null
        restoreListener = null
    }

    fun position(): Position? {
        if (restoring != null) {
            return restoring
        }
        var anchor: String? = null
        var offset = 0
        var i = 1
        while (i < timeline.getChildCount()) {
            val v: View = timeline.getChildAt(i)
            if (v.getBottom() > scroll.getScrollY()) {
                for (e in views.entries) {
                    if (e.value === v) {
                        anchor = e.key
                        offset = v.getTop() - scroll.getScrollY()
                        break
                    }
                }
                if (anchor != null) {
                    break
                }
            }
            i++
        }
        return Position(anchor, offset, scroll.getScrollY(), timeline.getHeight() - scroll.getHeight() - scroll.getScrollY() < dp(48))
    }

    fun restorePosition(p: Position?) {
        followNextRows = false
        followLayout = false
        queuePosition(p)
    }

    private fun queuePosition(p: Position?) {
        cancelRestore()
        restoring = p
        if (p == null) {
            return
        }
        restoreObserver = scroll.getViewTreeObserver()
        restoreListener = ViewTreeObserver.OnPreDrawListener {
            restorePositionAfterLayout()
            return@OnPreDrawListener true
        }
        restoreObserver!!.addOnPreDrawListener(restoreListener)
        // Fallback for hosts laid out off screen. Pre-draw settles the visible frame
        // before any new row can be painted at the old scroll position.
        scroll.post(Runnable(this::restorePositionAfterLayout))
    }

    private fun restorePositionAfterLayout() {
        if (restoring == null || timeline.getWidth() == 0 || scroll.getHeight() == 0 || timeline.isLayoutRequested() || scroll.isLayoutRequested()) {
            return
        }
        val p: Position? = restoring
        cancelRestore()
        val anchor: View? = if (p!!.anchor == null) null else views.get(p.anchor)
        scroll.scrollTo(0, if (p.bottom) Math.max(0, timeline.getHeight() - scroll.getHeight()) else if (anchor == null) p.y else anchor.getTop() - p.offset)
    }

    // ScrollView.fullScroll also moves keyboard focus to a transcript child.
    // Streaming must only move the viewport, leaving both composers and IME composition alone.
    fun bottomAfterLayout() {
        queuePosition(Position(null, 0, 0, true))
    }

    fun followAfterSend() {
        followNextRows = true
        followLayout = true
        bottomAfterLayout()
    }

    constructor(c: Context, open: Consumer<JSONObject>, loadOlder: Runnable, sendShell: Runnable, terminal: Runnable, interrupt: Runnable) : super(c) {
        this.open = open
        setOrientation(LinearLayout.VERTICAL)
        setBackgroundColor(Color.BLACK)
        status = label("", 12)
        status.setVisibility(View.GONE)
        addView(status)
        scroll = object : ScrollView(c) {
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                // Selectable TextViews may consume DOWN before a parent's OnTouchListener.
                // Observe dispatch without intercepting selection or the ScrollView gesture.
                touchingTimeline = e.getActionMasked() != MotionEvent.ACTION_UP && e.getActionMasked() != MotionEvent.ACTION_CANCEL
                cancelRestore()
                followNextRows = false
                followLayout = false
                return super.dispatchTouchEvent(e)
            }
        }
        scroll.setFillViewport(true)
        scroll.addOnLayoutChangeListener(View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (restoring == null && followLayout) {
                bottomAfterLayout()
            }
        })
        scroll.setSmoothScrollingEnabled(false)
        timeline = LinearLayout(c)
        timeline.setOrientation(LinearLayout.VERTICAL)
        timeline.addOnLayoutChangeListener(View.OnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (followLayout) {
                followLayout = false
                if (restoring == null) {
                    bottomAfterLayout()
                }
            }
        })
        timeline.setPadding(dp(12), dp(4), dp(12), dp(16))
        scroll.addView(timeline)
        older = pill(tr("Предыдущие сообщения", "Earlier messages"), loadOlder)
        timeline.addView(older)
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.0f))
        requests = LinearLayout(c)
        requests.setOrientation(LinearLayout.VERTICAL)
        requests.setPadding(dp(12), 0, dp(12), 0)
        timeline.addView(requests, LinearLayout.LayoutParams(-1, -2))
        connection = label("", 13)
        connection.setVisibility(View.GONE)
        requests.addView(connection)
        attachmentDrafts = LinearLayout(c)
        attachmentDrafts.setOrientation(LinearLayout.VERTICAL)
        attachmentDrafts.setVisibility(View.GONE)
        addView(attachmentDrafts, LinearLayout.LayoutParams(-1, -2))
        val shell = LinearLayout(c)
        shellRow = shell
        shell.setGravity(Gravity.CENTER_VERTICAL)
        shell.setPadding(dp(8), dp(4), dp(8), dp(4))
        shellLabel = label("Shell", 12)
        shell.addView(shellLabel)
        folder = WorkspaceIconButton(c)
        folder.setIcon(c.getDrawable(R.drawable.workspace_folder), dp(24))
        folder.setTextColor(Pal.ACCENT)
        folder.setContentDescription(tr("Прикрепить файл", "Attach file"))
        folder.setVisibility(View.GONE)
        shell.addView(folder, LinearLayout.LayoutParams(dp(44), dp(44)))
        shellInput = EditText(c)
        shellInput.setTextColor(Pal.TEXT2)
        shellInput.setHintTextColor(Pal.MUTED)
        shellInput.setTextSize(13.0f)
        shellInput.setMinLines(1)
        shellInput.setMaxLines(3)
        shellInput.setHint(tr("Команда без модели…", "Command without model…"))
        shellInput.setTypeface(Typeface.MONOSPACE)
        shellInput.setSaveEnabled(false)
        shellInput.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        shellInput.setImeOptions(EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        shell.addView(shellInput, LinearLayout.LayoutParams(0, -2, 1.0f))
        val send = WorkspaceIconButton(c)
        shellSend = send
        send.setIcon(c.getDrawable(R.drawable.workspace_play), dp(24))
        send.setTextColor(Pal.ACCENT)
        send.setContentDescription(tr("Выполнить shell-команду", "Run shell command"))
        send.setOnClickListener(View.OnClickListener { v -> sendShell.run() })
        shell.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(shell, LinearLayout.LayoutParams(-1, -2))
        shellEnabled(false)
        keysHost = LinearLayout(c)
        keysHost.setOrientation(LinearLayout.VERTICAL)
        keysHost.setVisibility(View.GONE)
        addView(keysHost, LinearLayout.LayoutParams(-1, -2))
    }

    fun attachmentPicker(enabled: Boolean) {
        folder.setVisibility(if (enabled) View.VISIBLE else View.GONE)
        shellLabel.setVisibility(if (enabled) View.GONE else View.VISIBLE)
    }

    fun connection(message: String) {
        connection(message, null)
    }

    fun connection(message: String, retry: Runnable?) {
        connection.setOnClickListener(if (retry == null) null else View.OnClickListener { v -> retry.run() })
        connection.setClickable(retry != null)
        if (connection.getText().toString().equals(message) && connection.getVisibility() == (if (message.isEmpty()) View.GONE else View.VISIBLE)) {
            return
        }
        val bottom: Boolean = timeline.getHeight() - scroll.getHeight() - scroll.getScrollY() < dp(48)
        connection.setText(message)
        connection.setVisibility(if (message.isEmpty()) View.GONE else View.VISIBLE)
        if (bottom) {
            followLayout = true
            bottomAfterLayout()
        }
    }

    fun clearRequests() {
        requests.removeAllViews()
        requests.addView(connection)
    }

    fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(getContext())!!.equals("ru")) ru else en
    }

    fun dp(n: Int): Int {
        return Math.round(n * getResources().getDisplayMetrics().density)
    }

    fun label(s: String, size: Int): TextView {
        val t = TextView(getContext())
        t.setText(s)
        t.setTextSize(size.toFloat())
        t.setTextColor(Pal.TEXT2)
        t.setPadding(dp(10), dp(8), dp(10), dp(8))
        return t
    }

    fun pill(s: String, action: Runnable): TextView {
        val t: TextView = label(s, 12)
        val bg = GradientDrawable()
        bg.setColor(Pal.CARD2)
        bg.setCornerRadius(dp(22).toFloat())
        bg.setStroke(dp(1), Pal.TINT_BORDER)
        t.setBackground(bg)
        t.setOnClickListener(View.OnClickListener { v -> action.run() })
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(dp(3), dp(5), dp(3), dp(5))
        t.setLayoutParams(lp)
        t.setMinHeight(dp(36))
        return t
    }

    fun olderState(loading: Boolean, error: String?) {
        older.setEnabled(!loading)
        older.setText(if (loading) tr("Загрузка сообщений…", "Loading messages…") else if (error != null) error else tr("Предыдущие сообщения", "Earlier messages"))
    }

    fun olderRows(rows: MutableList<JSONObject?>, hasOlder: Boolean) {
        val fits: Boolean = timeline.getHeight() <= scroll.getHeight()
        val held: Position? = position()
        rows(rows, hasOlder)
        restorePosition(if (fits) Position(null, 0, 0, false) else Position(held!!.anchor, held.offset, held.y, false))
    }

    fun clear() {
        olderState(false, null)
        cancelRestore()
        touchingTimeline = false
        followNextRows = false
        followLayout = false
        views.clear()
        values.clear()
        data.clear()
        timeline.removeViews(1, timeline.getChildCount() - 1)
        clearRequests()
        timeline.addView(requests)
        scroll.scrollTo(0, 0)
    }

    fun rows(rows: MutableList<JSONObject?>, hasOlder: Boolean) {
        var rows = rows
        if (codexActions) {
            rows = CodexActionTimeline.group(rows, groupAllTools)
        }
        val held: Position? = position()
        val bottom: Boolean = !touchingTimeline && (followNextRows || held!!.bottom)
        var changed = false
        val present: MutableSet<String> = HashSet<String>()
        var index = 1
        for (r in rows) {
            val key: String = r!!.optString("key")
            present.add(key)
            data.put(key, r)
            val value: String = r.toString() + (if (codexActions) WorkspaceLanguage.code(getContext()) else "")
            if (r.optString("kind").equals("actionGroup")) {
                val existing: View? = views.get(key)
                val card: CodexActionsView = if (existing is CodexActionsView) existing as CodexActionsView else CodexActionsView(this, open)
                views.put(key, card)
                if (!value.equals(values.get(key))) {
                    changed = true
                    values.put(key, value)
                    AgentChatScale.prepare(card)
                    card.update(r)
                }
                if (card.getParent() == null) {
                    changed = true
                    timeline.addView(card, index)
                } else if (timeline.indexOfChild(card) != index) {
                    changed = true
                    timeline.removeView(card)
                    timeline.addView(card, index)
                }
                index++
                continue
            }
            if (r.optString("kind").equals("attachment")) {
                val existing: View? = views.get(key)
                val card: LinearLayout = if (existing is LinearLayout) existing as LinearLayout else LinearLayout(getContext())
                if (!value.equals(values.get(key))) {
                    changed = true
                    values.put(key, value)
                    AgentChatScale.prepare(card)
                    card.removeAllViews()
                    card.setOrientation(LinearLayout.VERTICAL)
                    val a: JSONObject? = r.optJSONObject("attachment")
                    if (a != null && (a.optString("type").equals("apk") || a.optString("type").equals("binary") || a.optString("type").equals("file"))) {
                        val isApk: Boolean = a.optString("type").equals("apk")
                        card.setOrientation(LinearLayout.HORIZONTAL)
                        card.setGravity(Gravity.CENTER_VERTICAL)
                        val badge = LinearLayout(getContext())
                        badge.setOrientation(LinearLayout.VERTICAL)
                        badge.setGravity(Gravity.CENTER)
                        val icon = ImageView(getContext())
                        icon.setImageResource(if (isApk) R.drawable.workspace_apk else R.drawable.workspace_server_file)
                        badge.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
                        val apk: TextView = label(if (isApk) "APK" else ChatAttachments.badge(a.optString("name")), 11)
                        apk.setGravity(Gravity.CENTER)
                        badge.addView(apk)
                        card.addView(badge, LinearLayout.LayoutParams(dp(72), -2))
                        val info = LinearLayout(getContext())
                        info.setOrientation(LinearLayout.VERTICAL)
                        val filename: TextView = label(a.optString("name", "APK"), 13)
                        filename.setMaxLines(2)
                        info.addView(filename)
                        val detail: TextView = label(Formatter.formatFileSize(getContext(), a.optLong("bytes")) + (if (isApk) " · SHA-256: " + tr("подготовка…", "preparing…") else ""), 11)
                        info.addView(detail)
                        card.addView(info, LinearLayout.LayoutParams(0, -2, 1.0f))
                        if (isApk) {
                            apkDetails.accept(a, detail)
                        }
                    } else if (a != null) {
                        val title: TextView = label(a.optString("name", tr("Вложение", "Attachment")), 13)
                        card.addView(title)
                        val type: String = a.optString("type")
                        if (type.equals("image") || type.equals("video")) {
                            val image = ImageView(getContext())
                            image.setScaleType(ImageView.ScaleType.FIT_CENTER)
                            image.setBackgroundColor(0xff111917.toInt())
                            image.setContentDescription(title.getText())
                            card.addView(image, LinearLayout.LayoutParams(-1, dp(160)))
                            thumbnail.accept(a, image)
                        }
                        val hint: TextView = label(if (type.equals("video")) tr("Видео · выбранные кадры, без звука · открыть", "Video · sampled frames, no audio · open") else tr("Нажмите, чтобы открыть", "Tap to open"), 11)
                        card.addView(hint)
                    }
                    val bg = GradientDrawable()
                    bg.setColor(Pal.CARD2)
                    bg.setCornerRadius(dp(14).toFloat())
                    bg.setStroke(dp(1), Pal.TINT_BORDER)
                    card.setBackground(bg)
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.setMargins(0, dp(6), 0, dp(8))
                    card.setLayoutParams(lp)
                    val k: String = key
                    card.setOnClickListener(View.OnClickListener { v ->
                        val row: JSONObject? = data.get(k)
                        if (row != null) {
                            open.accept(row)
                        }
                    })
                }
                views.put(key, card)
                if (card.getParent() == null) {
                    changed = true
                    timeline.addView(card, index)
                } else if (timeline.indexOfChild(card) != index) {
                    changed = true
                    timeline.removeView(card)
                    timeline.addView(card, index)
                }
                index++
                continue
            }
            if (r.optString("kind").equals("text") && AgentMarkdown.rich(r.optString("body"))) {
                if (markdown == null) {
                    markdown = AgentMarkdown.create(getContext())
                }
                val existing: View? = views.get(key)
                val block: AgentMarkdownView = if (existing is AgentMarkdownView) existing as AgentMarkdownView else AgentMarkdownView(getContext(), markdown)
                if (!value.equals(values.get(key)) || existing !== block) {
                    changed = true
                    values.put(key, value)
                    AgentChatScale.prepare(block)
                    val k: String = key
                    block.update(assistant, r.optString("body"), if (r.optBoolean("more")) Runnable {
                        val row: JSONObject? = data.get(k)
                        if (row != null) {
                            open.accept(row)
                        }
                    } else null)
                }
                if (existing != null && existing !== block) {
                    timeline.removeView(existing)
                }
                views.put(key, block)
                if (block.getParent() == null) {
                    changed = true
                    timeline.addView(block, index)
                } else if (timeline.indexOfChild(block) != index) {
                    changed = true
                    timeline.removeView(block)
                    timeline.addView(block, index)
                }
                index++
                continue
            }
            val previous: View? = views.get(key)
            if (previous != null && !(previous is TextView)) {
                timeline.removeView(previous)
                views.remove(key)
                values.remove(key)
            }
            var t: TextView? = views.get(key) as TextView?
            if (t == null) {
                t = label("", 14)
                views.put(key, t)
            }
            if (!value.equals(values.get(key))) {
                changed = true
                values.put(key, value)
                AgentChatScale.prepare(t)
                val kind: String = r.optString("kind")
                val error: Boolean = kind.equals("error")
                val chip: Boolean = kind.equals("reasoning") || kind.equals("tool") || kind.equals("receipt")
                val caption: String = if (kind.equals("reasoning")) tr("Рассуждение ▾", "Thinking ▾") else if (kind.equals("receipt")) receiptTitle(r) else (if (codexActions) CodexActionLabels.title(r, WorkspaceLanguage.code(getContext())!!.equals("ru")) else r.optString("title")) + " ▾"
                val body: String = r.optString("body")
                val shown: String = if (AgentChatErrors.nativeDiagnosticHint(body)) tr("Подробности доступны по нажатию на сообщение об ошибке.", "Tap the error message for details.") else body
                t.setText(if (error) tr("Ошибка · ", "Error · ") + assistant + "\n" + AgentChatErrors.explain(body, WorkspaceLanguage.code(getContext())!!.equals("ru")) + tr("\nПодробности ▾", "\nDetails ▾") else if (chip) caption else (if (kind.equals("user")) tr("Вы: ", "You: ") else if (kind.equals("text")) assistant + ": " else "") + shown + (if (r.optBoolean("more")) tr("\nНажмите для полного текста…", "\nTap for full text…") else ""))
                t.setTextIsSelectable(!chip && !error)
                if (kind.equals("text")) {
                    if (markdown == null) {
                        markdown = AgentMarkdown.create(getContext())
                    }
                    val formatted = SpannableStringBuilder(assistant + ": ")
                    formatted.append(markdown!!.toMarkdown(AgentMarkdown.checkboxes(shown)))
                    if (r.optBoolean("more")) {
                        formatted.append(tr("\nНажмите для полного текста…", "\nTap for full text…"))
                    }
                    markdown!!.setParsedMarkdown(t, formatted)
                }
                val k: String = key
                t.setOnClickListener(if (error || chip || r.optBoolean("more")) View.OnClickListener { v ->
                    val row: JSONObject? = data.get(k)
                    if (row != null) {
                        open.accept(row)
                    }
                } else null)
                t.setTextSize((if (chip) 12 else 14).toFloat())
                t.setTypeface(if (chip) Typeface.DEFAULT else Typeface.DEFAULT)
                val bg = GradientDrawable()
                bg.setColor(if (error) 0xff241416.toInt() else if (chip) Pal.CARD2 else Color.BLACK)
                bg.setCornerRadius((if (error) dp(12) else if (chip) dp(22) else 0).toFloat())
                bg.setStroke(dp(1), if (error) 0xff704343.toInt() else if (chip) Pal.TINT_BORDER else if (kind.equals("user")) 0xff43776b.toInt() else Color.BLACK)
                t.setBackground(bg)
                val lp = LinearLayout.LayoutParams(if (chip) -2 else -1, -2)
                lp.setMargins(0, dp(6), 0, dp(if (chip) 3 else 10))
                lp.gravity = Gravity.START
                t.setLayoutParams(lp)
            }
            if (t.getParent() == null) {
                changed = true
                timeline.addView(t, index)
            } else if (timeline.indexOfChild(t) != index) {
                changed = true
                timeline.removeView(t)
                timeline.addView(t, index)
            }
            index++
        }
        for (key in ArrayList<String>(views.keys)) {
            if (!present.contains(key)) {
                changed = true
                timeline.removeView(views.remove(key))
                values.remove(key)
                data.remove(key)
            }
        }
        older.setVisibility(if (hasOlder) View.VISIBLE else View.GONE)
        scaleContent()
        if (changed) {
            if (bottom) {
                followLayout = true
            }
            followNextRows = false
            queuePosition(if (bottom) Position(null, 0, 0, true) else Position(held!!.anchor, held.offset, held.y, false))
        }
    }

    private fun receiptTitle(r: JSONObject?): String {
        val title: String = r!!.optString("title")
        return if (title.contains("Denied")) tr("Отклонено ▾", "Denied ▾") else if (title.contains("project")) tr("Разрешено для проекта ▾", "Allowed for project ▾") else tr("Разрешено разово ▾", "Allowed once ▾")
    }
}
