package com.termux.app.workspace

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject

/** One stable, expandable card for a consecutive run of structured agent actions.*/
class CodexActionsView : LinearLayout {
    @JvmField val heading: TextView
    @JvmField val summary: TextView
    @JvmField val steps: LinearLayout
    private val owner: AgentChatView
    private val open: Consumer<JSONObject>
    private val entries: MutableMap<String, TextView?> = HashMap<String, TextView?>()
    private val data: MutableMap<String, JSONObject> = HashMap<String, JSONObject>()
    private var expanded: Boolean = false
    private var skillName: String = ""
    private var count: Int = 0
    private var active: Int = 0
    private var errors: Int = 0
    private var failures: Int = 0
    private var declined: Int = 0
    private var interrupted: Int = 0
    private var onlyShell: Boolean = false

    constructor(owner: AgentChatView, open: Consumer<JSONObject>) : super(owner.getContext()) {
        this.owner = owner
        this.open = open
        setOrientation(LinearLayout.VERTICAL)
        setPadding(owner.dp(4), owner.dp(3), owner.dp(4), owner.dp(4))
        val background = GradientDrawable()
        background.setColor(Pal.PANEL)
        background.setCornerRadius(owner.dp(12).toFloat())
        background.setStroke(owner.dp(1), Pal.TINT_BORDER)
        setBackground(background)
        heading = owner.label("", 13)
        heading.setMinHeight(owner.dp(36))
        heading.setPadding(owner.dp(10), owner.dp(5), owner.dp(10), owner.dp(5))
        heading.setGravity(Gravity.CENTER_VERTICAL)
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        heading.setOnClickListener(View.OnClickListener { v ->
            expanded = !expanded
            refreshHeading()
        })
        addView(heading, LinearLayout.LayoutParams(-1, -2))
        summary = owner.label("", 12)
        summary.setTextColor(Pal.MUTED)
        summary.setMaxLines(2)
        summary.setPadding(owner.dp(10), 0, owner.dp(10), owner.dp(4))
        summary.setOnClickListener(View.OnClickListener { v -> heading.performClick() })
        addView(summary, LinearLayout.LayoutParams(-1, -2))
        steps = LinearLayout(getContext())
        steps.setOrientation(LinearLayout.VERTICAL)
        steps.setVisibility(View.GONE)
        addView(steps, LinearLayout.LayoutParams(-1, -2))
        val layout = LinearLayout.LayoutParams(-1, -2)
        layout.setMargins(0, owner.dp(4), 0, owner.dp(6))
        setLayoutParams(layout)
    }

    fun update(group: JSONObject?) {
        val rows: JSONArray? = group!!.optJSONArray("steps")
        if (rows == null) {
            return
        }
        skillName = group.optString("skillName")
        count = rows.length()
        onlyShell = true
        active = 0
        errors = 0
        failures = 0
        declined = 0
        interrupted = 0
        var current = ""
        val present: MutableSet<String> = HashSet<String>()
        var i = 0
        while (i < rows.length()) {
            val row: JSONObject? = rows.optJSONObject(i)
            if (row == null) {
                i++
                continue
            }
            onlyShell = onlyShell and CodexActionTimeline.shell(row)
            if (CodexActionTimeline.running(row)) {
                active++
                if (current.isEmpty()) {
                    current = caption(row)
                }
            }
            if (CodexActionTimeline.failed(row)) {
                errors++
                val status: String = CodexActionTimeline.status(row)
                if (status.equals("declined")) {
                    declined++
                } else if (status.equals("aborted") || status.equals("cancelled") || status.equals("canceled")) {
                    interrupted++
                } else {
                    failures++
                }
            }
            val key: String = row.optString("key")
            present.add(key)
            data.put(key, row)
            var item: TextView? = entries.get(key)
            if (item == null) {
                item = owner.label("", 12)
                item.setMinHeight(owner.dp(48))
                item.setMaxLines(4)
                entries.put(key, item)
                val id: String = key
                item.setOnClickListener(View.OnClickListener { v ->
                    val original: JSONObject? = data.get(id)
                    if (original != null) {
                        open.accept(original)
                    }
                })
            }
            val state: String = state(row)
            val command: String = caption(row)
            val caption: String = (if (CodexActionTimeline.failed(row)) "! " else if (CodexActionTimeline.running(row)) "● " else if (CodexActionTimeline.status(row).equals("completed")) "✓ " else "○ ") + command + "\n    " + state
            if (!item.getText().toString().equals(caption)) {
                item.setText(caption)
            }
            item.setTextColor(if (CodexActionTimeline.failed(row)) 0xffffb08f.toInt() else Pal.TEXT2)
            item.setContentDescription(command + ". " + state + ". " + owner.tr("Открыть действие и подробности", "Open action and details"))
            if (item.getParent() == null) {
                steps.addView(item, i)
            } else if (steps.indexOfChild(item) != i) {
                steps.removeView(item)
                steps.addView(item, i)
            }
            i++
        }
        for (key in HashSet<String>(entries.keys)) {
            if (!present.contains(key)) {
                steps.removeView(entries.remove(key))
                data.remove(key)
            }
        }
        var errorText: String = if (failures == 1) owner.tr(if (onlyShell) "Одна команда завершилась с ошибкой" else "Одно действие завершилось с ошибкой", if (onlyShell) "One command failed" else "One action failed") else if (failures > 1) owner.tr(if (onlyShell) "Команд с ошибкой: " else "Действий с ошибкой: ", if (onlyShell) "Failed commands: " else "Failed actions: ") + failures else ""
        if (declined > 0) {
            errorText += (if (errorText.isEmpty()) "" else " · ") + owner.tr("Отклонено: ", "Declined: ") + declined
        }
        if (interrupted > 0) {
            errorText += (if (errorText.isEmpty()) "" else " · ") + owner.tr("Прервано: ", "Interrupted: ") + interrupted
        }
        val caption: String = if (active > 0) owner.tr("Сейчас: ", "Current: ") + current + (if (errorText.isEmpty()) "" else " · " + errorText) else errorText
        if (!summary.getText().toString().equals(caption)) {
            summary.setText(caption)
        }
        summary.setVisibility(if (caption.isEmpty()) View.GONE else View.VISIBLE)
        refreshHeading()
    }

