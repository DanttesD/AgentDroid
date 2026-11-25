package com.termux.app.workspace

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.util.Arrays
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Named OpenCode connections are independent from Codex authorization and from each other.*/
object AiAgentsUi {
    fun interface Choice {
        fun chosen(value: String, title: String?)
    }

    fun interface ModelChoice {
        fun chosen(provider: String, model: String, variant: String?)
    }

    private fun interface Result {
        fun ready(value: JSONObject)
    }

    @JvmStatic
    private fun column(a: Activity): LinearLayout {
        val l = LinearLayout(a)
        l.setOrientation(LinearLayout.VERTICAL)
        l.setPadding(24, 12, 24, 12)
        return l
    }

    @JvmStatic
    private fun text(a: Activity, label: String?): TextView {
        val t = TextView(a)
        t.setText(label)
        t.setTextColor(Pal.TEXT)
        t.setTextSize(14.0f)
        t.setPadding(0, 12, 0, 12)
        return t
    }

    @JvmStatic
    private fun action(a: Activity, label: String?, call: Runnable): TextView {
        val v: TextView = WorkspaceDialogs.action(a, label, 0)
        v.setOnClickListener(View.OnClickListener { x -> call.run() })
        return v
    }

    @JvmStatic
    private fun scroll(a: Activity, child: View): ScrollView {
        val v = ScrollView(a)
        v.addView(child)
        return v
    }

    @JvmStatic
    private fun field(a: Activity, hint: String?, value: String): EditText {
        val e = EditText(a)
        e.setTextColor(Pal.TEXT)
        e.setHintTextColor(Pal.MUTED)
        e.setHint(hint)
        e.setText(value)
        e.setSingleLine()
        return e
    }

