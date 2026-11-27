package com.termux.app.agent

import android.R
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import com.agentdroid.agent.ThemeColors

/** Paints any AlertDialog in the app theme (surface, text, accent buttons, tinted inputs). Colours come from [AppTheme].*/
object DialogSkin {

    @JvmStatic
    fun attach(d: AlertDialog) {
        if (d.getWindow() == null) {
            return
        }
        d.getWindow()!!.getDecorView().addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                apply(d)
            }

            override fun onViewDetachedFromWindow(v: View) {
            }
        })
    }

    @JvmStatic
    fun apply(d: AlertDialog) {
        val t: ThemeColors = AppTheme.colors(d.getContext())
        val dp: Float = d.getContext().getResources().getDisplayMetrics().density
        val bg = GradientDrawable()
        bg.setColor(t.s1)
        bg.setCornerRadius(28 * dp)
        bg.setStroke(Math.max(1, Math.round(dp)), t.line)
        if (d.getWindow() != null) {
            d.getWindow()!!.setBackgroundDrawable(bg)
            val root: View = d.getWindow()!!.getDecorView()
            paint(root, t)
            root.post(Runnable { paint(root, t) })
        }
    }

    @JvmStatic
    private fun paint(v: View, t: ThemeColors) {
        if (v is EditText) {
            val e: EditText = v as EditText
            e.setTextColor(t.on)
            e.setHintTextColor(t.on2)
            e.setBackgroundTintList(ColorStateList.valueOf(t.line))
            e.setHighlightColor(t.p and 0xFFFFFF or 0x55000000)
        } else if (v is Button) {
            (v as Button).setTextColor(t.p)
        } else if (v is CheckBox || v is RadioButton) {
            val c: CompoundButton = v as CompoundButton
            c.setTextColor(t.on)
            val st: Array<IntArray> = arrayOf<IntArray>(intArrayOf(R.attr.state_checked), intArrayOf())
            c.setButtonTintList(ColorStateList(st, intArrayOf(t.p, t.on2)))
        } else if (v is TextView) {
            val tv: TextView = v as TextView
            val sp: Float = tv.getTextSize() / tv.getResources().getDisplayMetrics().scaledDensity
            if (tv.getCurrentTextColor() != t.p && tv.getBackground() == null) {
                tv.setTextColor(if (sp <= 13.5f) t.on2 else t.on)
            }
        } else if (v is ListView) {
            val l: ListView = v as ListView
            l.setDivider(ColorDrawable(t.line))
            l.setDividerHeight(1)
        }
        if (v is ViewGroup) {
            val g: ViewGroup = v as ViewGroup
            var i = 0
            while (i < g.getChildCount()) {
                paint(g.getChildAt(i), t)
                i++
            }
        }
    }
}
