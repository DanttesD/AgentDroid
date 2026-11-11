package com.termux.terminal

/** A circular byte buffer allowing one producer and one consumer thread. */
internal class ByteQueue(size: Int) {

    private val mBuffer = ByteArray(size)
    private var mHead = 0
    private var mStoredBytes = 0
    private var mOpen = true

    // The monitor is the queue itself (as with the synchronized methods of the Java original).
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor: java.lang.Object
        get() = this as java.lang.Object

    @Synchronized
    fun close() {
        mOpen = false
        monitor.notify()
    }

    @Synchronized
    fun read(buffer: ByteArray, block: Boolean): Int {
        while (mStoredBytes == 0 && mOpen) {
            if (block) {
                try {
                    monitor.wait()
                } catch (e: InterruptedException) {
                    // Ignore.
                }
            } else {
                return 0
            }
        }
        if (!mOpen) return -1

        var totalRead = 0
        val bufferLength = mBuffer.size
        val wasFull = bufferLength == mStoredBytes
        var length = buffer.size
        var offset = 0
        while (length > 0 && mStoredBytes > 0) {
            val oneRun = Math.min(bufferLength - mHead, mStoredBytes)
            val bytesToCopy = Math.min(length, oneRun)
            System.arraycopy(mBuffer, mHead, buffer, offset, bytesToCopy)
            mHead += bytesToCopy
            if (mHead >= bufferLength) mHead = 0
            mStoredBytes -= bytesToCopy
            length -= bytesToCopy
            offset += bytesToCopy
            totalRead += bytesToCopy
        }
        if (wasFull) monitor.notify()
        return totalRead
    }

    /**
     * Attempt to write the specified portion of the provided buffer to the queue.
     *
     * Returns whether the output was totally written, false if it was closed before.
     */
    fun write(buffer: ByteArray, offset: Int, lengthToWrite: Int): Boolean {
        var offset = offset
        var lengthToWrite = lengthToWrite
        if (lengthToWrite + offset > buffer.size) {
            throw IllegalArgumentException("length + offset > buffer.length")
        } else if (lengthToWrite <= 0) {
            throw IllegalArgumentException("length <= 0")
        }

        val bufferLength = mBuffer.size

        synchronized(this) {
            while (lengthToWrite > 0) {
                while (bufferLength == mStoredBytes && mOpen) {
                    try {
                        monitor.wait()
                    } catch (e: InterruptedException) {
                        // Ignore.
                    }
                }
                if (!mOpen) return false
                val wasEmpty = mStoredBytes == 0
                var bytesToWriteBeforeWaiting = Math.min(lengthToWrite, bufferLength - mStoredBytes)
                lengthToWrite -= bytesToWriteBeforeWaiting

                while (bytesToWriteBeforeWaiting > 0) {
                    var tail = mHead + mStoredBytes
                    val oneRun: Int
                    if (tail >= bufferLength) {
                        // Buffer: [.............]
                        // ________________H_______T
                        // =>
                        // Buffer: [.............]
                        // ___________T____H
                        // onRun= _____----_
                        tail = tail - bufferLength
                        oneRun = mHead - tail
                    } else {
                        oneRun = bufferLength - tail
                    }
                    val bytesToCopy = Math.min(oneRun, bytesToWriteBeforeWaiting)
                    System.arraycopy(buffer, offset, mBuffer, tail, bytesToCopy)
                    offset += bytesToCopy
                    bytesToWriteBeforeWaiting -= bytesToCopy
                    mStoredBytes += bytesToCopy
                }
                if (wasEmpty) monitor.notify()
            }
        }
        return true
    }
}
