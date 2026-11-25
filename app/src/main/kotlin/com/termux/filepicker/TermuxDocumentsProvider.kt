package com.termux.filepicker

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.LinkedList
import java.util.Locale

/**
 * A document provider for the Storage Access Framework which exposes the files in the
 * $HOME/ directory to other apps.
 * <p/>
 * Note that this replaces providing an activity matching the ACTION_GET_CONTENT intent:
 * <p/>
 * "A document provider and ACTION_GET_CONTENT should be considered mutually exclusive. If you
 * support both of them simultaneously, your app will appear twice in the system picker UI,
 * offering two different ways of accessing your stored data. This would be confusing for users."
 * - http://developer.android.com/guide/topics/providers/document-provider.html#43
 **/
open class TermuxDocumentsProvider : DocumentsProvider() {

    protected open fun getBaseDirectory(): File {
        return TermuxConstants.TERMUX_HOME_DIR
    }

    private fun roots(): Array<File> {
        return arrayOf<File>(getBaseDirectory())
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(if (projection != null) projection else DEFAULT_ROOT_PROJECTION)
        val applicationName: String = getContext()!!.getString(R.string.application_name)

        val row: MatrixCursor.RowBuilder = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, getDocIdForFile(getBaseDirectory()))
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, getDocIdForFile(getBaseDirectory()))
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, null)
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
        row.add(DocumentsContract.Root.COLUMN_TITLE, applicationName)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, getBaseDirectory().getFreeSpace())
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(if (projection != null) projection else DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId)
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String): Cursor {
        val result = MatrixCursor(if (projection != null) projection else DEFAULT_DOCUMENT_PROJECTION)
        val parent: File = getFileForDocId(parentDocumentId)
        val children: Array<File>? = parent.listFiles()
        if (children == null) {
            throw FileNotFoundException("Cannot list document directory")
        }
        for (file in children) {
            try {
                includeFile(result, File(parentDocumentId, file.getName()).getAbsolutePath())
            } catch (ignored: FileNotFoundException) {
                /* Disappeared or points outside HOME.*/
            }
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? {
        return ProviderFiles.open(documentId, mode, signal, roots(), emptyArray<File>())
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal): AssetFileDescriptor {
        val pfd: ParcelFileDescriptor? = openDocument(documentId, "r", signal)
        return AssetFileDescriptor(pfd, 0L, pfd!!.getStatSize())
    }

    override fun onCreate(): Boolean {
        return true
    }

    @Throws(FileNotFoundException::class)
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String?): String {
        if (displayName == null || displayName.isEmpty() || displayName.equals(".") || displayName.equals("..") || displayName.indexOf('/') >= 0 || displayName.indexOf('\\') >= 0 || displayName.indexOf('\u0000') >= 0) {
            throw FileNotFoundException("Invalid document name")
        }
        val parent: File = getFileForDocId(parentDocumentId)
        try {
            (ProviderFiles.openDirectory(parent, *roots())).use { owner ->
                val anchored = File("/proc/self/fd/" + owner!!.getFd())
                for (number in 1..10000) {
                    val name: String = if (number == 1) displayName else displayName + " (" + number + ")"
                    val candidate = File(anchored, name)
                    try {
                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                            Files.createDirectory(candidate.toPath())
                        } else {
                            Files.createFile(candidate.toPath())
                        }
                        return File(parentDocumentId, name).getAbsolutePath()
                    } catch (ignored: FileAlreadyExistsException) {
                        /* Also covers dangling symlinks.*/
                    }
                }
            }
        } catch (e: IOException) {
            val failure = FileNotFoundException("Cannot create document")
            failure.initCause(e)
            throw failure
        }
        throw FileNotFoundException("Too many document name conflicts")
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val file: File = getFileForDocId(documentId)
        try {
            if (file.equals(getBaseDirectory().getCanonicalFile())) {
                throw FileNotFoundException("Cannot delete provider root")
            }
            // Keep the original basename: deleting an internal symlink must unlink it,
            // never remove its target. The checked parent descriptor anchors this mutation.
            val original: File = File(documentId).getAbsoluteFile()
            (ProviderFiles.openDirectory(original.getParentFile(), *roots())).use { parent ->
                Files.delete(File("/proc/self/fd/" + parent!!.getFd(), original.getName()).toPath())
            }
        } catch (e: IOException) {
            val failure = FileNotFoundException("Cannot delete document")
            failure.initCause(e)
            throw failure
        }
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String {
        val file: File = getFileForDocId(documentId)
        return getMimeType(file, documentId)
    }

    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(rootId: String, query: String?, projection: Array<String>?): Cursor {
        val result = MatrixCursor(if (projection != null) projection else DEFAULT_DOCUMENT_PROJECTION)
        getFileForDocId(rootId)

        val pending = LinkedList<File>()
        val visited: MutableSet<String> = HashSet<String>()
        val needle: String = if (query == null) "" else (query as java.lang.String).toLowerCase(Locale.ROOT)
        pending.add(File(rootId))
        // Bound both work and queue size even for a tree with no matching names.
        val maxScanned = 10000
        var scanned = 0
        while (!pending.isEmpty() && result.getCount() < 50 && scanned++ < maxScanned) {
            val candidate: File = pending.removeFirst()
            var file: File
            try {
                file = getFileForDocId(candidate.getPath())
            } catch (ignored: FileNotFoundException) {
                continue
            }
            if (file.isDirectory()) {
                if (!visited.add(file.getPath())) {
                    continue
                }
                val children: Array<File>? = file.listFiles()
                if (children != null) {
                    for (child in children) {
                        if (pending.size + scanned >= maxScanned) {
                            break
                        }
                        pending.add(File(candidate, child.getName()))
                    }
                }
            } else if ((candidate.getName() as java.lang.String).toLowerCase(Locale.ROOT).contains(needle)) {
                try {
                    includeFile(result, candidate.getAbsolutePath())
                } catch (ignored: FileNotFoundException) {
                    /* Changed while searching.*/
                }
            }
        }

        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        try {
            val parent: File = getFileForDocId(parentDocumentId)
            val child: File = getFileForDocId(documentId)
            return parent.isDirectory() && !parent.equals(child) && ProviderFiles.contains(parent, child)
        } catch (e: FileNotFoundException) {
            return false
        }
    }

    /**
     * Get the file given a document id (the reverse of [.getDocIdForFile]).
     **/
    @Throws(FileNotFoundException::class)
    private fun getFileForDocId(docId: String): File {
        val file: File = ProviderFiles.resolve(docId, *roots())
        if (!file.exists()) {
            throw FileNotFoundException("Document not found")
        }
        if (!file.isDirectory() && !file.isFile()) {
            throw FileNotFoundException("Unsupported document type")
        }
        return file
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId the stable absolute-path document ID
     **/
    @Throws(FileNotFoundException::class)
    private fun includeFile(result: MatrixCursor, docId: String) {
        val file: File = getFileForDocId(docId)

        var flags = 0
        if (file.isDirectory()) {
            if (file.canWrite()) {
                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (file.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        if (!file.equals(getFileForDocId(getBaseDirectory().getPath())) && file.getParentFile() != null && file.getParentFile()!!.canWrite()) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }

        val displayName: String = File(docId).getName()
        val mimeType: String = getMimeType(file, docId)
        if (mimeType.startsWith("image/")) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        }

        val row: MatrixCursor.RowBuilder = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Document.COLUMN_ICON, R.mipmap.ic_launcher)
    }

    companion object {

        private const val ALL_MIME_TYPES: String = "*/*"

        // The default columns to return information about a root if no specific
        // columns are requested in a query.
        private val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf<String>(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_ICON, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)

        // The default columns to return information about a document if no specific
        // columns are requested in a query.
        private val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf<String>(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)

        /**
         * Get the document id given a file. This document id must be consistent across time as other
         * applications may save the ID and use it to reference documents later.
         * <p/>
         * The reverse of @{link #getFileForDocId}.
         **/
        @JvmStatic
        private fun getDocIdForFile(file: File): String {
            return file.getAbsolutePath()
        }

        @JvmStatic
        private fun getMimeType(file: File, docId: String): String {
            if (file.isDirectory()) {
                return DocumentsContract.Document.MIME_TYPE_DIR
            }
            val mime: String? = ProviderFiles.mimeType(File(docId))
            return if (mime == null) "application/octet-stream" else mime
        }
    }
}
