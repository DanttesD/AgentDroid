package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.widget.EditText
import java.util.Arrays
import java.util.function.Consumer
import java.util.function.IntPredicate

/** Explicit UI choices; legacy terminal/Google profiles remain readable without migration.*/
object GeminiAuthUi {
    @JvmField val MODES: Array<String> = arrayOf<String>("google", "api", "vertex")

    @JvmStatic
    private fun tr(a: Activity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun label(a: Activity, mode: String): String {
        when (mode) {
            "google" -> {
                return tr(a, "Google-аккаунт (недоступно)", "Google account (unavailable)")
            }
            "vertex" -> {
                return "Vertex AI · Google Cloud"
            }
            "auto" -> {
                return tr(a, "Ранее выбранный вход CLI", "Previously selected CLI sign-in")
            }
            else -> {
                return tr(a, "Gemini API-ключ", "Gemini API key")
            }
        }
    }

    @JvmStatic
    fun description(a: Activity, mode: String): String {
        when (mode) {
            "google" -> {
                return tr(a, "Текущий Gemini CLI сообщает о прекращении поддержки входа Google для личных аккаунтов. Для нового подключения выберите API-ключ или Vertex AI.", "The current Gemini CLI reports that Google sign-in for individual accounts is no longer supported. Choose an API key or Vertex AI for a new connection.")
            }
            "vertex" -> {
                return tr(a, "Подключение через Google Cloud: нужны настроенный Vertex AI и его учётные данные. Облачный проект — не рабочая папка. Квоты и оплата Google Cloud отдельные. Gemini API-ключ не заменяет учётные данные Vertex AI.", "Connect through Google Cloud: configured Vertex AI and its credentials are required. The cloud project is not a working folder. Google Cloud quotas and billing are separate. A Gemini API key does not replace Vertex AI credentials.")
            }
            "auto" -> {
                return tr(a, "Сохранённый способ входа CLI оставлен без изменений. Для смены выберите API-ключ или Vertex AI; обычный вход Google в текущем CLI недоступен.", "The saved CLI authentication choice is preserved. Choose an API key or Vertex AI to change it; ordinary Google sign-in is unavailable in the current CLI.")
            }
            else -> {
                return tr(a, "Вход только по Gemini API-ключу. Условия и оплата API отдельные от входа через Google.", "Sign in using a Gemini API key. API terms and billing are separate from Google sign-in.")
            }
        }
    }

    @JvmStatic
    fun choose(a: Activity, current: String, changed: Consumer<String>) {
        val labels = arrayOfNulls<String>(MODES.size)
        for (i in 0 until (labels.size)) {
            labels[i] = label(a, MODES[i]) + "\n" + description(a, MODES[i])
        }
        val index: Int = Arrays.asList(*MODES).indexOf(current)
        val checked: Int = if (index == 0) -1 else index
        WorkspaceDialogs.builder(a).setTitle(tr(a, "Способ входа Gemini", "Gemini authentication")).setSingleChoiceItems(WorkspaceDialogs.choiceAdapter(a, labels, checked, IntPredicate { i -> i != 0 }), checked, DialogInterface.OnClickListener { d, w ->
            if (w <= 0 || w >= MODES.size) {
                return@OnClickListener
            }
            changed.accept(MODES[w])
            d.dismiss()
        }).setNegativeButton(tr(a, "Назад", "Back"), null).show()
    }

    @JvmStatic
    fun location(a: Activity, current: String?, changed: Consumer<String>) {
        val values: Array<String> = arrayOf<String>("global", "us-central1", "europe-west4", "asia-northeast1")
        val labels: Array<String> = arrayOf<String>("global", "us-central1", "europe-west4", "asia-northeast1", tr(a, "Указать другой регион…", "Enter another location…"))
        WorkspaceDialogs.builder(a).setTitle(tr(a, "Регион Vertex AI", "Vertex AI location")).setSingleChoiceItems(labels, Arrays.asList(*values).indexOf(current), DialogInterface.OnClickListener { d, w ->
            d.dismiss()
            if (w < values.size) {
                changed.accept(values[w])
                return@OnClickListener
            }
            val input = EditText(a)
            input.setSingleLine(true)
            input.setText(current)
            val dialog: AlertDialog = WorkspaceDialogs.builder(a).setTitle(tr(a, "Другой регион Vertex AI", "Another Vertex AI location")).setView(input).setPositiveButton(tr(a, "Выбрать", "Select"), null).setNegativeButton(tr(a, "Назад", "Back"), null).create()
            dialog.setOnShowListener(DialogInterface.OnShowListener { shown ->
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                    val value: String = (input.getText().toString() as java.lang.String).trim()
                    if (!GeminiCli.validCloudLocation(value)) {
                        input.setError(tr(a, "Укажите global или регион, например us-central1", "Enter global or a location such as us-central1"))
                        return@OnClickListener
                    }
                    changed.accept(value)
                    dialog.dismiss()
                })
            })
            dialog.show()
        }).setNegativeButton(tr(a, "Назад", "Back"), null).show()
    }
}
