package com.termux.app.agent

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The approval question as a card inside the chat feed: the description and three buttons (Allow, 1 hour, Deny).
 * After the answer the card collapses to one line, so the feed keeps a record of what was allowed.
 * [.ask] blocks the calling (agent) thread, like the dialog it replaces.
 **/
class InlineApproval {

    private class Pending {
        @JvmField val state: AtomicInteger = AtomicInteger(OPEN)
        @JvmField val latch: CountDownLatch = CountDownLatch(1)
        @JvmField val description: String
        @JvmField var card: LinearLayout? = null

        constructor(description: String) {
            this.description = description
        }
    }

    private val activity: Activity
    private val ui: Ui?
    private val feed: LinearLayout?
    private val scrollDown: Runnable
    /** Touched on the UI thread only.*/
    private val open: MutableList<Pending> = ArrayList<Pending>()

    constructor(activity: Activity, ui: Ui?, feed: LinearLayout?, scrollDown: Runnable) {
        this.activity = activity
        this.ui = ui
        this.feed = feed
        this.scrollDown = scrollDown
    }

    private fun settle(p: Pending, to: Int): Boolean {
        if (!p.state.compareAndSet(OPEN, to)) {
            return false
        }
        p.latch.countDown()
        return true
    }

    /** TRUE / FALSE once answered (a timeout is FALSE); null when the card could not stay (the screen was left).*/
    fun ask(description: String, force: Boolean): Boolean? {
        val p = Pending(description)
        activity.runOnUiThread(Runnable {
            if (activity.isFinishing() || activity.isDestroyed()) {
                settle(p, MOVED)
                return@Runnable
            }
            p.card = card(p, force)
            open.add(p)
            feed!!.addView(p.card)
            scrollDown.run()
        })
        try {
            if (!p.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) && settle(p, DENIED)) {
                activity.runOnUiThread(Runnable { collapse(p, false, activity.getString(R.string.chat_approval_timeout)) })
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (settle(p, DENIED)) {
                activity.runOnUiThread(Runnable { collapse(p, false, activity.getString(R.string.chat_denied)) })
            }
            return false
        }
        val s: Int = p.state.get()
        return if (s == MOVED) null else java.lang.Boolean.valueOf(s == ALLOWED)
    }

    /** The screen is going away: open cards are removed, the questions are asked again where the user is now. UI thread.*/
    fun releasePending() {
        for (p in ArrayList<Pending>(open)) {
            if (settle(p, MOVED) && p.card != null) {
                feed!!.removeView(p.card)
            }
        }
        open.clear()
    }

    /** Stop or new chat: every open question is answered "no". UI thread.*/
    fun denyAll() {
        for (p in ArrayList<Pending>(open)) {
            if (settle(p, DENIED)) {
                collapse(p, false, activity.getString(R.string.chat_denied))
            }
        }
        open.clear()
    }

    private fun card(p: Pending, force: Boolean): LinearLayout {
        val c: LinearLayout = ui!!.column()
        c.setBackground(ui.shape(ui.t.s1, 18.0f, ui.t.p, 2.0f))
        c.setPadding(ui.dp(14.0f), ui.dp(12.0f), ui.dp(14.0f), ui.dp(12.0f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = ui.dp(12.0f)
        c.setLayoutParams(lp)

        val head = LinearLayout(activity)
        head.setGravity(Gravity.CENTER_VERTICAL)
        val shield: ImageView = ui.icon(R.drawable.ic_shield, 20, ui.t.p)
        val sl = LinearLayout.LayoutParams(ui.dp(20.0f), ui.dp(20.0f))
        sl.rightMargin = ui.dp(8.0f)
        head.addView(shield, sl)
        head.addView(ui.text(activity.getString(R.string.agent_allow_title), 15.0f, ui.t.on, true))
        c.addView(head)

        val body: TextView = ui.text(p.description, 13.5f, ui.t.on2, false)
        body.setTextIsSelectable(true)
        c.addView(body, ui.gap(8))

        val buttons = LinearLayout(activity)
        buttons.addView(button(R.string.agent_allow, Ui.Kind.FILLED, View.OnClickListener { v -> answer(p, ALLOWED, true, activity.getString(R.string.chat_approved), false) }))
        if (!force) {
            buttons.addView(button(R.string.chat_allow_hour_short, Ui.Kind.TONAL, View.OnClickListener { v -> answer(p, ALLOWED, true, activity.getString(R.string.chat_approved), true) }))
        }
        buttons.addView(button(R.string.agent_deny, Ui.Kind.DANGER, View.OnClickListener { v -> answer(p, DENIED, false, activity.getString(R.string.chat_denied), false) }))
        c.addView(buttons, ui.gap(12))
        return c
    }

    private fun button(label: Int, kind: Ui.Kind, l: View.OnClickListener): TextView {
        val b: TextView = ui!!.button(activity.getString(label), kind, l)
        b.setTextSize(13.5f)
        b.setSingleLine(true)
        b.setMinHeight(ui.dp(44.0f))
        b.setPadding(ui.dp(6.0f), 0, ui.dp(6.0f), 0)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = ui.dp(6.0f)
        b.setLayoutParams(lp)
        return b
    }

    private fun answer(p: Pending, state: Int, ok: Boolean, label: String, forAnHour: Boolean) {
        if (!settle(p, state)) {
            return
        }
        if (forAnHour) {
            ApprovalCenter.setMode(activity, 1)
        }
        collapse(p, ok, label)
        open.remove(p)
    }

    private fun collapse(p: Pending, ok: Boolean, label: String) {
        val c: LinearLayout? = p.card
        if (c == null) {
            return
        }
        c.removeAllViews()
        c.setBackground(null)
        c.setPadding(0, 0, 0, 0)
        var first: String = (p.description as java.lang.String).split("\n", 2)[0]
        if (first.length > 90) {
            first = first.substring(0, 90) + "…"
        }
        val line: TextView = ui!!.text((if (ok) "✓ " else "✕ ") + label + " · " + first, 13.0f, if (ok) ui.t.ok else ui.t.err, false)
        c.addView(line)
    }

    companion object {
        private val TIMEOUT_MS: Long = TimeUnit.MINUTES.toMillis(3L)
        private const val OPEN: Int = 0
        private const val ALLOWED: Int = 1
        private const val DENIED: Int = 2
        private const val MOVED: Int = 3
    }
}
