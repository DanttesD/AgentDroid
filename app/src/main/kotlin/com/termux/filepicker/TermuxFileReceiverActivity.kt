package com.termux.filepicker

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Patterns
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.data.DataUtils
import com.termux.shared.data.IntentUtils
import com.termux.shared.interact.MessageDialogUtils
import com.termux.shared.interact.TextInputDialogUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

open class TermuxFileReceiverActivity : Activity() {

    /**
     * If the activity should be finished when the name input dialog is dismissed. This is disabled
     * before showing an error dialog, since the act of showing the error dialog will cause the
     * name input dialog to be implicitly dismissed, and we do not want to finish the activity directly
     * when showing the error dialog.
     **/
    @JvmField var mFinishOnDismissNameDialog: Boolean = true
    private var mReceivedStream: InputStream? = null

    protected override fun onDestroy() {
        closeReceivedStream()
        super.onDestroy()
    }

    private fun closeReceivedStream() {
        if (mReceivedStream != null) {
            try {
                mReceivedStream!!.close()
            } catch (ignored: IOException) {
            }
            mReceivedStream = null
        }
    }

    protected override fun onResume() {
        super.onResume()

        val intent: Intent = getIntent()
        val action: String? = intent.getAction()
        val type: String? = intent.getType()
        val scheme: String? = intent.getScheme()

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val sharedTitle: String? = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_TITLE, null)

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            val sharedText: String? = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

