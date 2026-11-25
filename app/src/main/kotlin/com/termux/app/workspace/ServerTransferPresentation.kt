package com.termux.app.workspace

import android.app.AlertDialog

/** Main-thread presentation only. Hiding the window never owns/cancels the transport.*/
class ServerTransferPresentation {
    private var owner: String = ""
    private var text: String = ""
    private var window: AlertDialog? = null
    private var active: Boolean = false
    private var background: Boolean = false
    private var bytes: Long = 0L
    private var total: Long = (-1).toLong()
    private var changed: Runnable = Runnable {  }

    fun listener(listener: Runnable?) {
        changed = if (listener == null) Runnable {  } else listener
    }

    fun active(): Boolean {
        return active
    }

    fun belongs(id: String): Boolean {
        return active && owner.equals(id)
    }

    fun text(): String {
        return text
    }

    fun indeterminate(): Boolean {
        return total < 0
    }

    fun progress(): Int {
        return if (total < 0) 0 else if (total == 0L) 1000 else Math.max(0.0, Math.min(1000.0, bytes * 1000.0 / total)).toInt()
    }

    fun begin(id: String, dialog: AlertDialog?, initial: String) {
        bytes = 0L
        total = (-1).toLong()
        owner = id
        window = dialog
        text = initial
        active = true
        background = false
        changed.run()
    }

    fun update(value: String) {
        text = value
        if (active) {
            changed.run()
        }
    }

    fun update(value: String, done: Long, size: Long) {
        text = value
        bytes = done
        total = size
        if (active) {
            changed.run()
        }
    }

    fun background() {
        if (!active || window == null) {
            return
        }
        background = true
        window!!.hide()
        changed.run()
    }

    fun foreground() {
        if (!active || window == null) {
            return
        }
        background = false
        window!!.show()
        changed.run()
    }

    fun finish(): Boolean {
        val hidden: Boolean = background
        active = false
        background = false
        window = null
        changed.run()
        return hidden
    }
}
