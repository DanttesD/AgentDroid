package com.termux.app.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.widget.TextView

/** Icon-only buttons center the drawable independently of text baselines/padding.*/
// Deliberately uses the platform widget: icon drawing/tinting is owned here and
// dimensions come from our explicit layout, without AppCompat text/drawable styling.
@SuppressLint("AppCompatCustomView")
class WorkspaceIconButton : TextView {
    private var icon: Drawable? = null
    private var iconSize: Int = 0
    private var badge: Boolean = false
    private val badgePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setBadge(value: Boolean) {
        badge = value
        invalidate()
    }

    constructor(context: Context) : super(context)

    fun setIcon(value: Drawable?, size: Int) {
        icon = value
        iconSize = size
        icon!!.setTint(getCurrentTextColor())
        setText("")
        setPadding(0, 0, 0, 0)
        invalidate()
    }

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        if (icon != null) {
            icon!!.setTint(color)
            invalidate()
        }
    }

    protected override fun onDraw(canvas: Canvas) {
        if (icon == null) {
            super.onDraw(canvas)
            return
        }
        val size: Int = Math.min(iconSize, Math.min(getWidth(), getHeight()))
        val x: Int = (getWidth() - size) / 2
        val y: Int = (getHeight() - size) / 2
        icon!!.setBounds(x, y, x + size, y + size)
        icon!!.draw(canvas)
        if (badge) {
            badgePaint.setColor(Pal.ACCENT)
            val d: Float = getResources().getDisplayMetrics().density
            canvas.drawCircle(getWidth() - 10 * d, 10 * d, 3 * d, badgePaint)
        }
    }
}
