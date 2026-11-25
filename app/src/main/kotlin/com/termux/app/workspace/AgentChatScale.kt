package com.termux.app.workspace

import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.termux.R
import java.util.function.BooleanSupplier

/** Local presentation only. Layout occupies its scaled size; no GPU View transform.*/
object AgentChatScale {
    private class Snapshot {
        @JvmField val left: Int
        @JvmField val top: Int
        @JvmField val right: Int
        @JvmField val bottom: Int
        @JvmField val minWidth: Int
        @JvmField val minHeight: Int
        @JvmField val width: Int
        @JvmField val height: Int
        @JvmField val ml: Int
        @JvmField val mt: Int
        @JvmField val mr: Int
        @JvmField val mb: Int
        @JvmField val textMinWidth: Int
        @JvmField val textMaxWidth: Int
        @JvmField val textMinHeight: Int
        @JvmField val textMaxHeight: Int
        @JvmField val textSize: Float
        @JvmField var applied: Int = 100

        constructor(v: View?) {
            left = v!!.getPaddingLeft()
            top = v.getPaddingTop()
            right = v.getPaddingRight()
            bottom = v.getPaddingBottom()
            minWidth = v.getMinimumWidth()
            minHeight = v.getMinimumHeight()
            val lp: ViewGroup.LayoutParams? = v.getLayoutParams()
            width = if (lp == null) -1 else lp.width
            height = if (lp == null) -1 else lp.height
            val margins: ViewGroup.MarginLayoutParams? = if (lp is ViewGroup.MarginLayoutParams) lp as ViewGroup.MarginLayoutParams else null
            ml = if (margins == null) 0 else margins.leftMargin
            mt = if (margins == null) 0 else margins.topMargin
            mr = if (margins == null) 0 else margins.rightMargin
            mb = if (margins == null) 0 else margins.bottomMargin
            val t: TextView? = if (v is TextView) v as TextView else null
            textSize = if (t == null) 0.0f else t.getTextSize()
            textMinWidth = if (t == null) 0 else t.getMinWidth()
            textMaxWidth = if (t == null) 0 else t.getMaxWidth()
            textMinHeight = if (t == null) 0 else t.getMinHeight()
            textMaxHeight = if (t == null) 0 else t.getMaxHeight()
        }

        fun apply(v: View?, percent: Int) {
            if (applied == percent) {
                return
            }
            applied = percent
            val factor: Float = percent / 100f
            v!!.setPadding(size(left, factor), size(top, factor), size(right, factor), size(bottom, factor))
            v.setMinimumWidth(size(minWidth, factor))
            v.setMinimumHeight(size(minHeight, factor))
            val lp: ViewGroup.LayoutParams? = v.getLayoutParams()
            if (lp != null) {
                var changed = false
                if (width > 0 && lp.width > 0) {
                    lp.width = size(width, factor)
                    changed = true
                }
                if (height > 0 && lp.height > 0) {
                    lp.height = size(height, factor)
                    changed = true
                }
                if (lp is ViewGroup.MarginLayoutParams) {
                    val m: ViewGroup.MarginLayoutParams = lp as ViewGroup.MarginLayoutParams
                    m.setMargins(size(ml, factor), size(mt, factor), size(mr, factor), size(mb, factor))
                    changed = true
                }
                if (changed) {
                    v.setLayoutParams(lp)
                }
            }
            if (v is TextView) {
                val t: TextView = v as TextView
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * factor)
                if (textMinWidth >= 0) {
                    t.setMinWidth(size(textMinWidth, factor))
                }
                if (textMaxWidth >= 0) {
                    t.setMaxWidth(size(textMaxWidth, factor))
                }
                if (textMinHeight >= 0) {
                    t.setMinHeight(size(textMinHeight, factor))
                }
                if (textMaxHeight >= 0) {
                    t.setMaxHeight(size(textMaxHeight, factor))
                }
            }
        }
    }

    @JvmStatic
    fun clamp(percent: Int): Int {
        return Math.max(50, Math.min(150, percent))
    }

    @JvmStatic
    private fun size(value: Int, factor: Float): Int {
        return if (value > 0 && value < 1000000) Math.max(1, Math.round(value * factor)) else value
    }

    @JvmStatic
    fun apply(v: View?, percent: Int) {
        var snapshot: Snapshot? = v!!.getTag(R.id.agent_chat_scale_snapshot) as Snapshot
        if (snapshot == null) {
            snapshot = Snapshot(v)
            v.setTag(R.id.agent_chat_scale_snapshot, snapshot)
        }
        snapshot.apply(v, clamp(percent))
        if (v is ViewGroup) {
            val group: ViewGroup = v as ViewGroup
            var i = 0
            while (i < group.getChildCount()) {
                apply(group.getChildAt(i), percent)
                i++
            }
        }
    }

    @JvmStatic
    fun prepare(v: View?) {
        if (v == null) {
            return
        }
        apply(v, 100)
        forget(v)
    }

    @JvmStatic
    private fun forget(v: View) {
        v.setTag(R.id.agent_chat_scale_snapshot, null)
        if (v is ViewGroup) {
            val group: ViewGroup = v as ViewGroup
            var i = 0
            while (i < group.getChildCount()) {
                forget(group.getChildAt(i))
                i++
            }
        }
    }

    @JvmStatic
    fun controls(body: LinearLayout, profile: WorkspaceStore.Session, owns: BooleanSupplier, preview: Runnable, save: Runnable) {
        val title: TextView = AgentStatusUi.info(body, "")
        title.setTag("agent-chat-scale-label")
        val slider = SeekBar(body.getContext())
        slider.setTag("agent-chat-scale")
        slider.setContentDescription(AgentStatusUi.tr(body.getContext(), "Масштаб чата", "Chat scale"))
        slider.setMax(100)
        slider.setProgress(clamp(profile.chatScale) - 50)
        body.addView(slider, LinearLayout.LayoutParams(-1, -2))
        val label = Runnable {
            title.setText(AgentStatusUi.tr(body.getContext(), "Масштаб чата: ", "Chat scale: ") + clamp(profile.chatScale) + "%")
        }
        label.run()
        val handler = Handler(Looper.getMainLooper())
        val dirty: BooleanArray = booleanArrayOf(false)
        val persist = Runnable {
            if (dirty[0]) {
                dirty[0] = false
                save.run()
            }
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                if (!owns.getAsBoolean()) {
                    return
                }
                val next: Int = clamp(value + 50)
                if (profile.chatScale == next) {
                    return
                }
                profile.chatScale = next
                label.run()
                preview.run()
                dirty[0] = true
                handler.removeCallbacks(persist)
                handler.postDelayed(persist, 250L)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                handler.removeCallbacks(persist)
                persist.run()
            }
        })
        slider.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
            }

            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(persist)
                persist.run()
            }
        })
        val reset: TextView = AgentStatusUi.row(body, "100%", "chat-scale-reset", Runnable {
            if (owns.getAsBoolean()) {
                slider.setProgress(50)
                handler.removeCallbacks(persist)
                persist.run()
            }
        })
        reset.setContentDescription(AgentStatusUi.tr(body.getContext(), "Сбросить масштаб чата до 100%", "Reset chat scale to 100%"))
    }
}
