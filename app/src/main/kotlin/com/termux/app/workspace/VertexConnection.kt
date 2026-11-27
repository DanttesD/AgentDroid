package com.termux.app.workspace

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.function.BooleanSupplier
import java.util.function.Consumer

/** Non-secret Vertex defaults and a per-run, data-only startup response.*/
object VertexConnection {
    const val PREFS: String = "workspace-vertex-connection"

    @JvmStatic
    fun selected(p: WorkspaceStore.Session?): Boolean {
        return p != null && p.geminiAgent && p.geminiAuth.equals("vertex")
    }

    @JvmStatic
    fun applyDefaults(c: Context, p: WorkspaceStore.Session?) {
        if (!selected(p) || !p!!.geminiCloudProject!!.isEmpty()) {
            return
        }
        val prefs: SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val project: String? = prefs.getString("project", "")
        val location: String? = prefs.getString("location", "global")
        if (!project!!.isEmpty() && GeminiCli.validCloudProject(project) && GeminiCli.validCloudLocation(location)) {
            p.geminiCloudProject = project
            p.geminiCloudLocation = location
        }
    }

    @JvmStatic
    fun remember(c: Context, project: String?, location: String?): Boolean {
        if (project!!.isEmpty() || !GeminiCli.validCloudProject(project) || location!!.isEmpty() || !GeminiCli.validCloudLocation(location)) {
            return false
        }
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("project", project).putString("location", location).commit()
    }

    @JvmStatic
    fun file(c: Context, p: WorkspaceStore.Session?, suffix: String): File {
        if (!GeminiCli.validThread(p!!.geminiSocketId)) {
            throw IllegalArgumentException("Invalid Vertex run")
        }
        return File(GeminiCli.directory(c.getFilesDir()), p.geminiSocketId + ".vertex-" + suffix)
    }

    @JvmStatic
    fun pending(c: Context, p: WorkspaceStore.Session?): Boolean {
        return selected(p) && file(c, p, "request").isFile()
    }

    @JvmStatic
    fun exports(p: WorkspaceStore.Session?): String {
        return "export JOBS_VERTEX_REQUEST=" + SessionShell.quote(GeminiCli.GUEST + "/" + p!!.geminiSocketId + ".vertex-request") + "; export JOBS_VERTEX_RESPONSE=" + SessionShell.quote(GeminiCli.GUEST + "/" + p.geminiSocketId + ".vertex-response") + "; "
    }

    @Throws(IOException::class)
    @JvmStatic
    fun respond(c: Context, p: WorkspaceStore.Session?, project: String, location: String?) {
        if (!selected(p) || !pending(c, p)) {
            throw IOException("Vertex setup is no longer waiting")
        }
        if (project.isEmpty() || !GeminiCli.validCloudProject(project) || location!!.isEmpty() || !GeminiCli.validCloudLocation(location)) {
            throw IOException("Invalid Vertex metadata")
        }
        val target: File = file(c, p, "response")
        val temp: File = File.createTempFile("vertex-", ".tmp", target.getParentFile())
        try {
            if (!temp.setReadable(false, false) || !temp.setWritable(false, false) || !temp.setReadable(true, true) || !temp.setWritable(true, true)) {
                throw IOException("Cannot protect Vertex metadata")
            }
            Files.write(temp.toPath(), ((project + "\n" + location + "\n") as java.lang.String).getBytes(StandardCharsets.UTF_8))
            if (!remember(c, project, location)) {
                throw IOException("Cannot save Vertex settings")
            }
            if (!temp.renameTo(target)) {
                throw IOException("Cannot deliver Vertex settings")
            }
        } finally {
            temp.delete()
        }
    }

    @JvmStatic
    fun prompt(a: Activity, p: WorkspaceStore.Session?, active: BooleanSupplier, completed: Runnable): AlertDialog {
        val box = LinearLayout(a)
        box.setOrientation(LinearLayout.VERTICAL)
        val pad: Int = (20 * a.getResources().getDisplayMetrics().density).toInt()
        box.setPadding(pad, pad, pad, pad)
        val help = TextView(a)
        help.setText(tr(a, "Учётные данные Google Cloud найдены. Для подключения Vertex AI укажите облачный проект. Это не рабочая папка и не код авторизации. Параметры сохранятся отдельно для следующих подключений Vertex; чат и пресет не создаются.", "Google Cloud credentials were found. Enter a cloud project for Vertex AI. This is not a working folder or authorization code. Settings are saved separately for future Vertex connections; no chat or preset is created."))
        box.addView(help)
        val project = EditText(a)
        project.setSingleLine(true)
        project.setHint(tr(a, "ID проекта Google Cloud", "Google Cloud project ID"))
        project.setTag("vertex-setup-project")
        project.setText(p!!.geminiCloudProject)
        box.addView(project)
        val location: Array<String?> = arrayOf<String?>(if (p.geminiCloudLocation!!.isEmpty()) "global" else p.geminiCloudLocation)
        val region = Button(a)
        region.setText(tr(a, "Регион: ", "Location: ") + location[0])
        region.setOnClickListener(View.OnClickListener { v ->
            GeminiAuthUi.location(a, location[0], Consumer<String> { value ->
                location[0] = value
                region.setText(tr(a, "Регион: ", "Location: ") + value)
            })
        })
        box.addView(region)
        val dialog: AlertDialog = WorkspaceDialogs.builder(a).setTitle(tr(a, "Подключение Vertex AI", "Vertex AI connection")).setView(box).setPositiveButton(tr(a, "Сохранить и продолжить", "Save and continue"), null).setNegativeButton(tr(a, "Назад", "Back"), null).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener(DialogInterface.OnShowListener { d ->
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
                if (!active.getAsBoolean() || !pending(a, p)) {
                    dialog.dismiss()
                    return@OnClickListener
                }
                val id: String = (project.getText().toString() as java.lang.String).trim()
                if (id.isEmpty() || !GeminiCli.validCloudProject(id)) {
                    project.setError(tr(a, "Укажите ID проекта, например my-project-123", "Enter a project ID such as my-project-123"))
                    return@OnClickListener
                }
                try {
                    respond(a, p, id, location[0])
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false)
                    dialog.dismiss()
                    completed.run()
                } catch (e: IOException) {
                    project.setError(tr(a, "Не удалось сохранить параметры. Повторите.", "Could not save settings. Try again."))
                }
            })
        })
        dialog.show()
        return dialog
    }

    @JvmStatic
    private fun tr(c: Context, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(c)!!.equals("ru")) ru else en
    }
}
