package com.termux.shared.termux

import android.annotation.SuppressLint
import java.io.File
import java.util.Arrays

/*
 * Version: v0.32.0
 *
 * Changelog
 *
 * - 0.1.0 (2021-03-08)
 *      - Initial Release.
 *
 * - 0.2.0 (2021-03-11)
 *      - Added `_DIR` and `_FILE` substrings to paths.
 *      - Added `INTERNAL_PRIVATE_APP_DATA_DIR*`, `TERMUX_CACHE_DIR*`, `TERMUX_DATABASES_DIR*`,
 *          `TERMUX_SHARED_PREFERENCES_DIR*`, `TERMUX_BIN_PREFIX_DIR*`, `TERMUX_ETC_DIR*`,
 *          `TERMUX_INCLUDE_DIR*`, `TERMUX_LIB_DIR*`, `TERMUX_LIBEXEC_DIR*`, `TERMUX_SHARE_DIR*`,
 *          `TERMUX_TMP_DIR*`, `TERMUX_VAR_DIR*`, `TERMUX_STAGING_PREFIX_DIR*`,
 *          `TERMUX_STORAGE_HOME_DIR*`, `TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME*`,
 *          `TERMUX_DEFAULT_PREFERENCES_FILE`.
 *      - Renamed `DATA_HOME_PATH` to `TERMUX_DATA_HOME_DIR_PATH`.
 *      - Renamed `CONFIG_HOME_PATH` to `TERMUX_CONFIG_HOME_DIR_PATH`.
 *      - Updated javadocs and spacing.
 *
 * - 0.3.0 (2021-03-12)
 *      - Remove `TERMUX_CACHE_DIR_PATH*`, `TERMUX_DATABASES_DIR_PATH*`,
 *          `TERMUX_SHARED_PREFERENCES_DIR_PATH*` since they may not be consistent on all devices.
 *      - Renamed `TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME` to
 *          `TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`. This should be used for
 *           accessing shared preferences between Termux app and its plugins if ever needed by first
 *           getting shared package context with {@link Context.createPackageContext(String,int}).
 *
 * - 0.4.0 (2021-03-16)
 *      - Added `BROADCAST_TERMUX_OPENED`,
 *          `TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`
 *          `TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`,
 *          `TERMUX_FLOAT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`,
 *          `TERMUX_STYLING_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`,
 *          `TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`,
 *          `TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION`.
 *
 * - 0.5.0 (2021-03-16)
 *      - Renamed "Termux Plugin app" labels to "Termux:Tasker app".
 *
 * - 0.6.0 (2021-03-16)
 *      - Added `TERMUX_FILE_SHARE_URI_AUTHORITY`.
 *
 * - 0.7.0 (2021-03-17)
 *      - Fixed javadocs.
 *
 * - 0.8.0 (2021-03-18)
 *      - Fixed Intent extra types javadocs.
 *      - Added following to `TERMUX_SERVICE`:
 *          `EXTRA_PENDING_INTENT`, `EXTRA_RESULT_BUNDLE`,
 *          `EXTRA_STDOUT`, `EXTRA_STDERR`, `EXTRA_EXIT_CODE`,
 *          `EXTRA_ERR`, `EXTRA_ERRMSG`.
 *
 * - 0.9.0 (2021-03-18)
 *      - Fixed javadocs.
 *
 * - 0.10.0 (2021-03-19)
 *      - Added following to `TERMUX_SERVICE`:
 *          `EXTRA_SESSION_ACTION`,
 *          `VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY`,
 *          `VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY`,
 *          `VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY`
 *          `VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY`.
 *      - Added following to `RUN_COMMAND_SERVICE`:
 *          `EXTRA_SESSION_ACTION`.
 *
 * - 0.11.0 (2021-03-24)
 *      - Added following to `TERMUX_SERVICE`:
 *          `EXTRA_COMMAND_LABEL`, `EXTRA_COMMAND_DESCRIPTION`, `EXTRA_COMMAND_HELP`, `EXTRA_PLUGIN_API_HELP`.
 *      - Added following to `RUN_COMMAND_SERVICE`:
 *          `EXTRA_COMMAND_LABEL`, `EXTRA_COMMAND_DESCRIPTION`, `EXTRA_COMMAND_HELP`.
 *      - Updated `RESULT_BUNDLE` related extras with `PLUGIN_RESULT_BUNDLE` prefixes.
 *
 * - 0.12.0 (2021-03-25)
 *      - Added following to `TERMUX_SERVICE`:
 *          `EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH`,
 *          `EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH`.
 *
 * - 0.13.0 (2021-03-25)
 *      - Added following to `RUN_COMMAND_SERVICE`:
 *          `EXTRA_PENDING_INTENT`.
 *
 * - 0.14.0 (2021-03-25)
 *      - Added `FDROID_PACKAGES_BASE_URL`,
 *          `TERMUX_GITHUB_ORGANIZATION_NAME`, `TERMUX_GITHUB_ORGANIZATION_URL`,
 *          `TERMUX_GITHUB_REPO_NAME`, `TERMUX_GITHUB_REPO_URL`, `TERMUX_FDROID_PACKAGE_URL`,
 *          `TERMUX_API_GITHUB_REPO_NAME`,`TERMUX_API_GITHUB_REPO_URL`, `TERMUX_API_FDROID_PACKAGE_URL`,
 *          `TERMUX_BOOT_GITHUB_REPO_NAME`, `TERMUX_BOOT_GITHUB_REPO_URL`, `TERMUX_BOOT_FDROID_PACKAGE_URL`,
 *          `TERMUX_FLOAT_GITHUB_REPO_NAME`, `TERMUX_FLOAT_GITHUB_REPO_URL`, `TERMUX_FLOAT_FDROID_PACKAGE_URL`,
 *          `TERMUX_STYLING_GITHUB_REPO_NAME`, `TERMUX_STYLING_GITHUB_REPO_URL`, `TERMUX_STYLING_FDROID_PACKAGE_URL`,
 *          `TERMUX_TASKER_GITHUB_REPO_NAME`, `TERMUX_TASKER_GITHUB_REPO_URL`, `TERMUX_TASKER_FDROID_PACKAGE_URL`,
 *          `TERMUX_WIDGET_GITHUB_REPO_NAME`, `TERMUX_WIDGET_GITHUB_REPO_URL` `TERMUX_WIDGET_FDROID_PACKAGE_URL`.
 *
 * - 0.15.0 (2021-04-06)
 *      - Fixed some variables that had `PREFIX_` substring missing in their name.
 *      - Added `TERMUX_CRASH_LOG_FILE_PATH`, `TERMUX_CRASH_LOG_BACKUP_FILE_PATH`,
 *          `TERMUX_GITHUB_ISSUES_REPO_URL`, `TERMUX_API_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_BOOT_GITHUB_ISSUES_REPO_URL`, `TERMUX_FLOAT_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_STYLING_GITHUB_ISSUES_REPO_URL`, `TERMUX_TASKER_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_WIDGET_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_GITHUB_WIKI_REPO_URL`, `TERMUX_PACKAGES_GITHUB_WIKI_REPO_URL`,
 *          `TERMUX_PACKAGES_GITHUB_REPO_NAME`, `TERMUX_PACKAGES_GITHUB_REPO_URL`, `TERMUX_PACKAGES_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_GAME_PACKAGES_GITHUB_REPO_NAME`, `TERMUX_GAME_PACKAGES_GITHUB_REPO_URL`, `TERMUX_GAME_PACKAGES_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_NAME`, `TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_URL`, `TERMUX_SCIENCE_PACKAGES_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_ROOT_PACKAGES_GITHUB_REPO_NAME`, `TERMUX_ROOT_PACKAGES_GITHUB_REPO_URL`, `TERMUX_ROOT_PACKAGES_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_NAME`, `TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_URL`, `TERMUX_UNSTABLE_PACKAGES_GITHUB_ISSUES_REPO_URL`,
 *          `TERMUX_X11_PACKAGES_GITHUB_REPO_NAME`, `TERMUX_X11_PACKAGES_GITHUB_REPO_URL`, `TERMUX_X11_PACKAGES_GITHUB_ISSUES_REPO_URL`.
 *      - Added following to `RUN_COMMAND_SERVICE`:
 *          `RUN_COMMAND_API_HELP_URL`.
 *
 * - 0.16.0 (2021-04-06)
 *      - Added `TERMUX_SUPPORT_EMAIL`, `TERMUX_SUPPORT_EMAIL_URL`, `TERMUX_SUPPORT_EMAIL_MAILTO_URL`,
 *          `TERMUX_REDDIT_SUBREDDIT`, `TERMUX_REDDIT_SUBREDDIT_URL`.
 *      - The `TERMUX_SUPPORT_EMAIL_URL` value must be fixed later when email has been set up.
 *
 * - 0.17.0 (2021-04-07)
 *      - Added `TERMUX_APP_NOTIFICATION_CHANNEL_ID`, `TERMUX_APP_NOTIFICATION_CHANNEL_NAME`, `TERMUX_APP_NOTIFICATION_ID`,
 *          `TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID`, `TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME`, `TERMUX_RUN_COMMAND_NOTIFICATION_ID`,
 *          `TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID`, `TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME`,
 *          `TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID`, `TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME`.
 *      - Updated javadocs.
 *
 * - 0.18.0 (2021-04-11)
 *      - Updated `TERMUX_SUPPORT_EMAIL_URL` to a valid email.
 *      - Removed `TERMUX_SUPPORT_EMAIL`.
 *
 * - 0.19.0 (2021-04-12)
 *      - Added `TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS`.
 *      - Added `TERMUX_SERVICE.EXTRA_STDIN`.
 *      - Added `RUN_COMMAND_SERVICE.EXTRA_STDIN`.
 *      - Deprecated `TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE`.
 *
 * - 0.20.0 (2021-05-13)
 *      - Added `TERMUX_WIKI`, `TERMUX_WIKI_URL`, `TERMUX_PLUGIN_APP_NAMES_LIST`, `TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST`.
 *      - Added `TERMUX_SETTINGS_ACTIVITY_NAME`.
 *
 * - 0.21.0 (2021-05-13)
 *      - Added `APK_RELEASE_FDROID`, `APK_RELEASE_FDROID_SIGNING_CERTIFICATE_SHA256_DIGEST`,
 *          `APK_RELEASE_GITHUB_DEBUG_BUILD`, `APK_RELEASE_GITHUB_DEBUG_BUILD_SIGNING_CERTIFICATE_SHA256_DIGEST`,
 *          `APK_RELEASE_GOOGLE_PLAYSTORE`, `APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST`.
 *
 * - 0.22.0 (2021-05-13)
 *      - Added `TERMUX_DONATE_URL`.
 *
 * - 0.23.0 (2021-06-12)
 *      - Rename `INTERNAL_PRIVATE_APP_DATA_DIR_PATH` to `TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH`.
 *
 * - 0.24.0 (2021-06-27)
 *      - Add `COMMA_NORMAL`, `COMMA_ALTERNATIVE`.
 *      - Added following to `TERMUX_APP.TERMUX_SERVICE`:
 *          `EXTRA_RESULT_DIRECTORY`, `EXTRA_RESULT_SINGLE_FILE`, `EXTRA_RESULT_FILE_BASENAME`,
 *          `EXTRA_RESULT_FILE_OUTPUT_FORMAT`, `EXTRA_RESULT_FILE_ERROR_FORMAT`, `EXTRA_RESULT_FILES_SUFFIX`.
 *      - Added following to `TERMUX_APP.RUN_COMMAND_SERVICE`:
 *          `EXTRA_RESULT_DIRECTORY`, `EXTRA_RESULT_SINGLE_FILE`, `EXTRA_RESULT_FILE_BASENAME`,
 *          `EXTRA_RESULT_FILE_OUTPUT_FORMAT`, `EXTRA_RESULT_FILE_ERROR_FORMAT`, `EXTRA_RESULT_FILES_SUFFIX`,
 *          `EXTRA_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS`, `EXTRA_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS`.
 *      - Added following to `RESULT_SENDER`:
 *           `FORMAT_SUCCESS_STDOUT`, `FORMAT_SUCCESS_STDOUT__EXIT_CODE`, `FORMAT_SUCCESS_STDOUT__STDERR__EXIT_CODE`
 *           `FORMAT_FAILED_ERR__ERRMSG__STDOUT__STDERR__EXIT_CODE`,
 *           `RESULT_FILE_ERR_PREFIX`, `RESULT_FILE_ERRMSG_PREFIX` `RESULT_FILE_STDOUT_PREFIX`,
 *           `RESULT_FILE_STDERR_PREFIX`, `RESULT_FILE_EXIT_CODE_PREFIX`.
 *
 * - 0.25.0 (2021-08-19)
 *      - Added following to `TERMUX_APP.TERMUX_SERVICE`:
 *          `EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL`.
 *      - Added following to `TERMUX_APP.RUN_COMMAND_SERVICE`:
 *          `EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL`.
 *
 * - 0.26.0 (2021-08-25)
 *      - Changed `TERMUX_ACTIVITY.ACTION_FAILSAFE_SESSION` to `TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION`.
 *
 * - 0.27.0 (2021-09-02)
 *      - Added `TERMUX_FLOAT_APP_NOTIFICATION_CHANNEL_ID`, `TERMUX_FLOAT_APP_NOTIFICATION_CHANNEL_NAME`,
 *          `TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE_NAME`.
 *      - Added following to `TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE`:
 *          `ACTION_STOP_SERVICE`, `ACTION_SHOW`, `ACTION_HIDE`.
 *
 * - 0.28.0 (2021-09-02)
 *      - Added `TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE*` and `TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE*`.
 *
 * - 0.29.0 (2021-09-04)
 *      - Added `TERMUX_SHORTCUT_TASKS_SCRIPTS_DIR_BASENAME`, `TERMUX_SHORTCUT_SCRIPT_ICONS_DIR_BASENAME`,
 *          `TERMUX_SHORTCUT_SCRIPT_ICONS_DIR_PATH`, `TERMUX_SHORTCUT_SCRIPT_ICONS_DIR`.
 *      - Added following to `TERMUX_WIDGET.TERMUX_WIDGET_PROVIDER`:
 *          `ACTION_WIDGET_ITEM_CLICKED`, `ACTION_REFRESH_WIDGET`, `EXTRA_FILE_CLICKED`.
 *      - Changed naming convention of `TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE.ACTION_*`.
 *      - Fixed wrong path set for `TERMUX_SHORTCUT_SCRIPTS_DIR_PATH`.
 *
 * - 0.30.0 (2021-09-08)
 *      - Changed `APK_RELEASE_GITHUB_DEBUG_BUILD`to `APK_RELEASE_GITHUB` and
 *          `APK_RELEASE_GITHUB_DEBUG_BUILD_SIGNING_CERTIFICATE_SHA256_DIGEST` to
 *          `APK_RELEASE_GITHUB_SIGNING_CERTIFICATE_SHA256_DIGEST`.
 *
 * - 0.31.0 (2021-09-09)
 *      - Added following to `TERMUX_APP.TERMUX_SERVICE`:
 *          `MIN_VALUE_EXTRA_SESSION_ACTION` and `MAX_VALUE_EXTRA_SESSION_ACTION`.
 *
 * - 0.32.0 (2021-09-23)
 *      - Added `TERMUX_API.TERMUX_API_ACTIVITY_NAME`, `TERMUX_TASKER.TERMUX_TASKER_ACTIVITY_NAME`
 *          and `TERMUX_WIDGET.TERMUX_WIDGET_ACTIVITY_NAME`.
 **/
