package com.termux.app.workspace

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One directly owned child at a time. Does not signal process groups or terminal sessions.*/
class ProjectFileTransport : AutoCloseable {
    fun interface Starter {
        @Throws(Exception::class)
        fun start(): Process
    }

    private var active: Operation? = null
    private var closed: Boolean = false

    @Throws(Exception::class)
    fun open(starter: Starter, input: ByteArray?, max: Long, timeoutMillis: Long): Operation {
        if (max < 0 || timeoutMillis <= 0) {
            throw IllegalArgumentException("INVALID_FILE_LIMIT")
        }
        val operation = Operation(max)
        synchronized(this) {
            if (closed) {
                throw IOException("CANCELLED")
            }
            if (active != null) {
                throw IOException("FILE_OPERATION_BUSY")
            }
            active = operation
        }
        try {
            var child: Process
            try {
                child = starter.start()
                operation.attach(child, timeoutMillis)
            } finally {
                operation.launched.countDown()
            }
            operation.check()
            (child.getOutputStream()).use { stdin ->
                if (input != null) {
                    stdin.write(input)
                }
            }
            operation.check()
            return operation
        } catch (failure: Exception) {
            operation.finish()
            synchronized(operation) {
                if (failure is Exception && operation.cancellation != null) {
                    throw IOException(operation.cancellation, failure)
                }
            }
            throw failure
        } catch (failure: Error) {
            operation.finish()
            synchronized(operation) {
                if (failure is Exception && operation.cancellation != null) {
                    throw IOException(operation.cancellation, failure)
                }
            }
            throw failure
        }
    }

    override fun close() {
        var operation: Operation?
        synchronized(this) {
            closed = true
            operation = active
        }
        if (operation != null) {
            operation!!.cancel("CANCELLED")
        }
    }

