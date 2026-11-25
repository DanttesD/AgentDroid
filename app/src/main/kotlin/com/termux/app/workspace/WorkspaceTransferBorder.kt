package com.termux.app.workspace

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock

/** Clockwise paint-only overlay; never requests layout or refreshes parent content.*/
class WorkspaceTransferBorder : Drawable {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline: Path = Path()
    private val segment: Path = Path()
    private val measure: PathMeasure = PathMeasure()
    private var active: Boolean = false
    private var framePending: Boolean = false
    private val frame: Runnable = Runnable {
        framePending = false
        if (active && isVisible()) {
            invalidateSelf()
        }
    }
    private val inset: Float
    private val radius: Float

    constructor(density: Float) : super() {
        inset = density
        radius = 12 * density
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(2 * density)
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setColor(Pal.ACCENT)
    }

    fun active(value: Boolean) {
        if (active == value) {
            return
        }
        active = value
        if (!value) {
            unscheduleSelf(frame)
            framePending = false
        }
        invalidateSelf()
    }

    fun active(): Boolean {
        return active
    }

    protected override fun onBoundsChange(b: Rect) {
        outline.reset()
        outline.addRoundRect(RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), radius, radius, Path.Direction.CW)
        measure.setPath(outline, true)
    }

    override fun draw(c: Canvas) {
        if (!active) {
            return
        }
        val length: Float = measure.getLength()
        if (length <= 0) {
            return
        }
        val start: Float = length * (SystemClock.uptimeMillis() % 2000) / 2000f
        val end: Float = start + length * 0.22f
        segment.reset()
        measure.getSegment(start, Math.min(end, length), segment, true)
        if (end > length) {
            measure.getSegment(0.0f, end - length, segment, true)
        }
        c.drawPath(segment, paint)
        if (isVisible() && !framePending && getCallback() != null) {
            framePending = true
            scheduleSelf(frame, SystemClock.uptimeMillis() + 16)
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) {
            unscheduleSelf(frame)
            framePending = false
        }
        return super.setVisible(visible, restart)
    }

    override fun setAlpha(alpha: Int) {
        paint.setAlpha(alpha)
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.setColorFilter(filter)
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
