package com.agentdroid.agent

/**
 * The phone's accessibility service as seen by the tools. Implemented by the app; absent (null) until
 * the user enables the service. Element numbers are valid only until the next [read] call.
 */
interface ScreenBridge {
    /** Set by the app when the accessibility service connects, cleared when it stops. */
    object Holder {
        @Volatile
        @JvmField
        var current: ScreenBridge? = null
    }

    /** Numbered text snapshot of the screen. `all` also lists non-interactive elements without text. */
    fun read(all: Boolean): String

    fun click(id: Int): String

    fun longClick(id: Int): String

    fun setText(id: Int, text: String): String

    /** direction: up, down, left, right (the finger's direction is the opposite of the content's). */
    fun scroll(id: Int, direction: String): String

    /** back, home, recents, notifications, quick_settings, lock_screen, power_dialog, screenshot. */
    fun global(action: String): String

    /** PNG bytes of the screen or null when unsupported (needs Android 11+). */
    fun screenshot(): ByteArray?

    /** Package of the app in the foreground, or empty when unknown. */
    fun foregroundPackage(): String
}
