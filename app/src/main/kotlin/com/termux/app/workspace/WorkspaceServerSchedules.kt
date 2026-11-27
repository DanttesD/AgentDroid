package com.termux.app.workspace

import android.R
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.util.concurrent.ExecutorService
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Schedule operations use only explicitly confirmed requests; no app timer runs the commands.*/
class WorkspaceServerSchedules : AutoCloseable {
    private val activity: TermuxActivity
    private val connection: ServerConnection?
    private val worker: ExecutorService
    private val dialogs: MutableList<AlertDialog> = ArrayList<AlertDialog>()
    private var closed: Boolean = false
    private var busy: Boolean = false

    constructor(a: TermuxActivity, c: ServerConnection?, w: ExecutorService) {
        activity = a
        connection = c
        worker = w
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun show(d: AlertDialog) {
        dialogs.add(d)
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener(DialogInterface.OnDismissListener { x -> dialogs.remove(d) })
        d.show()
    }

    private fun body(): LinearLayout {
        val b = LinearLayout(activity)
        b.setOrientation(LinearLayout.VERTICAL)
        val p: Int = WorkspaceDialogs.dp(activity, 16.0f)
        b.setPadding(p, 0, p, p)
        return b
    }

    private fun label(b: LinearLayout, text: String): TextView {
        val v = TextView(activity)
        v.setText(text)
        v.setTextColor(Pal.TEXT)
        v.setTextSize(14.0f)
        v.setPadding(0, 8, 0, 8)
        b.addView(v)
        return v
    }

    private fun field(b: LinearLayout, name: String, value: String): EditText {
        label(b, name)
        val v = EditText(activity)
        v.setTextColor(Pal.TEXT)
        v.setText(value)
        v.setSingleLine(true)
        b.addView(v)
        return v
    }

    private fun request(data: JSONObject, done: Consumer<JSONObject?>) {
        if (closed || busy) {
            return
        }
        busy = true
        val waiting: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("Расписания задач", "Task schedules")).setMessage(t("Проверка / выполнение на сервере…", "Checking / working on server…")).create()
        waiting.setCancelable(false)
        show(waiting)
        worker.execute(Runnable {
            var result: JSONObject? = null
            var error: String? = null
            try {
                result = connection!!.schedules(data)
            } catch (e: Exception) {
                val s: String? = e.message
                error = if (s != null && (s as java.lang.String).matches("[A-Z_]{1,80}")) s else "SCHEDULE_ERROR"
            }
            val value: JSONObject? = result
            val problem: String? = error
            activity.runOnUiThread(Runnable {
                busy = false
                waiting.dismiss()
                if (closed) {
                    return@Runnable
                }
                if (problem != null) {
                    message(t("Операция не выполнена", "Operation failed"), problem + "\n" + t("Проверьте Python, доступность планировщика и права. Перед повторным созданием проверьте список: при обрыве связи расписание могло сохраниться.", "Check Python, scheduler availability and permissions. Check the list before creating again: a disconnected request may already have saved the schedule."))
                } else {
                    done.accept(value)
                }
            })
        })
    }

