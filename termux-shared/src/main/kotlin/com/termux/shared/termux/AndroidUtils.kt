package com.termux.shared.termux

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.google.common.base.Joiner
import com.termux.shared.android.SELinuxUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.packages.PackageUtils
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone
import java.util.regex.Matcher
import java.util.regex.Pattern

object AndroidUtils {

    /**
     * Get a markdown [String] for the app info for the package associated with the `context`.
     *
     * @param context The context for operations for the package.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getAppInfoMarkdownString(context: Context): String? {
        val markdownString = StringBuilder()
        val applicationInfo: ApplicationInfo? = context.getApplicationInfo()
        if (applicationInfo == null) {
            return null
        }

        AndroidUtils.appendPropertyToMarkdown(markdownString, "APP_NAME", PackageUtils.getAppNameForPackage(context))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "PACKAGE_NAME", PackageUtils.getPackageNameForPackage(context))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "VERSION_NAME", PackageUtils.getVersionNameForPackage(context))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "VERSION_CODE", PackageUtils.getVersionCodeForPackage(context))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "TARGET_SDK", PackageUtils.getTargetSDKForPackage(context))
        AndroidUtils.appendPropertyToMarkdown(markdownString, "IS_DEBUGGABLE_BUILD", PackageUtils.isAppForPackageADebuggableBuild(context))

        if (PackageUtils.isAppInstalledOnExternalStorage(context)!!) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "IS_INSTALLED_ON_EXTERNAL_STORAGE", true)
        }

        AndroidUtils.appendPropertyToMarkdown(markdownString, "SE_PROCESS_CONTEXT", SELinuxUtils.getContext())
        AndroidUtils.appendPropertyToMarkdown(markdownString, "SE_FILE_CONTEXT", SELinuxUtils.getFileContext(context.getFilesDir().getAbsolutePath()))

        val seInfoUser: String? = PackageUtils.getApplicationInfoSeInfoUserForPackage(applicationInfo)
        AndroidUtils.appendPropertyToMarkdown(markdownString, "SE_INFO", PackageUtils.getApplicationInfoSeInfoForPackage(applicationInfo) + (if (DataUtils.isNullOrEmpty(seInfoUser)) "" else seInfoUser))

        val filesDir: String = context.getFilesDir().getAbsolutePath()
        if (!filesDir.equals("/data/user/0/" + context.getPackageName() + "/files") && !filesDir.equals("/data/data/" + context.getPackageName() + "/files")) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "FILES_DIR", filesDir)
        }

        val userId: Long? = PackageUtils.getSerialNumberForCurrentUser(context)
        if (userId == null || userId != 0L) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "USER_ID", userId)
        }

        AndroidUtils.appendPropertyToMarkdownIfSet(markdownString, "PROFILE_OWNER", PackageUtils.getProfileOwnerPackageNameForUser(context))

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for the device info.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getDeviceInfoMarkdownString(context: Context): String {
        // Some properties cannot be read with {@link System#getProperty(String)} but can be read
        // directly by running getprop command
        val systemProperties: Properties = getSystemProperties()

        val markdownString = StringBuilder()

        markdownString.append("## Device Info")

        markdownString.append("\n\n### Software\n")
        appendPropertyToMarkdown(markdownString, "OS_VERSION", getSystemPropertyWithAndroidAPI("os.version"))
        appendPropertyToMarkdown(markdownString, "SDK_INT", Build.VERSION.SDK_INT)
        // If its a release version
        if ("REL".equals(Build.VERSION.CODENAME)) {
            appendPropertyToMarkdown(markdownString, "RELEASE", Build.VERSION.RELEASE)
        } else {
            appendPropertyToMarkdown(markdownString, "CODENAME", Build.VERSION.CODENAME)
        }
        appendPropertyToMarkdown(markdownString, "ID", Build.ID)
        appendPropertyToMarkdown(markdownString, "DISPLAY", Build.DISPLAY)
        appendPropertyToMarkdown(markdownString, "INCREMENTAL", Build.VERSION.INCREMENTAL)
        appendPropertyToMarkdownIfSet(markdownString, "SECURITY_PATCH", systemProperties.getProperty("ro.build.version.security_patch"))
        appendPropertyToMarkdownIfSet(markdownString, "IS_DEBUGGABLE", systemProperties.getProperty("ro.debuggable"))
        appendPropertyToMarkdownIfSet(markdownString, "IS_EMULATOR", systemProperties.getProperty("ro.boot.qemu"))
        appendPropertyToMarkdownIfSet(markdownString, "IS_TREBLE_ENABLED", systemProperties.getProperty("ro.treble.enabled"))
        appendPropertyToMarkdown(markdownString, "TYPE", Build.TYPE)
        appendPropertyToMarkdown(markdownString, "TAGS", Build.TAGS)

        markdownString.append("\n\n### Hardware\n")
        appendPropertyToMarkdown(markdownString, "MANUFACTURER", Build.MANUFACTURER)
        appendPropertyToMarkdown(markdownString, "BRAND", Build.BRAND)
        appendPropertyToMarkdown(markdownString, "MODEL", Build.MODEL)
        appendPropertyToMarkdown(markdownString, "PRODUCT", Build.PRODUCT)
        appendPropertyToMarkdown(markdownString, "BOARD", Build.BOARD)
        appendPropertyToMarkdown(markdownString, "HARDWARE", Build.HARDWARE)
        appendPropertyToMarkdown(markdownString, "DEVICE", Build.DEVICE)

        appendPropertyToMarkdown(markdownString, "SUPPORTED_ABIS", Joiner.on(", ").skipNulls().join(Build.SUPPORTED_ABIS))
        appendPropertyToMarkdown(markdownString, "SUPPORTED_32_BIT_ABIS", Joiner.on(", ").skipNulls().join(Build.SUPPORTED_32_BIT_ABIS))
        appendPropertyToMarkdown(markdownString, "SUPPORTED_64_BIT_ABIS", Joiner.on(", ").skipNulls().join(Build.SUPPORTED_64_BIT_ABIS))

        // If on Android >= 15
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                appendPropertyToMarkdownIfSet(markdownString, "PAGE_SIZE", Os.sysconf(OsConstants._SC_PAGESIZE))
            } catch (t: Throwable) {
                // Ignore
            }
        }

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    @JvmStatic
    fun getSystemProperties(): Properties {
        val systemProperties = Properties()

        // getprop commands returns values in the format `[key]: [value]`
        // Regex matches string starting with a literal `[`,
        // followed by one or more characters that do not match a closing square bracket as the key,
        // followed by a literal `]: [`,
        // followed by one or more characters as the value,
        // followed by string ending with literal `]`
        // multiline values will be ignored
        val propertiesPattern: Pattern = Pattern.compile("^\\[([^]]+)]: \\[(.+)]\$")

        try {
            val process: Process = ProcessBuilder().command("/system/bin/getprop").redirectErrorStream(true).start()

            val inputStream: InputStream = process.getInputStream()
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            var key: String?
            var value: String?

            while (true) {
                line = bufferedReader.readLine()
                if (line == null) break
                val matcher: Matcher = propertiesPattern.matcher(line)
                if (matcher.matches()) {
                    key = matcher.group(1)
                    value = matcher.group(2)
                    if (key != null && value != null && !key.isEmpty() && !value.isEmpty()) {
                        systemProperties.put(key, value)
                    }
                }
            }

            bufferedReader.close()
            process.destroy()
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage("Failed to get run \"/system/bin/getprop\" to get system properties.", e)
        }

        //for (String key : systemProperties.stringPropertyNames()) {
        //    Logger.logVerbose(key + ": " +  systemProperties.get(key));
        //}
        return systemProperties
    }

    @JvmStatic
    fun getSystemPropertyWithAndroidAPI(property: String): String? {
        try {
            return System.getProperty(property)
        } catch (e: Exception) {
            Logger.logVerbose("Failed to get system property \"" + property + "\":" + e.message)
            return null
        }
    }

    @JvmStatic
    fun appendPropertyToMarkdownIfSet(markdownString: StringBuilder, label: String, value: Any?) {
        if (value == null) {
            return
        }
        if (value is String && (value as String).isEmpty() || "REL".equals(value)) {
            return
        }
        markdownString.append("\n").append(getPropertyMarkdown(label, value))
    }

    @JvmStatic
    fun appendPropertyToMarkdown(markdownString: StringBuilder, label: String, value: Any?) {
        markdownString.append("\n").append(getPropertyMarkdown(label, value))
    }

    @JvmStatic
    fun getPropertyMarkdown(label: String, value: Any?): String {
        return MarkdownUtils.getSingleLineMarkdownStringEntry(label, value, "-")
    }

    @JvmStatic
    fun getCurrentTimeStamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
        df.setTimeZone(TimeZone.getTimeZone("UTC"))
        return df.format(Date())
    }

    @JvmStatic
    fun getCurrentMilliSecondUTCTimeStamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z")
        df.setTimeZone(TimeZone.getTimeZone("UTC"))
        return df.format(Date())
    }

    @JvmStatic
    fun getCurrentMilliSecondLocalTimeStamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS")
        df.setTimeZone(TimeZone.getDefault())
        return df.format(Date())
    }
}
