package com.termux.app.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.function.BooleanSupplier

/** Exclusive creation in the chosen Download directory, without following a destination link.*/
object ProjectFileExport {
    @Throws(IOException::class)
    @JvmStatic
    fun save(input: InputStream, directory: File, name: String?, limit: Long, cancelled: BooleanSupplier): File {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\u0000') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0 || limit < 0) {
            throw IOException("INVALID_EXPORT_NAME")
        }
        check(cancelled)
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw IOException("DOWNLOAD_UNAVAILABLE")
        }
        val dot: Int = name.lastIndexOf('.')
        val stem: String = if (dot > 0) name.substring(0, dot) else name
        val suffix: String = if (dot > 0) name.substring(dot) else ""
        for (number in 1..10000) {
            check(cancelled)
            val destination = File(directory, if (number == 1) name else stem + "-" + number + suffix)
            var stream: OutputStream
            try {
                stream = Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            } catch (collision: FileAlreadyExistsException) {
                continue
            }
            var complete = false
            try {
                (stream).use { output ->
                    val buffer = ByteArray(32768)
                    var total: Long = 0L
                    var count: Int
                    while (true) {
                        check(cancelled)
                        count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        check(cancelled)
                        total += count
                        if (total > limit) {
                            throw IOException("EXPORT_TOO_LARGE")
                        }
                        output.write(buffer, 0, count)
                    }
                    check(cancelled)
                }
                complete = true
                return destination
            } finally {
                if (!complete) {
                    destination.delete()
                }
            }
        }
        throw IOException("TOO_MANY_EXPORT_COLLISIONS")
    }

    @Throws(InterruptedIOException::class)
    @JvmStatic
    private fun check(cancelled: BooleanSupplier) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw InterruptedIOException("EXPORT_CANCELLED")
        }
    }
}
