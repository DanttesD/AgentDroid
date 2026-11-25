package com.termux.app.workspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup

/** Paint-only activity cue. Idle and active have the same height: never resizes the PTY.*/
class WorkspaceActivityLine : View {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix: Matrix = Matrix()
    private var gradient: LinearGradient? = null
    private var working: Boolean = false
    private var framePending: Boolean = false
    private val cadence: FrameCadence = FrameCadence()
    private var animationTimeNanos: Long = 0L
    private var stop: View? = null
    private var routeHost: ViewGroup? = null
    private val route: Route = Route()
    private val lineLocation: IntArray = IntArray(2)
    private val stopLocation: IntArray = IntArray(2)
    private val hostLocation: IntArray = IntArray(2)

    fun stopTarget(target: View?) {
        if (stop === target) {
            return
        }
        stop = target
        clearRoute()
        invalidate()
    }

    private fun clearRoute() {
        if (routeHost != null) {
            routeHost!!.getOverlay().remove(route)
            routeHost = null
        }
    }

    fun routing(): Boolean {
        return routeHost != null
    }

    private fun drawRoute(): Boolean {
        if (stop == null || !stop!!.isEnabled() || !stop!!.isShown() || stop!!.getWindowToken() !== getWindowToken() || !(getParent() is ViewGroup) || getWidth() == 0) {
            clearRoute()
            return false
        }
        val host: ViewGroup = getParent() as ViewGroup
        getLocationInWindow(lineLocation)
        stop!!.getLocationInWindow(stopLocation)
        host.getLocationInWindow(hostLocation)
        val radius: Float = 19.5f * getResources().getDisplayMetrics().density
        val left: Int = lineLocation[0] - hostLocation[0]
        val top: Int = Math.min(lineLocation[1], Math.round(stopLocation[1] + stop!!.getHeight() / 2f - radius)) - hostLocation[1]
        val bottom: Int = Math.max(lineLocation[1] + getHeight(), Math.round(stopLocation[1] + stop!!.getHeight() / 2f + radius)) - hostLocation[1]
        val cx: Float = stopLocation[0] - hostLocation[0] + stop!!.getWidth() / 2f - left
        val cy: Float = stopLocation[1] - hostLocation[1] + stop!!.getHeight() / 2f - top
        val lineY: Float = lineLocation[1] - hostLocation[1] + getHeight() / 2f - top
        route.geometry(left, top, getWidth(), Math.max(1, bottom - top), lineY, cx, cy, radius)
        route.frameTimeNanos = if (animationTimeNanos == 0L) System.nanoTime() else animationTimeNanos
        if (routeHost !== host) {
            clearRoute()
            routeHost = host
            host.getOverlay().add(route)
        }
        return true
    }

    private class Route : Drawable {
        private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val measure: PathMeasure = PathMeasure()
        private val part: Path = Path()
        private var lastLineY: Float = java.lang.Float.NaN
        private var lastCx: Float = 0f
        private var lastCy: Float = 0f
        private var lastRadius: Float = 0f
        @JvmField var frameTimeNanos: Long = 0L

        constructor() : super() {
            paint.setStyle(Paint.Style.STROKE)
            paint.setStrokeWidth(2.0f)
            paint.setStrokeCap(Paint.Cap.ROUND)
        }

        fun geometry(x: Int, y: Int, width: Int, height: Int, lineY: Float, cx: Float, cy: Float, radius: Float) {
            val bounds: Rect = getBounds()
            if (bounds.left == x && bounds.top == y && bounds.width() == width && bounds.height() == height && lastLineY == lineY && lastCx == cx && lastCy == cy && lastRadius == radius) {
                return
            }
            lastLineY = lineY
            lastCx = cx
            lastCy = cy
            lastRadius = radius
            setBounds(x, y, x + width, y + height)
            measure.setPath(routePath(width.toFloat(), lineY, cx, cy, radius), false)
            paint.setStrokeWidth(Math.max(1.5f, radius / 15))
        }

