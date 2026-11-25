package com.termux.app.workspace

import android.app.AlertDialog
import android.content.DialogInterface
import com.termux.app.TermuxActivity
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

/** Explicit local-only key selection/import. No previews, shared-storage exports or SSH commands.*/
class WorkspaceSshKeyPicker : AutoCloseable {
    private val activity: TermuxActivity
    private val localPicker: WorkspaceLocalFilePicker
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val browsers: MutableList<WorkspaceFileBrowser> = ArrayList<WorkspaceFileBrowser>()
    private val dialogs: MutableList<AlertDialog> = ArrayList<AlertDialog>()
    private val imported: MutableSet<File?> = Collections.synchronizedSet(HashSet<File?>())
    @Volatile private var closed: Boolean = false
    private var retained: String = ""

    constructor(activity: TermuxActivity) {
        this.activity = activity
        localPicker = WorkspaceLocalFilePicker(activity)
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    private fun show(d: AlertDialog) {
        dialogs.add(d)
        d.show()
    }

    fun choose(selected: Consumer<String>) {
        if (closed) {
            return
        }
        localPicker.choose(t("Выбрать приватный SSH-ключ", "Choose private SSH key"), WorkspaceFileBrowser.FileChoice { source, entry -> select(source, entry, selected) })
    }

    private fun select(source: WorkspaceStore.Session, entry: ProjectFiles.Entry, selected: Consumer<String>) {
        if (closed) {
            return
        }
        var direct = false
        try {
            direct = ProjectFiles.environment(source).equals("termux") && SshKeyFiles.privateTermuxFile(entry.path)
        } catch (ignored: IOException) {
        }
        if (direct) {
            read(source, entry, false, selected)
            return
        }
        show(WorkspaceDialogs.builder(activity).setTitle(t("Скопировать ключ в Termux?", "Copy key into Termux?")).setMessage(t("SSH этого сервера работает в Termux. Ключ из другого окружения или общей памяти будет скопирован в приватный каталог приложения с правами 600. Оригинал останется, существующие ключи не перезаписываются.", "This server uses Termux SSH. A key from another environment or shared storage will be copied into the app’s private directory with mode 600. The original stays unchanged; existing keys are not overwritten.")).setPositiveButton(t("Скопировать и выбрать", "Copy and select"), DialogInterface.OnClickListener { d, w -> read(source, entry, true, selected) }).setNegativeButton(t("Отмена", "Cancel"), null).create())
    }

    private fun read(source: WorkspaceStore.Session, entry: ProjectFiles.Entry, copy: Boolean, selected: Consumer<String>) {
        if (closed) {
            return
        }
        val progress: AlertDialog = WorkspaceDialogs.builder(activity).setMessage(t("Проверка файла ключа…", "Checking key file…")).setCancelable(false).create()
        show(progress)
        worker.execute(Runnable {
            var result: File? = null
            var success = false
            try {
                if (copy) {
                    result = SshKeyFiles.createPrivate(activity.getFilesDir())
                    imported.add(result)
                    (ProjectFiles(activity, source)).use { files ->
                        files.export(entry, result, SshKeyFiles.MAX)
                    }
                } else {
                    result = File(entry.path)
                }
                SshKeyFiles.validate(result)
                success = true
            } catch (ignored: Exception) {
            } // No raw exceptions or file contents in diagnostics.
            if (closed) {
                if (copy && result != null) {
                    result.delete()
                    imported.remove(result)
                }
                return@Runnable
            }
            val file: File? = result
            val ok: Boolean = success
            activity.runOnUiThread(Runnable {
                progress.dismiss()
                if (closed || !ok) {
                    if (copy && file != null) {
                        file.delete()
                        imported.remove(file)
                    }
                    if (!closed) {
                        show(WorkspaceDialogs.builder(activity).setMessage(t("Не удалось прочитать приватный ключ. Выберите доступный файл приватного ключа (не .pub) размером до 256 КБ.", "Could not read a private key. Choose an accessible private-key file (not .pub), up to 256 KiB.")).setPositiveButton("OK", null).create())
                    }
                    return@Runnable
                }
                selected.accept(file!!.getAbsolutePath())
            })
        })
    }

    fun retain(path: String) {
        try {
            retained = if (path.isEmpty()) "" else File(SshKeyFiles.identity(path)).getCanonicalPath()
        } catch (error: IOException) {
            retained = path
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        localPicker.close()
        for (browser in browsers) {
            browser.close()
        }
        for (dialog in dialogs) {
            if (dialog.isShowing()) {
                dialog.dismiss()
            }
        }
        worker.shutdown() // Only independent, bounded file operations; no user sessions are interrupted.
        synchronized(imported) {
            for (file in imported) {
                var path: String
                try {
                    path = file!!.getCanonicalPath()
                } catch (error: IOException) {
                    path = file!!.getAbsolutePath()
                }
                if (!path.equals(retained)) {
                    file!!.delete()
                }
            }
        }
    }
}