    @JvmStatic
    private fun prefs(c: Context): SharedPreferences {
        return c.getSharedPreferences("ai-connections", Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun connections(c: Context): JSONArray {
        try {
            return JSONArray(prefs(c).getString("list", "[]"))
        } catch (e: JSONException) {
            return JSONArray()
        }
    }

    @JvmStatic
    fun title(c: Context, id: String): String? {
        val items: JSONArray = connections(c)
        var i = 0
        while (i < items.length()) {
            val item: JSONObject? = items.optJSONObject(i)
            if (item != null && id.equals(item.optString("id"))) {
                return item.optString("name")
            }
            i++
        }
        return WorkspaceText.tr("Основное подключение")
    }

    @JvmStatic
    private fun query(a: Activity, id: String, live: WorkspaceStore.Session?, q: JSONObject, result: Result) {
        val target: WorkspaceStore.Session? = if (live == null) null else AiAgents.snapshot(live)
        val loading: AlertDialog = WorkspaceDialogs.builder(a).setTitle("OpenCode").setMessage(WorkspaceText.tr("Проверка окружения и чтение данных…")).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        if (q.optString("op").equals("connect") || q.optString("op").equals("model")) {
            loading.setCancelable(false)
            loading.setCanceledOnTouchOutside(false)
            loading.setButton(DialogInterface.BUTTON_NEGATIVE, null, null as DialogInterface.OnClickListener?)
        }
        loading.show()
        Thread(Runnable {
            var data: JSONObject? = null
            try {
                data = AiAgents.call(a, id, target, q)
            } catch (ignored: Exception) {
            }
            val value: JSONObject? = data
            a.runOnUiThread(Runnable {
                if (a.isFinishing() || a.isDestroyed() || !loading.isShowing()) {
                    return@Runnable
                }
                loading.dismiss()
                if (value == null) {
                    WorkspaceDialogs.builder(a).setTitle("OpenCode").setMessage(WorkspaceText.tr("Нет подключения. Сначала создайте и запустите сессию OpenCode: компоненты установятся в Ubuntu автоматически. Затем повторите выбор модели или настройку подключения. Codex работает независимо.")).setPositiveButton(WorkspaceText.tr("Закрыть"), null).show()
                    return@Runnable
                }
                result.ready(value)
            })
        }, "ai-agent-read").start()
    }

    @JvmStatic
    fun op(op: String): JSONObject {
        try {
            return JSONObject().put("op", op)
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
    }

    @JvmStatic
    fun chooseConnection(a: Activity, selected: String, result: Choice) {
        val items: JSONArray = connections(a)
        val names: MutableList<String?> = ArrayList<String?>()
        val ids: MutableList<String> = ArrayList<String>()
        names.add(WorkspaceText.tr("Основное подключение"))
        ids.add(AiAgents.DEFAULT_CONNECTION)
        var i = 0
        while (i < items.length()) {
            val item: JSONObject? = items.optJSONObject(i)
            if (item != null) {
                names.add(item.optString("name"))
                ids.add(item.optString("id"))
            }
            i++
        }
        WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Подключение OpenCode")).setSingleChoiceItems(names.toTypedArray(), ids.indexOf(selected), DialogInterface.OnClickListener { d, w ->
            result.chosen(ids.get(w), names.get(w))
            d.dismiss()
        }).setNeutralButton(WorkspaceText.tr("Аккаунты и API-ключи"), DialogInterface.OnClickListener { d, w -> showConnections(a) }).show()
    }

    @JvmStatic
    fun showConnections(a: Activity) {
        val items: JSONArray = connections(a)
        val names: MutableList<String?> = ArrayList<String?>()
        val ids: MutableList<String> = ArrayList<String>()
        names.add(WorkspaceText.tr("Основное подключение"))
        ids.add(AiAgents.DEFAULT_CONNECTION)
        var i = 0
        while (i < items.length()) {
            val item: JSONObject? = items.optJSONObject(i)
            if (item != null) {
                names.add(item.optString("name"))
                ids.add(item.optString("id"))
            }
            i++
        }
        WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Аккаунты и API-ключи")).setItems(names.toTypedArray(), DialogInterface.OnClickListener { d, w -> editConnection(a, ids.get(w), names.get(w)) }).setPositiveButton(WorkspaceText.tr("+ Подключение"), DialogInterface.OnClickListener { d, w ->
            val name: EditText = field(a, WorkspaceText.tr("Название"), "")
            val create: AlertDialog = WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Новое подключение")).setView(name).setPositiveButton(WorkspaceText.tr("Создать"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
            create.setOnShowListener(DialogInterface.OnShowListener { x ->
                create.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                    val n: String = (name.getText().toString() as java.lang.String).trim()
                    if (n.isEmpty() || n.length > 60) {
                        name.setError(WorkspaceText.tr("Укажите название"))
                        return@OnClickListener
                    }
                    val id: String = UUID.randomUUID().toString()
                    try {
                        val next: JSONArray = connections(a)
                        next.put(JSONObject().put("id", id).put("name", n))
                        prefs(a).edit().putString("list", next.toString()).apply()
                    } catch (e: JSONException) {
                        return@OnClickListener
                    }
                    create.dismiss()
                    editConnection(a, id, n)
                })
            })
            create.show()
        }).show()
    }

    @JvmStatic
    private fun editConnection(a: Activity, id: String, name: String?) {
        val form: LinearLayout = column(a)
        form.addView(text(a, WorkspaceText.tr("OpenCode · Ubuntu. Подключение выбирается отдельно для каждой сессии. Вход Codex не меняется.")))
        val provider: Array<String> = arrayOf<String>("google")
        val codes: Array<String> = arrayOf<String>("google", "openai", "anthropic", "openrouter")
        val labels: Array<String> = arrayOf<String>("Google Gemini", "OpenAI", "Anthropic Claude", "OpenRouter")
        val choose: TextView = action(a, "Google Gemini", Runnable {  })
        form.addView(choose)
        choose.setOnClickListener(View.OnClickListener { v ->
            WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Провайдер")).setSingleChoiceItems(labels, Arrays.asList(*codes).indexOf(provider[0]), DialogInterface.OnClickListener { d, w ->
                provider[0] = codes[w]
                choose.setText(labels[w])
                d.dismiss()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
        val key: EditText = field(a, WorkspaceText.tr("API-ключ (не отображается)"), "")
        key.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        key.setSaveEnabled(false)
        key.setLongClickable(false)
        form.addView(key)
        form.addView(text(a, WorkspaceText.tr("Ключ хранится локально средствами OpenCode в отдельном подключении. Сохранение не проверяет баланс или доступ к модели и не отправляет запрос к ИИ. Для подписки Codex используйте агент Codex и его обычный вход.")))
        form.addView(action(a, WorkspaceText.tr("Каталог моделей"), Runnable { chooseModel(a, id, null, ModelChoice { p, m, v ->  }) }))
        val editor: AlertDialog = WorkspaceDialogs.builder(a).setTitle(name).setView(scroll(a, form)).setPositiveButton(WorkspaceText.tr("Сохранить ключ"), null).setNegativeButton(WorkspaceText.tr("Закрыть"), null).create()
        editor.setOnDismissListener(DialogInterface.OnDismissListener { d -> key.setText("") })
        editor.setOnShowListener(DialogInterface.OnShowListener { x ->
            editor.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                val secret: String = (key.getText().toString() as java.lang.String).trim()
                if (secret.isEmpty()) {
                    key.setError(WorkspaceText.tr("Введите API-ключ"))
                    return@OnClickListener
                }
                val q: JSONObject = op("connect")
                try {
                    q.put("provider", provider[0]).put("key", secret)
                } catch (e: JSONException) {
                    return@OnClickListener
                }
                key.setText("")
                query(a, id, null, q, Result { value ->
                    editor.dismiss()
                    WorkspaceDialogs.builder(a).setMessage(WorkspaceText.tr("Ключ сохранён. Доступ к модели проверяется при обращении к провайдеру.")).setPositiveButton(WorkspaceText.tr("Закрыть"), null).show()
                })
            })
        })
        editor.show()
    }

    @JvmStatic
    fun chooseModel(a: Activity, connection: String, live: WorkspaceStore.Session?, result: ModelChoice) {
        query(a, connection, live, op("models"), Result { data ->
            var models: JSONArray? = data.optJSONArray("models")
            if (models == null) {
                models = JSONArray()
            }
            val catalog: JSONArray? = models
            val body: LinearLayout = column(a)
            val search: EditText = field(a, WorkspaceText.tr("Поиск моделей"), "")
            body.addView(search)
            val list = ListView(a)
            body.addView(list, LinearLayout.LayoutParams(-1, WorkspaceDialogs.dp(a, 300.0f)))
            val visible: MutableList<JSONObject> = ArrayList<JSONObject>()
            val labels: MutableList<String> = ArrayList<String>()
            val adapter: ArrayAdapter<String> = object : ArrayAdapter<String>(a, R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                    return WorkspaceDialogs.action(a, getItem(position), 0)
                }
            }
            list.setAdapter(adapter)
            val refresh = Runnable {
                visible.clear()
                labels.clear()
                val q: String = (search.getText().toString() as java.lang.String).toLowerCase(Locale.ROOT)
                var i = 0
                while (i < catalog!!.length()) {
                    val m: JSONObject? = catalog.optJSONObject(i)
                    if (m == null) {
                        i++
                        continue
                    }
                    val label: String = m.optString("provider") + " / " + m.optString("name")
                    if ((label as java.lang.String).toLowerCase(Locale.ROOT).contains(q)) {
                        visible.add(m)
                        labels.add(label)
                    }
                    i++
                }
                adapter.notifyDataSetChanged()
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, x: Int, y: Int, z: Int) {
                }

                override fun afterTextChanged(e: Editable) {
                }

                override fun onTextChanged(s: CharSequence, x: Int, y: Int, z: Int) {
                    refresh.run()
                }
            })
            refresh.run()
            body.addView(text(a, WorkspaceText.tr("Каталог не гарантирует доступ аккаунта. Модель и поведение применяются после подтверждения.")))
            val picker: AlertDialog = WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Выбрать модель")).setView(body).setNeutralButton(WorkspaceText.tr("Ввести ID модели"), DialogInterface.OnClickListener { d, w ->
                val id: EditText = field(a, "provider/model", "")
                val manual: AlertDialog = WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Ввести ID модели")).setView(id).setPositiveButton(WorkspaceText.tr("Применить"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
                manual.setOnShowListener(DialogInterface.OnShowListener { x ->
                    manual.getButton(-1).setOnClickListener(View.OnClickListener { v ->
                        val value: String = (id.getText().toString() as java.lang.String).trim()
                        if (value.isEmpty() || !AiAgents.validModel(value)) {
                            id.setError("provider/model")
                            return@OnClickListener
                        }
                        val index: Int = value.indexOf('/')
                        WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Подтвердить выбор")).setMessage(value).setPositiveButton(WorkspaceText.tr("Применить"), DialogInterface.OnClickListener { confirm, n ->
                            result.chosen(value.substring(0, index), value.substring(index + 1), "")
                            manual.dismiss()
                        }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
                    })
                })
                manual.show()
            }).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
            list.setOnItemClickListener(AdapterView.OnItemClickListener { p, v, pos, row ->
                val m: JSONObject = visible.get(pos)
                picker.dismiss()
                val variants: JSONArray? = m.optJSONArray("variants")
                val values: MutableList<String?> = ArrayList<String?>()
                values.add("")
                if (variants != null) {
                    var i = 0
                    while (i < variants.length()) {
                        values.add(variants.optJSONObject(i).optString("id"))
                        i++
                    }
                }
                val names: Array<String?> = values.toTypedArray()
                names[0] = WorkspaceText.tr("По умолчанию")
                WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Поведение модели")).setItems(names, DialogInterface.OnClickListener { d, w ->
                    WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Подтвердить выбор")).setMessage(m.optString("provider") + " / " + m.optString("name") + "\n" + names[w]).setPositiveButton(WorkspaceText.tr("Применить"), DialogInterface.OnClickListener { dlg, n -> result.chosen(m.optString("provider"), m.optString("id"), values.get(w)) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
                }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
            })
            picker.show()
        })
    }

    @JvmStatic
    fun chooseChat(a: Activity, connection: String, result: Choice) {
        chooseChat(a, connection, null, result)
    }

    @JvmStatic
    fun chooseChat(a: Activity, connection: String, profile: WorkspaceStore.Session?, result: Choice) {
        query(a, connection, profile, op("sessions"), Result { data ->
            val sessions: JSONArray? = data.optJSONArray("sessions")
            val names: MutableList<String> = ArrayList<String>()
            val ids: MutableList<String> = ArrayList<String>()
            if (sessions != null) {
                var i = 0
                while (i < sessions.length()) {
                    val s: JSONObject? = sessions.optJSONObject(i)
                    if (s != null && AiAgents.validThread(s.optString("id"))) {
                        names.add(s.optString("name") + " · " + s.optString("directory"))
                        ids.add(s.optString("id"))
                    }
                    i++
                }
            }
            if (names.isEmpty()) {
                WorkspaceDialogs.builder(a).setMessage(WorkspaceText.tr("Сохранённых чатов OpenCode в этом подключении нет.")).setPositiveButton(WorkspaceText.tr("Закрыть"), null).show()
                return@Result
            }
            WorkspaceDialogs.builder(a).setTitle(WorkspaceText.tr("Чаты OpenCode")).setItems(names.toTypedArray(), DialogInterface.OnClickListener { d, w -> result.chosen(ids.get(w), names.get(w)) }).setNegativeButton(WorkspaceText.tr("Отмена"), null).show()
        })
    }

    @JvmStatic
    fun modeDescription(mode: String): String? {
        return WorkspaceText.tr(if (mode.equals("plan")) "Plan — анализ и подготовка плана. Режим не заменяет ограничения доступа: для запрета изменений выберите «Только чтение»." else "Build — реализация задач: правки файлов, сборки и команды. Возможные действия определяются уровнем доступа.")
    }

    @JvmStatic
    fun accessLabel(access: String): String? {
        return WorkspaceText.tr(if (access.equals("full")) "Полный доступ · без подтверждений" else if (access.equals("read")) "Только чтение · без команд" else "С подтверждениями")
    }

    @JvmStatic
    fun accessDescription(access: String): String? {
        return WorkspaceText.tr(if (access.equals("full")) "Разрешённые инструменты могут изменять видимые Ubuntu файлы и выполнять команды без подтверждений. Это не root Android и не отдельная песочница. Политики OpenCode могут дополнительно ограничивать действия." else if (access.equals("read")) "Чтение, поиск файлов и вопросы разрешены. Редактирование, shell, субагенты, MCP и неизвестные инструменты запрещены правилами OpenCode. Это ограничения инструментов, а не защита всей файловой системы." else "OpenCode запрашивает разрешение на действия. Если выбрать «Разрешать всегда», OpenCode может сохранить разрешение для проекта. Файловой песочницы этот режим не создаёт.")
    }

    @JvmStatic
    fun showConfirmedModel(profile: WorkspaceStore.Session, actual: JSONObject?, label: TextView?, changed: Runnable): Boolean {
        if (actual == null || !AiAgents.validModel(actual.optString("providerID") + "/" + actual.optString("id"))) {
            return false
        }
        label!!.setText(modelLabel(actual))
        profile.aiModel = actual.optString("providerID") + "/" + actual.optString("id")
        profile.aiVariant = actual.optString("variant")
        changed.run()
        return true
    }

    @JvmStatic
    fun sessionModelLabel(profile: WorkspaceStore.Session, actual: JSONObject?): String? {
        var value: String = if (actual == null) "" else actual.optString("providerID") + "/" + actual.optString("id")
        var variant: String? = if (actual == null) "" else actual.optString("variant")
        if (value.isEmpty() || !AiAgents.validModel(value)) {
            value = profile.aiModel
            variant = profile.aiVariant
        }
        if (value.isEmpty()) {
            return WorkspaceText.tr("По умолчанию")
        }
        val slash: Int = value.indexOf('/')
        return (if (slash < 0) value else value.substring(slash + 1)) + (if (variant!!.isEmpty()) "" else " · " + variant)
    }

    @JvmStatic
    fun modelLabel(model: JSONObject?): String? {
        if (model == null || model.optString("id").isEmpty()) {
            return WorkspaceText.tr("По умолчанию")
        }
        val variant: String = model.optString("variant")
        return model.optString("providerID") + " / " + model.optString("id") + (if (variant.isEmpty()) "" else " · " + variant)
    }

    @JvmStatic
    fun active(a: Activity, s: WorkspaceStore.Session, changed: Runnable) {
        active(a, s, changed, Runnable {  }, changed)
    }

    @JvmStatic
    fun active(a: Activity, s: WorkspaceStore.Session, changed: Runnable, preview: Runnable, save: Runnable) {
        val ui = OpenCodeStatusUi(a, s, changed, null)
        ui.chatPresentation(preview, save)
        ui.show()
    }
}
