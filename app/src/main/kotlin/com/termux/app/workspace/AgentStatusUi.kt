package com.termux.app.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared presentation only. Native catalogs, authorization, limits and policies stay provider-owned.*/
object AgentStatusUi {
    @JvmStatic
    fun tr(c: Context, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(c)!!.equals("ru")) ru else en
    }

    @JvmStatic
    fun chip(c: Context, label: String?, description: String?, click: Runnable): TextView {
        val v = TextView(c)
        v.setText(label)
        v.setTextSize(12.0f)
        v.setTextColor(Pal.ACCENT)
        v.setGravity(Gravity.CENTER)
        v.setMinHeight(WorkspaceDialogs.dp(c, 34.0f))
        v.setMaxLines(2)
        v.setEllipsize(TextUtils.TruncateAt.END)
        v.setPadding(WorkspaceDialogs.dp(c, 12.0f), WorkspaceDialogs.dp(c, 4.0f), WorkspaceDialogs.dp(c, 12.0f), WorkspaceDialogs.dp(c, 4.0f))
        v.setBackground(RippleDrawable(ColorStateList.valueOf(Pal.RIPPLE), WorkspaceDialogs.background(c, false), null))
        v.setContentDescription(description)
        v.setOnClickListener(View.OnClickListener { view -> click.run() })
        return v
    }

    @JvmStatic
    fun attach(parent: LinearLayout?, chip: TextView?) {
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.bottomMargin = WorkspaceDialogs.dp(parent!!.getContext(), 6.0f)
        lp.topMargin = lp.bottomMargin
        parent.addView(chip, lp)
    }

    @JvmStatic
    fun body(c: Context): LinearLayout {
        val body = LinearLayout(c)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(WorkspaceDialogs.dp(c, 12.0f), WorkspaceDialogs.dp(c, 4.0f), WorkspaceDialogs.dp(c, 12.0f), WorkspaceDialogs.dp(c, 12.0f))
        return body
    }

    @JvmStatic
    fun row(body: LinearLayout, label: String, key: String, click: Runnable): TextView {
        val c: Context = body.getContext()
        val v: TextView = WorkspaceDialogs.action(c, label, 0)
        v.setTextSize(13.0f)
        v.setGravity(Gravity.CENTER)
        v.setMinHeight(WorkspaceDialogs.dp(c, 48.0f))
        v.setTag("agent-status-" + key)
        v.setOnClickListener(View.OnClickListener { view -> click.run() })
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = WorkspaceDialogs.dp(c, 8.0f)
        body.addView(v, lp)
        return v
    }

    @JvmStatic
    fun available(row: TextView?, enabled: Boolean) {
        row!!.setEnabled(enabled)
        row.setAlpha(if (enabled) 1f else 0.45f)
    }

    @JvmStatic
    fun level(c: Context, value: String): String {
        when (value) {
            "default" -> {
                return tr(c, "По умолчанию CLI", "CLI default")
            }
            "minimal" -> {
                return tr(c, "Минимальный", "Minimal")
            }
            "low" -> {
                return tr(c, "Низкий", "Low")
            }
            "medium" -> {
                return tr(c, "Средний", "Medium")
            }
            "high" -> {
                return tr(c, "Высокий", "High")
            }
            "xhigh" -> {
                return tr(c, "Очень высокий", "Extra high")
            }
            "max" -> {
                return tr(c, "Максимальный", "Maximum")
            }
            else -> {
                return tr(c, "Нет данных", "Unavailable")
            }
        }
    }

    @JvmStatic
    fun info(body: LinearLayout, label: String): TextView {
        val v = TextView(body.getContext())
        v.setTag("agent-status-info")
        v.setText(label)
        v.setTextSize(12.0f)
        v.setTextColor(Pal.MUTED)
        val pad: Int = WorkspaceDialogs.dp(body.getContext(), 4.0f)
        v.setPadding(pad, pad, pad, pad)
        v.setTextIsSelectable(true)
        body.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    @JvmStatic
    fun scroll(body: LinearLayout): ScrollView {
        val scroll = ScrollView(body.getContext())
        scroll.setFillViewport(true)
        scroll.addView(body)
        return scroll
    }
}
