package com.termux.app.agent

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.agentdroid.agent.ThemeColors
import com.termux.R

/** Small view factory in the Obsidian look: cards, buttons, chips, switch rows. Everything takes its colours from [ThemeColors].*/
class Ui {
    enum class Kind {
        FILLED,
        TONAL,
        OUTLINE,
        DANGER
    }

    @JvmField val ctx: Context
    @JvmField val t: ThemeColors
    private val density: Float

    constructor(c: Context) {
        ctx = c
        t = AppTheme.colors(c)
        density = c.getResources().getDisplayMetrics().density
    }

    fun dp(v: Float): Int {
        return Math.round(v * density)
    }

    fun shape(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setCornerRadius(dp(radiusDp).toFloat())
        if (stroke != 0) {
            g.setStroke(Math.max(1, dp(strokeDp)), stroke)
        }
        return g
    }

    fun icon(res: Int, sizeDp: Int, color: Int): ImageView {
        val v = ImageView(ctx)
        v.setImageResource(res)
        v.setColorFilter(color)
        v.setScaleType(ImageView.ScaleType.FIT_CENTER)
        val p: Int = dp((48 - sizeDp) / 2f)
        v.setPadding(0, 0, 0, 0)
        v.setLayoutParams(LinearLayout.LayoutParams(dp(sizeDp.toFloat()), dp(sizeDp.toFloat())))
        return v
    }

    /** The header of a sub-screen: a back arrow and the title.*/
    fun backHeader(title: CharSequence, back: View.OnClickListener): LinearLayout {
        val head = LinearLayout(ctx)
        head.setGravity(Gravity.CENTER_VERTICAL)
        head.setPadding(dp(8.0f), dp(24.0f), dp(16.0f), dp(4.0f))
        head.addView(iconButton(R.drawable.ic_back, t.on, ctx.getString(R.string.settings_back), back))
        head.addView(text(title, 24.0f, t.on, true))
        return head
    }

    /** A tappable round icon button 48dp wide.*/
    fun iconButton(res: Int, color: Int, desc: CharSequence, l: View.OnClickListener): FrameLayout {
        val f = FrameLayout(ctx)
        val i = ImageView(ctx)
        i.setImageResource(res)
        i.setColorFilter(color)
        f.addView(i, FrameLayout.LayoutParams(dp(24.0f), dp(24.0f), Gravity.CENTER))
        f.setContentDescription(desc)
        f.setClickable(true)
        f.setOnClickListener(l)
        f.setLayoutParams(LinearLayout.LayoutParams(dp(48.0f), dp(48.0f)))
        return f
    }

    fun bubbleShape(fill: Int, tl: Float, tr: Float, br: Float, bl: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.setCornerRadii(floatArrayOf(dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(), dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat()))
        return g
    }

    fun text(s: CharSequence, sp: Float, color: Int, bold: Boolean): TextView {
        val v = TextView(ctx)
        v.setText(s)
        v.setTextSize(sp)
        v.setTextColor(color)
        if (bold) {
            v.setTypeface(Typeface.DEFAULT_BOLD)
        }
        return v
    }

    fun title(s: CharSequence): TextView {
        return text(s, 28.0f, t.on, true)
    }

    fun section(s: CharSequence): TextView {
        val v: TextView = text((s.toString() as java.lang.String).toUpperCase(), 12.0f, t.on2, true)
        v.setLetterSpacing(0.08f)
        v.setPadding(dp(4.0f), dp(8.0f), 0, dp(4.0f))
        return v
    }

    fun column(): LinearLayout {
        val l = LinearLayout(ctx)
        l.setOrientation(LinearLayout.VERTICAL)
        return l
    }

    fun card(): LinearLayout {
        val l: LinearLayout = column()
        l.setBackground(shape(t.s1, 22.0f, t.line, 1.0f))
        l.setPadding(dp(16.0f), dp(14.0f), dp(16.0f), dp(14.0f))
        return l
    }

    fun gap(topDp: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.topMargin = dp(topDp.toFloat())
        return p
    }

    fun button(label: CharSequence, kind: Kind, l: View.OnClickListener?): TextView {
        var fill: Int
        var fg: Int
        var stroke = 0
        when (kind) {
            Kind.FILLED -> {
                fill = t.p
                fg = t.onp
            }
            Kind.DANGER -> {
                fill = t.err2
                fg = t.err
            }
            Kind.OUTLINE -> {
                fill = 0
                fg = t.on
                stroke = t.line
            }
            else -> {
                fill = t.pc
                fg = t.onpc
            }
        }
        val b: TextView = text(label, 15.0f, fg, true)
        b.setGravity(Gravity.CENTER)
        b.setAllCaps(false)
        b.setMinHeight(dp(48.0f))
        b.setPadding(dp(22.0f), 0, dp(22.0f), 0)
        b.setBackground(shape(fill, 24.0f, stroke, 1.0f))
        b.setClickable(true)
        b.setOnClickListener(l)
        return b
    }

