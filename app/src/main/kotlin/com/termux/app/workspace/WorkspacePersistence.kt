package com.termux.app.workspace

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One FIFO writer per workspace file, shared across overlapping Activity lifetimes.*/
class WorkspacePersistence : AutoCloseable {
    private class State {
        @JvmField val key: String
        @JvmField val file: AtomicFile
        @JvmField val writer: ExecutorService = Executors.newSingleThreadExecutor()
        @JvmField val owners: MutableSet<WorkspacePersistence> = HashSet<WorkspacePersistence>()
        @JvmField var pending: ByteArray? = null
        @JvmField var lastWrite: Future<*>? = null

        constructor(key: String, file: AtomicFile) {
            this.key = key
            this.file = file
        }
    }

    private val state: State?
    private val failed: Runnable
    private var closed: Boolean = false

    constructor(file: AtomicFile, failed: Runnable) {
        this.failed = failed
        var key: String
        try {
            key = file.getBaseFile().getCanonicalPath()
        } catch (error: IOException) {
            key = file.getBaseFile().getAbsolutePath()
        }
        synchronized(FILES) {
            var found: State? = FILES.get(key)
            if (found == null) {
                found = State(key, file)
                FILES.put(key, found)
            }
            state = found
            state.owners.add(this)
        }
    }

    @Throws(IOException::class)
    fun read(): ByteArray? {
        synchronized(FILES) {
            // A writer registers pending before it can touch AtomicFile. Reads
            // during queued/in-flight writes use immutable snapshots, not .bak.
            if (state!!.pending != null) {
                return state.pending
            }
            val base: File = state.file.getBaseFile()
            if (!base.exists() && !File(base.getPath() + ".bak").exists()) {
                return null
            }
            return state.file.readFully()
        }
    }

    fun write(snapshot: ByteArray, completed: Runnable) {
        synchronized(FILES) {
            if (closed) {
                return
            }
            state!!.pending = snapshot
            state.lastWrite = state.writer.submit(Runnable {
                var out: FileOutputStream? = null
                var success = false
                val failures: MutableList<Runnable> = ArrayList<Runnable>()
                try {
                    out = state.file.startWrite()
                    out!!.write(snapshot)
                    state.file.finishWrite(out)
                    success = true
                } catch (error: IOException) {
                    if (out != null) {
                        try {
                            state.file.failWrite(out)
                        } catch (ignored: RuntimeException) {
                        }
                    }
                } catch (error: RuntimeException) {
                    if (out != null) {
                        try {
                            state.file.failWrite(out)
                        } catch (ignored: RuntimeException) {
                        }
                    }
                } finally {
                    synchronized(FILES) {
                        if (state.pending === snapshot) {
                            state.pending = null
                        }
                        if (!success) {
                            for (owner in state.owners) {
                                failures.add(owner.failed)
                            }
                        }
                        dispose(state)
                    }
                }
                if (success) {
                    completed.run()
                } else {
                    for (notify in failures) {
                        notify.run()
                    }
                }
            })
        }
    }

    @Throws(Exception::class)
    fun awaitWrites() {
        var last: Future<*>?
        synchronized(FILES) {
            last = state!!.lastWrite
        }
        if (last != null) {
            last!!.get(10L, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        synchronized(FILES) {
            if (closed) {
                return
            }
            closed = true
            state!!.owners.remove(this)
            dispose(state)
        }
    }

    companion object {
        private val FILES: MutableMap<String, State?> = HashMap<String, State?>()

        @JvmStatic
        private fun dispose(state: State?) {
            if (state!!.owners.isEmpty() && state.pending == null) {
                FILES.remove(state.key, state)
                state.writer.shutdown()
            }
        }
    }
}