/**
 * A class that defines shared constants of the Termux app and its plugins.
 * This class will be hosted by termux-shared lib and should be imported by other termux plugin
 * apps as is instead of copying constants to random classes. The 3rd party apps can also import
 * it for interacting with termux apps. If changes are made to this file, increment the version number
 * and add an entry in the Changelog section above.
 *
 * Termux app default package name is "com.termux" and is used in [.TERMUX_PREFIX_DIR_PATH].
 * The binaries compiled for termux have [.TERMUX_PREFIX_DIR_PATH] hardcoded in them but it
 * can be changed during compilation.
 *
 * The [.TERMUX_PACKAGE_NAME] must be the same as the applicationId of termux-app build.gradle
 * since its also used by [.TERMUX_FILES_DIR_PATH].
 * If [.TERMUX_PACKAGE_NAME] is changed, then binaries, specially used in bootstrap need to be
 * compiled appropriately. Check https://github.com/termux/termux-packages/wiki/Building-packages
 * for more info.
 *
 * Ideally the only places where changes should be required if changing package name are the following:
 * - The [.TERMUX_PACKAGE_NAME] in [TermuxConstants].
 * - The "applicationId" in "build.gradle" of termux-app. This is package name that android and app
 *      stores will use and is also the final package name stored in "AndroidManifest.xml".
 * - The "manifestPlaceholders" values for [.TERMUX_PACKAGE_NAME] and *_APP_NAME in
 *      "build.gradle" of termux-app.
 * - The "ENTITY" values for [.TERMUX_PACKAGE_NAME] and *_APP_NAME in "strings.xml" of
 *      termux-app and of termux-shared.
 * - The "shortcut.xml" and "*_preferences.xml" files of termux-app since dynamic variables don't
 *      work in it.
 * - Optionally the "package" in "AndroidManifest.xml" if modifying project structure of termux-app.
 *      This is package name for java classes project structure and is prefixed if activity and service
 *      names use dot (.) notation. This is currently not advisable since this will break lot of
 *      stuff, including termux-* packages.
 * - Optionally the *_PATH variables in [TermuxConstants] containing the string "termux".
 *
 * Check https://developer.android.com/studio/build/application-id for info on "package" in
 * "AndroidManifest.xml" and "applicationId" in "build.gradle".
 *
 * The [.TERMUX_PACKAGE_NAME] must be used in source code of Termux app and its plugins instead
 * of hardcoded "com.termux" paths.
 **/
object TermuxConstants {

    /**
     * Termux app constants.
     **/
    object TERMUX_APP {

        /**
         * Termux app core activity.
         **/
        object TERMUX_ACTIVITY {

            /** Intent extra for if termux failsafe session needs to be started and is used by [TERMUX_ACTIVITY] and [TERMUX_SERVICE.ACTION_STOP_SERVICE]*/
            const val EXTRA_FAILSAFE_SESSION: String = TermuxConstants.TERMUX_PACKAGE_NAME + ".app.failsafe_session" // Default: "com.termux.app.failsafe_session"

            /** Intent action to make termux request storage permissions*/
            const val ACTION_REQUEST_PERMISSIONS: String = TermuxConstants.TERMUX_PACKAGE_NAME + ".app.request_storage_permissions" // Default: "com.termux.app.request_storage_permissions"

