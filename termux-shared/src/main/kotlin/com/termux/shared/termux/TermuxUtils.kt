package com.termux.shared.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import com.termux.shared.R
import com.termux.shared.file.TermuxFileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.models.errors.Error
import com.termux.shared.packages.PackageUtils
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxTask
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.regex.Pattern
import org.apache.commons.io.IOUtils

object TermuxUtils {

    private const val LOG_TAG: String = "TermuxUtils"

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_API_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxAPIPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_API_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_BOOT_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxBootPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_BOOT_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxFloatPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_FLOAT_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_STYLING_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxStylingPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_STYLING_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_TASKER_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxTaskerPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_TASKER_PACKAGE_NAME)
    }

    /**
     * Get the [Context] for [TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME] package.
     *
     * @param context The [Context] to use to get the [Context] of the package.
     * @return Returns the [Context]. This will `null` if an exception is raised.
     **/
    @JvmStatic
    fun getTermuxWidgetPackageContext(context: Context): Context? {
        return PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_WIDGET_PACKAGE_NAME)
    }

    /**
     * Check if Termux app is installed and enabled. This can be used by external apps that don't
     * share `sharedUserId` with the Termux app.
     *
     * If your third-party app is targeting sdk `30` (android `11`), then it needs to add `com.termux`
     * package to the `queries` element or request `QUERY_ALL_PACKAGES` permission in its
     * `AndroidManifest.xml`. Otherwise it will get `PackageSetting{...... com.termux/......} BLOCKED`
     * errors in `logcat` and `RUN_COMMAND` won't work.
     * Check [package-visibility](https://developer.android.com/training/basics/intents/package-visibility#package-name),
     * `QUERY_ALL_PACKAGES` [googleplay policy](https://support.google.com/googleplay/android-developer/answer/10158779
     * and this [article](https://medium.com/androiddevelopers/working-with-package-visibility-dc252829de2d) for more info.
     *
     * `* <manifest
     *     <queries>
     *         <package android:name="com.termux" />
     *    </queries>
     * </manifest>
     * `
     *
     * @param context The context for operations.
     * @return Returns `errmsg` if [TermuxConstants.TERMUX_PACKAGE_NAME] is not installed
     * or disabled, otherwise `null`.
     **/
    @JvmStatic
    fun isTermuxAppInstalled(context: Context): String? {
        return PackageUtils.isAppInstalled(context, TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_PACKAGE_NAME)
    }

    /**
     * Check if Termux:API app is installed and enabled. This can be used by external apps that don't
     * share `sharedUserId` with the Termux:API app.
     *
     * @param context The context for operations.
     * @return Returns `errmsg` if [TermuxConstants.TERMUX_API_PACKAGE_NAME] is not installed
     * or disabled, otherwise `null`.
     **/
    @JvmStatic
    fun isTermuxAPIAppInstalled(context: Context): String? {
        return PackageUtils.isAppInstalled(context, TermuxConstants.TERMUX_API_APP_NAME, TermuxConstants.TERMUX_API_PACKAGE_NAME)
    }

    /**
     * Check if Termux app is installed and accessible. This can only be used by apps that share
     * `sharedUserId` with the Termux app.
     *
     * This is done by checking if first checking if app is installed and enabled and then if
     * `currentPackageContext` can be used to get the [Context] of the app with
     * [TermuxConstants.TERMUX_PACKAGE_NAME] and then if
     * [TermuxConstants.TERMUX_PREFIX_DIR_PATH] exists and has
     * [FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS] permissions. The directory will not
     * be automatically created and neither the missing permissions automatically set.
     *
     * @param currentPackageContext The context of current package.
     * @return Returns `errmsg` if failed to get termux package [Context] or
     *         [TermuxConstants.TERMUX_PREFIX_DIR_PATH] is accessible, otherwise `null`.
     **/
    @JvmStatic
    fun isTermuxAppAccessible(currentPackageContext: Context): String? {
        var errmsg: String? = isTermuxAppInstalled(currentPackageContext)
        if (errmsg == null) {
            val termuxPackageContext: Context? = TermuxUtils.getTermuxPackageContext(currentPackageContext)
            // If failed to get Termux app package context
            if (termuxPackageContext == null) {
                errmsg = currentPackageContext.getString(R.string.error_termux_app_package_context_not_accessible)
            }

            if (errmsg == null) {
                // If TermuxConstants.TERMUX_PREFIX_DIR_PATH is not a directory or does not have required permissions
                val error: Error? = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(false, false)
                if (error != null) {
                    errmsg = currentPackageContext.getString(R.string.error_termux_prefix_dir_path_not_accessible, PackageUtils.getAppNameForPackage(currentPackageContext))
                }
            }
        }

        if (errmsg != null) {
            return errmsg + " " + currentPackageContext.getString(R.string.msg_termux_app_required_by_app, PackageUtils.getAppNameForPackage(currentPackageContext))
        } else {
            return null
        }
    }

    /**
     * Send the [TermuxConstants.BROADCAST_TERMUX_OPENED] broadcast to notify apps that Termux
     * app has been opened.
     *
     * @param context The Context to send the broadcast.
     **/
    @JvmStatic
    fun sendTermuxOpenedBroadcast(context: Context) {
        val broadcast = Intent(TermuxConstants.BROADCAST_TERMUX_OPENED)
        val matches: MutableList<ResolveInfo> = context.getPackageManager().queryBroadcastReceivers(broadcast, 0)

        // send broadcast to registered Termux receivers
        // this technique is needed to work around broadcast changes that Oreo introduced
        for (info in matches) {
            val explicitBroadcast = Intent(broadcast)
            val cname = ComponentName(info.activityInfo.applicationInfo.packageName, info.activityInfo.name)
            explicitBroadcast.setComponent(cname)
            context.sendBroadcast(explicitBroadcast)
        }
    }

    /**
     * Get a markdown [String] for the apps info of all/any termux plugin apps installed.
     *
     * @param currentPackageContext The context of current package.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getTermuxPluginAppsInfoMarkdownString(currentPackageContext: Context?): String? {
        if (currentPackageContext == null) {
            return "null"
        }

        val markdownString = StringBuilder()

        val termuxPluginAppPackageNamesList: MutableList<String>? = TermuxConstants.TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST

        if (termuxPluginAppPackageNamesList != null) {
            var i = 0
            while (i < termuxPluginAppPackageNamesList.size) {
                val termuxPluginAppPackageName: String = termuxPluginAppPackageNamesList.get(i)
                val termuxPluginAppContext: Context? = PackageUtils.getContextForPackage(currentPackageContext, termuxPluginAppPackageName)
                // If the package context for the plugin app is not null, then assume its installed and get its info
                if (termuxPluginAppContext != null) {
                    if (i != 0) {
                        markdownString.append("\n\n")
                    }
                    markdownString.append(getAppInfoMarkdownString(termuxPluginAppContext, false))
                }
                i++
            }
        }

        if (markdownString.toString().isEmpty()) {
            return null
        }

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for the app info. If the `context` passed is different
     * from the [TermuxConstants.TERMUX_PACKAGE_NAME] package context, then this function
     * must have been called by a different package like a plugin, so we return info for both packages
     * if `returnTermuxPackageInfoToo` is `true`.
     *
     * @param currentPackageContext The context of current package.
     * @param returnTermuxPackageInfoToo If set to `true`, then will return info of the
     * [TermuxConstants.TERMUX_PACKAGE_NAME] package as well if its different from current package.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getAppInfoMarkdownString(currentPackageContext: Context?, returnTermuxPackageInfoToo: Boolean): String {
        if (currentPackageContext == null) {
            return "null"
        }

        val markdownString = StringBuilder()

        val termuxPackageContext: Context? = getTermuxPackageContext(currentPackageContext)

        var termuxPackageName: String? = null
        var termuxAppName: String? = null
        if (termuxPackageContext != null) {
            termuxPackageName = PackageUtils.getPackageNameForPackage(termuxPackageContext)
            termuxAppName = PackageUtils.getAppNameForPackage(termuxPackageContext)
        }

        val currentPackageName: String = PackageUtils.getPackageNameForPackage(currentPackageContext)
        val currentAppName: String = PackageUtils.getAppNameForPackage(currentPackageContext)

        val isTermuxPackage: Boolean = termuxPackageName != null && termuxPackageName.equals(currentPackageName)

        if (returnTermuxPackageInfoToo && !isTermuxPackage) {
            markdownString.append("## ").append(currentAppName).append(" App Info (Current)\n")
        } else {
            markdownString.append("## ").append(currentAppName).append(" App Info\n")
        }
        markdownString.append(getAppInfoMarkdownStringInner(currentPackageContext))

        if (returnTermuxPackageInfoToo && termuxPackageContext != null && !isTermuxPackage) {
            markdownString.append("\n\n## ").append(termuxAppName).append(" App Info\n")
            markdownString.append(getAppInfoMarkdownStringInner(termuxPackageContext))
        }

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for the app info for the package associated with the `context`.
     *
     * @param context The context for operations for the package.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getAppInfoMarkdownStringInner(context: Context): String {
        val markdownString = StringBuilder()

        markdownString.append(AndroidUtils.getAppInfoMarkdownString(context))

        var error: Error?
        error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(context, true, true)
        if (error != null) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "TERMUX_FILES_DIR", TermuxConstants.TERMUX_FILES_DIR_PATH)
            AndroidUtils.appendPropertyToMarkdown(markdownString, "IS_TERMUX_FILES_DIR_ACCESSIBLE", "false - " + Error.getMinimalErrorString(error))
        }

        val signingCertificateSHA256Digest: String? = PackageUtils.getSigningCertificateSHA256DigestForPackage(context)
        if (signingCertificateSHA256Digest != null) {
            AndroidUtils.appendPropertyToMarkdown(markdownString, "APK_RELEASE", getAPKRelease(signingCertificateSHA256Digest))
            AndroidUtils.appendPropertyToMarkdown(markdownString, "SIGNING_CERTIFICATE_SHA256_DIGEST", signingCertificateSHA256Digest)
        }

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for reporting an issue.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getReportIssueMarkdownString(context: Context?): String {
        if (context == null) {
            return "null"
        }

        val markdownString = StringBuilder()

        markdownString.append("## Where To Report An Issue")

        markdownString.append("\n\n").append(context.getString(R.string.msg_report_issue, TermuxConstants.TERMUX_WIKI_URL)).append("\n")

        markdownString.append("\n\n### Email\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_SUPPORT_EMAIL_URL, TermuxConstants.TERMUX_SUPPORT_EMAIL_MAILTO_URL)).append("  ")

        markdownString.append("\n\n### Reddit\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_REDDIT_SUBREDDIT, TermuxConstants.TERMUX_REDDIT_SUBREDDIT_URL)).append("  ")

        markdownString.append("\n\n### Github Issues for Termux apps\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_API_APP_NAME, TermuxConstants.TERMUX_API_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_BOOT_APP_NAME, TermuxConstants.TERMUX_BOOT_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_FLOAT_APP_NAME, TermuxConstants.TERMUX_FLOAT_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_STYLING_APP_NAME, TermuxConstants.TERMUX_STYLING_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_TASKER_APP_NAME, TermuxConstants.TERMUX_TASKER_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_WIDGET_APP_NAME, TermuxConstants.TERMUX_WIDGET_GITHUB_ISSUES_REPO_URL)).append("  ")

        markdownString.append("\n\n### Github Issues for Termux packages\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_GAME_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_GAME_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_SCIENCE_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_ROOT_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_ROOT_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_UNSTABLE_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_X11_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_X11_PACKAGES_GITHUB_ISSUES_REPO_URL)).append("  ")

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for important links.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getImportantLinksMarkdownString(context: Context?): String {
        if (context == null) {
            return "null"
        }

        val markdownString = StringBuilder()

        markdownString.append("## Important Links")

        markdownString.append("\n\n### Github\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_API_APP_NAME, TermuxConstants.TERMUX_API_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_BOOT_APP_NAME, TermuxConstants.TERMUX_BOOT_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_FLOAT_APP_NAME, TermuxConstants.TERMUX_FLOAT_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_STYLING_APP_NAME, TermuxConstants.TERMUX_STYLING_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_TASKER_APP_NAME, TermuxConstants.TERMUX_TASKER_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_WIDGET_APP_NAME, TermuxConstants.TERMUX_WIDGET_GITHUB_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_URL)).append("  ")

        markdownString.append("\n\n### Email\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_SUPPORT_EMAIL_URL, TermuxConstants.TERMUX_SUPPORT_EMAIL_MAILTO_URL)).append("  ")

        markdownString.append("\n\n### Reddit\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_REDDIT_SUBREDDIT, TermuxConstants.TERMUX_REDDIT_SUBREDDIT_URL)).append("  ")

        markdownString.append("\n\n### Wiki\n")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_WIKI, TermuxConstants.TERMUX_WIKI_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_APP_NAME, TermuxConstants.TERMUX_GITHUB_WIKI_REPO_URL)).append("  ")
        markdownString.append("\n").append(MarkdownUtils.getLinkMarkdownString(TermuxConstants.TERMUX_PACKAGES_GITHUB_REPO_NAME, TermuxConstants.TERMUX_PACKAGES_GITHUB_WIKI_REPO_URL)).append("  ")

        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for APT info of the app.
     *
     * This will take a few seconds to run due to running `apt update` command.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun geAPTInfoMarkdownString(context: Context): String? {
        var aptInfoScript: String?
        val inputStream: InputStream = context.getResources().openRawResource(R.raw.apt_info_script)
        try {
            aptInfoScript = IOUtils.toString(inputStream, Charset.defaultCharset())
        } catch (e: IOException) {
            Logger.logError(LOG_TAG, "Failed to get APT info script: " + e.message)
            return null
        }

        IOUtils.closeQuietly(inputStream)

        if (aptInfoScript == null || aptInfoScript.isEmpty()) {
            Logger.logError(LOG_TAG, "The APT info script is null or empty")
            return null
        }

        aptInfoScript = (aptInfoScript as java.lang.String).replaceAll(Pattern.quote("@TERMUX_PREFIX@"), TermuxConstants.TERMUX_PREFIX_DIR_PATH)

        val executionCommand = ExecutionCommand(1, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", null, aptInfoScript, null, true, false)
        executionCommand.commandLabel = "APT Info Command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val termuxTask: TermuxTask? = TermuxTask.execute(context, executionCommand, null, TermuxShellEnvironmentClient(), true)
        if (termuxTask == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode!! != 0) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        if (!executionCommand.resultData.stderr.toString().isEmpty()) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
        }

        val markdownString = StringBuilder()

        markdownString.append("## ").append(TermuxConstants.TERMUX_APP_NAME).append(" APT Info\n\n")
        markdownString.append(executionCommand.resultData.stdout.toString())
        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    /**
     * Get a markdown [String] for info for termux debugging.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getTermuxDebugMarkdownString(context: Context): String? {
        val statInfo: String? = TermuxFileUtils.getTermuxFilesStatMarkdownString(context)
        val logcatInfo: String? = getLogcatDumpMarkdownString(context)

        if (statInfo != null && logcatInfo != null) {
            return statInfo + "\n\n" + logcatInfo
        } else if (statInfo != null) {
            return statInfo
        } else {
            return logcatInfo
        }
    }

    /**
     * Get a markdown [String] for logcat command dump.
     *
     * @param context The context for operations.
     * @return Returns the markdown [String].
     **/
    @JvmStatic
    fun getLogcatDumpMarkdownString(context: Context): String? {
        // Build script
        // We need to prevent OutOfMemoryError since StreamGobbler StringBuilder + StringBuilder.toString()
        // may require lot of memory if dump is too large.
        // Putting a limit at 3000 lines. Assuming average 160 chars/line will result in 500KB usage
        // per object.
        // That many lines should be enough for debugging for recent issues anyways assuming termux
        // has not been granted READ_LOGS permission s.
        val logcatScript = "/system/bin/logcat -d -t 3000 2>&1"

        // Run script
        // Logging must be disabled for output of logcat command itself in StreamGobbler
        val executionCommand = ExecutionCommand(1, "/system/bin/sh", null, logcatScript + "\n", "/", true, true)
        executionCommand.commandLabel = "Logcat dump command"
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF
        val termuxTask: TermuxTask? = TermuxTask.execute(context, executionCommand, null, TermuxShellEnvironmentClient(), true)
        if (termuxTask == null || !executionCommand.isSuccessful()) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            return null
        }

        // Build script output
        val logcatOutput = StringBuilder()
        logcatOutput.append("\$ ").append(logcatScript)
        logcatOutput.append("\n").append(executionCommand.resultData.stdout.toString())

        val stderrSet: Boolean = !executionCommand.resultData.stderr.toString().isEmpty()
        if (executionCommand.resultData.exitCode!! != 0 || stderrSet) {
            Logger.logErrorExtended(LOG_TAG, executionCommand.toString())
            if (stderrSet) {
                logcatOutput.append("\n").append(executionCommand.resultData.stderr.toString())
            }
            logcatOutput.append("\n").append("exit code: ").append(executionCommand.resultData.exitCode!!.toString())
        }

        // Build markdown output
        val markdownString = StringBuilder()
        markdownString.append("## Logcat Dump\n\n")
        markdownString.append("\n\n").append(MarkdownUtils.getMarkdownCodeForString(logcatOutput.toString(), true))
        markdownString.append("\n##\n")

        return markdownString.toString()
    }

    @JvmStatic
    fun getAPKRelease(signingCertificateSHA256Digest: String?): String {
        if (signingCertificateSHA256Digest == null) {
            return "null"
        }

        when ((signingCertificateSHA256Digest as java.lang.String).toUpperCase(Locale.ROOT)) {
            TermuxConstants.APK_RELEASE_FDROID_SIGNING_CERTIFICATE_SHA256_DIGEST -> {
                return TermuxConstants.APK_RELEASE_FDROID
            }
            TermuxConstants.APK_RELEASE_GITHUB_SIGNING_CERTIFICATE_SHA256_DIGEST -> {
                return TermuxConstants.APK_RELEASE_GITHUB
            }
            TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST -> {
                return TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE
            }
            else -> {
                return "Unknown"
            }
        }
    }

    /**
     * Get a process id of the main app process of the [TermuxConstants.TERMUX_PACKAGE_NAME]
     * package.
     *
     * @param context The context for operations.
     * @return Returns the process if found and running, otherwise `null`.
     **/
    @JvmStatic
    fun getTermuxAppPID(context: Context): String? {
        return PackageUtils.getPackagePID(context, TermuxConstants.TERMUX_PACKAGE_NAME)
    }
}
