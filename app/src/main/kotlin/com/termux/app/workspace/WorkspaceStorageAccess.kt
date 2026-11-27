package com.termux.app.workspace

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Activity-owned continuation; permission denial never opens an empty shared-storage browser.*/
class WorkspaceStorageAccess {
    private val activity: Activity
    private var pending: Runnable? = null
    private var declined: Runnable? = null
    private var closed: Boolean = false

    constructor(activity: Activity) {
        this.activity = activity
    }

    private fun t(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    fun granted(): Boolean {
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    }

    fun request(ready: Runnable) {
        request(ready, null)
    }

    fun request(ready: Runnable, cancelled: Runnable?) {
        if (closed || pending != null) {
            return
        }
        if (granted()) {
            ready.run()
            return
        }
        pending = ready
        declined = cancelled
        activity.requestPermissions(arrayOf<String>(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST)
    }

    fun permissionResult(request: Int): Boolean {
        if (request != REQUEST) {
            return false
        }
        complete()
        return true
    }

    fun activityResult(request: Int): Boolean {
        if (request != REQUEST) {
            return false
        }
        complete()
        return true
    }

    private fun complete() {
        if (closed) {
            return
        }
        val ready: Runnable? = pending
        val cancelled: Runnable? = declined
        pending = null
        declined = null
        if (ready == null) {
            return
        }
        if (granted()) {
            ready.run()
            return
        }
        WorkspaceDialogs.builder(activity).setTitle(t("Нет доступа к памяти устройства", "Device storage access denied")).setMessage(t("Разрешите доступ к файлам в настройках Android. Без него содержимое общей памяти недоступно. Приватные папки других приложений могут оставаться закрытыми.", "Allow file access in Android settings. Shared storage cannot be browsed without it. Other apps’ private folders may remain restricted.")).setPositiveButton(t("Открыть настройки", "Open settings"), DialogInterface.OnClickListener { d, w ->
            if (closed) {
                return@OnClickListener
            }
            pending = ready
            declined = cancelled
            val intent = Intent(if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION else Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName()))
            try {
                activity.startActivityForResult(intent, REQUEST)
            } catch (e: ActivityNotFoundException) {
                pending = null
                declined = null
                if (cancelled != null && !closed) {
                    cancelled.run()
                }
            }
        }).setNegativeButton(t("Отмена", "Cancel"), DialogInterface.OnClickListener { d, w ->
            if (cancelled != null && !closed) {
                cancelled.run()
            }
        }).setOnCancelListener(DialogInterface.OnCancelListener { d ->
            if (cancelled != null && !closed) {
                cancelled.run()
            }
        }).show()
    }

    fun close() {
        closed = true
        pending = null
        declined = null
    }

    companion object {
        const val REQUEST: Int = 0x5351
    }
}
