package com.termux.shared.packages

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager
import com.termux.shared.R
import com.termux.shared.data.DataUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.reflection.ReflectionUtils
import com.termux.shared.termux.TermuxConstants
import java.security.MessageDigest

object PackageUtils {

    private const val LOG_TAG: String = "PackageUtils"

    /**
     * Get the [Context] for the package name.
     *
     * @param context The [Context] to use to get the [Context] of the `packageName`.
     * @param packageName The package name whose [Context] to get.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getContextForPackage(context: Context, packageName: String): Context? {
        try {
            return context.createPackageContext(packageName, Context.CONTEXT_RESTRICTED)
        } catch (e: Exception) {
            Logger.logVerbose(LOG_TAG, "Failed to get \"" + packageName + "\" package context: " + e.message)
            return null
        }
    }

    /**
     * Get the [Context] for a package name.
     *
     * @param context The [Context] to use to get the [Context] of the `packageName`.
     * @param packageName The package name whose [Context] to get.
     * @param exitAppOnError If `true` and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getContextForPackageOrExitApp(context: Context, packageName: String, exitAppOnError: Boolean): Context? {
        val packageContext: Context? = getContextForPackage(context, packageName)

        if (packageContext == null && exitAppOnError) {
            val errorMessage: String = context.getString(R.string.error_get_package_context_failed_message, packageName, TermuxConstants.TERMUX_GITHUB_REPO_URL)
            Logger.logError(LOG_TAG, errorMessage)
            MessageDialogUtils.exitAppWithErrorMessage(context, context.getString(R.string.error_get_package_context_failed_title), errorMessage)
        }

        return packageContext
    }

    /**
     * Get the [PackageInfo] for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the [PackageInfo]. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun getPackageInfoForPackage(context: Context): PackageInfo? {
        return getPackageInfoForPackage(context, 0)
    }

    /**
     * Get the [PackageInfo] for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @param flags The flags to pass to [PackageManager.getPackageInfo].
     * @return Returns the [PackageInfo]. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun getPackageInfoForPackage(context: Context, flags: Int): PackageInfo? {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), flags)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get the `seInfo` [Field] of the [ApplicationInfo] class.
     *
     * String retrieved from the seinfo tag found in selinux policy. This value can be set through
     * the mac_permissions.xml policy construct. This value is used for setting an SELinux security
     * context on the process as well as its data directory.
     *
     * https://cs.android.com/android/platform/superproject/+/android-7.1.0_r1:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=609
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=981
     * https://cs.android.com/android/platform/superproject/+/android-7.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/SELinuxMMAC.java;l=282
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/pm/SELinuxMMAC.java;l=375
     * https://cs.android.com/android/_/android/platform/frameworks/base/+/be0b8896d1bc385d4c8fb54c21929745935dcbea
     *
     * @param applicationInfo The [ApplicationInfo] for the package.
     * @return Returns the selinux info or `null` if an exception was raised.
     **/
    @JvmStatic
    fun getApplicationInfoSeInfoForPackage(applicationInfo: ApplicationInfo): String? {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        try {
            return ReflectionUtils.invokeField(ApplicationInfo::class.java, if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "seinfo" else "seInfo", applicationInfo).value as String?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get seInfo field value for ApplicationInfo class", e)
            return null
        }
    }

