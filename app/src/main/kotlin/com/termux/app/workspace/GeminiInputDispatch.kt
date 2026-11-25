package com.termux.app.workspace

/** One paste/submit per captured Gemini run, with cancellation that can never type in a new tab.*/
class GeminiInputDispatch {
    interface Target {
        fun current(): Boolean

        fun paste(text: String)

        fun enter()

        fun cancelled(text: String)
    }

    fun interface Scheduler {
        fun later(task: Runnable, milliseconds: Long)
    }

    private var generation: Long = 0L
    private var pending: Boolean = false

    fun pending(): Boolean {
        return pending
    }

    fun cancel() {
        generation++
        pending = false
    }

    fun send(text: String, target: Target, scheduler: Scheduler): Boolean {
        if (pending || !target.current()) {
            return false
        }
        pending = true
        val token: Long = ++generation
        target.paste(text)
        // Pinned CLI has 30ms fast-Return and 40ms unsafe-paste guards.
        scheduler.later(Runnable {
            if (token != generation) {
                return@Runnable
            }
            pending = false
            if (target.current()) {
                target.enter()
            } else {
                target.cancelled(text)
            }
        }, 160L)
        return true
    }
}