            /** Intent action to make termux reload its termux session styling*/
            const val ACTION_RELOAD_STYLE: String = TermuxConstants.TERMUX_PACKAGE_NAME + ".app.reload_style" // Default: "com.termux.app.reload_style"
            /** Intent `String` extra for what to reload for the TERMUX_ACTIVITY.ACTION_RELOAD_STYLE intent. This has been deperecated.*/
            @java.lang.Deprecated
            const val EXTRA_RELOAD_STYLE: String = TermuxConstants.TERMUX_PACKAGE_NAME + ".app.reload_style" // Default: "com.termux.app.reload_style"
        }

        /**
         * Termux app core service.
         **/
        object TERMUX_SERVICE {

            /** Intent action to stop TERMUX_SERVICE*/
            const val ACTION_STOP_SERVICE: String = TERMUX_PACKAGE_NAME + ".service_stop" // Default: "com.termux.service_stop"

            /** Intent action to make TERMUX_SERVICE acquire a wakelock*/
            const val ACTION_WAKE_LOCK: String = TERMUX_PACKAGE_NAME + ".service_wake_lock" // Default: "com.termux.service_wake_lock"

            /** Intent action to make TERMUX_SERVICE release wakelock*/
            const val ACTION_WAKE_UNLOCK: String = TERMUX_PACKAGE_NAME + ".service_wake_unlock" // Default: "com.termux.service_wake_unlock"

            /** Intent action to execute command with TERMUX_SERVICE*/
            const val ACTION_SERVICE_EXECUTE: String = TERMUX_PACKAGE_NAME + ".service_execute" // Default: "com.termux.service_execute"

            /** Uri scheme for paths sent via intent to TERMUX_SERVICE*/
            const val URI_SCHEME_SERVICE_EXECUTE: String = TERMUX_PACKAGE_NAME + ".file" // Default: "com.termux.file"
            /** Intent `String[]` extra for arguments to the executable of the command for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_ARGUMENTS: String = TERMUX_PACKAGE_NAME + ".execute.arguments" // Default: "com.termux.execute.arguments"
            /** Intent `String` extra for stdin of the command for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_STDIN: String = TERMUX_PACKAGE_NAME + ".execute.stdin" // Default: "com.termux.execute.stdin"
            /** Intent `String` extra for command current working directory for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_WORKDIR: String = TERMUX_PACKAGE_NAME + ".execute.cwd" // Default: "com.termux.execute.cwd"
            /** Intent `boolean` extra for command background mode for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_BACKGROUND: String = TERMUX_PACKAGE_NAME + ".execute.background" // Default: "com.termux.execute.background"
            /** Intent `String` extra for custom log level for background commands defined by [com.termux.shared.logger.Logger] for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL: String = TERMUX_PACKAGE_NAME + ".execute.background_custom_log_level" // Default: "com.termux.execute.background_custom_log_level"
            /** Intent `String` extra for session action for foreground commands for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_SESSION_ACTION: String = TERMUX_PACKAGE_NAME + ".execute.session_action" // Default: "com.termux.execute.session_action"
            /** Intent `String` extra for label of the command for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_COMMAND_LABEL: String = TERMUX_PACKAGE_NAME + ".execute.command_label" // Default: "com.termux.execute.command_label"
            /** Intent markdown `String` extra for description of the command for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_COMMAND_DESCRIPTION: String = TERMUX_PACKAGE_NAME + ".execute.command_description" // Default: "com.termux.execute.command_description"
            /** Intent markdown `String` extra for help of the command for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_COMMAND_HELP: String = TERMUX_PACKAGE_NAME + ".execute.command_help" // Default: "com.termux.execute.command_help"
            /** Intent markdown `String` extra for help of the plugin API for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent (Internal Use Only)*/
            const val EXTRA_PLUGIN_API_HELP: String = TERMUX_PACKAGE_NAME + ".execute.plugin_api_help" // Default: "com.termux.execute.plugin_help"
            /** Intent `Parcelable` extra for the pending intent that should be sent with the
             * result of the execution command to the execute command caller for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_PENDING_INTENT: String = "pendingIntent" // Default: "pendingIntent"
            /** Intent `String` extra for the directory path in which to write the result of the
             * execution command for the execute command caller for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_RESULT_DIRECTORY: String = TERMUX_PACKAGE_NAME + ".execute.result_directory" // Default: "com.termux.execute.result_directory"
            /** Intent `boolean` extra for whether the result should be written to a single file
             * or multiple files (err, errmsg, stdout, stderr, exit_code) in
             * [.EXTRA_RESULT_DIRECTORY] for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_RESULT_SINGLE_FILE: String = TERMUX_PACKAGE_NAME + ".execute.result_single_file" // Default: "com.termux.execute.result_single_file"
            /** Intent `String` extra for the basename of the result file that should be created
             * in [.EXTRA_RESULT_DIRECTORY] if [.EXTRA_RESULT_SINGLE_FILE] is `true`
             * for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_RESULT_FILE_BASENAME: String = TERMUX_PACKAGE_NAME + ".execute.result_file_basename" // Default: "com.termux.execute.result_file_basename"
            /** Intent `String` extra for the output [Formatter] format of the
             * [.EXTRA_RESULT_FILE_BASENAME] result file for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_RESULT_FILE_OUTPUT_FORMAT: String = TERMUX_PACKAGE_NAME + ".execute.result_file_output_format" // Default: "com.termux.execute.result_file_output_format"
            /** Intent `String` extra for the error [Formatter] format of the
             * [.EXTRA_RESULT_FILE_BASENAME] result file for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_RESULT_FILE_ERROR_FORMAT: String = TERMUX_PACKAGE_NAME + ".execute.result_file_error_format" // Default: "com.termux.execute.result_file_error_format"
            /** Intent `String` extra for the optional suffix of the result files that should
             * be created in [.EXTRA_RESULT_DIRECTORY] if [.EXTRA_RESULT_SINGLE_FILE] is
             * `false` for the TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent*/
            const val EXTRA_RESULT_FILES_SUFFIX: String = TERMUX_PACKAGE_NAME + ".execute.result_files_suffix" // Default: "com.termux.execute.result_files_suffix"

            /** The value for [.EXTRA_SESSION_ACTION] extra that will set the new session as
             * the current session and will start [TERMUX_ACTIVITY] if its not running to bring
             * the new session to foreground.
             **/
            const val VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY: Int = 0

            /** The value for [.EXTRA_SESSION_ACTION] extra that will keep any existing session
             * as the current session and will start [TERMUX_ACTIVITY] if its not running to
             * bring the existing session to foreground. The new session will be added to the left
             * sidebar in the sessions list.
             **/
            const val VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY: Int = 1

            /** The value for [.EXTRA_SESSION_ACTION] extra that will set the new session as
             * the current session but will not start [TERMUX_ACTIVITY] if its not running
             * and session(s) will be seen in Termux notification and can be clicked to bring new
             * session to foreground. If the [TERMUX_ACTIVITY] is already running, then this
             * will behave like [.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY].
             **/
            const val VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY: Int = 2

            /** The value for [.EXTRA_SESSION_ACTION] extra that will keep any existing session
             * as the current session but will not start [TERMUX_ACTIVITY] if its not running
             * and session(s) will be seen in Termux notification and can be clicked to bring
             * existing session to foreground. If the [TERMUX_ACTIVITY] is already running,
             * then this will behave like [.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY].
             **/
            const val VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY: Int = 3

            /** The minimum allowed value for [.EXTRA_SESSION_ACTION].*/
            const val MIN_VALUE_EXTRA_SESSION_ACTION: Int = VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY

            /** The maximum allowed value for [.EXTRA_SESSION_ACTION].*/
            const val MAX_VALUE_EXTRA_SESSION_ACTION: Int = VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY

            /** Intent `Bundle` extra to store result of execute command that is sent back for the
             * TERMUX_SERVICE.ACTION_SERVICE_EXECUTE intent if the [.EXTRA_PENDING_INTENT] is not
             * `null`*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE: String = "result" // Default: "result"
            /** Intent `String` extra for stdout value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT: String = "stdout" // Default: "stdout"
            /** Intent `String` extra for original length of stdout value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH: String = "stdout_original_length" // Default: "stdout_original_length"
            /** Intent `String` extra for stderr value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR: String = "stderr" // Default: "stderr"
            /** Intent `String` extra for original length of stderr value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH: String = "stderr_original_length" // Default: "stderr_original_length"
            /** Intent `int` extra for exit code value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE: String = "exitCode" // Default: "exitCode"
            /** Intent `int` extra for err value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_ERR: String = "err" // Default: "err"
            /** Intent `String` extra for errmsg value of execute command of the [.EXTRA_PLUGIN_RESULT_BUNDLE]*/
            const val EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG: String = "errmsg" // Default: "errmsg"
        }

        /**
         * Termux app run command service to receive commands sent by 3rd party apps.
         **/
        object RUN_COMMAND_SERVICE {

            /** Termux RUN_COMMAND Intent help url*/
            const val RUN_COMMAND_API_HELP_URL: String = TERMUX_GITHUB_WIKI_REPO_URL + "/RUN_COMMAND-Intent" // Default: "https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent"

            /** Intent action to execute command with RUN_COMMAND_SERVICE*/
            const val ACTION_RUN_COMMAND: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND" // Default: "com.termux.RUN_COMMAND"

