package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R
import java.io.IOException
import java.util.Comparator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Predicate
import org.json.JSONArray
import org.json.JSONObject

/** User-invoked history browser. Codex deletion uses its native thread/delete API only.*/
class WorkspaceChatHistories {

    fun interface Call {
        @Throws(Exception::class)
        fun call(method: String, params: JSONObject): JSONObject
    }

    class Entry {
        @JvmField val kind: String
        @JvmField val environment: String
        @JvmField val id: String
        @JvmField val title: String
        @JvmField val project: String
        @JvmField val modified: Long

        constructor(k: String, e: String, i: String, t: String, p: String, m: Long) {
            kind = k
            environment = e
            id = i
            title = t
            project = p
            modified = m
        }
    }

    @JvmField val activity: Activity
    @JvmField val store: WorkspaceStore
    @JvmField val worker: Executor
    @JvmField val active: Predicate<Entry>
    @JvmField val saved: Runnable
    @JvmField val guarded: Boolean
    @JvmField val entries: MutableList<Entry> = ArrayList<Entry>()
    @JvmField var dialog: AlertDialog? = null
    @JvmField var status: TextView? = null
    @JvmField var rows: LinearLayout? = null
    @JvmField var search: EditText? = null
    @JvmField var filter: String = "all"
    @JvmField var busy: Boolean = false

    constructor(a: Activity, s: WorkspaceStore, w: Executor, check: Predicate<Entry>, changed: Runnable) : this(a, s, w, check, changed, false)

    constructor(a: Activity, s: WorkspaceStore, w: Executor, check: Predicate<Entry>, changed: Runnable, guarded: Boolean) {
        activity = a
        store = s
        worker = w
        active = check
        saved = changed
        this.guarded = guarded
    }

    fun builder(): WorkspaceDialogs.Builder {
        val b: WorkspaceDialogs.Builder = WorkspaceDialogs.builder(activity)
        if (guarded) {
            b.blockOutsideDismiss()
            b.setCancelable(false)
        }
        return b
    }

    fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    fun ui(r: Runnable) {
        activity.runOnUiThread(Runnable {
            if (!activity.isFinishing() && dialog != null && dialog!!.isShowing()) {
                r.run()
            }
        })
    }

    fun dp(v: Int): Int {
        return WorkspaceDialogs.dp(activity, v.toFloat())
    }

    fun text(s: String, size: Int): TextView {
        val v = TextView(activity)
        v.setText(s)
        v.setTextColor(Pal.TEXT)
        v.setTextSize(size.toFloat())
        v.setPadding(dp(12), dp(10), dp(12), dp(10))
        return v
    }

    fun column(): LinearLayout {
        val v = LinearLayout(activity)
        v.setOrientation(LinearLayout.VERTICAL)
        return v
    }

