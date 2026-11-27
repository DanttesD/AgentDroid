package com.termux.app.workspace

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.TextView
import com.termux.R
import com.termux.app.agent.DialogSkin
import java.util.function.IntPredicate

/** Shared appearance for workspace dialogs; no shell execution or persistent state.*/
object WorkspaceDialogs {
    class Builder : AlertDialog.Builder {
        private var blockOutside: Boolean = false

        constructor(c: Context) : super(c, R.style.WorkspaceDialog)

        fun blockOutsideDismiss(): Builder {
            blockOutside = true
            return this
        }

        override fun create(): AlertDialog {
            val dialog: AlertDialog = super.create()
            if (blockOutside) {
                dialog.setCanceledOnTouchOutside(false)
            }
            DialogSkin.attach(dialog)
            return dialog
        }

        override fun setItems(items: Array<CharSequence?>, listener: DialogInterface.OnClickListener): AlertDialog.Builder {
            return super.setAdapter(Rows(getContext(), items, -1), listener)
        }

        override fun setSingleChoiceItems(items: Array<CharSequence?>, selected: Int, listener: DialogInterface.OnClickListener): AlertDialog.Builder {
            return super.setSingleChoiceItems(Rows(getContext(), items, selected), selected, listener)
        }
    }

    private class Rows : ArrayAdapter<CharSequence> {
        private val selected: Int
        private val enabled: IntPredicate

        constructor(c: Context, items: Array<out CharSequence?>, selected: Int) : this(c, items, selected, IntPredicate { position -> true })

        constructor(c: Context, items: Array<out CharSequence?>, selected: Int, enabled: IntPredicate) : super(c, 0, items) {
            this.selected = selected
            this.enabled = enabled
        }

        override fun isEnabled(position: Int): Boolean {
            return enabled.test(position)
        }

        override fun areAllItemsEnabled(): Boolean {
            var i = 0
            while (i < getCount()) {
                if (!isEnabled(i)) {
                    return false
                }
                i++
            }
            return true
        }

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val c: Context = getContext()
            val outer = FrameLayout(c)
            outer.setPadding(dp(c, 16.0f), dp(c, 4.0f), dp(c, 16.0f), dp(c, 4.0f))
            val label: String = getItem(position)!!.toString()
            val row: TextView = action(c, label, if (label.equals(WorkspaceText.tr("Удалить"))) R.drawable.workspace_delete else if (label.equals(WorkspaceText.tr("Редактировать"))) R.drawable.workspace_edit else if (label.contains(WorkspaceText.tr("избранное")!!) || label.contains(WorkspaceText.tr("избранного")!!)) R.drawable.workspace_star else 0)
            row.setBackground(background(c, position == selected))
            row.setDuplicateParentStateEnabled(true)
            outer.addView(row, FrameLayout.LayoutParams(-1, -2))
            outer.setEnabled(isEnabled(position))
            outer.setAlpha(if (isEnabled(position)) 1f else 0.4f)
            return outer
        }
    }

    @JvmStatic
    fun dp(c: Context, n: Float): Int {
        return Math.round(c.getResources().getDisplayMetrics().density * n)
    }

    @JvmStatic
    fun builder(c: Context): Builder {
        return Builder(c)
    }

    @JvmStatic
    fun choiceAdapter(c: Context, items: Array<out CharSequence?>, selected: Int, enabled: IntPredicate): ListAdapter {
        return Rows(c, items, selected, enabled)
    }

    @JvmStatic
    fun action(c: Context, label: String?, icon: Int): TextView {
        val row = TextView(c)
        row.setText(label)
        row.setTextSize(15.0f)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setMinHeight(dp(c, 54.0f))
        row.setPadding(dp(c, 16.0f), dp(c, 12.0f), dp(c, 16.0f), dp(c, 12.0f))
        val color: Int = if (label!!.equals(WorkspaceText.tr("Удалить"))) Pal.DANGER else Pal.TEXT
        row.setTextColor(color)
        if (icon != 0) {
            val image: Drawable = c.getDrawable(icon)!!.mutate()
            image.setTint(color)
            image.setBounds(0, 0, dp(c, 22.0f), dp(c, 22.0f))
            row.setCompoundDrawables(image, null, null, null)
            row.setCompoundDrawablePadding(dp(c, 14.0f))
        }
        row.setBackground(background(c, false))
        return row
    }

    @JvmStatic
    fun background(c: Context, selected: Boolean): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), panel(c, true))
        states.addState(intArrayOf(android.R.attr.state_focused), panel(c, true))
        states.addState(intArrayOf(), panel(c, selected))
        return states
    }

    @JvmStatic
    private fun panel(c: Context, highlight: Boolean): Drawable {
        val panel = GradientDrawable()
        panel.setColor(if (highlight) Pal.TINT else Pal.PANEL)
        panel.setCornerRadius(dp(c, 12.0f).toFloat())
        panel.setStroke(dp(c, 1.0f), if (highlight) Pal.ACCENT else Pal.BORDER)
        if (!highlight) {
            return panel
        }
        val marker = GradientDrawable()
        marker.setColor(Pal.ACCENT)
        marker.setCornerRadius(dp(c, 2.0f).toFloat())
        val layers = LayerDrawable(arrayOf<Drawable>(panel, marker))
        layers.setLayerSize(1, dp(c, 4.0f), dp(c, 32.0f))
        layers.setLayerGravity(1, Gravity.LEFT or Gravity.CENTER_VERTICAL)
        return layers
    }

    @JvmStatic
    fun commandRow(c: Context, command: WorkspaceStore.Command): LinearLayout {
        val row = LinearLayout(c)
        row.setOrientation(LinearLayout.HORIZONTAL)
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.setPadding(dp(c, 16.0f), dp(c, 14.0f), dp(c, 16.0f), dp(c, 14.0f))
        row.setBackground(background(c, false))
        val labels = LinearLayout(c)
        labels.setOrientation(LinearLayout.VERTICAL)
        val title = TextView(c)
        title.setText(command.name)
        title.setTextSize(16.0f)
        title.setTextColor(Pal.TEXT)
        title.setTypeface(null, Typeface.BOLD)
        labels.addView(title)
        val preview = TextView(c)
        preview.setText(command.command)
        preview.setTextSize(12.0f)
        preview.setTextColor(Pal.MUTED)
        preview.setTypeface(Typeface.MONOSPACE)
        preview.setMaxLines(2)
        preview.setEllipsize(TextUtils.TruncateAt.END)
        preview.setPadding(0, dp(c, 6.0f), 0, dp(c, 6.0f))
        labels.addView(preview)
        val category = TextView(c)
        category.setText(command.category)
        category.setTextSize(11.0f)
        category.setTextColor(Pal.MUTED)
        labels.addView(category)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1.0f))
        if (command.favorite) {
            val star = TextView(c)
            star.setText("★")
            star.setTextSize(24.0f)
            star.setTextColor(Pal.ACCENT)
            star.setPadding(dp(c, 12.0f), 0, 0, 0)
            star.setContentDescription(WorkspaceText.tr("Избранная команда"))
            row.addView(star)
        }
        return row
    }
}