            /** Intent `String` extra for absolute path of command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_COMMAND_PATH: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_PATH" // Default: "com.termux.RUN_COMMAND_PATH"
            /** Intent `String[]` extra for arguments to the executable of the command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_ARGUMENTS: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_ARGUMENTS" // Default: "com.termux.RUN_COMMAND_ARGUMENTS"
            /** Intent `boolean` extra for whether to replace comma alternative characters in arguments with comma characters for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS" // Default: "com.termux.RUN_COMMAND_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS"
            /** Intent `String` extra for the comma alternative characters in arguments that should be replaced instead of the default [.COMMA_ALTERNATIVE] for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS" // Default: "com.termux.RUN_COMMAND_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS"

            /** Intent `String` extra for stdin of the command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_STDIN: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_STDIN" // Default: "com.termux.RUN_COMMAND_STDIN"
            /** Intent `String` extra for current working directory of command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_WORKDIR: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_WORKDIR" // Default: "com.termux.RUN_COMMAND_WORKDIR"
            /** Intent `boolean` extra for whether to run command in background or foreground terminal session for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_BACKGROUND: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_BACKGROUND" // Default: "com.termux.RUN_COMMAND_BACKGROUND"
            /** Intent `String` extra for custom log level for background commands defined by [com.termux.shared.logger.Logger] for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL" // Default: "com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL"
            /** Intent `String` extra for session action of foreground commands for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_SESSION_ACTION: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_SESSION_ACTION" // Default: "com.termux.RUN_COMMAND_SESSION_ACTION"
            /** Intent `String` extra for label of the command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_COMMAND_LABEL: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_COMMAND_LABEL" // Default: "com.termux.RUN_COMMAND_COMMAND_LABEL"
            /** Intent markdown `String` extra for description of the command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_COMMAND_DESCRIPTION: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_COMMAND_DESCRIPTION" // Default: "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
            /** Intent markdown `String` extra for help of the command for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_COMMAND_HELP: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_COMMAND_HELP" // Default: "com.termux.RUN_COMMAND_COMMAND_HELP"
            /** Intent `Parcelable` extra for the pending intent that should be sent with the result of the execution command to the execute command caller for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_PENDING_INTENT: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_PENDING_INTENT" // Default: "com.termux.RUN_COMMAND_PENDING_INTENT"
            /** Intent `String` extra for the directory path in which to write the result of
             * the execution command for the execute command caller for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_RESULT_DIRECTORY: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_RESULT_DIRECTORY" // Default: "com.termux.RUN_COMMAND_RESULT_DIRECTORY"
            /** Intent `boolean` extra for whether the result should be written to a single file
             * or multiple files (err, errmsg, stdout, stderr, exit_code) in
             * [.EXTRA_RESULT_DIRECTORY] for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_RESULT_SINGLE_FILE: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_RESULT_SINGLE_FILE" // Default: "com.termux.RUN_COMMAND_RESULT_SINGLE_FILE"
            /** Intent `String` extra for the basename of the result file that should be created
             * in [.EXTRA_RESULT_DIRECTORY] if [.EXTRA_RESULT_SINGLE_FILE] is `true`
             * for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_RESULT_FILE_BASENAME: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_RESULT_FILE_BASENAME" // Default: "com.termux.RUN_COMMAND_RESULT_FILE_BASENAME"
            /** Intent `String` extra for the output [Formatter] format of the
             * [.EXTRA_RESULT_FILE_BASENAME] result file for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_RESULT_FILE_OUTPUT_FORMAT: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_RESULT_FILE_OUTPUT_FORMAT" // Default: "com.termux.RUN_COMMAND_RESULT_FILE_OUTPUT_FORMAT"
            /** Intent `String` extra for the error [Formatter] format of the
             * [.EXTRA_RESULT_FILE_BASENAME] result file for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_RESULT_FILE_ERROR_FORMAT: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_RESULT_FILE_ERROR_FORMAT" // Default: "com.termux.RUN_COMMAND_RESULT_FILE_ERROR_FORMAT"
            /** Intent `String` extra for the optional suffix of the result files that should be
             * created in [.EXTRA_RESULT_DIRECTORY] if [.EXTRA_RESULT_SINGLE_FILE] is
             * `false` for the RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND intent*/
            const val EXTRA_RESULT_FILES_SUFFIX: String = TERMUX_PACKAGE_NAME + ".RUN_COMMAND_RESULT_FILES_SUFFIX" // Default: "com.termux.RUN_COMMAND_RESULT_FILES_SUFFIX"
        }

        /** Termux app core activity name.*/
        const val TERMUX_ACTIVITY_NAME: String = TERMUX_PACKAGE_NAME + ".app.TermuxActivity" // Default: "com.termux.app.TermuxActivity"

        /** Termux app settings activity name.*/
        const val TERMUX_SETTINGS_ACTIVITY_NAME: String = TERMUX_PACKAGE_NAME + ".app.activities.SettingsActivity" // Default: "com.termux.app.activities.SettingsActivity"

        /** Termux app core service name.*/
        const val TERMUX_SERVICE_NAME: String = TERMUX_PACKAGE_NAME + ".app.TermuxService" // Default: "com.termux.app.TermuxService"

        /** Termux app run command service name.*/
        const val RUN_COMMAND_SERVICE_NAME: String = TERMUX_PACKAGE_NAME + ".app.RunCommandService" // Termux app service to receive commands from 3rd party apps "com.termux.app.RunCommandService"
    }

    /**
     * Termux class to send back results of commands to their callers like plugin or 3rd party apps.
     **/
    object RESULT_SENDER {

        /*
         * The default `Formatter` format strings to use for `ResultConfig#resultFileBasename`
         * if `ResultConfig#resultSingleFile` is `true`.
         **/

        /** The [Formatter] format string for success if only `stdout` needs to be written to
         * [ResultConfig.resultFileBasename] where `stdout` maps to `%1$s`.
         * This is used when `err` equals [Errno.ERRNO_SUCCESS] (-1) and `stderr` is empty
         * and `exit_code` equals `0` and [ResultConfig.resultFileOutputFormat] is not passed.*/
        const val FORMAT_SUCCESS_STDOUT: String = "%1\$s%n"
        /** The [Formatter] format string for success if `stdout` and `exit_code` need to be written to
         * [ResultConfig.resultFileBasename] where `stdout` maps to `%1$s` and `exit_code` to `%2$s`.
         * This is used when `err` equals [Errno.ERRNO_SUCCESS] (-1) and `stderr` is empty
         * and `exit_code` does not equal `0` and [ResultConfig.resultFileOutputFormat] is not passed.
         * The exit code will be placed in a markdown inline code.*/
        const val FORMAT_SUCCESS_STDOUT__EXIT_CODE: String = "%1\$s%n%n%n%nexit_code=%2\$s%n"
        /** The [Formatter] format string for success if `stdout`, `stderr` and `exit_code` need to be
         * written to [ResultConfig.resultFileBasename] where `stdout` maps to `%1$s`, `stderr`
         * maps to `%2$s` and `exit_code` to `%3$s`.
         * This is used when `err` equals [Errno.ERRNO_SUCCESS] (-1) and `stderr` is not empty
         * and [ResultConfig.resultFileOutputFormat] is not passed.
         * The stdout and stderr will be placed in a markdown code block. The exit code will be placed
         * in a markdown inline code. The surrounding backticks will be 3 more than the consecutive
         * backticks in any parameter itself for code blocks.*/
        const val FORMAT_SUCCESS_STDOUT__STDERR__EXIT_CODE: String = "stdout=%n%1\$s%n%n%n%nstderr=%n%2\$s%n%n%n%nexit_code=%3\$s%n"
        /** The [Formatter] format string for failure if `err`, `errmsg`(`error`), `stdout`,
         * `stderr` and `exit_code` need to be written to [ResultConfig.resultFileBasename] where
         * `err` maps to `%1$s`, `errmsg` maps to `%2$s`, `stdout` maps
         * to `%3$s`, `stderr` to `%4$s` and `exit_code` maps to `%5$s`.
         * Do not define an argument greater than `5`, like `%6$s` if you change this value since it will
         * raise [IllegalFormatException].
         * This is used when `err` does not equal [Errno.ERRNO_SUCCESS] (-1) and
         * [ResultConfig.resultFileErrorFormat] is not passed.
         * The errmsg, stdout and stderr will be placed in a markdown code block. The err and exit code
         * will be placed in a markdown inline code. The surrounding backticks will be 3 more than
         * the consecutive backticks in any parameter itself for code blocks. The stdout, stderr
         * and exit code may be empty without any surrounding backticks if not set.*/
        const val FORMAT_FAILED_ERR__ERRMSG__STDOUT__STDERR__EXIT_CODE: String = "err=%1\$s%n%n%n%nerrmsg=%n%2\$s%n%n%n%nstdout=%n%3\$s%n%n%n%nstderr=%n%4\$s%n%n%n%nexit_code=%5\$s%n"

        /*
         * The default prefixes to use for result files under `ResultConfig#resultDirectoryPath`
         * if `ResultConfig#resultSingleFile` is `false`.
         **/

        /** The prefix for the err result file.*/
        const val RESULT_FILE_ERR_PREFIX: String = "err"
        /** The prefix for the errmsg result file.*/
        const val RESULT_FILE_ERRMSG_PREFIX: String = "errmsg"
        /** The prefix for the stdout result file.*/
        const val RESULT_FILE_STDOUT_PREFIX: String = "stdout"
        /** The prefix for the stderr result file.*/
        const val RESULT_FILE_STDERR_PREFIX: String = "stderr"
        /** The prefix for the exitCode result file.*/
        const val RESULT_FILE_EXIT_CODE_PREFIX: String = "exit_code"
    }