    private fun caption(row: JSONObject): String {
        if (CodexActionTimeline.shell(row) && !row.optString("skillSource").equals("read")) {
            return CodexActionTimeline.command(row)
        }
        return CodexActionLabels.title(row, WorkspaceLanguage.code(getContext())!!.equals("ru"))
    }

    private fun state(row: JSONObject): String {
        val status: String = CodexActionTimeline.status(row)
        var value: String = if (status.equals("attached")) owner.tr("Инструкции переданы агенту", "Instructions supplied to the agent") else if (status.equals("declined")) owner.tr("Отклонено", "Declined") else if (status.equals("aborted") || status.equals("cancelled") || status.equals("canceled")) owner.tr("Прервано", "Interrupted") else if (CodexActionTimeline.failed(row)) owner.tr("Ошибка", "Failed") else if (CodexActionTimeline.running(row)) owner.tr("В работе", "Running") else if (status.equals("awaiting_approval")) owner.tr("Ожидает разрешения", "Awaiting approval") else if (status.equals("pending") || status.equals("scheduled")) owner.tr("В очереди", "Queued") else if (status.equals("completed")) owner.tr("Завершено", "Completed") else owner.tr("Статус неизвестен", "Status unknown")
        if (row.has("exitCode") && !row.isNull("exitCode")) {
            value += owner.tr(" · код ", " · exit ") + row.optInt("exitCode")
        }
        if (row.has("durationMs") && !row.isNull("durationMs") && row.optLong("durationMs", (-1).toLong()) >= 0) {
            val ms: Long = row.optLong("durationMs")
            value += " · " + (if (ms < 1000) ms.toString() + owner.tr(" мс", " ms") else (java.lang.String.format(Locale.ROOT, "%.1f", ms / 1000.0) as java.lang.String).replace(".", owner.tr(",", ".")) + owner.tr(" с", " s"))
        }
        return value
    }

    private fun actionWord(): String {
        val ru: String = if (count % 100 >= 11 && count % 100 <= 14) " действий" else if (count % 10 == 1) " действие" else if (count % 10 >= 2 && count % 10 <= 4) " действия" else " действий"
        return owner.tr(ru, if (count == 1) " action" else " actions")
    }

    private fun refreshHeading() {
        val caption: String = (if (!skillName.isEmpty()) owner.tr("Навык: ", "Skill: ") + skillName else if (active > 0) owner.tr("В работе", "Running") else owner.tr("Хронология", "Timeline")) + " · " + count + actionWord() + (if (expanded) " ▴" else " ▾")
        if (!heading.getText().toString().equals(caption)) {
            heading.setText(caption)
        }
        val layout: LinearLayout.LayoutParams = getLayoutParams() as LinearLayout.LayoutParams
        val width: Int = if (!skillName.isEmpty() && !expanded) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        if (layout.width != width) {
            layout.width = width
            setLayoutParams(layout)
        }
        heading.setTextColor(if (errors > 0) 0xffffb08f.toInt() else Pal.ACCENT)
        steps.setVisibility(if (expanded) View.VISIBLE else View.GONE)
        heading.setContentDescription(caption + ". " + owner.tr(if (expanded) "Свернуть" else "Развернуть", if (expanded) "Collapse" else "Expand"))
    }
}
