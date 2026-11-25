package com.termux.app.workspace

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.app.TermuxActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A static snapshot: no polling, installer restart or terminal focus changes.*/
object EnvironmentInstallLogUi {
    private val IO: ExecutorService = Executors.newSingleThreadExecutor()

    @JvmStatic
    private fun t(a: TermuxActivity, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(a)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun show(a: TermuxActivity, label: String, report: String) {
        val body = LinearLayout(a)
        body.setOrientation(LinearLayout.VERTICAL)
        val gap: Int = WorkspaceDialogs.dp(a, 12.0f)
        body.setPadding(gap, 0, gap, gap)
        val scroll = ScrollView(a)
        val log = TextView(a)
        log.setTextColor(Pal.TEXT)
        log.setTextSize(12.0f)
        log.setTypeface(Typeface.MONOSPACE)
        log.setTextIsSelectable(true)
        log.setText(report)
        log.setTag("environment-install-log-text")
        scroll.addView(log)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.0f))
        val info = TextView(a)
        info.setTextSize(11.0f)
        info.setTextColor(Pal.MUTED)
        info.setText(t(a, "Журнал последней попытки. Адреса URL и известные секреты скрыты автоматически; проверьте текст перед отправкой.", "Last attempt log. URLs and known secrets are automatically redacted; review before sharing."))
        body.addView(info)
        val status = TextView(a)
        status.setTextSize(12.0f)
        status.setTextColor(Pal.ACCENT)
        status.setTag("environment-install-log-status")
        body.addView(status)
        val holder = FrameLayout(a)
        holder.addView(body, FrameLayout.LayoutParams(-1, (a.getResources().getDisplayMetrics().heightPixels * 0.6).toInt()))
        val dialog: AlertDialog = WorkspaceDialogs.builder(a).setTitle(t(a, "Журнал установки · ", "Installation log · ") + label).setView(holder).setNegativeButton(t(a, "Назад", "Back"), null).setPositiveButton(t(a, "В Download", "To Download"), null).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        val saving: BooleanArray = booleanArrayOf(false)
        val main = Handler(Looper.getMainLooper())
        dialog.getButton(-1).setTag("environment-install-log-download")
        dialog.getButton(-1).setOnClickListener(View.OnClickListener { v ->
            a.requestWorkspaceStorageAccess(Runnable {
                if (!dialog.isShowing() || saving[0]) {
                    return@Runnable
                }
                saving[0] = true
                dialog.getButton(-1).setEnabled(false)
                status.setText(t(a, "Сохранение…", "Saving…"))
                IO.execute(Runnable {
                    var out: File? = null
                    var result: String
                    try {
                        val folder: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!folder.isDirectory() && !folder.mkdirs()) {
                            throw IOException()
                        }
                        out = File.createTempFile("Termux-Jobs-environment-install-", ".txt", folder)
                        (FileOutputStream(out)).use { stream ->
                            stream.write((report as java.lang.String).getBytes(StandardCharsets.UTF_8))
                        }
                        result = t(a, "Сохранено: ", "Saved: ") + out!!.getAbsolutePath()
                    } catch (e: Exception) {
                        if (out != null) {
                            out.delete()
                        }
                        result = t(a, "Не удалось сохранить в Download. Проверьте доступ и свободное место.", "Could not save to Download. Check storage access and free space.")
                    }
                    val message: String = result
                    main.post(Runnable {
                        saving[0] = false
                        if (dialog.isShowing()) {
                            status.setText(message)
                            dialog.getButton(-1).setEnabled(true)
                        }
                    })
                })
            }, Runnable {
                if (dialog.isShowing()) {
                    status.setText(t(a, "Для сохранения нужен доступ к памяти устройства.", "Device storage access is required to save."))
                }
            })
        })
    }
}