        override fun draw(c: Canvas) {
            val length: Float = measure.getLength()
            if (length <= 0) {
                return
            }
            val head: Float = frameTimeNanos % 1800000000L / 1800000000f * length
            val tail: Float = Math.min(length * 0.3f, 350.0f)
            c.save()
            c.translate(getBounds().left.toFloat(), getBounds().top.toFloat())
            for (i in 0 until (32)) {
                var begin: Float = head - tail + tail * i / 32
                var end: Float = head - tail + tail * (i + 1) / 32
                while (begin < 0) {
                    begin += length
                    end += length
                }
                part.reset()
                measure.getSegment(begin, Math.min(end, length), part, true)
                if (end > length) {
                    measure.getSegment(0.0f, end - length, part, true)
                }
                paint.setColor(Pal.ACCENT)
                paint.setAlpha((i + 1) * 255 / 32)
                paint.setStrokeCap(if (i == 0 || i == 31) Paint.Cap.ROUND else Paint.Cap.BUTT)
                c.drawPath(part, paint)
            }
            c.restore()
        }

        override fun setAlpha(value: Int) {
        }

        override fun setColorFilter(filter: ColorFilter?) {
            paint.setColorFilter(filter)
        }

        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }
    }

    /** Display-frame driven, with an average 60 Hz ceiling on faster panels.*/
    class FrameCadence {
        private var next: Long = 0L

        fun due(time: Long): Boolean {
            if (next == 0L || time - next > PERIOD * 4) {
                next = time + PERIOD
                return true
            }
            if (time + PERIOD / 20 < next) {
                return false
            }
            next += PERIOD
            return true
        }

        fun reset() {
            next = 0L
        }

        companion object {
            const val PERIOD: Long = 16666667L
        }
    }

    private fun canAnimate(): Boolean {
        return working && isAttachedToWindow() && isShown() && getWindowVisibility() == View.VISIBLE
    }

    private val frame: Choreographer.FrameCallback = Choreographer.FrameCallback { time ->
        framePending = false
        if (!canAnimate()) {
            return@FrameCallback
        }
        if (cadence.due(time)) {
            animationTimeNanos = time
            route.frameTimeNanos = time
            invalidate()
            if (routeHost != null) {
                route.invalidateSelf()
            }
        }
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!framePending && canAnimate()) {
            framePending = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun cancelFrame() {
        if (framePending) {
            Choreographer.getInstance().removeFrameCallback(frame)
        }
        framePending = false
        cadence.reset()
        animationTimeNanos = 0L
    }

    constructor(c: Context, a: AttributeSet) : super(c, a) {
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
    }

    fun working(value: Boolean) {
        if (working == value) {
            return
        }
        working = value
        if (!value) {
            cancelFrame()
            clearRoute()
        }
        setVisibility(if (value) View.VISIBLE else View.INVISIBLE)
        invalidate()
    }

    fun working(): Boolean {
        return working
    }

    protected override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(0.0f, 0.0f, Math.max(1.0f, w * 0.45f), 0.0f, intArrayOf(Pal.ACCENT and 0xFFFFFF, Pal.ACCENT, Pal.ACCENT and 0xFFFFFF), null, Shader.TileMode.CLAMP)
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!working || gradient == null) {
            return
        }
        if (drawRoute()) {
            scheduleFrame()
            return
        }
        val width: Float = getWidth().toFloat()
        val phase: Float = (if (animationTimeNanos == 0L) System.nanoTime() else animationTimeNanos) % 1400000000L / 1400000000f
        matrix.setTranslate(-width * 0.45f + width * 1.45f * phase, 0.0f)
        gradient!!.setLocalMatrix(matrix)
        paint.setShader(gradient)
        canvas.drawRect(0.0f, 0.0f, width, getHeight().toFloat(), paint)
        scheduleFrame()
    }

    protected override fun onVisibilityChanged(v: View, visibility: Int) {
        super.onVisibilityChanged(v, visibility)
        if (visibility != View.VISIBLE) {
            cancelFrame()
            clearRoute()
        } else if (working) {
            invalidate()
        }
    }

    protected override fun onDetachedFromWindow() {
        cancelFrame()
        clearRoute()
        super.onDetachedFromWindow()
    }

    protected override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) {
            cancelFrame()
            clearRoute()
        } else if (working) {
            invalidate()
        }
    }

    companion object {
        @JvmStatic
        fun routePath(width: Float, lineY: Float, cx: Float, cy: Float, radius: Float): Path {
            val p = Path()
            p.moveTo(0.0f, lineY)
            p.lineTo(Math.max(0.0f, cx - radius - 8), lineY)
            p.quadTo(cx, lineY, cx, cy + radius)
            val circle = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            p.arcTo(circle, 90.0f, (-180).toFloat(), false)
            p.arcTo(circle, (-90).toFloat(), (-180).toFloat(), false)
            p.quadTo(cx, lineY, Math.min(width, cx + radius + 8), lineY)
            p.lineTo(width, lineY)
            return p
        }
    }
}
