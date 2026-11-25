package com.termux.app.workspace

/** Field focus and the manual button share one Activity-owned keyboard endpoint.*/
class DesktopKeyboardState {
    @JvmField var enabled: Boolean = false
    @JvmField var automatic: Boolean = true
    private var seenVisible: Boolean = false
    private var automaticHide: Boolean = false
    private var suppressed: Boolean = false
    private var focusSerial: Long = (-1).toLong()
    private var tapSerial: Long = (-1).toLong()

    fun toggle(): Boolean {
        enabled = !enabled
        suppressed = !enabled
        automaticHide = false
        seenVisible = false
        return enabled
    }

    /** A real field transition can open the keyboard without first pressing the button.*/
    fun focus(serial: Long, text: Boolean): Boolean? {
        if (serial == focusSerial) {
            return null
        }
        focusSerial = serial
        if (serial == 0L || !automatic) {
            return null
        }
        if (text) {
            if (suppressed) {
                return null
            }
            enabled = true
            automaticHide = false
            return true
        }
        suppressed = false
        if (!enabled) {
            return null
        }
        automaticHide = true
        return false
    }

    /** Reopening the same focused field after Back requires a fresh pointer tap.*/
    fun tap(serial: Long, text: Boolean): Boolean? {
        if (serial == tapSerial) {
            return null
        }
        tapSerial = serial
        if (serial == 0L || !text || !automatic) {
            return null
        }
        suppressed = false
        enabled = true
        automaticHide = false
        return true
    }

    fun visibility(visible: Boolean?): Boolean {
        if (visible == null) {
            return false
        } // Host reattachment has no window insets yet.
        if (visible) {
            seenVisible = true
            return false
        }
        if (!seenVisible) {
            return false
        }
        seenVisible = false
        if (automaticHide) {
            automaticHide = false
            return false
        }
        val changed: Boolean = enabled
        enabled = false
        suppressed = true
        return changed
    }

    fun reset() {
        suppressed = false
        automaticHide = suppressed
        seenVisible = automaticHide
        enabled = seenVisible
        tapSerial = (-1).toLong()
        focusSerial = tapSerial
    }
}
