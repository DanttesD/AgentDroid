package com.termux.shared.packages

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.termux.shared.R
import com.termux.shared.logger.Logger
import java.util.Arrays

object PermissionUtils {

    const val REQUEST_GRANT_STORAGE_PERMISSION: Int = 1000

    const val REQUEST_DISABLE_BATTERY_OPTIMIZATIONS: Int = 2000
    const val REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION: Int = 2001

    private const val LOG_TAG: String = "PermissionUtils"

    @JvmStatic
    fun checkPermission(context: Context, permission: String?): Boolean {
        if (permission == null) {
            return false
        }
        return checkPermissions(context, arrayOf<String>(permission))
    }

    @JvmStatic
    fun checkPermissions(context: Context, permissions: Array<String>?): Boolean {
        if (permissions == null) {
            return false
        }

        var result: Int
        for (p in permissions) {
            result = ContextCompat.checkSelfPermission(context, p)
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    @JvmStatic
    fun requestPermission(activity: Activity, permission: String?, requestCode: Int) {
        if (permission == null) {
            return
        }
        requestPermissions(activity, arrayOf<String>(permission), requestCode)
    }

    @JvmStatic
    fun requestPermissions(activity: Activity?, permissions: Array<String>?, requestCode: Int) {
        if (activity == null || permissions == null) {
            return
        }

        var result: Int
        Logger.showToast(activity, activity.getString(R.string.message_sudo_please_grant_permissions), true)
        try {
            Thread.sleep(1000L)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        for (permission in permissions) {
            result = ContextCompat.checkSelfPermission(activity, permission)
            if (result != PackageManager.PERMISSION_GRANTED) {
                Logger.logDebug(LOG_TAG, "Requesting Permissions: " + Arrays.toString(permissions))
                try {
                    activity.requestPermissions(arrayOf<String>(permission), requestCode)
                } catch (e: Exception) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request permissions with request code " + requestCode + ": " + Arrays.toString(permissions), e)
                }
            }
        }
    }

    @JvmStatic
    fun checkDisplayOverOtherAppsPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @JvmStatic
    fun requestDisplayOverOtherAppsPermission(context: Activity, requestCode: Int) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.getPackageName()))
        context.startActivityForResult(intent, requestCode)
    }

    @JvmStatic
    fun validateDisplayOverOtherAppsPermissionForPostAndroid10(context: Context, logResults: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }

        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(context)) {
            if (logResults) {
                Logger.logWarn(LOG_TAG, context.getPackageName() + " does not have Display over other apps (SYSTEM_ALERT_WINDOW) permission")
            }
            return false
        } else {
            if (logResults) {
                Logger.logDebug(LOG_TAG, context.getPackageName() + " already has Display over other apps (SYSTEM_ALERT_WINDOW) permission")
            }
            return true
        }
    }

    @JvmStatic
    fun checkIfBatteryOptimizationsDisabled(context: Context): Boolean {
        val powerManager: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
        return powerManager!!.isIgnoringBatteryOptimizations(context.getPackageName())
    }

    @SuppressLint("BatteryLife")
    @JvmStatic
    fun requestDisableBatteryOptimizations(activity: Activity, requestCode: Int) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.setData(Uri.parse("package:" + activity.getPackageName()))
        activity.startActivityForResult(intent, requestCode)
    }
}
