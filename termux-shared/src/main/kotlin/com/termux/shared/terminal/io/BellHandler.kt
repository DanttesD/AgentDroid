package com.termux.shared.terminal.io

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.termux.shared.logger.Logger

open class BellHandler {

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var lastBell: Long = 0L
    private val bellRunnable: Runnable

    private constructor(vibrator: Vibrator?) {
        bellRunnable = object : Runnable {
            override fun run() {
                if (vibrator != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(DURATION, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            vibrator.vibrate(DURATION)
                        }
                    } catch (e: Exception) {
                        // Issue on samsung devices on android 8
                        // java.lang.NullPointerException: Attempt to read from field 'android.os.VibrationEffect com.android.server.VibratorService$Vibration.mEffect' on a null object reference
                        Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run vibrator", e)
                    }
                }
            }
        }
    }

    @Synchronized
    open fun doBell() {
        val now: Long = now()
        val timeSinceLastBell: Long = now - lastBell

        if (timeSinceLastBell < 0) {
            // there is a next bell pending; don't schedule another one
        } else if (timeSinceLastBell < MIN_PAUSE) {
            // there was a bell recently, schedule the next one
            handler.postDelayed(bellRunnable, MIN_PAUSE - timeSinceLastBell)
            lastBell = lastBell + MIN_PAUSE
        } else {
            // the last bell was long ago, do it now
            bellRunnable.run()
            lastBell = now
        }
    }

    private fun now(): Long {
        return SystemClock.uptimeMillis()
    }

    companion object {
        private var instance: BellHandler? = null
        private val lock: Any = Any()

        private const val LOG_TAG: String = "BellHandler"

        @JvmStatic
        fun getInstance(context: Context): BellHandler? {
            if (instance == null) {
                synchronized(lock) {
                    if (instance == null) {
                        instance = BellHandler(context.getApplicationContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?)
                    }
                }
            }

            return instance
        }

        private const val DURATION: Long = 50L
        private const val MIN_PAUSE: Long = 3 * DURATION
    }
}
