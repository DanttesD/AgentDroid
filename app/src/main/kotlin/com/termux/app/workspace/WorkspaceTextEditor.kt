package com.termux.app.workspace

import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.io.File
import java.util.concurrent.ExecutorService

/** In-memory text editor: no autosave of project contents or secrets.*/
class WorkspaceTextEditor {
    private val activity: TermuxActivity
    private val files: ProjectFiles
    private val worker: ExecutorService
    private val original: TextFileEdits.Snapshot
    private val saved: Runnable
    private val dismissed: Runnable
    private var dialog: AlertDialog? = null
    private var input: EditText? = null
    private var status: TextView? = null
    private var closed: Boolean = false
    private var saving: Boolean = false

    constructor(activity: TermuxActivity, files: ProjectFiles, worker: ExecutorService, original: TextFileEdits.Snapshot, saved: Runnable) : this(activity, files, worker, original, saved, Runnable {  })

    constructor(activity: TermuxActivity, files: ProjectFiles, worker: ExecutorService, original: TextFileEdits.Snapshot, saved: Runnable, dismissed: Runnable) {
        this.activity = activity
        this.files = files
        this.worker = worker
        this.original = original
        this.saved = saved
        this.dismissed = dismissed
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    fun show() {
        val body = LinearLayout(activity)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(16), 0, dp(16), dp(12))
        val path = TextView(activity)
        path.setText(original.path)
        path.setTextColor(Pal.MUTED)
        path.setTextSize(12.0f)
        path.setMaxLines(2)
        body.addView(path)
        input = EditText(activity)
        input!!.setTextColor(Pal.TEXT)
        input!!.setHintTextColor(Pal.MUTED)
        input!!.setTextSize(14.0f)
        input!!.setTypeface(Typeface.MONOSPACE)
        input!!.setGravity(Gravity.TOP)
        input!!.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        input!!.setImeOptions(EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI)
        input!!.setSingleLine(false)
        input!!.setHorizontallyScrolling(false)
        input!!.setVerticalScrollBarEnabled(true)
        input!!.setSaveEnabled(false)
        input!!.setContentDescription(WorkspaceText.tr("Текст файла"))
        input!!.setText(original.text)
        body.addView(input, LinearLayout.LayoutParams(-1, Math.min(dp(360), activity.getResources().getDisplayMetrics().heightPixels / 3)))
        status = TextView(activity)
        status!!.setTextColor(Pal.MUTED)
        status!!.setTextSize(12.0f)
        status!!.setText(WorkspaceText.tr("UTF-8 · до 256 КБ · Enter переносит строку"))
        body.addView(status)
        dialog = WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Редактировать") + " · " + File(original.path).getName()).setView(body).setPositiveButton(WorkspaceText.tr("Сохранить"), null).setNegativeButton(WorkspaceText.tr("Отмена"), null).create()
        dialog!!.setCanceledOnTouchOutside(false)
        dialog!!.setOnKeyListener(DialogInterface.OnKeyListener { d, key, event ->
            if (key != KeyEvent.KEYCODE_BACK) {
                return@OnKeyListener false
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                requestClose()
            }
            return@OnKeyListener true
        })
        dialog!!.setOnDismissListener(DialogInterface.OnDismissListener { d -> close() })
        dialog!!.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v -> save() })
            dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(View.OnClickListener { v -> requestClose() })
        })
        dialog!!.show()
    }

    private fun requestClose() {
        if (saving) {
            status!!.setText(WorkspaceText.tr("Дождитесь завершения сохранения"))
            return
        }
        if (input!!.getText().toString().equals(original.text)) {
            close()
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(WorkspaceText.tr("Закрыть без сохранения?")).setMessage(WorkspaceText.tr("Изменения текста будут потеряны.")).setPositiveButton(WorkspaceText.tr("Закрыть без сохранения"), DialogInterface.OnClickListener { d, w -> close() }).setNegativeButton(WorkspaceText.tr("Продолжить редактирование"), null).show()
    }

    private fun save() {
        if (closed || saving) {
            return
        }
        val text: String = input!!.getText().toString()
        try {
            TextFileEdits.encode(text)
        } catch (e: Exception) {
            status!!.setText(e.message)
            return
        }
        if (text.equals(original.text)) {
            close()
            return
        }
        saving = true
        input!!.setEnabled(false)
        dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false)
        dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(false)
        status!!.setText(WorkspaceText.tr("Сохранение…"))
        worker.execute(Runnable {
            var error: String? = null
            try {
                files.editSave(original, text)
            } catch (e: Exception) {
                error = e.message
            }
            val result: String? = error
            activity.runOnUiThread(Runnable {
                if (closed) {
                    return@Runnable
                }
                saving = false
                if (result != null) {
                    input!!.setEnabled(true)
                    dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true)
                    dialog!!.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(true)
                    status!!.setText(result)
                    return@Runnable
                }
                close()
                saved.run()
                activity.showToast(WorkspaceText.tr("Файл сохранён"), false)
            })
        })
    }

    fun serverProfileId(): String {
        return files.profile.id
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        if (dialog != null && dialog!!.isShowing()) {
            dialog!!.dismiss()
        }
        dismissed.run()
    }
}
