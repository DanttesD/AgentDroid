package com.termux.app.workspace

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RunnableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serial mutations/shared transport; bounded isolated lanes for independent read-only transports.*/
class ServerWorkQueues : AutoCloseable {
    private fun interface Timed {
        fun queuedAt(): Long
    }

    private class Task<V> : FutureTask<V>, Timed {
        @JvmField val created: Long = System.nanoTime()

        constructor(work: Callable<V>) : super(work)

        override fun queuedAt(): Long {
            return created
        }
    }

    private class Work : Runnable, Timed {
        @JvmField val work: Runnable
        @JvmField val created: Long = System.nanoTime()

        constructor(r: Runnable) {
            work = r
        }

        override fun queuedAt(): Long {
            return created
        }

        override fun run() {
            work.run()
        }
    }

    private class Queue : ThreadPoolExecutor {
        constructor(count: Int, waiting: BlockingQueue<Runnable>) : super(count, count, 0L, TimeUnit.MILLISECONDS, waiting, ThreadPoolExecutor.AbortPolicy())

        protected override fun <T> newTaskFor(task: Callable<T>): RunnableFuture<T> {
            return Task<T>(task)
        }

        protected override fun <T> newTaskFor(task: Runnable, result: T): RunnableFuture<T> {
            return Task<T>(Executors.callable(task, result))
        }

        override fun execute(task: Runnable) {
            super.execute(if (task is Timed) task else Work(task))
        }

        protected override fun beforeExecute(thread: Thread, task: Runnable) {
            ServerDiagnostics.queueStarted((System.nanoTime() - (task as Timed).queuedAt()) / 1000000)
        }

        protected override fun afterExecute(task: Runnable, error: Throwable) {
            ServerDiagnostics.queueFinished()
        }
    }

    @JvmField val foreground: ExecutorService = Queue(1, LinkedBlockingQueue<Runnable>())
    @JvmField val monitor: ThreadPoolExecutor = Queue(1, ArrayBlockingQueue<Runnable>(1))
    @JvmField val helpers: ThreadPoolExecutor = Queue(2, ArrayBlockingQueue<Runnable>(2))

    fun helper(work: Runnable): Future<*> {
        return helpers.submit(work)
    }

    fun cancelHelper(task: Future<*>?) {
        if (task != null) {
            task.cancel(true)
        }
        helpers.purge()
    }

    override fun close() {
        stop(foreground)
        stop(monitor)
        stop(helpers)
    }

    companion object {
        @JvmStatic
        private fun stop(executor: ExecutorService) {
            for (task in executor.shutdownNow()) {
                if (task is Future<*>) {
                    (task as Future<Any>).cancel(false)
                }
            }
        }
    }
}
