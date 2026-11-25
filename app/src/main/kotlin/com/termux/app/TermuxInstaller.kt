package com.termux.app

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Environment
import android.system.Os
import android.util.Pair
import android.view.WindowManager
import com.termux.R
import com.termux.app.agent.ThemedDialog
import com.termux.app.utils.CrashUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.file.TermuxFileUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.markdown.MarkdownUtils
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.models.errors.Error
import com.termux.shared.packages.PackageUtils
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxTask
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 **/
object TermuxInstaller {

    private const val LOG_TAG: String = "TermuxInstaller"

    /** Performs bootstrap setup if necessary.*/
    @JvmStatic
    fun setupBootstrapIfNeeded(activity: Activity, whenDone: Runnable) {
        var bootstrapErrorMessage: String
        var filesDirectoryAccessibleError: Error?

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true)
        val isFilesDirectoryAccessible: Boolean = filesDirectoryAccessibleError == null

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_PREFIX_DIR_PATH, false))
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible)
            Logger.logError(LOG_TAG, bootstrapErrorMessage)
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
            MessageDialogUtils.exitAppWithErrorMessage(activity, activity.getString(R.string.bootstrap_error_title), bootstrapErrorMessage)
            return
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false)
            Logger.logError(LOG_TAG, bootstrapErrorMessage)
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage)
            MessageDialogUtils.showMessage(activity, activity.getString(R.string.bootstrap_error_title), bootstrapErrorMessage, null)
            return
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, true)) {
            val PREFIX_FILE_LIST: Array<File>? = TermuxConstants.TERMUX_PREFIX_DIR.listFiles()
            // If prefix directory is empty or only contains the tmp directory
            if (PREFIX_FILE_LIST == null || PREFIX_FILE_LIST.size == 0 || PREFIX_FILE_LIST.size == 1 && TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH.equals(PREFIX_FILE_LIST[0].getAbsolutePath())) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains the tmp directory.")
            } else {
                configureAgentDroidRepository()
                whenDone.run()
                return
            }
        } else if (FileUtils.fileExists(TermuxConstants.TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.")
        }

        val progress: ProgressDialog = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false)
        object : Thread() {
            override fun run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.")

                    var error: Error?

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH, true)
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                        return
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TermuxConstants.TERMUX_PREFIX_DIR_PATH, true)
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                        return
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true)
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                        return
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true)
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                        return
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH + "\".")

                    val buffer = ByteArray(8096)
                    val symlinks: MutableList<Pair<String, String>> = ArrayList<Pair<String, String>>(50)

                    val zipBytes: ByteArray = loadZipBytes()
                    val zipInput = ZipInputStream(ByteArrayInputStream(zipBytes))
                    var primaryException: Throwable? = null
                    try {
                        var zipEntry: ZipEntry
                        while (true) {
                            zipEntry = zipInput.getNextEntry()
                            if (zipEntry == null) break
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                val symlinksReader = BufferedReader(InputStreamReader(zipInput))
                                var line: String? = null
                                while (true) {
                                    line = symlinksReader.readLine()
                                    if (line == null) break
                                    val parts: Array<String> = (line as java.lang.String).split("←")
                                    if (parts.size != 2) {
                                        throw RuntimeException("Malformed symlink line: " + line)
                                    }
                                    val oldPath: String = parts[0]
                                    val newPath: String = TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1]
                                    symlinks.add(Pair.create(oldPath, newPath))

                                    error = ensureDirectoryExists(File(newPath).getParentFile())
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                                        return
                                    }
                                }
                            } else {
                                val zipEntryName: String = zipEntry.getName()
                                val targetFile = File(TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName)
                                val isDirectory: Boolean = zipEntry.isDirectory()

                                error = ensureDirectoryExists(if (isDirectory) targetFile else targetFile.getParentFile())
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error))
                                    return
                                }

                                if (!isDirectory) {
                                    (FileOutputStream(targetFile)).use { outStream ->
                                        var readBytes: Int = 0
                                        while (true) {
                                            readBytes = zipInput.read(buffer)
                                            if (readBytes == -1) break
                                            outStream.write(buffer, 0, readBytes)
                                        }
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") || zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods") || zipEntryName.equals("etc/termux/bootstrap/termux-bootstrap-second-stage.sh")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 448)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        primaryException = t
                        throw t
                    } finally {
                        if (primaryException != null) {
                            try {
                                zipInput.close()
                            } catch (suppressed: Throwable) {
                                primaryException.addSuppressed(suppressed)
                            }
                        } else {
                            zipInput.close()
                        }
                    }

                    if (symlinks.isEmpty()) {
                        throw RuntimeException("No SYMLINKS.txt encountered")
                    }
                    for (symlink in symlinks) {
                        Os.symlink(symlink.first, symlink.second)
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.")

                    if (!TermuxConstants.TERMUX_STAGING_PREFIX_DIR.renameTo(TermuxConstants.TERMUX_PREFIX_DIR)) {
                        throw RuntimeException("Moving termux prefix staging to prefix directory failed")
                    }

                    // Run Termux bootstrap second stage.
                    val termuxBootstrapSecondStageFile: String = TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
                    if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                        Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since script not found at \"" + termuxBootstrapSecondStageFile + "\" path.")
                    } else {
                        if (!FileUtils.fileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", true)) {
                            Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since bash not found.")
                        }
                        Logger.logInfo(LOG_TAG, "Running Termux bootstrap second stage.")

                        val executionCommand = ExecutionCommand(-1, termuxBootstrapSecondStageFile, null, null, null, true, false)
                        executionCommand.commandLabel = "Termux Bootstrap Second Stage Command"
                        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL
                        val termuxTask: TermuxTask? = TermuxTask.execute(activity, executionCommand, null, TermuxShellEnvironmentClient(), true)
                        if (termuxTask == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode!! != 0) {
                            // Generate debug report before deleting broken prefix directory to get `stat` info at time of failure.
                            showBootstrapErrorDialog(activity, whenDone, MarkdownUtils.getMarkdownCodeForString(executionCommand.toString(), true))

                            // Delete prefix directory as otherwise when app is restarted, the broken prefix directory would be used and logged into.
                            error = FileUtils.deleteFile("termux prefix directory", TermuxConstants.TERMUX_PREFIX_DIR_PATH, true)
                            if (error != null) {
                                Logger.logErrorExtended(LOG_TAG, error.toString())
                            }
                            return
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.")
                    configureAgentDroidRepository()
                    activity.runOnUiThread(whenDone)
                } catch (e: Exception) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)))
                } finally {
                    activity.runOnUiThread(Runnable {
                        try {
                            progress.dismiss()
                        } catch (e: RuntimeException) {
                            // Activity already dismissed - ignore.
                        }
                    })
                }
            }
        }.start()
    }

    private const val AGENTDROID_REPO_MAIN: String = "https://danttesd.github.io/AgentDroid/apt/main"

    /**
     * AGENTDROID: make `pkg` and apt use only our repository. The termux-tools package ships the list
     * of official mirrors and `pkg` rewrites sources.list from them, which would pull packages built
     * for com.termux. Point the chosen mirror at our repository and fix sources.list.
     * Safe to run on every start.
     **/
    @JvmStatic
    private fun configureAgentDroidRepository() {
        try {
            val etc = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc")
            val mirrors = File(etc, "termux/mirrors")
            val ours = File(mirrors, "agentdroid")
            mirrors.mkdirs()
            val mirrorText: String = "# This file is sourced by pkg\n# AgentDroid repository\nWEIGHT=1\nMAIN=\"" + AGENTDROID_REPO_MAIN + "\"\nROOT=\"" + AGENTDROID_REPO_MAIN + "\"\nX11=\"" + AGENTDROID_REPO_MAIN + "\"\n"
            writeIfDifferent(ours, mirrorText)
            writeIfDifferent(File(mirrors, "default"), mirrorText)
            val chosen = File(etc, "termux/chosen_mirrors")
            if (!chosen.exists() || !chosen.getCanonicalPath().equals(ours.getCanonicalPath())) {
                chosen.delete()
                Os.symlink(ours.getAbsolutePath(), chosen.getAbsolutePath())
            }
            writeIfDifferent(File(etc, "apt/sources.list"), "# AgentDroid repository\ndeb " + AGENTDROID_REPO_MAIN + "/ stable main\n")
            // x11/root repositories are not provided
            File(etc, "apt/sources.list.d/x11.list").delete()
            File(etc, "apt/sources.list.d/root.list").delete()
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not configure the AgentDroid repository", e)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun writeIfDifferent(file: File, text: String) {
        if (file.isFile() && String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).equals(text)) {
            return
        }
        val parent: File? = file.getParentFile()
        if (parent != null) {
            parent.mkdirs()
        }
        Files.write(file.toPath(), (text as java.lang.String).getBytes(StandardCharsets.UTF_8))
    }

    @JvmStatic
    fun showBootstrapErrorDialog(activity: Activity, whenDone: Runnable, message: String?) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message)

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message)

        activity.runOnUiThread(Runnable {
            try {
                ThemedDialog(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body).setNegativeButton(R.string.bootstrap_error_abort, DialogInterface.OnClickListener { dialog, which ->
                    dialog.dismiss()
                    activity.finish()
                }).setPositiveButton(R.string.bootstrap_error_try_again, DialogInterface.OnClickListener { dialog, which ->
                    dialog.dismiss()
                    FileUtils.deleteFile("termux prefix directory", TermuxConstants.TERMUX_PREFIX_DIR_PATH, true)
                    TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone)
                }).show()
            } catch (e1: WindowManager.BadTokenException) {
                // Activity already dismissed - ignore.
            }
        })
    }

    @JvmStatic
    private fun sendBootstrapCrashReportNotification(activity: Activity, message: String?) {
        CrashUtils.sendCrashReportNotification(activity, LOG_TAG, "## Bootstrap Error\n\n" + message + "\n\n" + TermuxUtils.getTermuxDebugMarkdownString(activity), true, true)
    }

    @JvmStatic
    fun setupStorageSymlinks(context: Context) {
        val LOG_TAG = "termux-storage"

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.")

        object : Thread() {
            override fun run() {
                try {
                    var error: Error?
                    val storageDir: File = TermuxConstants.TERMUX_STORAGE_HOME_DIR

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath())
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage())
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString())
                        CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Error.getErrorMarkdownString(error), true, true)
                        return
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".")

                    val sharedDir: File = Environment.getExternalStorageDirectory()
                    Os.symlink(sharedDir.getAbsolutePath(), File(storageDir, "shared").getAbsolutePath())

                    val downloadsDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    Os.symlink(downloadsDir.getAbsolutePath(), File(storageDir, "downloads").getAbsolutePath())

                    val dcimDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    Os.symlink(dcimDir.getAbsolutePath(), File(storageDir, "dcim").getAbsolutePath())

                    val picturesDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    Os.symlink(picturesDir.getAbsolutePath(), File(storageDir, "pictures").getAbsolutePath())

                    val musicDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    Os.symlink(musicDir.getAbsolutePath(), File(storageDir, "music").getAbsolutePath())

                    val moviesDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    Os.symlink(moviesDir.getAbsolutePath(), File(storageDir, "movies").getAbsolutePath())

                    val dirs: Array<File>? = context.getExternalFilesDirs(null)
                    if (dirs != null && dirs.size > 1) {
                        for (i in 1 until (dirs.size)) {
                            val dir: File? = dirs[i]
                            if (dir == null) {
                                continue
                            }
                            val symlinkName: String = "external-" + i
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".")
                            Os.symlink(dir.getAbsolutePath(), File(storageDir, symlinkName).getAbsolutePath())
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.")
                } catch (e: Exception) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.message)
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e)
                    CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)), true, true)
                }
            }
        }.start()
    }

    @JvmStatic
    private fun ensureDirectoryExists(directory: File?): Error? {
        return FileUtils.createDirectoryFile(directory!!.getAbsolutePath())
    }

    @JvmStatic
    fun loadZipBytes(): ByteArray {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap")
        return getZip()
    }

    @JvmStatic
    external fun getZip(): ByteArray
}
