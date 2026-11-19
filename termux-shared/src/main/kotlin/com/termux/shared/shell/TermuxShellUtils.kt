package com.termux.shared.shell

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.termux.shared.android.SELinuxUtils
import com.termux.shared.data.DataUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.errors.Error
import com.termux.shared.packages.PackageUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Collections
import java.util.Locale

object TermuxShellUtils {

    @JvmField var TERMUX_VERSION_NAME: String? = null
    @JvmField var TERMUX_IS_DEBUGGABLE_BUILD: String? = null
    @JvmField var TERMUX_APP_PID: String? = null
    @JvmField var TERMUX_APK_RELEASE: String? = null

    @JvmField var TERMUX_API_VERSION_NAME: String? = null

    @JvmStatic
    fun getDefaultWorkingDirectoryPath(): String {
        return TermuxConstants.TERMUX_HOME_DIR_PATH
    }

    @JvmStatic
    fun getDefaultBinPath(): String {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
    }

    @JvmStatic
    fun buildEnvironment(currentPackageContext: Context, isFailSafe: Boolean, workingDirectory: String?): Array<String> {
        var workingDirectory = workingDirectory
        TermuxConstants.TERMUX_HOME_DIR.mkdirs()

        if (workingDirectory == null || workingDirectory.isEmpty()) {
            workingDirectory = getDefaultWorkingDirectoryPath()
        }

        val environment: MutableList<String> = ArrayList<String>()

        loadTermuxEnvVariables(currentPackageContext)

        if (TERMUX_VERSION_NAME != null) {
            environment.add("TERMUX_VERSION=" + TERMUX_VERSION_NAME)
        }
        if (TERMUX_IS_DEBUGGABLE_BUILD != null) {
            environment.add("TERMUX_IS_DEBUGGABLE_BUILD=" + TERMUX_IS_DEBUGGABLE_BUILD)
        }
        if (TERMUX_APP_PID != null) {
            environment.add("TERMUX_APP_PID=" + TERMUX_APP_PID)
        }
        if (TERMUX_APK_RELEASE != null) {
            environment.add("TERMUX_APK_RELEASE=" + TERMUX_APK_RELEASE)
        }

        if (TERMUX_API_VERSION_NAME != null) {
            environment.add("TERMUX_API_VERSION=" + TERMUX_API_VERSION_NAME)
        }

        environment.add("TERM=xterm-256color")
        environment.add("COLORTERM=truecolor")

        try {
            var applicationInfo: ApplicationInfo? = currentPackageContext.getPackageManager().getApplicationInfo(TermuxConstants.TERMUX_PACKAGE_NAME, 0)
            if (applicationInfo != null && !applicationInfo.enabled) {
                applicationInfo = null
            }

            if (applicationInfo != null) {
                environment.add("TERMUX_APP__DATA_DIR=" + applicationInfo.dataDir)
                environment.add("TERMUX_APP__LEGACY_DATA_DIR=/data/data/" + applicationInfo.packageName)

                environment.add("TERMUX_APP__SE_FILE_CONTEXT=" + SELinuxUtils.getFileContext(applicationInfo.dataDir))

                val seInfoUser: String? = PackageUtils.getApplicationInfoSeInfoUserForPackage(applicationInfo)
                environment.add("TERMUX_APP__SE_INFO=" + PackageUtils.getApplicationInfoSeInfoForPackage(applicationInfo) + (if (DataUtils.isNullOrEmpty(seInfoUser)) "" else seInfoUser))
            }
        } catch (e: Exception) {
            // Ignore
        }

        environment.add("TERMUX__ROOTFS_DIR=" + TermuxConstants.TERMUX_FILES_DIR_PATH)
        environment.add("HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH)
        environment.add("TERMUX__HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH)
        environment.add("PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        environment.add("TERMUX__PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        // proot-distro and other tools read the app package from here (default would be com.termux)
        environment.add("TERMUX_APP__PACKAGE_NAME=" + TermuxConstants.TERMUX_PACKAGE_NAME)

        environment.add("TERMUX__SE_PROCESS_CONTEXT=" + SELinuxUtils.getContext())

        environment.add("BOOTCLASSPATH=" + System.getenv("BOOTCLASSPATH"))
        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"))
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"))
        // EXTERNAL_STORAGE is needed for /system/bin/am to work on at least
        // Samsung S7 - see https://plus.google.com/110070148244138185604/posts/gp8Lk3aCGp3.
        environment.add("EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE"))

        // These variables are needed if running on Android 10 and higher.
        addToEnvIfPresent(environment, "ANDROID_ART_ROOT")
        addToEnvIfPresent(environment, "DEX2OATBOOTCLASSPATH")
        addToEnvIfPresent(environment, "ANDROID_I18N_ROOT")
        addToEnvIfPresent(environment, "ANDROID_RUNTIME_ROOT")
        addToEnvIfPresent(environment, "ANDROID_TZDATA_ROOT")

        environment.add("ANDROID__BUILD_VERSION_SDK=" + Build.VERSION.SDK_INT)

        if (isFailSafe) {
            // Keep the default path so that system binaries can be used in the failsafe session.
            environment.add("PATH= " + System.getenv("PATH"))
        } else {
            environment.add("LANG=en_US.UTF-8")
            environment.add("PATH=" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
            environment.add("PWD=" + workingDirectory)
            environment.add("TMPDIR=" + TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH)
        }

        return environment.toTypedArray()
    }

    @JvmStatic
    fun addToEnvIfPresent(environment: MutableList<String>, name: String) {
        val value: String? = System.getenv(name)
        if (value != null) {
            environment.add(name + "=" + value)
        }
    }

    @JvmStatic
    fun setupProcessArgs(fileToExecute: String?, arguments: Array<String?>?): Array<String?> {
        // The file to execute may either be:
        // - An elf file, in which we execute it directly.
        // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
        //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
        // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo.
        var interpreter: String? = null
        try {
            val file = File(fileToExecute)
            val `in` = FileInputStream(file)
            var primaryException: Throwable? = null
            try {
                val buffer = ByteArray(256)
                val bytesRead: Int = `in`.read(buffer)
                if (bytesRead > 4) {
                    if (buffer[0].toInt() == 0x7F && buffer[1].toInt() == 'E'.code && buffer[2].toInt() == 'L'.code && buffer[3].toInt() == 'F'.code) {
                        // Elf file, do nothing.
                    } else if (buffer[0].toInt() == '#'.code && buffer[1].toInt() == '!'.code) {
                        // Try to parse shebang.
                        val builder = StringBuilder()
                        for (i in 2 until (bytesRead)) {
                            val c: Char = buffer[i].toInt().toChar()
                            if (c == ' ' || c == '\n') {
                                if (builder.length == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    val executable: String = builder.toString()
                                    if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                        val parts: Array<String> = (executable as java.lang.String).split("/")
                                        val binary: String = parts[parts.size - 1]
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary
                                    }
                                    break
                                }
                            } else {
                                builder.append(c)
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh"
                    }
                }
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                if (primaryException != null) {
                    try {
                        `in`.close()
                    } catch (suppressed: Throwable) {
                        primaryException.addSuppressed(suppressed)
                    }
                } else {
                    `in`.close()
                }
            }
        } catch (e: IOException) {
            // Ignore.
        }

        val result: MutableList<String?> = ArrayList<String?>()
        if (interpreter != null) {
            result.add(interpreter)
        }
        result.add(fileToExecute)
        if (arguments != null) {
            Collections.addAll(result, *arguments)
        }
        return result.toTypedArray()
    }

    @JvmStatic
    fun clearTermuxTMPDIR(onlyIfExists: Boolean) {
        if (onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false)) {
            return
        }

        var error: Error?
        error = FileUtils.clearDirectory("\$TMPDIR", FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null))
        if (error != null) {
            Logger.logErrorExtended(error.toString())
        }
    }

    @JvmStatic
    fun loadTermuxEnvVariables(currentPackageContext: Context) {
        val termuxAPKReleaseOld: String? = TERMUX_APK_RELEASE
        TERMUX_APK_RELEASE = null
        TERMUX_APP_PID = TERMUX_APK_RELEASE
        TERMUX_IS_DEBUGGABLE_BUILD = TERMUX_APP_PID
        TERMUX_VERSION_NAME = TERMUX_IS_DEBUGGABLE_BUILD

        // Check if Termux app is installed and not disabled
        if (TermuxUtils.isTermuxAppInstalled(currentPackageContext) == null) {
            // This function may be called by a different package like a plugin, so we get version for Termux package via its context
            val termuxPackageContext: Context? = TermuxUtils.getTermuxPackageContext(currentPackageContext)
            if (termuxPackageContext != null) {
                TERMUX_VERSION_NAME = PackageUtils.getVersionNameForPackage(termuxPackageContext)
                TERMUX_IS_DEBUGGABLE_BUILD = if (PackageUtils.isAppForPackageADebuggableBuild(termuxPackageContext)!!) "1" else "0"

                TERMUX_APP_PID = TermuxUtils.getTermuxAppPID(currentPackageContext)

                // Getting APK signature is a slightly expensive operation, so do it only when needed
                if (termuxAPKReleaseOld == null) {
                    val signingCertificateSHA256Digest: String? = PackageUtils.getSigningCertificateSHA256DigestForPackage(termuxPackageContext)
                    if (signingCertificateSHA256Digest != null) {
                        TERMUX_APK_RELEASE = ((TermuxUtils.getAPKRelease(signingCertificateSHA256Digest) as java.lang.String).replaceAll("[^a-zA-Z]", "_") as java.lang.String).toUpperCase(Locale.ROOT)
                    }
                } else {
                    TERMUX_APK_RELEASE = termuxAPKReleaseOld
                }
            }
        }

        TERMUX_API_VERSION_NAME = null

        // Check if Termux:API app is installed and not disabled
        if (TermuxUtils.isTermuxAPIAppInstalled(currentPackageContext) == null) {
            // This function may be called by a different package like a plugin, so we get version for Termux:API package via its context
            val termuxAPIPackageContext: Context? = TermuxUtils.getTermuxAPIPackageContext(currentPackageContext)
            if (termuxAPIPackageContext != null) {
                TERMUX_API_VERSION_NAME = PackageUtils.getVersionNameForPackage(termuxAPIPackageContext)
            }
        }
    }
}
