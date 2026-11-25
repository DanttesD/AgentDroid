package com.termux.app.workspace

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Local key import: private directory, exclusive filename, bounded input; never logs contents.*/
object SshKeyFiles {
    const val MAX: Long = (256 * 1024).toLong()

    @JvmStatic
    fun identity(path: String): String {
        return if (path.startsWith("~/")) TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(1) else path
    }

    @Throws(IOException::class)
    @JvmStatic
    fun privateTermuxFile(path: String): Boolean {
        val canonical: String = File(identity(path)).getCanonicalPath()
        return canonical.startsWith(File(TermuxConstants.TERMUX_FILES_DIR_PATH).getCanonicalPath() + "/")
    }

    @Throws(IOException::class)
    @JvmStatic
    fun validate(file: File?) {
        if (!file!!.isFile() || !file.canRead() || file.length() == 0L || file.length() > MAX) {
            throw IOException("INVALID_KEY_FILE")
        }
        val header = ByteArray(80)
        var count: Int
        val `in` = FileInputStream(file)
        var primaryException: Throwable? = null
        try {
            count = `in`.read(header)
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
        val first: String = (String(header, 0, Math.max(0, count), StandardCharsets.US_ASCII) as java.lang.String).split("\r?\n", 2)[0]
        if (!(first as java.lang.String).matches("-----BEGIN (OPENSSH |RSA |DSA |EC |ENCRYPTED )?PRIVATE KEY-----")) {
            throw IOException("PRIVATE_KEY_REQUIRED")
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun ownerOnly(file: File, directory: Boolean) {
        if (!file.setReadable(false, false) || !file.setWritable(false, false) || !file.setExecutable(false, false) || !file.setReadable(true, true) || !file.setWritable(true, true) || directory && !file.setExecutable(true, true)) {
            throw IOException("KEY_PERMISSIONS_FAILED")
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun createPrivate(files: File): File {
        val directory = File(files, "server-keys")
        if (!directory.getCanonicalFile().equals(File(files.getCanonicalFile(), "server-keys"))) {
            throw IOException("INVALID_KEY_DIRECTORY")
        }
        if (!directory.isDirectory() && !directory.mkdir()) {
            throw IOException("INVALID_KEY_DIRECTORY")
        }
        ownerOnly(directory, true)
        val key: File = File.createTempFile("identity-", ".key", directory)
        try {
            ownerOnly(key, false)
            return key
        } catch (error: IOException) {
            key.delete()
            throw error
        }
    }
}