    /**
     * Get the `seInfoUser` [Field] of the [ApplicationInfo] class.
     *
     * Also check [.getApplicationInfoSeInfoForPackage].
     *
     * @param applicationInfo The [ApplicationInfo] for the package.
     * @return Returns the selinux info user or `null` if an exception was raised.
     **/
    @JvmStatic
    fun getApplicationInfoSeInfoUserForPackage(applicationInfo: ApplicationInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions()
        try {
            return ReflectionUtils.invokeField(ApplicationInfo::class.java, "seInfoUser", applicationInfo).value as String?
        } catch (e: Exception) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get seInfoUser field value for ApplicationInfo class", e)
            return null
        }
    }

    /**
     * Get the app name for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the `android:name` attribute.
     **/
    @JvmStatic
    fun getAppNameForPackage(context: Context): String {
        return context.getApplicationInfo().loadLabel(context.getPackageManager()).toString()
    }

    /**
     * Get the package name for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the package name.
     **/
    @JvmStatic
    fun getPackageNameForPackage(context: Context): String {
        return context.getApplicationInfo().packageName
    }

    /**
     * Get the `targetSdkVersion` for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the `targetSdkVersion`.
     **/
    @JvmStatic
    fun getTargetSDKForPackage(context: Context): Int {
        return context.getApplicationInfo().targetSdkVersion
    }

    /**
     * Check if the app associated with the `context` has [ApplicationInfo.FLAG_DEBUGGABLE]
     * set.
     *
     * @param context The [Context] for the package.
     * @return Returns the `versionName`. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun isAppForPackageADebuggableBuild(context: Context): Boolean? {
        return 0 != context.getApplicationInfo().flags and ApplicationInfo.FLAG_DEBUGGABLE
    }

    /**
     * Check if the app associated with the `context` has [ApplicationInfo.FLAG_EXTERNAL_STORAGE]
     * set.
     *
     * @param context The [Context] for the package.
     * @return Returns the `versionName`. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun isAppInstalledOnExternalStorage(context: Context): Boolean? {
        return 0 != context.getApplicationInfo().flags and ApplicationInfo.FLAG_EXTERNAL_STORAGE
    }

    /**
     * Get the `versionCode` for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the `versionCode`. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun getVersionCodeForPackage(context: Context): Int? {
        try {
            return getPackageInfoForPackage(context)!!.versionCode
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get the `versionName` for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the `versionName`. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun getVersionNameForPackage(context: Context): String? {
        try {
            return getPackageInfoForPackage(context)!!.versionName
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get the `SHA-256 digest` of signing certificate for the package associated with the `context`.
     *
     * @param context The [Context] for the package.
     * @return Returns the `SHA-256 digest`. This will be `null` if an exception is raised.
     **/
    @JvmStatic
    fun getSigningCertificateSHA256DigestForPackage(context: Context): String? {
        try {
            /*
             * Todo: We may need AndroidManifest queries entries if package is installed but with a different signature on android 11
             * https://developer.android.com/training/package-visibility
             * Need a device that allows (manual) installation of apk with mismatched signature of
             * sharedUserId apps to test. Currently, if its done, PackageManager just doesn't load
             * the package and removes its apk automatically if its installed as a user app instead of system app
             * W/PackageManager: Failed to parse /path/to/com.termux.tasker.apk: Signature mismatch for shared user: SharedUserSetting{xxxxxxx com.termux/10xxx}
             **/
            val packageInfo: PackageInfo? = getPackageInfoForPackage(context, PackageManager.GET_SIGNATURES)
            if (packageInfo == null) {
                return null
            }
            return DataUtils.bytesToHex(MessageDigest.getInstance("SHA-256").digest(packageInfo.signatures!![0].toByteArray()))
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get the serial number for the current user.
     *
     * @param context The [Context] for operations.
     * @return Returns the serial number. This will be `null` if failed to get it.
     **/
    @JvmStatic
    fun getSerialNumberForCurrentUser(context: Context): Long? {
        val userManager: UserManager? = context.getSystemService(Context.USER_SERVICE) as UserManager?
        if (userManager == null) {
            return null
        }
        return userManager.getSerialNumberForUser(Process.myUserHandle())
    }

    /**
     * Check if the current user is the primary user. This is done by checking if the the serial
     * number for the current user equals 0.
     *
     * @param context The [Context] for operations.
     * @return Returns `true` if the current user is the primary user, otherwise [@code false}.
     **/
    @JvmStatic
    fun isCurrentUserThePrimaryUser(context: Context): Boolean {
        val userId: Long? = getSerialNumberForCurrentUser(context)
        return userId != null && userId == 0L
    }

    /**
     * Get the profile owner package name for the current user.
     *
     * @param context The [Context] for operations.
     * @return Returns the profile owner package name. This will be `null` if failed to get it
     * or no profile owner for the current user.
     **/
    @JvmStatic
    fun getProfileOwnerPackageNameForUser(context: Context): String? {
        val devicePolicyManager: DevicePolicyManager? = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager?
        if (devicePolicyManager == null) {
            return null
        }
        val activeAdmins: MutableList<ComponentName>? = devicePolicyManager.getActiveAdmins()
        if (activeAdmins != null) {
            for (admin in activeAdmins) {
                val packageName: String = admin.getPackageName()
                if (devicePolicyManager.isProfileOwnerApp(packageName)) {
                    return packageName
                }
            }
        }
        return null
    }

    /**
     * Get the process id of the main app process of a package. This will work for sharedUserId. Note
     * that some apps have multiple processes for the app like with `android:process=":background"`
     * attribute in AndroidManifest.xml.
     *
     * @param context The [Context] for operations.
     * @param packageName The package name of the process.
     * @return Returns the process if found and running, otherwise `null`.
     **/
    @JvmStatic
    fun getPackagePID(context: Context, packageName: String): String? {
        val activityManager: ActivityManager? = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        if (activityManager != null) {
            val processInfos: MutableList<ActivityManager.RunningAppProcessInfo>? = activityManager.getRunningAppProcesses()
            if (processInfos != null) {
                var processInfo: ActivityManager.RunningAppProcessInfo
                var i = 0
                while (i < processInfos.size) {
                    processInfo = processInfos.get(i)
                    if (processInfo.processName.equals(packageName)) {
                        return java.lang.String.valueOf(processInfo.pid)
                    }
                    i++
                }
            }
        }
        return null
    }

    /**
     * Check if app is installed and enabled. This can be used by external apps that don't
     * share `sharedUserId` with the an app.
     *
     * If your third-party app is targeting sdk `30` (android `11`), then it needs to add package
     * name to the `queries` element or request `QUERY_ALL_PACKAGES` permission in its
     * `AndroidManifest.xml`. Otherwise it will get `PackageSetting{...... package_name/......} BLOCKED`
     * errors in `logcat` and `RUN_COMMAND` won't work.
     * Check [package-visibility](https://developer.android.com/training/basics/intents/package-visibility#package-name),
     * `QUERY_ALL_PACKAGES` [googleplay policy](https://support.google.com/googleplay/android-developer/answer/10158779
     * and this [article](https://medium.com/androiddevelopers/working-with-package-visibility-dc252829de2d) for more info.
     *
     * `* <manifest
     *     <queries>
     *         <package android:name="package_name" />
     *    </queries>
     * </manifest>
     * `
     *
     * @param context The context for operations.
     * @return Returns `errmsg` if `packageName` is not installed or disabled, otherwise `null`.
     **/
    @JvmStatic
    fun isAppInstalled(context: Context, appName: String, packageName: String): String? {
        var errmsg: String? = null

        val packageManager: PackageManager = context.getPackageManager()

        var applicationInfo: ApplicationInfo?
        try {
            applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            applicationInfo = null
        }
        val isAppEnabled: Boolean = applicationInfo != null && applicationInfo.enabled

        // If app is not installed or is disabled
        if (!isAppEnabled) {
            errmsg = context.getString(R.string.error_app_not_installed_or_disabled_warning, appName, packageName)
        }

        return errmsg
    }

    /**
     * Enable or disable a [ComponentName] with a call to
     * [PackageManager.setComponentEnabledSetting].
     *
     * @param context The [Context] for operations.
     * @param packageName The package name of the component.
     * @param className The [Class] name of the component.
     * @param state If component should be enabled or disabled.
     * @param toastString If this is not `null` or empty, then a toast before setting state.
     * @param showErrorMessage If an error message toast should be shown.
     * @return Returns the errmsg if failed to set state, otherwise `null`.
     **/
    @JvmStatic
    fun setComponentState(context: Context, packageName: String, className: String, state: Boolean, toastString: String?, showErrorMessage: Boolean): String? {
        try {
            val packageManager: PackageManager? = context.getPackageManager()
            if (packageManager != null) {
                val componentName = ComponentName(packageName, className)
                if (toastString != null) {
                    Logger.showToast(context, toastString, true)
                }
                packageManager.setComponentEnabledSetting(componentName, if (state) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            return null
        } catch (e: Exception) {
            val errmsg: String = context.getString(if (state) R.string.error_enable_component_failed else R.string.error_disable_component_failed, packageName, className) + ": " + e.message
            if (showErrorMessage) {
                Logger.showToast(context, errmsg, true)
            }
            return errmsg
        }
    }

    /**
     * Check if state of a [ComponentName] is [PackageManager.COMPONENT_ENABLED_STATE_DISABLED]
     * with a call to [PackageManager.getComponentEnabledSetting].
     *
     * @param context The [Context] for operations.
     * @param packageName The package name of the component.
     * @param className The [Class] name of the component.
     * @param logErrorMessage If an error message should be logged.
     * @return Returns `true` if disabled, `false` if not and `null` if failed to
     * get the state.
     **/
    @JvmStatic
    fun isComponentDisabled(context: Context, packageName: String, className: String, logErrorMessage: Boolean): Boolean? {
        try {
            val packageManager: PackageManager? = context.getPackageManager()
            if (packageManager != null) {
                val componentName = ComponentName(packageName, className)
                // Will throw IllegalArgumentException: Unknown component: ComponentInfo{} if app
                // for context is not installed or component does not exist.
                return packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        } catch (e: Exception) {
            if (logErrorMessage) {
                Logger.logStackTraceWithMessage(LOG_TAG, context.getString(R.string.error_get_component_state_failed, packageName, className), e)
            }
        }

        return null
    }

    /**
     * Check if an [android.app.Activity] [ComponentName] can be called by calling
     * [PackageManager.queryIntentActivities].
     *
     * @param context The [Context] for operations.
     * @param packageName The package name of the component.
     * @param className The [Class] name of the component.
     * @param flags The flags to filter results.
     * @return Returns `true` if it exists, otherwise `false`.
     **/
    @JvmStatic
    fun doesActivityComponentExist(context: Context, packageName: String, className: String, flags: Int): Boolean {
        try {
            val packageManager: PackageManager? = context.getPackageManager()
            if (packageManager != null) {
                val intent = Intent()
                intent.setClassName(packageName, className)
                return packageManager.queryIntentActivities(intent, flags).size > 0
            }
        } catch (e: Exception) {
            // ignore
        }

        return false
    }
}