    private fun op(name: String): JSONObject {
        try {
            return JSONObject().put("op", name)
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
    }

    private fun message(title: String, text: String) {
        show(WorkspaceDialogs.builder(activity).setTitle(title).setMessage(text).setPositiveButton("OK", null).create())
    }

    fun create(title: String, directory: String, command: String) {
        request(op("capabilities"), Consumer<JSONObject?> { caps ->
            val keys: MutableList<String> = ArrayList<String>()
            if (caps!!.optBoolean("cron")) {
                keys.add("cron")
            }
            if (caps.optBoolean("systemd")) {
                keys.add("systemd")
            }
            if (keys.isEmpty()) {
                message(t("Планировщик недоступен", "Scheduler unavailable"), t("Нужен crontab или работающий systemd --user. Приложение не устанавливает пакеты и не включает linger автоматически.", "Requires crontab or a working systemd --user manager. The app does not install packages or enable linger automatically."))
                return@Consumer
            }
            val body: LinearLayout = body()
            label(body, t("Механизм расписания", "Scheduler"))
            val backend = Spinner(activity)
            backend.setAdapter(ArrayAdapter<String>(activity, R.layout.simple_spinner_dropdown_item, keys))
            body.addView(backend)
            val plan: EditText = field(body, t("Расписание", "Schedule"), "*/30 * * * *")
            label(body, t("Cron: 5 полей, например */30 * * * *. systemd: daily, hourly, weekly или *-*-* 03:00:00.", "Cron: 5 fields, e.g. */30 * * * *. systemd: daily, hourly, weekly or *-*-* 03:00:00."))
            label(body, t("Время сервера: ", "Server time: ") + caps.optString("time") + " · " + caps.optString("timezone") + (if (caps.optString("cronTimezone").isEmpty()) "" else "\nCRON_TZ: " + caps.optString("cronTimezone")))
            label(body, t("Работает без телефона. Cron требует запущенную службу cron. systemd --user без linger может остановиться после выхода пользователя; автозапуск после перезагрузки зависит от настройки сервера. Команды неинтерактивные, с правами SSH-пользователя, до 1 часа, без &/nohup; окружение/PATH может отличаться от SSH-терминала. Вывод содержит секреты, если команда их печатает.", "Runs without the phone. Cron requires a running cron daemon. systemd --user without linger may stop after logout; reboot startup depends on server configuration. Noninteractive commands run as the SSH user, up to 1 hour, no &/nohup; environment/PATH can differ from the SSH terminal. Output may contain secrets printed by the command."))
            backend.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p: AdapterView<*>) {
                }

                override fun onItemSelected(p: AdapterView<*>, v: View, pos: Int, id: Long) {
                    plan.setText(if (keys.get(pos).equals("cron")) "*/30 * * * *" else "daily")
                }
            })
            val scroll = ScrollView(activity)
            scroll.addView(body)
            val form: AlertDialog = WorkspaceDialogs.builder(activity).setTitle(t("По расписанию", "Scheduled run")).setView(scroll).setPositiveButton(t("Продолжить", "Continue"), null).setNegativeButton(t("Отмена", "Cancel"), null).create()
            show(form)
            form.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                val schedule: String = (plan.getText().toString() as java.lang.String).trim()
                val engine: String = keys.get(backend.getSelectedItemPosition())
                if (schedule.isEmpty()) {
                    return@OnClickListener
                }
                form.dismiss()
                val preview: String = connection!!.profile!!.user + "@" + connection.profile!!.host + "\n" + directory + "\n" + engine + " · " + schedule + "\n\n" + command + "\n\n" + t("Создаёт закрытый каталог ~/.local/share/termux-jobs-schedules-v1 и запись в crontab текущего пользователя либо собственные systemd --user units. Расписания независимы от агента и остаются после его удаления.", "Creates a private ~/.local/share/termux-jobs-schedules-v1 directory and a user crontab entry or app-owned systemd --user units. Schedules are independent of the agent and remain after its removal.")
                show(WorkspaceDialogs.builder(activity).setTitle(t("Создать расписание?", "Create schedule?")).setMessage(preview).setPositiveButton(t("Создать", "Create"), DialogInterface.OnClickListener { x, w ->
                    try {
                        val req: JSONObject = op("create").put("backend", engine).put("plan", schedule).put("title", title).put("directory", directory).put("command", command)
                        request(req, Consumer<JSONObject?> { r -> list() })
                    } catch (e: JSONException) {
                        throw IllegalStateException(e)
                    }
                }).setNegativeButton(t("Отмена", "Cancel"), null).create())
            })
        })
    }

    fun list() {
        request(op("list"), Consumer<JSONObject?> { result ->
            val entries: JSONArray? = result!!.optJSONArray("schedules")
            if (entries == null || entries.length() == 0) {
                message(t("Расписания задач", "Task schedules"), t("Расписаний приложения пока нет.", "No app-owned schedules yet."))
                return@Consumer
            }
            val labels = arrayOfNulls<String>(entries.length())
            for (i in 0 until (labels.size)) {
                val s: JSONObject = entries.optJSONObject(i)
                labels[i] = s.optString("title") + "\n" + s.optString("backend") + " · " + s.optString("plan") + " · " + (if (s.optBoolean("enabled")) t("Включено", "Enabled") else t("Выключено", "Disabled"))
            }
            show(WorkspaceDialogs.builder(activity).setTitle(t("Расписания задач", "Task schedules")).setItems(labels, DialogInterface.OnClickListener { d, w -> details(entries.optJSONObject(w)) }).setPositiveButton(t("Закрыть", "Close"), null).create())
        })
    }

    private fun details(entry: JSONObject) {
        val items: Array<String> = arrayOf<String>(t("Журнал последнего запуска", "Last run log"), if (entry.optBoolean("enabled")) t("Отключить расписание", "Disable schedule") else t("Включить расписание", "Enable schedule"), t("Изменить расписание", "Edit schedule"), t("Удалить расписание", "Delete schedule"))
        show(WorkspaceDialogs.builder(activity).setTitle(entry.optString("title")).setItems(items, DialogInterface.OnClickListener { d, w ->
            if (w == 0) {
                send("log", entry, Consumer<JSONObject?> { r ->
                    val s: JSONObject? = r!!.optJSONObject("schedule")
                    val last: JSONObject? = if (s == null) null else s.optJSONObject("last")
                    val text: String = (if (last == null) "" else t("Состояние: ", "State: ") + last.optString("state") + "\n") + r.optString("log")
                    val log = TextView(activity)
                    log.setText(text)
                    log.setTextIsSelectable(true)
                    log.setTypeface(Typeface.MONOSPACE)
                    log.setTextColor(Pal.TEXT)
                    val scroll = ScrollView(activity)
                    scroll.addView(log)
                    show(WorkspaceDialogs.builder(activity).setTitle(t("Последний запуск", "Last run")).setView(scroll).setPositiveButton("OK", null).create())
                })
            }
            if (w == 1) {
                confirm(if (entry.optBoolean("enabled")) "disable" else "enable", entry)
            }
            if (w == 3) {
                confirm("delete", entry)
            }
            if (w == 2) {
                val b: LinearLayout = body()
                val plan: EditText = field(b, t("Новое расписание", "New schedule"), entry.optString("plan"))
                show(WorkspaceDialogs.builder(activity).setTitle(t("Изменить расписание", "Edit schedule")).setView(b).setPositiveButton(t("Продолжить", "Continue"), DialogInterface.OnClickListener { x, y ->
                    val value: String = (plan.getText().toString() as java.lang.String).trim()
                    show(WorkspaceDialogs.builder(activity).setTitle(t("Применить расписание?", "Apply schedule?")).setMessage(entry.optString("title") + "\n" + entry.optString("backend") + " · " + value).setPositiveButton(t("Да", "Yes"), DialogInterface.OnClickListener { a, z ->
                        try {
                            request(op("edit").put("id", entry.getString("id")).put("plan", value), Consumer<JSONObject?> { r -> list() })
                        } catch (e: JSONException) {
                            throw IllegalStateException(e)
                        }
                    }).setNegativeButton(t("Нет", "No"), null).create())
                }).setNegativeButton(t("Отмена", "Cancel"), null).create())
            }
        }).setPositiveButton(t("Закрыть", "Close"), null).create())
    }

    private fun send(action: String, entry: JSONObject, done: Consumer<JSONObject?>) {
        try {
            request(op(action).put("id", entry.getString("id")), done)
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
    }

    private fun confirm(action: String, entry: JSONObject) {
        show(WorkspaceDialogs.builder(activity).setTitle(t("Подтвердить действие", "Confirm action")).setMessage(entry.optString("title") + "\n" + action + "\n\n" + t("Меняется только это расписание приложения. Отключение не прерывает уже выполняющуюся команду; удаление выполняющегося расписания заблокировано. При ошибке изменения расписание может остаться выключенным — проверьте список.", "Only this app-owned schedule changes. Disabling does not stop a running command; deleting a running schedule is blocked. A failed change may leave it disabled: check the list.")).setPositiveButton(t("Да", "Yes"), DialogInterface.OnClickListener { d, w -> send(action, entry, Consumer<JSONObject?> { r -> list() }) }).setNegativeButton(t("Нет", "No"), null).create())
    }

    fun serverProfileId(): String {
        return connection!!.profile!!.id
    }

    override fun close() {
        closed = true
        for (d in ArrayList<AlertDialog>(dialogs)) {
            d.dismiss()
        }
    }
}