    /**
     * Termux:API app constants.
     **/
    object TERMUX_API {

        /** Termux:API app core activity name.*/
        const val TERMUX_API_ACTIVITY_NAME: String = TERMUX_API_PACKAGE_NAME + ".activities.TermuxAPIActivity" // Default: "com.termux.tasker.activities.TermuxAPIActivity"
    }

    /**
     * Termux:Float app constants.
     **/
    object TERMUX_FLOAT_APP {

        /**
         * Termux:Float app core service.
         **/
        object TERMUX_FLOAT_SERVICE {

            /** Intent action to stop TERMUX_FLOAT_SERVICE.*/
            const val ACTION_STOP_SERVICE: String = TERMUX_FLOAT_PACKAGE_NAME + ".ACTION_STOP_SERVICE" // Default: "com.termux.float.ACTION_STOP_SERVICE"

            /** Intent action to show float window.*/
            const val ACTION_SHOW: String = TERMUX_FLOAT_PACKAGE_NAME + ".ACTION_SHOW" // Default: "com.termux.float.ACTION_SHOW"

            /** Intent action to hide float window.*/
            const val ACTION_HIDE: String = TERMUX_FLOAT_PACKAGE_NAME + ".ACTION_HIDE" // Default: "com.termux.float.ACTION_HIDE"
        }

        /** Termux:Float app core service name.*/
        const val TERMUX_FLOAT_SERVICE_NAME: String = TERMUX_FLOAT_PACKAGE_NAME + ".TermuxFloatService" // Default: "com.termux.window.TermuxFloatService"
    }

    /**
     * Termux:Styling app constants.
     **/
    object TERMUX_STYLING {

        /** Termux:Styling app core activity name.*/
        const val TERMUX_STYLING_ACTIVITY_NAME: String = TERMUX_STYLING_PACKAGE_NAME + ".TermuxStyleActivity" // Default: "com.termux.styling.TermuxStyleActivity"
    }

    /**
     * Termux:Tasker app constants.
     **/
    object TERMUX_TASKER {

        /** Termux:Tasker app core activity name.*/
        const val TERMUX_TASKER_ACTIVITY_NAME: String = TERMUX_TASKER_PACKAGE_NAME + ".activities.TermuxTaskerActivity" // Default: "com.termux.tasker.activities.TermuxTaskerActivity"
    }

    /**
     * Termux:Widget app constants.
     **/
    object TERMUX_WIDGET {

        /**
         * Termux:Widget app [android.appwidget.AppWidgetProvider] class.
         **/
        object TERMUX_WIDGET_PROVIDER {

            /** Intent action for if an item is clicked in the widget.*/
            const val ACTION_WIDGET_ITEM_CLICKED: String = TERMUX_WIDGET_PACKAGE_NAME + ".ACTION_WIDGET_ITEM_CLICKED" // Default: "com.termux.widget.ACTION_WIDGET_ITEM_CLICKED"

            /** Intent action to refresh files in the widget.*/
            const val ACTION_REFRESH_WIDGET: String = TERMUX_WIDGET_PACKAGE_NAME + ".ACTION_REFRESH_WIDGET" // Default: "com.termux.widget.ACTION_REFRESH_WIDGET"

            /**  Intent `String` extra for the file clicked for the TERMUX_WIDGET_PROVIDER.ACTION_WIDGET_ITEM_CLICKED intent.*/
            const val EXTRA_FILE_CLICKED: String = TERMUX_WIDGET_PACKAGE_NAME + ".EXTRA_FILE_CLICKED" // Default: "com.termux.widget.EXTRA_FILE_CLICKED"
        }

        /** Termux:Widget app core activity name.*/
        const val TERMUX_WIDGET_ACTIVITY_NAME: String = TERMUX_WIDGET_PACKAGE_NAME + ".activities.TermuxWidgetActivity" // Default: "com.termux.widget.activities.TermuxWidgetActivity"

        /**  Intent `String` extra for the token of the Termux:Widget app shortcuts.*/
        const val EXTRA_TOKEN_NAME: String = TERMUX_PACKAGE_NAME + ".shortcut.token" // Default: "com.termux.shortcut.token"
    }

    /*
     * Termux organization variables.
     **/

    /** Termux Github organization name*/
    const val TERMUX_GITHUB_ORGANIZATION_NAME: String = "termux" // Default: "termux"
    /** Termux Github organization url*/
    const val TERMUX_GITHUB_ORGANIZATION_URL: String = "https://github.com/" + TERMUX_GITHUB_ORGANIZATION_NAME // Default: "https://github.com/termux"

    /** F-Droid packages base url*/
    const val FDROID_PACKAGES_BASE_URL: String = "https://f-droid.org/en/packages" // Default: "https://f-droid.org/en/packages"

    /*
     * Termux and its plugin app and package names and urls.
     **/

    /** Termux app name*/
    const val TERMUX_APP_NAME: String = "Termux" // Default: "Termux"
    /** Termux package name*/
    const val TERMUX_PACKAGE_NAME: String = "com.agentdroid" // Default: "com.termux"
    /** Termux Github repo name*/
    const val TERMUX_GITHUB_REPO_NAME: String = "termux-app" // Default: "termux-app"
    /** Termux Github repo url*/
    const val TERMUX_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-app"
    /** Termux Github issues repo url*/
    const val TERMUX_GITHUB_ISSUES_REPO_URL: String = TERMUX_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-app/issues"
    /** Termux F-Droid package url*/
    const val TERMUX_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux"

    /** Termux:API app name*/
    const val TERMUX_API_APP_NAME: String = "Termux:API" // Default: "Termux:API"
    /** Termux:API app package name*/
    const val TERMUX_API_PACKAGE_NAME: String = TERMUX_PACKAGE_NAME + ".api" // Default: "com.termux.api"
    /** Termux:API Github repo name*/
    const val TERMUX_API_GITHUB_REPO_NAME: String = "termux-api" // Default: "termux-api"
    /** Termux:API Github repo url*/
    const val TERMUX_API_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_API_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-api"
    /** Termux:API Github issues repo url*/
    const val TERMUX_API_GITHUB_ISSUES_REPO_URL: String = TERMUX_API_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-api/issues"
    /** Termux:API F-Droid package url*/
    const val TERMUX_API_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_API_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux.api"

    /** Termux:Boot app name*/
    const val TERMUX_BOOT_APP_NAME: String = "Termux:Boot" // Default: "Termux:Boot"
    /** Termux:Boot app package name*/
    const val TERMUX_BOOT_PACKAGE_NAME: String = TERMUX_PACKAGE_NAME + ".boot" // Default: "com.termux.boot"
    /** Termux:Boot Github repo name*/
    const val TERMUX_BOOT_GITHUB_REPO_NAME: String = "termux-boot" // Default: "termux-boot"
    /** Termux:Boot Github repo url*/
    const val TERMUX_BOOT_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_BOOT_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-boot"
    /** Termux:Boot Github issues repo url*/
    const val TERMUX_BOOT_GITHUB_ISSUES_REPO_URL: String = TERMUX_BOOT_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-boot/issues"
    /** Termux:Boot F-Droid package url*/
    const val TERMUX_BOOT_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_BOOT_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux.boot"

    /** Termux:Float app name*/
    const val TERMUX_FLOAT_APP_NAME: String = "Termux:Float" // Default: "Termux:Float"
    /** Termux:Float app package name*/
    const val TERMUX_FLOAT_PACKAGE_NAME: String = TERMUX_PACKAGE_NAME + ".window" // Default: "com.termux.window"
    /** Termux:Float Github repo name*/
    const val TERMUX_FLOAT_GITHUB_REPO_NAME: String = "termux-float" // Default: "termux-float"
    /** Termux:Float Github repo url*/
    const val TERMUX_FLOAT_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_FLOAT_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-float"
    /** Termux:Float Github issues repo url*/
    const val TERMUX_FLOAT_GITHUB_ISSUES_REPO_URL: String = TERMUX_FLOAT_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-float/issues"
    /** Termux:Float F-Droid package url*/
    const val TERMUX_FLOAT_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_FLOAT_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux.window"

    /** Termux:Styling app name*/
    const val TERMUX_STYLING_APP_NAME: String = "Termux:Styling" // Default: "Termux:Styling"
    /** Termux:Styling app package name*/
    const val TERMUX_STYLING_PACKAGE_NAME: String = TERMUX_PACKAGE_NAME + ".styling" // Default: "com.termux.styling"
    /** Termux:Styling Github repo name*/
    const val TERMUX_STYLING_GITHUB_REPO_NAME: String = "termux-styling" // Default: "termux-styling"
    /** Termux:Styling Github repo url*/
    const val TERMUX_STYLING_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_STYLING_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-styling"
    /** Termux:Styling Github issues repo url*/
    const val TERMUX_STYLING_GITHUB_ISSUES_REPO_URL: String = TERMUX_STYLING_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-styling/issues"
    /** Termux:Styling F-Droid package url*/
    const val TERMUX_STYLING_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_STYLING_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux.styling"

