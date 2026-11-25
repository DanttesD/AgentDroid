package com.termux.app.agent

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.R

/** Bottom navigation shared by the four top-level screens: Chat, Agents, Phone, Environment.*/
object NavBar {
    const val CHAT: Int = 0
    const val AGENTS: Int = 1
    const val PHONE: Int = 2
    const val ENV: Int = 3
    private val ICONS: IntArray = intArrayOf(R.drawable.ic_nav_chat, R.drawable.ic_nav_agents, R.drawable.ic_nav_phone, R.drawable.ic_nav_env)
    private val LABELS: IntArray = intArrayOf(R.string.nav_chat, R.string.nav_agents, R.string.nav_phone, R.string.nav_env)
    private val TARGETS: Array<Class<*>> = arrayOf<Class<*>>(ChatActivity::class.java, AgentsActivity::class.java, PhoneActivity::class.java, EnvActivity::class.java)

    @JvmStatic
    fun build(a: Activity, active: Int): View {
        val ui = Ui(a)
        val bar = LinearLayout(a)
        bar.setOrientation(LinearLayout.HORIZONTAL)
        bar.setBackgroundColor(ui.t.s1)
        bar.setPadding(ui.dp(8.0f), ui.dp(8.0f), ui.dp(8.0f), ui.dp(10.0f))
        for (i in 0 until (4)) {
            val idx: Int = i
            val on: Boolean = i == active
            val item: LinearLayout = ui.column()
            item.setGravity(Gravity.CENTER_HORIZONTAL)
            val pill = FrameLayout(a)
            val ic = ImageView(a)
            ic.setImageResource(ICONS[i])
            ic.setColorFilter(if (on) ui.t.onpc else ui.t.on2)
            pill.addView(ic, FrameLayout.LayoutParams(ui.dp(24.0f), ui.dp(24.0f), Gravity.CENTER))
            pill.setBackground(ui.shape(if (on) ui.t.pc else 0, 16.0f, 0, 0.0f))
            item.addView(pill, LinearLayout.LayoutParams(ui.dp(64.0f), ui.dp(32.0f)))
            val label: TextView = ui.text(a.getString(LABELS[i]), 12.0f, if (on) ui.t.onpc else ui.t.on2, on)
            label.setGravity(Gravity.CENTER)
            item.addView(label, ui.gap(2))
            item.setClickable(true)
            item.setContentDescription(a.getString(LABELS[i]))
            item.setOnClickListener(View.OnClickListener { v -> go(a, idx, active) })
            bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    @JvmStatic
    private fun go(a: Activity, idx: Int, active: Int) {
        if (idx == active) {
            return
        }
        val i = Intent(a, TARGETS[idx])
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        a.startActivity(i)
        a.overridePendingTransition(0, 0)
    }
}
