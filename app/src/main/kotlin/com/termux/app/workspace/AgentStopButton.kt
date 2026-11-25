package com.termux.app.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.Button

/** A scoped request interrupt control. Its idle state is visibly crossed out.*/
class AgentStopButton : View {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hand: Path = Path()

    constructor(c: Context) : super(c) {
        setClickable(true)
        setFocusable(true)
        setEnabled(false)
        hand.moveTo(7.0f, 14.0f)
        hand.lineTo(7.0f, 6.0f)
        hand.quadTo(7.0f, 4.0f, 8.5f, 4.0f)
        hand.quadTo(10.0f, 4.0f, 10.0f, 6.0f)
        hand.lineTo(10.0f, 11.0f)
        hand.lineTo(10.0f, 3.0f)
        hand.quadTo(10.0f, 1.0f, 11.5f, 1.0f)
        hand.quadTo(13.0f, 1.0f, 13.0f, 3.0f)
        hand.lineTo(13.0f, 11.0f)
        hand.lineTo(13.0f, 4.0f)
        hand.quadTo(13.0f, 2.0f, 14.5f, 2.0f)
        hand.quadTo(16.0f, 2.0f, 16.0f, 4.0f)
        hand.lineTo(16.0f, 12.0f)
        hand.lineTo(16.0f, 7.0f)
        hand.quadTo(16.0f, 5.0f, 17.5f, 5.0f)
        hand.quadTo(19.0f, 5.0f, 19.0f, 7.0f)
        hand.lineTo(19.0f, 15.0f)
        hand.cubicTo(19.0f, 21.0f, 16.0f, 24.0f, 11.0f, 24.0f)
        hand.cubicTo(7.0f, 24.0f, 5.0f, 21.0f, 3.0f, 18.0f)
        hand.lineTo(1.0f, 14.0f)
        hand.quadTo(0.0f, 12.0f, 1.5f, 11.5f)
        hand.quadTo(3.0f, 11.0f, 4.0f, 13.0f)
        hand.lineTo(7.0f, 17.0f)
        hand.close()
    }

    override fun getAccessibilityClassName(): CharSequence {
        return Button::class.java.getName()
    }

    protected override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d: Float = getResources().getDisplayMetrics().density
        val cx: Float = getWidth() / 2f
        val cy: Float = getHeight() / 2f
        val r: Float = Math.min(Math.min(getWidth(), getHeight()) / 2f - 3 * d, 18 * d)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(if (isEnabled()) 0xffbc3e4d.toInt() else 0xff303a40.toInt())
        canvas.drawCircle(cx, cy, r, paint)
        canvas.save()
        val scale: Float = r / 16
        canvas.translate(cx - 10 * scale, cy - 12 * scale)
        canvas.scale(scale, scale)
        paint.setColor(if (isEnabled()) Color.WHITE else 0xff8c9a9f.toInt())
        canvas.drawPath(hand, paint)
        canvas.restore()
        if (!isEnabled()) {
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeCap(Paint.Cap.ROUND)
            paint.setStrokeWidth(3 * d)
            paint.setColor(0xff0b1217.toInt())
            canvas.drawLine(cx - r * 0.72f, cy + r * 0.72f, cx + r * 0.72f, cy - r * 0.72f, paint)
            paint.setStrokeWidth(1.5f * d)
            paint.setColor(0xffa3afb4.toInt())
            canvas.drawLine(cx - r * 0.72f, cy + r * 0.72f, cx + r * 0.72f, cy - r * 0.72f, paint)
        }
    }
}
