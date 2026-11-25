package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.util.Arrays
import java.util.function.Consumer
import java.util.function.IntConsumer
import org.json.JSONArray
import org.json.JSONObject

/** On-demand reference only. Copy/paste never submits commands to a PTY.*/
class WorkspaceErrorLibraryUi {
    private val activity: TermuxActivity
    private val ru: Boolean
    private val insert: Consumer<String>
    private var library: WorkspaceErrorLibrary? = null
    private var dialog: AlertDialog? = null
    private var results: LinearLayout? = null
    private var count: TextView? = null
    private var search: EditText? = null
    private var env: String = "all"
    private var category: String = "all"
    private var context: String = "all"
    private val categoryButtons: MutableList<TextView> = ArrayList<TextView>()

    private constructor(a: TermuxActivity, insert: Consumer<String>) {
        activity = a
        ru = WorkspaceLanguage.code(a)!!.equals("ru")
        this.insert = insert
    }

    private fun t(a: String, b: String): String {
        return if (ru) a else b
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    private fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    private fun text(value: String, size: Int): TextView {
        val v = TextView(activity)
        v.setText(value)
        v.setTextSize(size.toFloat())
        v.setTextColor(Pal.TEXT)
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        return v
    }

    private fun add(parent: LinearLayout?, child: View?) {
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(6)
        parent!!.addView(child, params)
    }

    private fun action(label: String, click: Runnable): TextView {
        val b: TextView = WorkspaceDialogs.action(activity, label, 0)
        b.setOnClickListener(View.OnClickListener { v -> click.run() })
        return b
    }

    private fun categoryLabel(key: String): String {
        val labels: Array<String> = if (ru) arrayOf<String>("Все", "Пакеты", "Файлы", "Запуск", "Сеть / SSH", "Сборки", "Git", "Окружение", "Python", "Node.js") else arrayOf<String>("All", "Packages", "Files", "Launch", "Network / SSH", "Builds", "Git", "Environment", "Python", "Node.js")
        return labels[Arrays.asList(*WorkspaceErrorLibrary.CATEGORIES).indexOf(key)]
    }

    private fun picker(description: String, labels: Array<String>, change: IntConsumer): Spinner {
        val spinner = Spinner(activity)
        val adapter = ArrayAdapter<String>(activity, R.layout.simple_spinner_dropdown_item, labels)
        spinner.setAdapter(adapter)
        spinner.setContentDescription(description)
        spinner.setMinimumHeight(dp(48))
        spinner.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>) {
            }

            override fun onItemSelected(p: AdapterView<*>, v: View, index: Int, id: Long) {
                change.accept(index)
            }
        })
        return spinner
    }

    private fun open() {
        try {
            library = WorkspaceErrorLibrary.load(activity)
        } catch (e: Exception) {
            WorkspaceDialogs.builder(activity).setTitle(label(activity)).setMessage(t("Не удалось прочитать встроенную базу. Данные окружений не изменены.", "Could not read the bundled library. Environment data is unchanged.")).setPositiveButton("OK", null).show()
            return
        }
        val body: LinearLayout = column()
        body.setPadding(dp(10), 0, dp(10), dp(8))
        body.setFocusableInTouchMode(true)
        val info: TextView = text(t("Офлайн-справочник, не сканер терминала. Решения зависят от версии и среды. Выберите систему и способ запуска.", "Offline reference, not a terminal scanner. Solutions depend on version and environment. Select the OS and launch method."), 12)
        add(body, info)
        search = EditText(activity)
        search!!.setSingleLine(true)
        search!!.setTextColor(Pal.TEXT)
        search!!.setTextSize(14.0f)
        search!!.setHint(t("Текст ошибки или описание проблемы", "Error text or problem description"))
        search!!.setContentDescription(search!!.getHint())
        search!!.setMinimumHeight(dp(48))
        add(body, search)
        add(body, picker(t("Система", "System"), arrayOf<String>(t("Все системы", "All systems"), "Termux", "Ubuntu", "Debian", "Arch Linux", "Alpine Linux", "Fedora", "openSUSE", t("Другие Linux", "Other Linux")), IntConsumer { index ->
            env = WorkspaceErrorLibrary.ENV[index]
            update()
        }))
        add(body, picker(t("Способ запуска", "Launch method"), if (ru) arrayOf<String>("Любой способ запуска", "Внутри proot", "Обычный Linux / VM", "Удалённо по SSH") else arrayOf<String>("Any launch method", "Inside proot", "Regular Linux / VM", "Remote over SSH"), IntConsumer { index ->
            context = WorkspaceErrorLibrary.CONTEXTS[index]
            update()
        }))
        val strip = HorizontalScrollView(activity)
        strip.setHorizontalScrollBarEnabled(false)
        val row = LinearLayout(activity)
        for (key in WorkspaceErrorLibrary.CATEGORIES) {
            val button: TextView = action(categoryLabel(key), Runnable {
                category = key
                update()
            })
            button.setTag(key)
            categoryButtons.add(button)
            val p = LinearLayout.LayoutParams(-2, dp(48))
            p.setMarginEnd(dp(6))
            row.addView(button, p)
        }
        strip.addView(row)
        add(body, strip)
        count = text("", 12)
        add(body, count)
        val scroll = ScrollView(activity)
        results = column()
        scroll.addView(results)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.0f))
        add(body, text(t("База ", "Library ") + library!!.version + t(" · обновляется с приложением. Команды не выполняются автоматически.", " · updated with the app. Commands never run automatically."), 11))
        val frame = FrameLayout(activity)
        frame.addView(body, FrameLayout.LayoutParams(-1, (activity.getResources().getDisplayMetrics().heightPixels * 0.7).toInt()))
        val titleRow = LinearLayout(activity)
        titleRow.setGravity(Gravity.CENTER_VERTICAL)
        titleRow.setPadding(dp(10), dp(8), dp(10), 0)
        val title: TextView = text(label(activity), 18)
        title.setTypeface(null, Typeface.BOLD)
        titleRow.addView(title, LinearLayout.LayoutParams(0, -2, 1.0f))
        val credit: TextView = text(t("Собрано с помощью AI!", "Compiled with AI!"), 11)
        credit.setTextColor(Pal.MUTED)
        credit.setMaxWidth(dp(135))
        credit.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        titleRow.addView(credit, LinearLayout.LayoutParams(-2, -2))
        dialog = WorkspaceDialogs.builder(activity).setCustomTitle(titleRow).setView(frame).setNegativeButton(t("Закрыть", "Close"), null).create()
        search!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {
            }

            override fun onTextChanged(s: CharSequence, st: Int, before: Int, c: Int) {
                update()
            }

            override fun afterTextChanged(e: Editable) {
            }
        })
        dialog!!.setOnShowListener(DialogInterface.OnShowListener { v ->
            if (dialog!!.getWindow() != null) {
                dialog!!.getWindow()!!.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                dialog!!.getWindow()!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            }
            body.requestFocus()
        })
        update()
        dialog!!.show()
    }

    private fun update() {
        if (results == null || search == null) {
            return
        }
        val matches: MutableList<WorkspaceErrorLibrary.Entry> = library!!.search(search!!.getText().toString(), env, category, context)
        results!!.removeAllViews()
        count!!.setText(t("Найдено: ", "Found: ") + matches.size + " / " + library!!.entries.size)
        for (b in categoryButtons) {
            val selected: Boolean = category.equals(b.getTag())
            b.setSelected(selected)
            b.setBackground(WorkspaceDialogs.background(activity, selected))
        }
        if (matches.isEmpty()) {
            add(results, text(t("Совпадений нет. Попробуйте часть сообщения, другой фильтр или все системы. Отсутствие карточки не означает отсутствие ошибки.", "No matches. Try part of the message, another filter or all systems. A missing entry does not mean there is no problem."), 14))
        }
        for (entry in matches) {
            val card: LinearLayout = column()
            card.setBackground(WorkspaceDialogs.background(activity, false))
            val title: TextView = text(entry.text("title", ru), 15)
            title.setTypeface(null, Typeface.BOLD)
            add(card, title)
            add(card, text(entry.text("symptom", ru), 12))
            if (entry.data.has("projectStatus")) {
                add(card, text(projectStatus(entry), 11))
            }
            add(card, text(categoryLabel(entry.category) + " · " + java.lang.String.join(", ", entry.environments), 11))
            card.setOnClickListener(View.OnClickListener { v -> detail(entry) })
            card.setContentDescription(entry.text("title", ru))
            add(results, card)
        }
    }

    private fun section(body: LinearLayout, title: String, value: String) {
        val heading: TextView = text(title, 14)
        heading.setTextColor(Pal.ACCENT)
        add(body, heading)
        val content: TextView = text(value, 14)
        content.setTextIsSelectable(true)
        add(body, content)
    }

    private fun projectStatus(entry: WorkspaceErrorLibrary.Entry): String {
        when (entry.data.optString("projectStatus")) {
            "open" -> {
                return t("Открытая проблема: готового решения нет", "Open issue: no confirmed fix")
            }
            "verified-code" -> {
                return t("Проверено кодом/тестами; проверка устройства отдельно", "Code/tests checked; device verification separate")
            }
            "resolved" -> {
                return t("Исправление подтверждено для описанного случая", "Fix confirmed for the described case")
            }
            else -> {
                return t("Разбор и условия применения", "Explanation and conditions")
            }
        }
    }

    private fun detail(e: WorkspaceErrorLibrary.Entry) {
        val body: LinearLayout = column()
        body.setPadding(dp(8), 0, dp(8), dp(8))
        section(body, t("Применимость", "Applies to"), java.lang.String.join(", ", e.environments) + (if (e.context.equals("proot")) " · proot" else "") + "\n" + e.text("symptom", ru))
        if (e.data.has("projectStatus")) {
            section(body, t("Опыт разработки Termux Job’s", "Termux Job’s development experience"), projectStatus(e) + "\n" + e.text("projectEvidence", ru))
        }
        section(body, t("Сначала проверить", "Check first"), e.text("check", ru))
        section(body, if (e.data.optString("projectStatus").equals("open")) t("Диагностика и временные меры", "Diagnostics and temporary measures") else t("Возможное решение", "Possible solution"), e.text("solution", ru))
        section(body, t("Условия и ограничения", "Conditions and limitations"), e.text("caution", ru))
        if (!e.command.isEmpty()) {
            section(body, t("Команды для проверки (не исправление)", "Diagnostic commands (not a fix)"), e.command)
            add(body, action(t("Скопировать команду", "Copy command"), Runnable {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostic command", e.command))
            }))
        }
        add(body, text(t("Проверено по источникам: ", "Sources reviewed: ") + e.data.optString("reviewed") + t(". Это не подтверждение на вашем устройстве.", ". This is not device verification."), 11))
        val sources: JSONArray = e.data.optJSONArray("sources")
        var i = 0
        while (i < sources.length()) {
            val s: JSONObject = sources.optJSONObject(i)
            val url: String = s.optString("url")
            val name: String = (if (s.optString("kind").equals("project")) t("Проект Termux Job’s / отчёты", "Termux Job’s project / reports") else if (s.optString("kind").equals("official")) t("Документация / автор проекта", "Documentation / project author") else t("Обсуждение пользователей", "Community discussion")) + " · " + Uri.parse(url).getHost()
            add(body, action(name, Runnable {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (error: ActivityNotFoundException) {
                    WorkspaceDialogs.builder(activity).setMessage(url).setPositiveButton("OK", null).show()
                }
            }))
            i++
        }
        val scroll = ScrollView(activity)
        scroll.addView(body)
        val details: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(e.text("title", ru)).setView(scroll).setNegativeButton(t("Закрыть", "Close"), null).create()
        if (!e.command.isEmpty()) {
            add(body, action(t("Вставить в сессию…", "Insert into session…"), Runnable {
                WorkspaceDialogs.builder(activity).setTitle(t("Вставить команду проверки?", "Insert diagnostic command?")).setMessage(t("Выберите терминал нужной системы. Поле ввода будет заменено, команда не отправляется. Проверьте, что внутри нет открытого чата или интерактивной программы.\n\n", "Choose the terminal for the correct OS. The input draft will be replaced; the command is not sent. Check that it is not inside a chat or interactive program.\n\n") + e.command).setPositiveButton(t("Вставить", "Insert"), DialogInterface.OnClickListener { v, w ->
                    details.dismiss()
                    dialog!!.dismiss()
                    insert.accept(e.command)
                }).setNegativeButton(t("Отмена", "Cancel"), null).show()
            }))
        }
        details.show()
    }

    companion object {
        @JvmStatic
        fun label(a: TermuxActivity): String {
            return if (WorkspaceLanguage.code(a)!!.equals("ru")) "Ошибки и решения" else "Errors and solutions"
        }

        @JvmStatic
        fun show(a: TermuxActivity, insert: Consumer<String>) {
            WorkspaceErrorLibraryUi(a, insert).open()
        }
    }
}