    /** Termux:Tasker app name*/
    const val TERMUX_TASKER_APP_NAME: String = "Termux:Tasker" // Default: "Termux:Tasker"
    /** Termux:Tasker app package name*/
    const val TERMUX_TASKER_PACKAGE_NAME: String = TERMUX_PACKAGE_NAME + ".tasker" // Default: "com.termux.tasker"
    /** Termux:Tasker Github repo name*/
    const val TERMUX_TASKER_GITHUB_REPO_NAME: String = "termux-tasker" // Default: "termux-tasker"
    /** Termux:Tasker Github repo url*/
    const val TERMUX_TASKER_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_TASKER_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-tasker"
    /** Termux:Tasker Github issues repo url*/
    const val TERMUX_TASKER_GITHUB_ISSUES_REPO_URL: String = TERMUX_TASKER_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-tasker/issues"
    /** Termux:Tasker F-Droid package url*/
    const val TERMUX_TASKER_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_TASKER_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux.tasker"

    /** Termux:Widget app name*/
    const val TERMUX_WIDGET_APP_NAME: String = "Termux:Widget" // Default: "Termux:Widget"
    /** Termux:Widget app package name*/
    const val TERMUX_WIDGET_PACKAGE_NAME: String = TERMUX_PACKAGE_NAME + ".widget" // Default: "com.termux.widget"
    /** Termux:Widget Github repo name*/
    const val TERMUX_WIDGET_GITHUB_REPO_NAME: String = "termux-widget" // Default: "termux-widget"
    /** Termux:Widget Github repo url*/
    const val TERMUX_WIDGET_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_WIDGET_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-widget"
    /** Termux:Widget Github issues repo url*/
    const val TERMUX_WIDGET_GITHUB_ISSUES_REPO_URL: String = TERMUX_WIDGET_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-widget/issues"
    /** Termux:Widget F-Droid package url*/
    const val TERMUX_WIDGET_FDROID_PACKAGE_URL: String = FDROID_PACKAGES_BASE_URL + "/" + TERMUX_WIDGET_PACKAGE_NAME // Default: "https://f-droid.org/en/packages/com.termux.widget"

    /*
     * Termux plugin apps lists.
     **/
    @JvmField val TERMUX_PLUGIN_APP_NAMES_LIST: MutableList<String> = Arrays.asList(TERMUX_API_APP_NAME, TERMUX_BOOT_APP_NAME, TERMUX_FLOAT_APP_NAME, TERMUX_STYLING_APP_NAME, TERMUX_TASKER_APP_NAME, TERMUX_WIDGET_APP_NAME)

    @JvmField val TERMUX_PLUGIN_APP_PACKAGE_NAMES_LIST: MutableList<String> = Arrays.asList(TERMUX_API_PACKAGE_NAME, TERMUX_BOOT_PACKAGE_NAME, TERMUX_FLOAT_PACKAGE_NAME, TERMUX_STYLING_PACKAGE_NAME, TERMUX_TASKER_PACKAGE_NAME, TERMUX_WIDGET_PACKAGE_NAME)

    /*
     * Termux APK releases.
     **/

    /** F-Droid APK release*/
    const val APK_RELEASE_FDROID: String = "F-Droid" // Default: "F-Droid"

    /** F-Droid APK release signing certificate SHA-256 digest*/
    const val APK_RELEASE_FDROID_SIGNING_CERTIFICATE_SHA256_DIGEST: String = "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42" // Default: "228FB2CFE90831C1499EC3CCAF61E96E8E1CE70766B9474672CE427334D41C42"

    /** Github APK release*/
    const val APK_RELEASE_GITHUB: String = "Github" // Default: "Github"

    /** Github APK release signing certificate SHA-256 digest*/
    const val APK_RELEASE_GITHUB_SIGNING_CERTIFICATE_SHA256_DIGEST: String = "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1" // Default: "B6DA01480EEFD5FBF2CD3771B8D1021EC791304BDD6C4BF41D3FAABAD48EE5E1"

    /** Google Play Store APK release*/
    const val APK_RELEASE_GOOGLE_PLAYSTORE: String = "Google Play Store" // Default: "Google Play Store"

    /** Google Play Store APK release signing certificate SHA-256 digest*/
    const val APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST: String = "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9" // Default: "738F0A30A04D3C8A1BE304AF18D0779BCF3EA88FB60808F657A3521861C2EBF9"

    /*
     * Termux packages urls.
     **/

    /** Termux Packages Github repo name*/
    const val TERMUX_PACKAGES_GITHUB_REPO_NAME: String = "termux-packages" // Default: "termux-packages"
    /** Termux Packages Github repo url*/
    const val TERMUX_PACKAGES_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_PACKAGES_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-packages"
    /** Termux Packages Github issues repo url*/
    const val TERMUX_PACKAGES_GITHUB_ISSUES_REPO_URL: String = TERMUX_PACKAGES_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-packages/issues"

    /** Termux Game Packages Github repo name*/
    const val TERMUX_GAME_PACKAGES_GITHUB_REPO_NAME: String = "game-packages" // Default: "game-packages"
    /** Termux Game Packages Github repo url*/
    const val TERMUX_GAME_PACKAGES_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_GAME_PACKAGES_GITHUB_REPO_NAME // Default: "https://github.com/termux/game-packages"
    /** Termux Game Packages Github issues repo url*/
    const val TERMUX_GAME_PACKAGES_GITHUB_ISSUES_REPO_URL: String = TERMUX_GAME_PACKAGES_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/game-packages/issues"

    /** Termux Science Packages Github repo name*/
    const val TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_NAME: String = "science-packages" // Default: "science-packages"
    /** Termux Science Packages Github repo url*/
    const val TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_NAME // Default: "https://github.com/termux/science-packages"
    /** Termux Science Packages Github issues repo url*/
    const val TERMUX_SCIENCE_PACKAGES_GITHUB_ISSUES_REPO_URL: String = TERMUX_SCIENCE_PACKAGES_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/science-packages/issues"

    /** Termux Root Packages Github repo name*/
    const val TERMUX_ROOT_PACKAGES_GITHUB_REPO_NAME: String = "termux-root-packages" // Default: "termux-root-packages"
    /** Termux Root Packages Github repo url*/
    const val TERMUX_ROOT_PACKAGES_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_ROOT_PACKAGES_GITHUB_REPO_NAME // Default: "https://github.com/termux/termux-root-packages"
    /** Termux Root Packages Github issues repo url*/
    const val TERMUX_ROOT_PACKAGES_GITHUB_ISSUES_REPO_URL: String = TERMUX_ROOT_PACKAGES_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/termux-root-packages/issues"

    /** Termux Unstable Packages Github repo name*/
    const val TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_NAME: String = "unstable-packages" // Default: "unstable-packages"
    /** Termux Unstable Packages Github repo url*/
    const val TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_NAME // Default: "https://github.com/termux/unstable-packages"
    /** Termux Unstable Packages Github issues repo url*/
    const val TERMUX_UNSTABLE_PACKAGES_GITHUB_ISSUES_REPO_URL: String = TERMUX_UNSTABLE_PACKAGES_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/unstable-packages/issues"

    /** Termux X11 Packages Github repo name*/
    const val TERMUX_X11_PACKAGES_GITHUB_REPO_NAME: String = "x11-packages" // Default: "x11-packages"
    /** Termux X11 Packages Github repo url*/
    const val TERMUX_X11_PACKAGES_GITHUB_REPO_URL: String = TERMUX_GITHUB_ORGANIZATION_URL + "/" + TERMUX_X11_PACKAGES_GITHUB_REPO_NAME // Default: "https://github.com/termux/x11-packages"
    /** Termux X11 Packages Github issues repo url*/
    const val TERMUX_X11_PACKAGES_GITHUB_ISSUES_REPO_URL: String = TERMUX_X11_PACKAGES_GITHUB_REPO_URL + "/issues" // Default: "https://github.com/termux/x11-packages/issues"

    /*
     * Termux miscellaneous urls.
     **/

    /** Termux Site*/
    const val TERMUX_SITE: String = TERMUX_APP_NAME + " Site" // Default: "Termux Site"

    /** Termux Site url*/
    const val TERMUX_SITE_URL: String = "https://termux.dev" // Default: "https://termux.dev"

    /** Termux Wiki*/
    const val TERMUX_WIKI: String = TERMUX_APP_NAME + " Wiki" // Default: "Termux Wiki"

    /** Termux Wiki url*/
    const val TERMUX_WIKI_URL: String = "https://wiki.termux.com" // Default: "https://wiki.termux.com"

    /** Termux Github wiki repo url*/
    const val TERMUX_GITHUB_WIKI_REPO_URL: String = TERMUX_GITHUB_REPO_URL + "/wiki" // Default: "https://github.com/termux/termux-app/wiki"

    /** Termux Packages wiki repo url*/
    const val TERMUX_PACKAGES_GITHUB_WIKI_REPO_URL: String = TERMUX_PACKAGES_GITHUB_REPO_URL + "/wiki" // Default: "https://github.com/termux/termux-packages/wiki"

    /** Termux support email url*/
    const val TERMUX_SUPPORT_EMAIL_URL: String = "support@termux.dev" // Default: "support@termux.dev"

    /** Termux support email mailto url*/
    const val TERMUX_SUPPORT_EMAIL_MAILTO_URL: String = "mailto:" + TERMUX_SUPPORT_EMAIL_URL // Default: "mailto:support@termux.dev"

    /** Termux Reddit subreddit*/
    const val TERMUX_REDDIT_SUBREDDIT: String = "r/termux" // Default: "r/termux"