    fun show() {
        val body: LinearLayout = column()
        search = EditText(activity)
        search!!.setTextColor(Pal.TEXT)
        search!!.setHintTextColor(Pal.MUTED)
        search!!.setHint(t("Найти чат или проект", "Find chat or project"))
        body.addView(search)
        val filters: TextView = WorkspaceDialogs.action(activity, t("Фильтр: Все", "Filter: All"), 0)
        filters.setOnClickListener(View.OnClickListener { v ->
            builder().setTitle(t("Истории чатов", "Chat histories")).setItems(arrayOf<String>(t("Все", "All"), "Codex · Termux", "Codex · Ubuntu", "Codex · Ubuntu AI"), DialogInterface.OnClickListener { d, w ->
                filter = arrayOf<String>("all", "termux", "ubuntu", "ubuntu-ai")[w]
                filters.setText(arrayOf<String>(t("Все", "All"), "Codex · Termux", "Codex · Ubuntu", "Codex · Ubuntu AI")[w])
                render()
            }).setNegativeButton(t("Отмена", "Cancel"), null).show()
        })
        body.addView(filters)
        status = text(t("Загрузка историй…", "Loading histories…"), 12)
        body.addView(status)
        rows = column()
        val viewport = ScrollView(activity)
        viewport.addView(rows)
        body.addView(viewport, LinearLayout.LayoutParams(-1, dp(340)))
        dialog = builder().setTitle(t("Истории чатов", "Chat histories")).setView(body).setNegativeButton(t("Назад", "Back"), null).create()
        dialog!!.show()
        search!!.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, c: Int, f: Int) {
            }

            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
                render()
            }

            override fun afterTextChanged(e: Editable) {
            }
        })
        load()
    }

    fun load() {
        busy = true
        worker.execute(Runnable {
            val found: MutableList<Entry> = ArrayList<Entry>()
            val errors: MutableList<String> = ArrayList<String>()
            for (environment in arrayOf<String>("termux", "ubuntu", "ubuntu-ai")) {
                val p = WorkspaceStore.Session()
                p.codexEnvironment = environment
                try {
                    (CodexRpc.history(activity, p)).use { rpc ->
                        for (archived in booleanArrayOf(false, true)) {
                            var cursor: String? = null
                            val seen: MutableSet<String> = HashSet<String>()
                            for (page in 0 until (200)) {
                                val params: JSONObject = CodexChatCatalog.params(cursor).put("archived", archived)
                                val result: JSONObject = rpc.call("thread/list", params)
                                val data: JSONArray = result.getJSONArray("data")
                                var i = 0
                                while (i < data.length()) {
                                    val e: CodexChatCatalog.Entry? = CodexChatCatalog.parse(data.getJSONObject(i))
                                    if (e != null && found.stream().noneMatch(Predicate<Entry> { x -> x.kind.equals("codex") && x.environment.equals(environment) && x.id.equals(e.id) })) {
                                        found.add(Entry("codex", environment, e.id, e.name, e.cwd, e.updated * 1000))
                                    }
                                    i++
                                }
                                val next: String = result.optString("nextCursor", "")
                                if (next.isEmpty() || next.equals("null")) {
                                    break
                                }
                                if (!seen.add(next) || page == 199) {
                                    throw IOException("Partial catalog: pagination limit")
                                }
                                cursor = next
                            }
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Codex · " + environment + ": " + t("каталог недоступен или прочитан частично", "catalog unavailable or partially read"))
                }
            }
            found.sortWith(Comparator<Entry> { a, b -> java.lang.Long.compare(b.modified, a.modified) })
            ui(Runnable {
                entries.clear()
                entries.addAll(found)
                busy = false
                status!!.setText(t("Историй: ", "Histories: ") + entries.size + (if (errors.isEmpty()) "" else "\n" + TextUtils.join("\n", errors)))
                render()
            })
        })
    }

    fun render() {
        if (rows == null) {
            return
        }
        rows!!.removeAllViews()
        val q: String = (search!!.getText().toString() as java.lang.String).toLowerCase(Locale.ROOT)
        for (e in entries) {
            if (!filter.equals("all") && !e.environment.equals(filter)) {
                continue
            }
            if (!((e.title + " " + e.project + " " + e.id) as java.lang.String).toLowerCase(Locale.ROOT).contains(q)) {
                continue
            }
            val row = LinearLayout(activity)
            row.setGravity(Gravity.CENTER_VERTICAL)
            val date: String = if (e.modified > 0) DateFormat.format("dd.MM.yyyy HH:mm", e.modified).toString() else ""
            val open: TextView = WorkspaceDialogs.action(activity, e.title + "\n" + ("Codex · " + e.environment) + " · " + date + "\n" + e.project, 0)
            open.setOnClickListener(View.OnClickListener { v -> view(e) })
            row.addView(open, LinearLayout.LayoutParams(0, -2, 1.0f))
            val trash = WorkspaceIconButton(activity)
            trash.setIcon(activity.getDrawable(R.drawable.workspace_delete)!!.mutate(), dp(22))
            trash.setTextColor(Pal.DANGER)
            trash.setContentDescription(t("Удалить историю", "Delete history"))
            trash.setOnClickListener(View.OnClickListener { v -> confirmDelete(e) })
            row.addView(trash, LinearLayout.LayoutParams(dp(48), dp(48)))
            rows!!.addView(row)
        }
    }

    fun view(e: Entry) {
        status!!.setText(t("Чтение истории…", "Reading history…"))
        worker.execute(Runnable {
            try {
                var value: String
                val p = WorkspaceStore.Session()
                p.codexEnvironment = e.environment
                val rpc = CodexRpc.history(activity, p)
                var primaryException: Throwable? = null
                try {
                    value = codexTranscript(rpc.call("thread/read", JSONObject().put("threadId", e.id).put("includeTurns", true)).getJSONObject("thread"))
                } catch (t: Throwable) {
                    primaryException = t
                    throw t
                } finally {
                    if (primaryException != null) {
                        try {
                            rpc.close()
                        } catch (suppressed: Throwable) {
                            primaryException.addSuppressed(suppressed)
                        }
                    } else {
                        rpc.close()
                    }
                }
                val out: String = value
                ui(Runnable {
                    val body: TextView = text(if (out.isEmpty()) t("История пуста", "History is empty") else out, 14)
                    body.setTextIsSelectable(true)
                    val scroll = ScrollView(activity)
                    scroll.addView(body)
                    builder().setTitle(e.title).setView(scroll).setPositiveButton(t("Назад", "Back"), null).show()
                    status!!.setText(t("Историй: ", "Histories: ") + entries.size)
                })
            } catch (ex: Exception) {
                ui(Runnable {
                    status!!.setText(t("Не удалось открыть историю: ", "Cannot open history: ") + ex.javaClass.getSimpleName())
                })
            }
        })
    }

    fun confirmDelete(e: Entry) {
        if (busy) {
            return
        }
        if (active.test(e)) {
            status!!.setText(t("Сначала закройте сессии с этим чатом.", "Close sessions using this chat first."))
            return
        }
        if (guarded) {
            StorageDeletionConsent.show(activity, t("Удалить историю чата?", "Delete chat history?"), e.title + "\n" + t("Разговор будет удалён; файлы проекта и авторизация сохранятся.", "The conversation will be deleted; project files and authentication are retained."), Runnable { delete(e) })
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(t("Удалить историю чата?", "Delete chat history?")).setMessage(e.title + "\n" + e.id + "\n" + t("Разговор будет удалён. Файлы проекта и авторизация не удаляются. Пресеты останутся, но потеряют привязку к этому чату. Закройте также разговоры, открытые вне приложения.", "The conversation will be deleted. Project files and authorization are kept. Presets remain but lose this chat binding. Also close conversations opened outside this app.")).setNegativeButton(t("Назад", "Back"), null).setPositiveButton(t("Удалить", "Delete"), DialogInterface.OnClickListener { d, w -> delete(e) }).show()
    }

    fun delete(e: Entry) {
        if (!store.isWritable()) {
            status!!.setText(t("Библиотека недоступна для записи", "Library is not writable"))
            return
        }
        if (active.test(e)) {
            status!!.setText(t("Чат снова открыт — удаление отменено.", "Chat was reopened; deletion cancelled."))
            return
        }
        if (!deleting.add(key(e))) {
            return
        }
        busy = true
        worker.execute(Runnable {
            try {
                val p = WorkspaceStore.Session()
                p.codexEnvironment = e.environment
                (CodexRpc.history(activity, p)).use { rpc ->
                    deleteCodex(e.id, Call(rpc::call))
                }
                activity.runOnUiThread(Runnable {
                    for (s in store.sessions) {
                        if (e.kind.equals("codex") && s!!.codexEnvironment.equals(e.environment) && s.codexThread.equals(e.id)) {
                            s.codexChat = "new"
                            s.codexThread = UUID.randomUUID().toString()
                        }
                    }
                    store.save()
                    entries.remove(e)
                    busy = false
                    deleting.remove(key(e))
                    saved.run()
                    if (dialog == null || !dialog!!.isShowing()) {
                        return@Runnable
                    }
                    status!!.setText(t("История удалена", "History deleted"))
                    render()
                })
            } catch (ex: Exception) {
                deleting.remove(key(e))
                ui(Runnable {
                    busy = false
                    status!!.setText(t("Удаление не подтверждено. Codex должен поддерживать thread/delete; архивирование вместо удаления не выполняется.", "Deletion not confirmed. Codex must support thread/delete; archiving is not substituted for deletion."))
                })
            }
        })
    }

    companion object {
        private val deleting: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @JvmStatic
        fun key(e: Entry): String {
            return e.kind + "|" + e.environment + "|" + e.id
        }

        @JvmStatic
        fun deleting(s: WorkspaceStore.Session?): Boolean {
            return deleting.contains("codex|" + s!!.codexEnvironment + "|" + s.codexThread)
        }

        @Throws(Exception::class)
        @JvmStatic
        fun deleteCodex(id: String, call: Call) {
            if (!CodexSessionShell.validThread(id)) {
                throw IllegalArgumentException("Invalid thread ID")
            }
            call.call("thread/delete", JSONObject().put("threadId", id))
        }

        @Throws(Exception::class)
        @JvmStatic
        fun codexTranscript(thread: JSONObject): String {
            val text = StringBuilder()
            val turns: JSONArray? = thread.optJSONArray("turns")
            if (turns == null) {
                throw IOException("This Codex version requires paginated history; full view unavailable")
            }
            var i = 0
            while (i < turns.length()) {
                val items: JSONArray? = turns.getJSONObject(i).optJSONArray("items")
                if (items == null) {
                    i++
                    continue
                }
                var j = 0
                while (j < items.length()) {
                    val item: JSONObject = items.getJSONObject(j)
                    val type: String = item.optString("type")
                    if (type.equals("agentMessage")) {
                        text.append("AI: ").append(item.optString("text")).append("\n\n")
                    } else if (type.equals("userMessage")) {
                        text.append("You: ")
                        val content: JSONArray? = item.optJSONArray("content")
                        if (content != null) {
                            var k = 0
                            while (k < content.length()) {
                                val c: JSONObject? = content.optJSONObject(k)
                                if (c != null) {
                                    text.append(c.optString("text", "[" + c.optString("type") + "]")).append('\n')
                                }
                                k++
                            }
                        }
                        text.append('\n')
                    }
                    if (text.length > 512000) {
                        return "" + text + "\n[Preview limited; saved history unchanged.]"
                    }
                    j++
                }
                i++
            }
            return text.toString()
        }
    }
}