            if (sharedUri != null) {
                handleContentUri(sharedUri, sharedTitle)
            } else if (sharedText != null) {
                if (isSharedTextAnUrl(sharedText)) {
                    handleUrlAndFinish(sharedText)
                } else {
                    var subject: String? = IntentUtils.getStringExtraIfSet(intent, Intent.EXTRA_SUBJECT, null)
                    if (subject == null) {
                        subject = sharedTitle
                    }
                    if (subject != null) {
                        subject += ".txt"
                    }
                    promptNameAndSave(ByteArrayInputStream((sharedText as java.lang.String).getBytes(StandardCharsets.UTF_8)), subject)
                }
            } else {
                showErrorDialogAndQuit("Send action without content - nothing to save.")
            }
        } else {
            val dataUri: Uri? = intent.getData()

            if (dataUri == null) {
                showErrorDialogAndQuit("Data uri not passed.")
                return
            }

            if ("content".equals(scheme)) {
                handleContentUri(dataUri, sharedTitle)
            } else if ("file".equals(scheme)) {
                // When e.g. clicking on a downloaded apk:
                val path: String? = dataUri.getPath()
                if (DataUtils.isNullOrEmpty(path)) {
                    showErrorDialogAndQuit("File path from data uri is null, empty or invalid.")
                    return
                }

                val file = File(path)
                try {
                    val `in` = FileInputStream(file)
                    promptNameAndSave(`in`, file.getName())
                } catch (e: FileNotFoundException) {
                    showErrorDialogAndQuit("Cannot open file: " + e.message + ".")
                }
            } else {
                showErrorDialogAndQuit("Unable to receive any file or URL.")
            }
        }
    }

    open fun showErrorDialogAndQuit(message: String) {
        mFinishOnDismissNameDialog = false
        MessageDialogUtils.showMessage(this, API_TAG, message, null, DialogInterface.OnClickListener { dialog, which -> finish() }, null, null, DialogInterface.OnDismissListener { dialog -> finish() })
    }

    open fun handleContentUri(uri: Uri, subjectFromIntent: String?) {
        try {
            var attachmentFileName: String? = null

            val projection = arrayOf<String>(OpenableColumns.DISPLAY_NAME)
            val c = getContentResolver().query(uri, projection, null, null, null)
            var primaryException: Throwable? = null
            try {
                if (c != null && c.moveToFirst()) {
                    val fileNameColumnId: Int = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (fileNameColumnId >= 0) {
                        attachmentFileName = c.getString(fileNameColumnId)
                    }
                }
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                if (primaryException != null) {
                    try {
                        c?.close()
                    } catch (suppressed: Throwable) {
                        primaryException.addSuppressed(suppressed)
                    }
                } else {
                    c?.close()
                }
            }

            if (attachmentFileName == null) {
                attachmentFileName = subjectFromIntent
            }

            val `in`: InputStream? = getContentResolver().openInputStream(uri)
            promptNameAndSave(`in`, attachmentFileName)
        } catch (e: Exception) {
            showErrorDialogAndQuit("Unable to handle shared content:\n\n" + e.message)
            Logger.logStackTraceWithMessage(LOG_TAG, "handleContentUri(uri=" + uri + ") failed", e)
        }
    }

    open fun promptNameAndSave(`in`: InputStream?, attachmentFileName: String?) {
        closeReceivedStream()
        if (`in` == null) {
            showErrorDialogAndQuit("Cannot open shared content.")
            return
        }
        mReceivedStream = `in`
        TextInputDialogUtils.textInput(this, R.string.title_file_received, attachmentFileName, R.string.action_file_received_edit, TextInputDialogUtils.TextSetListener { text ->
            val outFile: File? = saveStreamWithName(`in`, text)
            if (outFile == null) {
                return@TextSetListener
            }

            val editorProgramFile = File(EDITOR_PROGRAM)
            if (!editorProgramFile.isFile()) {
                showErrorDialogAndQuit("The following file does not exist:\n\$HOME/bin/termux-file-editor\n\nCreate this file as a script or a symlink - it will be called with the received file as only argument.")
                return@TextSetListener
            }

            // Do this for the user if necessary:
            //noinspection ResultOfMethodCallIgnored
            editorProgramFile.setExecutable(true)

            val scriptUri: Uri = Uri.Builder().scheme("file").path(EDITOR_PROGRAM).build()

            val executeIntent = Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri)
            executeIntent.setClass(this@TermuxFileReceiverActivity, TermuxService::class.java)
            executeIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf<String>(outFile.getAbsolutePath()))
            startService(executeIntent)
            finish()
        }, R.string.action_file_received_open_directory, TextInputDialogUtils.TextSetListener { text ->
            if (saveStreamWithName(`in`, text) == null) {
                return@TextSetListener
            }

            val executeIntent = Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
            executeIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_WORKDIR, TERMUX_RECEIVEDIR)
            executeIntent.setClass(this@TermuxFileReceiverActivity, TermuxService::class.java)
            startService(executeIntent)
            finish()
        }, android.R.string.cancel, TextInputDialogUtils.TextSetListener { text -> finish() }, DialogInterface.OnDismissListener { dialog ->
            if (mFinishOnDismissNameDialog) {
                finish()
            }
        })
    }

    open fun receiveDirectory(): File {
        return File(TERMUX_RECEIVEDIR)
    }

    open fun saveStreamWithName(`in`: InputStream, attachmentFileName: String): File? {
        var outFile: File? = null
        var created = false
        try {
            val source = `in`
            var primaryException1: Throwable? = null
            try {
                // The provider's display name and the editable title are untrusted basenames.
                if (source == null || DataUtils.isNullOrEmpty(attachmentFileName) || attachmentFileName.equals(".") || attachmentFileName.equals("..") || attachmentFileName.indexOf('/') >= 0 || attachmentFileName.indexOf('\\') >= 0 || attachmentFileName.indexOf('\u0000') >= 0) {
                    throw IOException("Choose a file name without a path.")
                }
                val receiveDir: File = receiveDirectory()
                if (!receiveDir.isDirectory() && !receiveDir.mkdirs()) {
                    throw IOException("Cannot create receive directory.")
                }
                outFile = File(receiveDir, attachmentFileName)
                // Exclusive creation is atomic: neither existing files nor links are truncated.
                val descriptor: FileDescriptor = Os.open(outFile.getPath(), OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 384)
                created = true
                try {
                    (FileOutputStream(descriptor)).use { output ->
                        val buffer = ByteArray(4096)
                        var count: Int = 0
                        while (true) {
                            count = source.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                        }
                    }
                } finally {
                    // This activity owns the descriptor returned by Os.open. On Android,
                    // a stream constructed from a caller-owned descriptor may leave it open.
                    if (descriptor.valid()) {
                        Os.close(descriptor)
                    }
                }
                return outFile
            } catch (t: Throwable) {
                primaryException1 = t
                throw t
            } finally {
                if (primaryException1 != null) {
                    try {
                        source.close()
                    } catch (suppressed: Throwable) {
                        primaryException1.addSuppressed(suppressed)
                    }
                } else {
                    source.close()
                }
            }
        } catch (e: IOException) {
            if (created && outFile != null) {
                outFile.delete()
            }
            showErrorDialogAndQuit("Cannot save shared file. Use a new file name; existing files are never overwritten.")
            return null
        } catch (e: ErrnoException) {
            if (created && outFile != null) {
                outFile.delete()
            }
            showErrorDialogAndQuit("Cannot save shared file. Use a new file name; existing files are never overwritten.")
            return null
        } catch (e: RuntimeException) {
            if (created && outFile != null) {
                outFile.delete()
            }
            showErrorDialogAndQuit("Cannot save shared file. Use a new file name; existing files are never overwritten.")
            return null
        } finally {
            if (mReceivedStream === `in`) {
                mReceivedStream = null
            }
        }
    }

    open fun handleUrlAndFinish(url: String) {
        val urlOpenerProgramFile = File(URL_OPENER_PROGRAM)
        if (!urlOpenerProgramFile.isFile()) {
            showErrorDialogAndQuit("The following file does not exist:\n\$HOME/bin/termux-url-opener\n\nCreate this file as a script or a symlink - it will be called with the shared URL as the first argument.")
            return
        }

        // Do this for the user if necessary:
        //noinspection ResultOfMethodCallIgnored
        urlOpenerProgramFile.setExecutable(true)

        val urlOpenerProgramUri: Uri = Uri.Builder().scheme("file").path(URL_OPENER_PROGRAM).build()

        val executeIntent = Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, urlOpenerProgramUri)
        executeIntent.setClass(this@TermuxFileReceiverActivity, TermuxService::class.java)
        executeIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS, arrayOf<String>(url))
        startService(executeIntent)
        finish()
    }

    companion object {

        const val TERMUX_RECEIVEDIR: String = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home/downloads"
        const val EDITOR_PROGRAM: String = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-file-editor"
        const val URL_OPENER_PROGRAM: String = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin/termux-url-opener"

        private const val API_TAG: String = TermuxConstants.TERMUX_APP_NAME + "FileReceiver"

        private const val LOG_TAG: String = "TermuxFileReceiverActivity"

        @JvmStatic
        fun isSharedTextAnUrl(sharedText: String?): Boolean {
            if (sharedText == null || sharedText.isEmpty()) {
                return false
            }

            return Patterns.WEB_URL.matcher(sharedText).matches() || Pattern.matches("magnet:\\?xt=urn:btih:.*?", sharedText)
        }
    }
}
