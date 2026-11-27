package com.termux.app

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import com.termux.app.utils.PluginUtils
import com.termux.filepicker.ProviderFiles
import com.termux.shared.data.IntentUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

open class TermuxOpenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val data: Uri? = intent.getData()
        if (data == null) {
            Logger.logError(LOG_TAG, "termux-open: Called without intent data")
            return
        }

        Logger.logVerbose(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent))

        val filePath: String? = data.getPath()
        val contentTypeExtra: String? = intent.getStringExtra("content-type")
        val useChooser: Boolean = intent.getBooleanExtra("chooser", false)
        var intentAction: String? = if (intent.getAction() == null) Intent.ACTION_VIEW else intent.getAction()
        when (intentAction!!) {
            Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
                // Ok.
            }
            else -> {
                Logger.logError(LOG_TAG, "Invalid action '" + intentAction + "', using 'view'")
                intentAction = Intent.ACTION_VIEW
            }
        }

        val isExternalUrl: Boolean = data.getScheme() != null && !data.getScheme()!!.equals("file")
        if (isExternalUrl) {
            val urlIntent = Intent(intentAction, data)
            if (intentAction.equals(Intent.ACTION_SEND)) {
                urlIntent.putExtra(Intent.EXTRA_TEXT, data.toString())
                urlIntent.setData(null)
            } else if (contentTypeExtra != null) {
                urlIntent.setDataAndType(data, contentTypeExtra)
            }
            urlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(urlIntent)
            } catch (e: ActivityNotFoundException) {
                Logger.logError(LOG_TAG, "termux-open: No app handles the url " + data)
            }
            return
        }

        if (filePath == null) {
            return
        }
        val fileToShare = File(filePath)
        if (!(fileToShare.isFile() && fileToShare.canRead())) {
            Logger.logError(LOG_TAG, "termux-open: Not a readable file: '" + fileToShare.getAbsolutePath() + "'")
            return
        }

        var sendIntent = Intent()
        sendIntent.setAction(intentAction)
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        var contentTypeToUse: String?
        if (contentTypeExtra == null) {
            contentTypeToUse = ProviderFiles.mimeType(fileToShare)
            if (contentTypeToUse == null) {
                contentTypeToUse = "application/octet-stream"
            }
        } else {
            contentTypeToUse = contentTypeExtra
        }

        val uriToShare: Uri = Uri.Builder().scheme("content").authority(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY).path(fileToShare.getAbsolutePath()).build()

        if (Intent.ACTION_SEND.equals(intentAction)) {
            sendIntent.putExtra(Intent.EXTRA_STREAM, uriToShare)
            sendIntent.setType(contentTypeToUse)
        } else {
            sendIntent.setDataAndType(uriToShare, contentTypeToUse)
        }

        if (useChooser) {
            sendIntent = Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(sendIntent)
        } catch (e: ActivityNotFoundException) {
            Logger.logError(LOG_TAG, "termux-open: No app handles the url " + data)
        }
    }

    open class ContentProvider : android.content.ContentProvider() {

        override fun onCreate(): Boolean {
            return true
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
            var projection = projection
            val file: File = fileForUri(uri)

            if (projection == null) {
                projection = arrayOf<String>(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, BaseColumns._ID)
            }

            val row = arrayOfNulls<Any>(projection.size)
            for (i in 0 until (projection.size)) {
                val column: String = projection[i]
                var value: Any?
                when (column) {
                    MediaStore.MediaColumns.DISPLAY_NAME -> {
                        value = File(uri.getPath()).getName()
                    }
                    MediaStore.MediaColumns.SIZE -> {
                        value = file.length()
                    }
                    BaseColumns._ID -> {
                        value = 1
                    }
                    else -> {
                        value = null
                    }
                }
                row[i] = value
            }

            val cursor = MatrixCursor(projection)
            cursor.addRow(row)
            return cursor
        }

        override fun getType(uri: Uri): String? {
            fileForUri(uri)
            return ProviderFiles.mimeType(File(uri.getPath()))
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            return null
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
            return 0
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
            return 0
        }

        @Throws(FileNotFoundException::class)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val file: File = fileForUri(uri)
            val errmsg: String? = externalAppsPolicyError()
            if (errmsg != null) {
                throw IllegalArgumentException(errmsg)
            }
            return ProviderFiles.open(file.getPath(), mode, null, allowedRoots(), readOnlyFiles())
        }

        open fun allowedRoots(): Array<File> {
            return arrayOf<File>(TermuxConstants.TERMUX_FILES_DIR, Environment.getExternalStorageDirectory())
        }

        open fun readOnlyFiles(): Array<File> {
            return arrayOf<File>(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE, TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE, TermuxConstants.TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE, TermuxConstants.TERMUX_FLOAT_PROPERTIES_SECONDARY_FILE)
        }

        open fun externalAppsPolicyError(): String? {
            return PluginUtils.checkIfAllowExternalAppsPolicyIsViolated(getContext(), LOG_TAG)
        }

        private fun fileForUri(uri: Uri): File {
            try {
                if (!"content".equals(uri.getScheme()) || !TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY.equals(uri.getAuthority()) || uri.getQuery() != null || uri.getFragment() != null) {
                    throw FileNotFoundException("Invalid provider URI")
                }
                val file: File = ProviderFiles.resolve(uri.getPath(), *allowedRoots())
                if (!file.isFile()) {
                    throw FileNotFoundException("Not a regular file")
                }
                return file
            } catch (e: IOException) {
                throw IllegalArgumentException("Invalid provider file", e)
            }
        }

        companion object {

            private const val LOG_TAG: String = "TermuxContentProvider"
        }
    }

    companion object {

        private const val LOG_TAG: String = "TermuxOpenReceiver"
    }
}
