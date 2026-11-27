package com.termux.app.workspace

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Guarded adapter for the CLI's own picker; never submits a model turn.*/
class CodexModelSelection {
    interface Terminal {
        fun screen(): String

        fun emptyPrompt(): Boolean

        fun menuOpen(): Boolean

        fun write(keys: String)
    }

    private val model: String
    private val display: String
    private val effort: String
    private var phase: Int = 0
    private var moves: Int = 0
    private var singleEffort: Boolean = false
    private var baseline: String? = null
    private var deadline: Long = 0L
    @JvmField var error: String? = ""
    @JvmField var finished: Boolean = false
    @JvmField var confirmed: Boolean = false

    constructor(model: String, display: String, effort: String) {
        this.model = model
        this.display = display
        this.effort = effort
    }

    fun singleEffort(value: Boolean) {
        singleEffort = value
    }

    private fun stop(reason: String?) {
        error = reason
        finished = true
    }

    private fun position(terminal: Terminal, screen: String, label: String): Boolean {
        val row: IntArray = row(screen, label)
        if (row[1] < 1) {
            stop(WorkspaceText.tr("Не удалось определить выделение в меню Codex. Завершите выбор в терминале."))
            return false
        }
        if (row[0] < 1) {
            if (++moves > 20) {
                stop(WorkspaceText.tr("Пункт не найден в меню Codex. Завершите выбор в терминале."))
                return false
            }
            terminal.write("\u001b[B")
            return false
        }
        if (row[0] == row[1]) {
            return true
        }
        if (++moves > 20) {
            stop(WorkspaceText.tr("Не удалось выделить пункт Codex. Завершите выбор в терминале."))
            return false
        }
        terminal.write(if (row[0] > row[1]) "\u001b[B" else "\u001b[A")
        return false
    }

    fun step(terminal: Terminal, now: Long) {
        if (finished) {
            return
        }
        val screen: String = terminal.screen()
        if (phase == 0) {
            baseline = acknowledgement(screen)
            deadline = now + 15000
            if (terminal.menuOpen() && screen.contains("Select Model and Effort")) {
                phase = 1
                return
            }
            if (!terminal.emptyPrompt()) {
                stop(WorkspaceText.tr("Ввод Codex занят или открыто другое меню. Освободите его и повторите выбор."))
                return
            }
            terminal.write("/model\r")
            phase = 1
            return
        }
        if (now > deadline) {
            stop(WorkspaceText.tr("Codex не подтвердил смену. Проверьте меню терминала; настройки не считаются применёнными."))
            return
        }
        if (phase == 1) {
            if (!terminal.menuOpen() || !screen.contains("Select Model and Effort")) {
                return
            }
            if (position(terminal, screen, display)) {
                if (singleEffort) {
                    if (screen.contains("s session")) {
                        terminal.write("s")
                        phase = 4
                    } else {
                        stop(WorkspaceText.tr("Codex не предлагает применение только к сессии для этой модели. Завершите выбор в терминале."))
                    }
                } else {
                    terminal.write("\r")
                    phase = 2
                    moves = 0
                }
            }
        } else if (phase == 2) {
            if (!terminal.menuOpen() || !screen.contains("Select Reasoning Level") && !screen.contains("Advanced Reasoning")) {
                return
            }
            // The effort picker must belong to the selected model.
            if (!normalize(screen).contains(normalize(display))) {
                stop(WorkspaceText.tr("Открылось меню другой модели. Завершите выбор в терминале."))
                return
            }
            val advanced: Boolean = effort.equals("max") || effort.equals("ultra")
            val direct: Boolean = row(screen, effortLabel(effort))[0] > 0
            val label: String = if (advanced && !direct) "More reasoning" else effortLabel(effort)
            if (position(terminal, screen, label)) {
                if (advanced && !direct) {
                    terminal.write("\r")
                    phase = 3
                    moves = 0
                } else if (screen.contains("s session")) {
                    terminal.write("s")
                    phase = 4
                } else {
                    stop(WorkspaceText.tr("Эта версия Codex не предлагает применение только к сессии. Завершите выбор в терминале."))
                }
            }
        } else if (phase == 3) {
            if (!terminal.menuOpen() || row(screen, effortLabel(effort))[0] < 1) {
                return
            }
            if (position(terminal, screen, effortLabel(effort))) {
                if (screen.contains("s session")) {
                    terminal.write("s")
                    phase = 4
                } else {
                    stop(WorkspaceText.tr("Для этого уровня Codex требует отдельное подтверждение. Завершите выбор в терминале."))
                }
            }
        } else {
            val changed: String? = acknowledgement(screen)
            val wanted: String = "Model changed to " + model + " " + effort
            if (!terminal.menuOpen() && changed!!.equals(wanted) && !changed.equals(baseline)) {
                confirmed = true
                finished = true
            }
        }
    }

    companion object {
        @JvmStatic
        fun normalize(value: String): String {
            return ((value as java.lang.String).toLowerCase(Locale.ROOT) as java.lang.String).replaceAll("[^a-z0-9]", "")
        }

        @JvmStatic
        fun effortLabel(value: String): String {
            when (value) {
                "none" -> {
                    return "None"
                }
                "minimal" -> {
                    return "Minimal"
                }
                "low" -> {
                    return "Low"
                }
                "medium" -> {
                    return "Medium"
                }
                "high" -> {
                    return "High"
                }
                "xhigh" -> {
                    return "Extra high"
                }
                "max" -> {
                    return "Max"
                }
                "ultra" -> {
                    return "Ultra"
                }
                else -> {
                    return ""
                }
            }
        }

        private val ROW: Pattern = Pattern.compile("(?m)^\\s*([›>]?)\\s*(\\d+)\\.\\s*([^\\r\\n]+)")

        @JvmStatic
        fun row(screen: String, label: String): IntArray {
            val matcher: Matcher = ROW.matcher(screen)
            var target = -1
            var selected = -1
            val wanted: String = normalize(label)
            while (matcher.find()) {
                val index: Int = Integer.parseInt(matcher.group(2))
                if (!matcher.group(1)!!.isEmpty()) {
                    selected = index
                }
                val text: String = ((matcher.group(3)!! as java.lang.String).split("\\s{2,}|\\(|\\u2026|\\.\\.\\.", 2)[0] as java.lang.String).trim()
                if (normalize(text).equals(wanted)) {
                    if (target != -1) {
                        return intArrayOf(-1, -1)
                    }
                    target = index
                }
            }
            return intArrayOf(target, selected)
        }

        @JvmStatic
        fun acknowledgement(screen: String): String? {
            val m: Matcher = Pattern.compile("(?m)Model changed to ([A-Za-z0-9._-]+)(?:\\s+(none|minimal|low|medium|high|xhigh|max|ultra))?\\b").matcher(screen)
            var result: String? = ""
            while (m.find()) {
                result = m.group()
            }
            return result
        }
    }
}