    /** Termux Reddit subreddit url*/
    const val TERMUX_REDDIT_SUBREDDIT_URL: String = "https://www.reddit.com/r/termux" // Default: "https://www.reddit.com/r/termux"

    /** Termux donate url*/
    const val TERMUX_DONATE_URL: String = TERMUX_SITE_URL + "/donate" // Default: "https://termux.dev/donate"

    /*
     * Termux app core directory paths.
     **/

    /** Termux app internal private app data directory path*/
    @SuppressLint("SdCardPath")
    const val TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH: String = "/data/data/" + TERMUX_PACKAGE_NAME // Default: "/data/data/com.termux"
    /** Termux app internal private app data directory*/
    @JvmField val TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR: File = File(TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH)

    /** Termux app Files directory path*/
    const val TERMUX_FILES_DIR_PATH: String = TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "/files" // Default: "/data/data/com.termux/files"
    /** Termux app Files directory*/
    @JvmField val TERMUX_FILES_DIR: File = File(TERMUX_FILES_DIR_PATH)

    /** Termux app $PREFIX directory path*/
    const val TERMUX_PREFIX_DIR_PATH: String = TERMUX_FILES_DIR_PATH + "/usr" // Default: "/data/data/com.termux/files/usr"
    /** Termux app $PREFIX directory*/
    @JvmField val TERMUX_PREFIX_DIR: File = File(TERMUX_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/bin directory path*/
    const val TERMUX_BIN_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/bin" // Default: "/data/data/com.termux/files/usr/bin"
    /** Termux app $PREFIX/bin directory*/
    @JvmField val TERMUX_BIN_PREFIX_DIR: File = File(TERMUX_BIN_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/etc directory path*/
    const val TERMUX_ETC_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/etc" // Default: "/data/data/com.termux/files/usr/etc"
    /** Termux app $PREFIX/etc directory*/
    @JvmField val TERMUX_ETC_PREFIX_DIR: File = File(TERMUX_ETC_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/include directory path*/
    const val TERMUX_INCLUDE_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/include" // Default: "/data/data/com.termux/files/usr/include"
    /** Termux app $PREFIX/include directory*/
    @JvmField val TERMUX_INCLUDE_PREFIX_DIR: File = File(TERMUX_INCLUDE_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/lib directory path*/
    const val TERMUX_LIB_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/lib" // Default: "/data/data/com.termux/files/usr/lib"
    /** Termux app $PREFIX/lib directory*/
    @JvmField val TERMUX_LIB_PREFIX_DIR: File = File(TERMUX_LIB_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/libexec directory path*/
    const val TERMUX_LIBEXEC_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/libexec" // Default: "/data/data/com.termux/files/usr/libexec"
    /** Termux app $PREFIX/libexec directory*/
    @JvmField val TERMUX_LIBEXEC_PREFIX_DIR: File = File(TERMUX_LIBEXEC_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/share directory path*/
    const val TERMUX_SHARE_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/share" // Default: "/data/data/com.termux/files/usr/share"
    /** Termux app $PREFIX/share directory*/
    @JvmField val TERMUX_SHARE_PREFIX_DIR: File = File(TERMUX_SHARE_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/tmp and $TMPDIR directory path*/
    const val TERMUX_TMP_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/tmp" // Default: "/data/data/com.termux/files/usr/tmp"
    /** Termux app $PREFIX/tmp and $TMPDIR directory*/
    @JvmField val TERMUX_TMP_PREFIX_DIR: File = File(TERMUX_TMP_PREFIX_DIR_PATH)

    /** Termux app $PREFIX/var directory path*/
    const val TERMUX_VAR_PREFIX_DIR_PATH: String = TERMUX_PREFIX_DIR_PATH + "/var" // Default: "/data/data/com.termux/files/usr/var"
    /** Termux app $PREFIX/var directory*/
    @JvmField val TERMUX_VAR_PREFIX_DIR: File = File(TERMUX_VAR_PREFIX_DIR_PATH)

    /** Termux app usr-staging directory path*/
    const val TERMUX_STAGING_PREFIX_DIR_PATH: String = TERMUX_FILES_DIR_PATH + "/usr-staging" // Default: "/data/data/com.termux/files/usr-staging"
    /** Termux app usr-staging directory*/
    @JvmField val TERMUX_STAGING_PREFIX_DIR: File = File(TERMUX_STAGING_PREFIX_DIR_PATH)

    /** Termux app $HOME directory path*/
    const val TERMUX_HOME_DIR_PATH: String = TERMUX_FILES_DIR_PATH + "/home" // Default: "/data/data/com.termux/files/home"
    /** Termux app $HOME directory*/
    @JvmField val TERMUX_HOME_DIR: File = File(TERMUX_HOME_DIR_PATH)

    /** Termux app config home directory path*/
    const val TERMUX_CONFIG_HOME_DIR_PATH: String = TERMUX_HOME_DIR_PATH + "/.config/termux" // Default: "/data/data/com.termux/files/home/.config/termux"
    /** Termux app config home directory*/
    @JvmField val TERMUX_CONFIG_HOME_DIR: File = File(TERMUX_CONFIG_HOME_DIR_PATH)

    /** Termux app data home directory path*/
    const val TERMUX_DATA_HOME_DIR_PATH: String = TERMUX_HOME_DIR_PATH + "/.termux" // Default: "/data/data/com.termux/files/home/.termux"
    /** Termux app data home directory*/
    @JvmField val TERMUX_DATA_HOME_DIR: File = File(TERMUX_DATA_HOME_DIR_PATH)

    /** Termux app storage home directory path*/
    const val TERMUX_STORAGE_HOME_DIR_PATH: String = TERMUX_HOME_DIR_PATH + "/storage" // Default: "/data/data/com.termux/files/home/storage"
    /** Termux app storage home directory*/
    @JvmField val TERMUX_STORAGE_HOME_DIR: File = File(TERMUX_STORAGE_HOME_DIR_PATH)

    /*
     * Termux app and plugin preferences and properties file paths.
     **/

    /** Termux app default SharedPreferences file basename without extension*/
    const val TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_PACKAGE_NAME + "_preferences" // Default: "com.termux_preferences"

    /** Termux:API app default SharedPreferences file basename without extension*/
    const val TERMUX_API_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_API_PACKAGE_NAME + "_preferences" // Default: "com.termux.api_preferences"

    /** Termux:Boot app default SharedPreferences file basename without extension*/
    const val TERMUX_BOOT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_BOOT_PACKAGE_NAME + "_preferences" // Default: "com.termux.boot_preferences"

    /** Termux:Float app default SharedPreferences file basename without extension*/
    const val TERMUX_FLOAT_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_FLOAT_PACKAGE_NAME + "_preferences" // Default: "com.termux.window_preferences"

    /** Termux:Styling app default SharedPreferences file basename without extension*/
    const val TERMUX_STYLING_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_STYLING_PACKAGE_NAME + "_preferences" // Default: "com.termux.styling_preferences"

    /** Termux:Tasker app default SharedPreferences file basename without extension*/
    const val TERMUX_TASKER_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_TASKER_PACKAGE_NAME + "_preferences" // Default: "com.termux.tasker_preferences"

    /** Termux:Widget app default SharedPreferences file basename without extension*/
    const val TERMUX_WIDGET_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION: String = TERMUX_WIDGET_PACKAGE_NAME + "_preferences" // Default: "com.termux.widget_preferences"

    /** Termux app termux.properties primary file path*/
    const val TERMUX_PROPERTIES_PRIMARY_FILE_PATH: String = TERMUX_DATA_HOME_DIR_PATH + "/termux.properties" // Default: "/data/data/com.termux/files/home/.termux/termux.properties"
    /** Termux app termux.properties primary file*/
    @JvmField val TERMUX_PROPERTIES_PRIMARY_FILE: File = File(TERMUX_PROPERTIES_PRIMARY_FILE_PATH)

    /** Termux app termux.properties secondary file path*/
    const val TERMUX_PROPERTIES_SECONDARY_FILE_PATH: String = TERMUX_CONFIG_HOME_DIR_PATH + "/termux.properties" // Default: "/data/data/com.termux/files/home/.config/termux/termux.properties"
    /** Termux app termux.properties secondary file*/
    @JvmField val TERMUX_PROPERTIES_SECONDARY_FILE: File = File(TERMUX_PROPERTIES_SECONDARY_FILE_PATH)

    /** Termux:Float app termux.properties primary file path*/
    const val TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE_PATH: String = TERMUX_DATA_HOME_DIR_PATH + "/termux.float.properties" // Default: "/data/data/com.termux/files/home/.termux/termux.float.properties"
    /** Termux:Float app termux.properties primary file*/
    @JvmField val TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE: File = File(TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE_PATH)

    /** Termux:Float app termux.properties secondary file path*/
    const val TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE_PATH: String = TERMUX_CONFIG_HOME_DIR_PATH + "/termux.float.properties" // Default: "/data/data/com.termux/files/home/.config/termux/termux.float.properties"
    /** Termux:Float app termux.properties secondary file*/
    @JvmField val TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE: File = File(TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE_PATH)

    /** Termux app and Termux:Styling colors.properties file path*/
    const val TERMUX_COLOR_PROPERTIES_FILE_PATH: String = TERMUX_DATA_HOME_DIR_PATH + "/colors.properties" // Default: "/data/data/com.termux/files/home/.termux/colors.properties"
    /** Termux app and Termux:Styling colors.properties file*/
    @JvmField val TERMUX_COLOR_PROPERTIES_FILE: File = File(TERMUX_COLOR_PROPERTIES_FILE_PATH)

    /** Termux app and Termux:Styling font.ttf file path*/
    const val TERMUX_FONT_FILE_PATH: String = TERMUX_DATA_HOME_DIR_PATH + "/font.ttf" // Default: "/data/data/com.termux/files/home/.termux/font.ttf"
    /** Termux app and Termux:Styling font.ttf file*/
    @JvmField val TERMUX_FONT_FILE: File = File(TERMUX_FONT_FILE_PATH)

    /** Termux app and plugins crash log file path*/
    const val TERMUX_CRASH_LOG_FILE_PATH: String = TERMUX_HOME_DIR_PATH + "/crash_log.md" // Default: "/data/data/com.termux/files/home/crash_log.md"

    /** Termux app and plugins crash log backup file path*/
    const val TERMUX_CRASH_LOG_BACKUP_FILE_PATH: String = TERMUX_HOME_DIR_PATH + "/crash_log_backup.md" // Default: "/data/data/com.termux/files/home/crash_log_backup.md"

    /*
     * Termux app plugin specific paths.
     **/

    /** Termux app directory path to store scripts to be run at boot by Termux:Boot*/
    const val TERMUX_BOOT_SCRIPTS_DIR_PATH: String = TERMUX_DATA_HOME_DIR_PATH + "/boot" // Default: "/data/data/com.termux/files/home/.termux/boot"
    /** Termux app directory to store scripts to be run at boot by Termux:Boot*/
    @JvmField val TERMUX_BOOT_SCRIPTS_DIR: File = File(TERMUX_BOOT_SCRIPTS_DIR_PATH)

    /** Termux app directory path to store foreground scripts that can be run by the termux launcher
     * widget provided by Termux:Widget*/
    const val TERMUX_SHORTCUT_SCRIPTS_DIR_PATH: String = TERMUX_HOME_DIR_PATH + "/.shortcuts" // Default: "/data/data/com.termux/files/home/.shortcuts"
    /** Termux app directory to store foreground scripts that can be run by the termux launcher widget provided by Termux:Widget*/
    @JvmField val TERMUX_SHORTCUT_SCRIPTS_DIR: File = File(TERMUX_SHORTCUT_SCRIPTS_DIR_PATH)

    /** Termux app directory basename that stores background scripts that can be run by the termux
     * launcher widget provided by Termux:Widget*/
    const val TERMUX_SHORTCUT_TASKS_SCRIPTS_DIR_BASENAME: String = "tasks" // Default: "tasks"
    /** Termux app directory path to store background scripts that can be run by the termux launcher
     * widget provided by Termux:Widget*/
    const val TERMUX_SHORTCUT_TASKS_SCRIPTS_DIR_PATH: String = TERMUX_SHORTCUT_SCRIPTS_DIR_PATH + "/" + TERMUX_SHORTCUT_TASKS_SCRIPTS_DIR_BASENAME // Default: "/data/data/com.termux/files/home/.shortcuts/tasks"
    /** Termux app directory to store background scripts that can be run by the termux launcher widget provided by Termux:Widget*/
    @JvmField val TERMUX_SHORTCUT_TASKS_SCRIPTS_DIR: File = File(TERMUX_SHORTCUT_TASKS_SCRIPTS_DIR_PATH)

    /** Termux app directory basename that stores icons for the foreground and background scripts
     * that can be run by the termux launcher widget provided by Termux:Widget*/
    const val TERMUX_SHORTCUT_SCRIPT_ICONS_DIR_BASENAME: String = "icons" // Default: "icons"
    /** Termux app directory path to store icons for the foreground and background scripts that can
     * be run by the termux launcher widget provided by Termux:Widget*/
    const val TERMUX_SHORTCUT_SCRIPT_ICONS_DIR_PATH: String = TERMUX_SHORTCUT_SCRIPTS_DIR_PATH + "/" + TERMUX_SHORTCUT_SCRIPT_ICONS_DIR_BASENAME // Default: "/data/data/com.termux/files/home/.shortcuts/icons"
    /** Termux app directory to store icons for the foreground and background scripts that can be
     * run by the termux launcher widget provided by Termux:Widget*/
    @JvmField val TERMUX_SHORTCUT_SCRIPT_ICONS_DIR: File = File(TERMUX_SHORTCUT_SCRIPT_ICONS_DIR_PATH)

    /** Termux app directory path to store scripts to be run by 3rd party twofortyfouram locale plugin
     * host apps like Tasker app via the Termux:Tasker plugin client*/
    const val TERMUX_TASKER_SCRIPTS_DIR_PATH: String = TERMUX_DATA_HOME_DIR_PATH + "/tasker" // Default: "/data/data/com.termux/files/home/.termux/tasker"
    /** Termux app directory to store scripts to be run by 3rd party twofortyfouram locale plugin host apps like Tasker app via the Termux:Tasker plugin client*/
    @JvmField val TERMUX_TASKER_SCRIPTS_DIR: File = File(TERMUX_TASKER_SCRIPTS_DIR_PATH)

    /*
     * Termux app and plugins notification variables.
     **/

    /** Termux app notification channel id used by [TERMUX_APP.TERMUX_SERVICE]*/
    const val TERMUX_APP_NOTIFICATION_CHANNEL_ID: String = "termux_notification_channel"
    /** Termux app notification channel name used by [TERMUX_APP.TERMUX_SERVICE]*/
    const val TERMUX_APP_NOTIFICATION_CHANNEL_NAME: String = TermuxConstants.TERMUX_APP_NAME + " App"
    /** Termux app unique notification id used by [TERMUX_APP.TERMUX_SERVICE]*/
    const val TERMUX_APP_NOTIFICATION_ID: Int = 1337

    /** Termux app notification channel id used by [TERMUX_APP.RUN_COMMAND_SERVICE]*/
    const val TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID: String = "termux_run_command_notification_channel"
    /** Termux app notification channel name used by [TERMUX_APP.RUN_COMMAND_SERVICE]*/
    const val TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME: String = TermuxConstants.TERMUX_APP_NAME + " RunCommandService"
    /** Termux app unique notification id used by [TERMUX_APP.RUN_COMMAND_SERVICE]*/
    const val TERMUX_RUN_COMMAND_NOTIFICATION_ID: Int = 1338

    /** Termux app notification channel id used for plugin command errors*/
    const val TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_ID: String = "termux_plugin_command_errors_notification_channel"
    /** Termux app notification channel name used for plugin command errors*/
    const val TERMUX_PLUGIN_COMMAND_ERRORS_NOTIFICATION_CHANNEL_NAME: String = TermuxConstants.TERMUX_APP_NAME + " Plugin Commands Errors"

    /** Termux app notification channel id used for crash reports*/
    const val TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_ID: String = "termux_crash_reports_notification_channel"
    /** Termux app notification channel name used for crash reports*/
    const val TERMUX_CRASH_REPORTS_NOTIFICATION_CHANNEL_NAME: String = TermuxConstants.TERMUX_APP_NAME + " Crash Reports"

    /** Termux app notification channel id used by [TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE]*/
    const val TERMUX_FLOAT_APP_NOTIFICATION_CHANNEL_ID: String = "termux_float_notification_channel"
    /** Termux app notification channel name used by [TERMUX_FLOAT_APP.TERMUX_FLOAT_SERVICE]*/
    const val TERMUX_FLOAT_APP_NOTIFICATION_CHANNEL_NAME: String = TermuxConstants.TERMUX_FLOAT_APP_NAME + " App"
    /** Termux app unique notification id used by [TERMUX_APP.TERMUX_SERVICE]*/
    const val TERMUX_FLOAT_APP_NOTIFICATION_ID: Int = 1339

    /*
     * Termux app and plugins miscellaneous variables.
     **/

    /** Android OS permission declared by Termux app in AndroidManifest.xml which can be requested by
     * 3rd party apps to run various commands in Termux app context*/
    const val PERMISSION_RUN_COMMAND: String = TERMUX_PACKAGE_NAME + ".permission.RUN_COMMAND" // Default: "com.termux.permission.RUN_COMMAND"

    /** Termux property defined in termux.properties file as a secondary check to PERMISSION_RUN_COMMAND
     * to allow 3rd party apps to run various commands in Termux app context*/
    const val PROP_ALLOW_EXTERNAL_APPS: String = "allow-external-apps" // Default: "allow-external-apps"
    /** Default value for [.PROP_ALLOW_EXTERNAL_APPS]*/
    const val PROP_DEFAULT_VALUE_ALLOW_EXTERNAL_APPS: String = "false" // Default: "false"

    /** The broadcast action sent when Termux App opens*/
    const val BROADCAST_TERMUX_OPENED: String = TERMUX_PACKAGE_NAME + ".app.OPENED"

    /** The Uri authority for Termux app file shares*/
    const val TERMUX_FILE_SHARE_URI_AUTHORITY: String = TERMUX_PACKAGE_NAME + ".files" // Default: "com.termux.files"

    /** The normal comma character (U+002C, &comma;, &#44;, comma)*/
    const val COMMA_NORMAL: String = "," // Default: ","

    /** The alternate comma character (U+201A, &sbquo;, &#8218;, single low-9 quotation mark) that
     * may be used instead of [.COMMA_NORMAL]*/
    const val COMMA_ALTERNATIVE: String = "‚" // Default: "‚"
}
