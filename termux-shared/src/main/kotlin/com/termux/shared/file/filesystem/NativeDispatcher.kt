package com.termux.shared.file.filesystem

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

object NativeDispatcher {

    @Throws(IOException::class)
    @JvmStatic
    fun stat(filePath: String?, fileAttributes: FileAttributes) {
        validateFileExistence(filePath)

        try {
            fileAttributes.loadFromStructStat(Os.stat(filePath))
        } catch (e: ErrnoException) {
            throw IOException("Failed to run Os.stat() on file at path \"" + filePath + "\": " + e.message)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun lstat(filePath: String?, fileAttributes: FileAttributes) {
        validateFileExistence(filePath)

        try {
            fileAttributes.loadFromStructStat(Os.lstat(filePath))
        } catch (e: ErrnoException) {
            throw IOException("Failed to run Os.lstat() on file at path \"" + filePath + "\": " + e.message)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun fstat(fileDescriptor: FileDescriptor, fileAttributes: FileAttributes) {
        validateFileDescriptor(fileDescriptor)

        try {
            fileAttributes.loadFromStructStat(Os.fstat(fileDescriptor))
        } catch (e: ErrnoException) {
            throw IOException("Failed to run Os.fstat() on file descriptor \"" + fileDescriptor.toString() + "\": " + e.message)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun validateFileExistence(filePath: String?) {
        if (filePath == null || filePath.isEmpty()) {
            throw IOException("The path is null or empty")
        }

        val file = File(filePath)

        //if (!file.exists())
        //    throw new IOException("No such file or directory: \"" + filePath + "\"");
    }

    @Throws(IOException::class)
    @JvmStatic
    fun validateFileDescriptor(fileDescriptor: FileDescriptor?) {
        if (fileDescriptor == null) {
            throw IOException("The file descriptor is null")
        }

        if (!fileDescriptor.valid()) {
            throw IOException("No such file descriptor: \"" + fileDescriptor.toString() + "\"")
        }
    }
}
