package com.termux.filepicker

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/** Filesystem guards shared by the two externally reachable legacy providers.*/
object ProviderFiles {

    // Linux O_CLOEXEC exists on every supported Android kernel, but the Java
    // OsConstants field is public only from API27. Keep the API26 app baseline.
    private const val OPEN_CLOEXEC: Int = 0x80000

    @JvmStatic
    fun contains(root: File, file: File): Boolean {
        return file.equals(root) || file.toPath().startsWith(root.toPath())
    }

    @Throws(FileNotFoundException::class)
    @JvmStatic
    fun resolve(path: String?, vararg roots: File): File {
        if (path == null || path.indexOf('\u0000') >= 0 || !File(path).isAbsolute()) {
            throw FileNotFoundException("Invalid provider path")
        }
        try {
            val file: File = File(path).getCanonicalFile()
            for (root in roots) {
                if (contains(root.getCanonicalFile(), file)) {
                    return file
                }
            }
        } catch (e: IOException) {
            throw failure("Cannot resolve provider path", e)
        }
        throw FileNotFoundException("Path outside provider roots")
    }

    @JvmStatic
    fun mimeType(file: File): String? {
        val name: String = file.getName()
        val dot: Int = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) {
            return null
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension((name.substring(dot + 1) as java.lang.String).toLowerCase(Locale.ROOT))
    }

    /**
     * Open without create/truncate, then validate the actual descriptor before any write.
     * NONBLOCK prevents a FIFO/device substituted after metadata checking from hanging a binder.
     * O_NOFOLLOW rejects a final symlink substituted after canonical resolution. Ancestor
     * substitution is checked through the opened descriptor's kernel path, not the original name.
     **/
    @Throws(FileNotFoundException::class)
    @JvmStatic
    fun open(path: String, mode: String, signal: CancellationSignal?, roots: Array<File>, readOnly: Array<File>): ParcelFileDescriptor? {
        if (signal != null) {
            signal.throwIfCanceled()
        }
        val file: File = resolve(path, *roots)
        if (!file.isFile()) {
            throw FileNotFoundException("Not a regular file")
        }
        val access: Int = ParcelFileDescriptor.parseMode(mode)
        val kind: Int = access and ParcelFileDescriptor.MODE_READ_WRITE
        var flags: Int = if (kind == ParcelFileDescriptor.MODE_READ_ONLY) OsConstants.O_RDONLY else if (kind == ParcelFileDescriptor.MODE_WRITE_ONLY) OsConstants.O_WRONLY else OsConstants.O_RDWR
        if (access and ParcelFileDescriptor.MODE_APPEND != 0) {
            flags = flags or OsConstants.O_APPEND
        }
        var descriptor: ParcelFileDescriptor? = openDescriptor(file, flags or OsConstants.O_NONBLOCK, roots)
        try {
            val stat: StructStat = Os.fstat(descriptor!!.getFileDescriptor())
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw FileNotFoundException("Not a regular file")
            }
            if (signal != null) {
                signal.throwIfCanceled()
            }
            if (kind != ParcelFileDescriptor.MODE_READ_ONLY && isProtected(stat, readOnly)) {
                // Preserve the legacy read-only downgrade, including hard-link aliases.
                descriptor.close()
                descriptor = null
                return open(path, "r", signal, roots, readOnly)
            }
            if (access and ParcelFileDescriptor.MODE_TRUNCATE != 0) {
                Os.ftruncate(descriptor.getFileDescriptor(), 0L)
            }
            val result: ParcelFileDescriptor? = descriptor
            descriptor = null
            return result
        } catch (e: IOException) {
            throw failure("Cannot open provider file", e)
        } catch (e: ErrnoException) {
            throw failure("Cannot open provider file", e)
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    /** Pin a checked parent for create/delete; callers use /proc/self/fd/N/basename.*/
    @Throws(FileNotFoundException::class)
    @JvmStatic
    fun openDirectory(directory: File?, vararg roots: File): ParcelFileDescriptor? {
        var descriptor: ParcelFileDescriptor? = openDescriptor(resolve(directory!!.getPath(), *roots), OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, roots)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor!!.getFileDescriptor()).st_mode)) {
                throw FileNotFoundException("Not a document directory")
            }
            val result: ParcelFileDescriptor? = descriptor
            descriptor = null
            return result
        } catch (e: ErrnoException) {
            throw failure("Cannot inspect document directory", e)
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    @Throws(FileNotFoundException::class)
    @JvmStatic
    private fun openDescriptor(file: File, flags: Int, roots: Array<out File>): ParcelFileDescriptor? {
        var descriptor: ParcelFileDescriptor? = null
        try {
            val raw: FileDescriptor = Os.open(file.getPath(), flags or OPEN_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                descriptor = ParcelFileDescriptor.dup(raw)
            } finally {
                Os.close(raw)
            }
            validateDescriptorPath(Os.readlink("/proc/self/fd/" + descriptor!!.getFd()), roots)
            val result: ParcelFileDescriptor? = descriptor
            descriptor = null
            return result
        } catch (e: IOException) {
            throw failure("Cannot open provider descriptor", e)
        } catch (e: ErrnoException) {
            throw failure("Cannot open provider descriptor", e)
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    @Throws(IOException::class, ErrnoException::class)
    @JvmStatic
    private fun validateDescriptorPath(openedPath: String, roots: Array<out File>) {
        try {
            resolve(openedPath, *roots)
            return
        } catch (outside: FileNotFoundException) {
            // Android bind-mount aliases (e.g. /data/data and /data/user/0) need not
            // canonicalize to the kernel's fd path. Accept only an ancestor that is
            // the very same directory inode as an allowed root, never a string alias.
            val rootStats = arrayOfNulls<StructStat>(roots.size)
            for (i in 0 until (roots.size)) {
                try {
                    val stat: StructStat = Os.stat(roots[i].getCanonicalPath())
                    if (OsConstants.S_ISDIR(stat.st_mode)) {
                        rootStats[i] = stat
                    }
                } catch (e: ErrnoException) {
                    // An unmounted/ungranted optional shared-storage root cannot
                    // invalidate a different, positively identified private root.
                    if (e.errno != OsConstants.ENOENT && e.errno != OsConstants.ENOTDIR && e.errno != OsConstants.EACCES) {
                        throw e
                    }
                }
            }
            var ancestor: File? = File(openedPath)
            while (ancestor != null) {
                try {
                    val current: StructStat = Os.stat(ancestor.getPath())
                    for (root in rootStats) {
                        if (root != null && OsConstants.S_ISDIR(current.st_mode) && current.st_dev == root.st_dev && current.st_ino == root.st_ino) {
                            return
                        }
                    }
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.ENOENT && e.errno != OsConstants.ENOTDIR) {
                        throw e
                    }
                }
                ancestor = ancestor.getParentFile()
            }
            throw outside
        }
    }

    @Throws(ErrnoException::class)
    @JvmStatic
    private fun isProtected(opened: StructStat, readOnly: Array<File>): Boolean {
        for (path in readOnly) {
            try {
                val protectedFile: StructStat = Os.stat(path.getPath())
                if (opened.st_dev == protectedFile.st_dev && opened.st_ino == protectedFile.st_ino) {
                    return true
                }
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENOENT && e.errno != OsConstants.ENOTDIR) {
                    throw e
                }
            }
        }
        return false
    }

    @JvmStatic
    private fun failure(message: String, cause: Exception): FileNotFoundException {
        val failure = FileNotFoundException(message)
        failure.initCause(cause)
        return failure
    }
}