    fun chip(label: CharSequence, selected: Boolean, l: View.OnClickListener): TextView {
        val c: TextView = text(label, 13.5f, if (selected) t.onpc else t.on, selected)
        c.setGravity(Gravity.CENTER)
        c.setMinHeight(dp(40.0f))
        c.setPadding(dp(16.0f), 0, dp(16.0f), 0)
        c.setBackground(shape(if (selected) t.pc else 0, 20.0f, if (selected) 0 else t.line, 1.0f))
        c.setClickable(true)
        c.setOnClickListener(l)
        return c
    }

    fun statusChip(label: CharSequence, on: Boolean): TextView {
        val c: TextView = text(label, 12.5f, if (on) t.onpc else t.on2, on)
        c.setGravity(Gravity.CENTER_VERTICAL)
        c.setPadding(dp(12.0f), 0, dp(12.0f), 0)
        c.setBackground(shape(if (on) t.pc else t.s2, 15.0f, 0, 0.0f))
        return c
    }

    fun styleChip(c: TextView?, label: CharSequence, on: Boolean) {
        c!!.setText((if (on) "● " else "○ ") + label)
        c.setTextColor(if (on) t.onpc else t.on2)
        c.setTypeface(if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        c.setBackground(shape(if (on) t.pc else t.s2, 15.0f, 0, 0.0f))
    }

    fun toggle(checked: Boolean, l: OnChecked?): Switch {
        val sw = Switch(ctx)
        sw.setChecked(checked)
        val st: Array<IntArray> = arrayOf<IntArray>(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.setThumbTintList(ColorStateList(st, intArrayOf(t.onp, t.on2)))
        sw.setTrackTintList(ColorStateList(st, intArrayOf(t.p, t.s3)))
        if (l != null) {
            sw.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b, c -> l.on(c) })
        }
        return sw
    }

    /** Row with a rounded icon box, title, subtitle and a trailing view (switch, chevron or nothing).*/
    fun iconRow(icon: Int, name: CharSequence, sub: CharSequence?, trailing: View?, click: View.OnClickListener?): LinearLayout {
        val r = LinearLayout(ctx)
        r.setGravity(Gravity.CENTER_VERTICAL)
        r.setMinimumHeight(dp(60.0f))
        r.setPadding(0, dp(6.0f), 0, dp(6.0f))
        val box = FrameLayout(ctx)
        box.setBackground(shape(t.s3, 12.0f, 0, 0.0f))
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(t.on)
        box.addView(iv, FrameLayout.LayoutParams(dp(20.0f), dp(20.0f), Gravity.CENTER))
        val bl = LinearLayout.LayoutParams(dp(36.0f), dp(36.0f))
        bl.rightMargin = dp(12.0f)
        r.addView(box, bl)
        val texts: LinearLayout = column()
        texts.addView(text(name, 15.0f, t.on, true))
        if (sub != null && sub.length > 0) {
            texts.addView(text(sub, 12.5f, t.on2, false))
        }
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            r.addView(trailing)
        }
        if (click != null) {
            r.setClickable(true)
            r.setOnClickListener(click)
        }
        return r
    }

    fun chevron(): View {
        return icon(R.drawable.ic_next, 20, t.on2)
    }

    /** Square-ish tile for the 2-column grids.*/
    fun tile(icon: Int, name: CharSequence, sub: CharSequence, hi: Boolean, click: View.OnClickListener): LinearLayout {
        val c: LinearLayout = column()
        c.setBackground(shape(t.s1, 22.0f, t.line, 1.0f))
        c.setPadding(dp(14.0f), dp(14.0f), dp(14.0f), dp(14.0f))
        val box = FrameLayout(ctx)
        box.setBackground(shape(if (hi) t.pc else t.s3, 14.0f, 0, 0.0f))
        val iv = ImageView(ctx)
        iv.setImageResource(icon)
        iv.setColorFilter(if (hi) t.onpc else t.on)
        box.addView(iv, FrameLayout.LayoutParams(dp(22.0f), dp(22.0f), Gravity.CENTER))
        c.addView(box, LinearLayout.LayoutParams(dp(40.0f), dp(40.0f)))
        c.addView(text(name, 15.0f, t.on, true), gap(10))
        c.addView(text(sub, 12.0f, t.on2, false))
        c.setClickable(true)
        c.setOnClickListener(click)
        return c
    }

    fun interface OnChecked {
        fun on(checked: Boolean)
    }

    /** Title, optional explanation and a switch on the right.*/
    fun switchRow(title: CharSequence, sub: CharSequence?, checked: Boolean, l: OnChecked): LinearLayout {
        val row = LinearLayout(ctx)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setMinimumHeight(dp(56.0f))
        val texts: LinearLayout = column()
        texts.addView(text(title, 15.0f, t.on, true))
        if (sub != null && sub.length > 0) {
            texts.addView(text(sub, 12.5f, t.on2, false))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx)
        sw.setChecked(checked)
        val st: Array<IntArray> = arrayOf<IntArray>(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.setThumbTintList(ColorStateList(st, intArrayOf(t.onp, t.on2)))
        sw.setTrackTintList(ColorStateList(st, intArrayOf(t.p, t.s3)))
        sw.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b, c -> l.on(c) })
        row.addView(sw)
        return row
    }

    fun divider(): View {
        val v = View(ctx)
        v.setBackgroundColor(t.line)
        v.setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        return v
    }
}