    inner class Operation : InputStream {
        private val max: Long
        private var total: Long = 0L
        private var child: Process? = null
        private var input: InputStream? = null
        @JvmField var cancellation: String? = null
        private var eof: Boolean = false
        private var streamClosed: Boolean = false
        private var timeout: ScheduledFuture<*>? = null
        private var force: ScheduledFuture<*>? = null
        private val finishing: AtomicBoolean = AtomicBoolean()
        @JvmField val launched: CountDownLatch = CountDownLatch(1)
        private val finished: CountDownLatch = CountDownLatch(1)

        constructor(max: Long) : super() {
            this.max = max
        }

        @Synchronized
        fun attach(value: Process, timeoutMillis: Long) {
            child = value
            input = value.getInputStream()
            timeout = CLOCK.schedule(Runnable { cancel("FILE_OPERATION_TIMEOUT") }, timeoutMillis, TimeUnit.MILLISECONDS)
            if (cancellation != null) {
                stop()
            }
        }

        @Throws(IOException::class)
        @Synchronized
        fun check() {
            if (cancellation != null) {
                throw IOException(cancellation)
            }
            if (Thread.currentThread().isInterrupted()) {
                throw IOException("CANCELLED")
            }
            if (streamClosed) {
                throw IOException("STREAM_CLOSED")
            }
        }

        @Synchronized
        fun cancel(reason: String) {
            if (finished.getCount() == 0L || cancellation != null) {
                return
            }
            cancellation = reason
            if (child != null) {
                stop()
            }
            if (!finishing.get()) {
                STOP.execute(Runnable(this::finish))
            }
        }

        private fun stop() {
            // Use only the Process object. Never retain or signal a numeric/reaped PID.
            if (force != null) {
                return
            }
            val owned: Process? = child
            force = CLOCK.schedule(Callable<Process>(owned!!::destroyForcibly), 1L, TimeUnit.SECONDS)
            STOP.execute(Runnable(owned::destroy))
        }

        @Throws(IOException::class)
        override fun read(): Int {
            val one = ByteArray(1)
            val count: Int = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 255
        }

        @Throws(IOException::class)
        override fun read(bytes: ByteArray?, offset: Int, length: Int): Int {
            if (bytes == null) {
                throw NullPointerException()
            }
            if (offset < 0 || length < 0 || length > bytes.size - offset) {
                throw IndexOutOfBoundsException()
            }
            try {
                check()
                if (length == 0) {
                    return 0
                }
                if (eof) {
                    return -1
                }
                // Probe one extra byte when at the bound; never deliver an excess chunk.
                val remaining: Long = max - total
                val requested: Int = if (remaining >= length) length else remaining.toInt() + 1
                val count: Int = input!!.read(bytes, offset, requested)
                check()
                if (count < 0) {
                    var status: Int
                    try {
                        status = child!!.waitFor()
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("CANCELLED", error)
                    }
                    check()
                    if (status != 0) {
                        throw IOException("SOURCE_UNAVAILABLE")
                    }
                    eof = true
                    finish()
                    return -1
                }
                if (count > remaining) {
                    throw IOException("FILE_LIMIT")
                }
                total += count
                return count
            } catch (failure: IOException) {
                finish()
                synchronized(this) {
                    if (cancellation != null) {
                        throw IOException(cancellation, failure)
                    }
                }
                throw failure
            } catch (failure: RuntimeException) {
                finish()
                synchronized(this) {
                    if (cancellation != null) {
                        throw IOException(cancellation, failure)
                    }
                }
                throw failure
            }
        }

        @Throws(IOException::class)
        override fun skip(count: Long): Long {
            var skipped: Long = 0L
            val buffer = ByteArray(Math.min(32768L, Math.max(0L, count)).toInt())
            while (skipped < count) {
                val n: Int = read(buffer, 0, Math.min(buffer.size.toLong(), count - skipped).toInt())
                if (n < 0) {
                    break
                }
                skipped += n
            }
            return skipped
        }

        override fun markSupported(): Boolean {
            return false
        }

        @Throws(IOException::class)
        override fun reset() {
            throw IOException("MARK_NOT_SUPPORTED")
        }

        override fun close() {
            synchronized(this) {
                streamClosed = true
            }
            finish()
        }

        fun finish() {
            var interrupted = false
            if (finishing.compareAndSet(false, true)) {
                try {
                    while (true) {
                        try {
                            launched.await()
                            break
                        } catch (error: InterruptedException) {
                            interrupted = true
                        }
                    }
                    synchronized(this) {
                        if (!eof && child != null) {
                            stop()
                        }
                    }
                    if (child != null) {
                        while (true) {
                            try {
                                child!!.waitFor()
                                break
                            } catch (error: InterruptedException) {
                                interrupted = true
                            }
                        }
                        try {
                            input!!.close()
                        } catch (ignored: IOException) {
                        }
                        try {
                            child!!.getOutputStream().close()
                        } catch (ignored: IOException) {
                        }
                        try {
                            child!!.getErrorStream().close()
                        } catch (ignored: IOException) {
                        }
                    }
                } finally {
                    synchronized(this) {
                        if (timeout != null) {
                            timeout!!.cancel(false)
                        }
                        if (force != null) {
                            force!!.cancel(false)
                        }
                    }
                    synchronized(this@ProjectFileTransport) {
                        if (active === this) {
                            active = null
                        }
                    }
                    finished.countDown()
                }
            } else {
                while (true) {
                    try {
                        finished.await()
                        break
                    } catch (error: InterruptedException) {
                        interrupted = true
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        private val CLOCK: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
            val thread = Thread(task, "file-transport-timer")
            thread.setDaemon(true)
            return@ThreadFactory thread
        })
        private val STOP: ExecutorService = Executors.newCachedThreadPool(ThreadFactory { task ->
            val thread = Thread(task, "file-transport-stop")
            thread.setDaemon(true)
            return@ThreadFactory thread
        })

        init {
            CLOCK.setRemoveOnCancelPolicy(true)
        }
    }
}
