package com.termux.app.workspace

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.termux.x11.X11ExtraKeys

/** A small, non-focusable sibling window above the embedded X11 surface.*/
class DesktopExtraKeys {
    private val activity: Activity
    private val changed: Runnable
    private var dialog: Dialog? = null
    private var anchor: View? = null
    private var release: Runnable? = null
    private var placed: Boolean = false
    private var panelX: Int = 0
    private var panelY: Int = 0
    private var panelWidth: Int = 0
    private var panelHeight: Int = 0
    private val attachment: View.OnAttachStateChangeListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
        }

        override fun onViewDetachedFromWindow(v: View) {
            close()
        }
    }
    private val layout: ViewTreeObserver.OnPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        position()
        return@OnPreDrawListener true
    }

    constructor(activity: Activity, changed: Runnable) {
        this.activity = activity
        this.changed = changed
    }

    fun showing(): Boolean {
        return dialog != null
    }

    fun show(target: View?, sink: X11ExtraKeys.Sink, releaseKeys: Runnable) {
        close()
        if (activity.isFinishing() || activity.isDestroyed()) {
            return
        }
        anchor = target
        release = releaseKeys
        placed = false
        val body = LinearLayout(activity)
        body.setPadding(dp(PADDING), dp(PADDING), dp(PADDING), dp(PADDING))
        body.setBackground(WorkspaceDialogs.background(activity, false))
        addHandle(body, true)
        val first = KeyGrid()
        body.addView(first, LinearLayout.LayoutParams(0, -1, 1.0f))
        key(first, "Esc", KeyEvent.KEYCODE_ESCAPE, false, sink)
        key(first, "Tab", KeyEvent.KEYCODE_TAB, false, sink)
        key(first, "Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, true, sink)
        key(first, "Alt", KeyEvent.KEYCODE_ALT_LEFT, true, sink)
        key(first, "Shift", KeyEvent.KEYCODE_SHIFT_LEFT, true, sink)
        val hide: TextView = button(first, "×")
        hide.setContentDescription(tr("Скрыть дополнительные клавиши", "Hide additional keys"))
        hide.setOnClickListener(View.OnClickListener { v -> close() })
        val second: KeyGrid = first
        key(second, "Home", KeyEvent.KEYCODE_MOVE_HOME, false, sink)
        key(second, "PgUp", KeyEvent.KEYCODE_PAGE_UP, false, sink)
        key(second, "↑", KeyEvent.KEYCODE_DPAD_UP, false, sink)
        key(second, "PgDn", KeyEvent.KEYCODE_PAGE_DOWN, false, sink)
        key(second, "End", KeyEvent.KEYCODE_MOVE_END, false, sink)
        val third: KeyGrid = first
        key(third, "←", KeyEvent.KEYCODE_DPAD_LEFT, false, sink)
        key(third, "↓", KeyEvent.KEYCODE_DPAD_DOWN, false, sink)
        key(third, "→", KeyEvent.KEYCODE_DPAD_RIGHT, false, sink)
        key(third, "Del", KeyEvent.KEYCODE_FORWARD_DEL, false, sink)
        key(third, "Enter", KeyEvent.KEYCODE_ENTER, false, sink)
        addHandle(body, false)
        dialog = Dialog(activity)
        dialog!!.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog!!.setContentView(body)
        dialog!!.setCancelable(false)
        dialog!!.setCanceledOnTouchOutside(false)
        val window: Window? = dialog!!.getWindow()
        window!!.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.getDecorView().setSystemUiVisibility(target!!.getWindowSystemUiVisibility())
        val attributes: WindowManager.LayoutParams = window.getAttributes()
        attributes.gravity = Gravity.TOP or Gravity.LEFT
        window.setAttributes(attributes)
        dialog!!.show()
        anchor!!.addOnAttachStateChangeListener(attachment)
        anchor!!.getViewTreeObserver().addOnPreDrawListener(layout)
        position()
        changed.run()
    }

    private fun button(row: KeyGrid, label: String): TextView {
        val button: TextView = KeyButton(activity)
        button.setText(label)
        button.setTextSize(12.0f)
        button.setSingleLine(true)
        button.setIncludeFontPadding(false)
        button.setTextColor(Pal.TEXT)
        button.setGravity(Gravity.CENTER)
        button.setFocusable(false)
        button.setContentDescription(label)
        button.setBackground(WorkspaceDialogs.background(activity, false))
        row.addView(button)
        return button
    }

    private fun key(row: KeyGrid, label: String, code: Int, modifier: Boolean, sink: X11ExtraKeys.Sink) {
        val button: TextView = button(row, label)
        if (modifier) {
            button.setOnClickListener(View.OnClickListener { v ->
                val down: Boolean = !button.isSelected()
                sink.key(code, down)
                button.setSelected(down)
                button.setBackground(WorkspaceDialogs.background(activity, down))
            })
            return
        }
        (button as KeyButton).physical(code, sink)
    }

    private fun addHandle(body: LinearLayout, resize: Boolean) {
        val p = LinearLayout.LayoutParams(dp(HANDLE_WIDTH), dp(32))
        p.gravity = Gravity.CENTER_VERTICAL
        body.addView(Handle(resize), p)
    }

    /** Reflow existing key views without replacing them or losing held modifiers.*/
    private inner class KeyGrid : ViewGroup {
        private var columns: Int = 6

        constructor() : super(activity)

        protected override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width: Int = View.MeasureSpec.getSize(widthSpec)
            val height: Int = View.MeasureSpec.getSize(heightSpec)
            val count: Int = getChildCount()
            var best: Double = java.lang.Double.MAX_VALUE
            for (c in 1..count) {
                val rows: Int = (count + c - 1) / c
                val w: Double = width.toDouble() / c
                val h: Double = height.toDouble() / rows
                var cost: Double = Math.abs(Math.log(Math.max(0.01, w) / Math.max(0.01, h) / 1.8)) + 0.15 * (c * rows - count) / count
                if (w < dp(KEY_WIDTH)) {
                    cost += 10 * (dp(KEY_WIDTH) - w) / dp(KEY_WIDTH)
                }
                if (h < dp(KEY_HEIGHT)) {
                    cost += 10 * (dp(KEY_HEIGHT) - h) / dp(KEY_HEIGHT)
                }
                if (cost < best) {
                    best = cost
                    columns = c
                }
            }
            val rows: Int = (count + columns - 1) / columns
            val gap: Int = dp(1)
            for (i in 0 until (count)) {
                getChildAt(i).measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width / columns - 2 * gap), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Math.max(1, height / rows - 2 * gap), View.MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width, height)
        }

        protected override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val rows: Int = (getChildCount() + columns - 1) / columns
            val gap: Int = dp(1)
            var i = 0
            while (i < getChildCount()) {
                val x: Int = i % columns * getWidth() / columns + gap
                val y: Int = i / columns * getHeight() / rows + gap
                val key: View = getChildAt(i)
                key.layout(x, y, x + key.getMeasuredWidth(), y + key.getMeasuredHeight())
                i++
            }
        }
    }

    /** Only these narrow tabs consume move/resize gestures; key input stays independent.*/
    private inner class Handle : View {
        private val resize: Boolean
        private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pointer: Int = -1
        private var startX: Int = 0
        private var startY: Int = 0
        private var startWidth: Int = 0
        private var startHeight: Int = 0
        private var downX: Float = 0f
        private var downY: Float = 0f

        constructor(resize: Boolean) : super(activity) {
            this.resize = resize
            setFocusable(false)
            setClickable(true)
            setContentDescription(if (resize) tr("Изменить размер панели клавиш", "Resize key panel") else tr("Переместить панель клавиш", "Move key panel"))
            setBackground(WorkspaceDialogs.background(activity, false))
        }

        protected override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.setColor(Pal.TEXT)
            paint.setStrokeWidth(dp(1).toFloat())
            paint.setStrokeCap(Paint.Cap.ROUND)
            val x: Float = getWidth() / 2f
            val y: Float = getHeight() / 2f
            val r: Float = dp(7).toFloat()
            val a: Float = dp(3).toFloat()
            if (resize) {
                canvas.drawLine(x - r, y + r, x + r, y - r, paint)
                canvas.drawLine(x + r, y - r, x + r, y - r + 2 * a, paint)
                canvas.drawLine(x + r, y - r, x + r - 2 * a, y - r, paint)
                canvas.drawLine(x - r, y + r, x - r, y + r - 2 * a, paint)
                canvas.drawLine(x - r, y + r, x - r + 2 * a, y + r, paint)
                return
            }
            canvas.drawLine(x - r, y, x + r, y, paint)
            canvas.drawLine(x, y - r, x, y + r, paint)
            for (sign in (-1..1) step 2) {
                canvas.drawLine(x + sign * r, y, x + sign * (r - a), y - a, paint)
                canvas.drawLine(x + sign * r, y, x + sign * (r - a), y + a, paint)
                canvas.drawLine(x, y + sign * r, x - a, y + sign * (r - a), paint)
                canvas.drawLine(x, y + sign * r, x + a, y + sign * (r - a), paint)
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.getActionMasked()) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    downX = event.getRawX()
                    downY = event.getRawY()
                    startX = panelX
                    startY = panelY
                    startWidth = panelWidth
                    startHeight = panelHeight
                    setPressed(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pointer < 0 || event.getPointerId(0) != pointer) {
                        return true
                    }
                    val dx: Int = Math.round(event.getRawX() - downX)
                    val dy: Int = Math.round(event.getRawY() - downY)
                    if (resize) {
                        val bounds = Rect()
                        if (anchor == null || !anchor!!.getGlobalVisibleRect(bounds)) {
                            return true
                        }
                        val right: Int = startX + startWidth
                        val bottom: Int = startY + startHeight
                        panelWidth = Math.min(right - bounds.left, Math.max(dp(184), startWidth - dx))
                        panelHeight = Math.min(bottom - bounds.top, Math.max(minHeight(panelWidth), startHeight - dy))
                        panelX = right - panelWidth
                        panelY = bottom - panelHeight
                    } else {
                        panelX = startX + dx
                        panelY = startY + dy
                    }
                    position()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.getActionIndex()) == pointer) {
                        pointer = -1
                        setPressed(false)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pointer = -1
                    setPressed(false)
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        performClick()
                    }
                    return true
                }
                else -> {
                    return true
                }
            }
        }
    }

    private fun minHeight(width: Int): Int {
        val columns: Int = Math.max(1, (width - dp(2 * HANDLE_WIDTH + 2 * PADDING)) / dp(KEY_WIDTH))
        return dp(2 * PADDING) + (16 + columns - 1) / columns * dp(KEY_HEIGHT)
    }

    private class KeyButton : AppCompatTextView {
        private var sink: X11ExtraKeys.Sink? = null
        private var code: Int = 0
        private var held: Boolean = false
        private var physicalClick: Boolean = false

        constructor(context: Context) : super(context)

        fun physical(code: Int, sink: X11ExtraKeys.Sink) {
            this.code = code
            this.sink = sink
            setClickable(true)
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (sink != null && !physicalClick) {
                sink!!.key(code, true)
                sink!!.key(code, false)
            }
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (sink == null) {
                return super.onTouchEvent(event)
            }
            when (event.getActionMasked()) {
                MotionEvent.ACTION_DOWN -> {
                    held = true
                    setPressed(true)
                    sink!!.key(code, true)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (held) {
                        held = false
                        sink!!.key(code, false)
                    }
                    setPressed(false)
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        physicalClick = true
                        performClick()
                        physicalClick = false
                    }
                    return true
                }
                else -> {
                    return true
                }
            }
        }
    }

    private fun position() {
        if (dialog == null || anchor == null) {
            return
        }
        val bounds = Rect()
        if (!anchor!!.getGlobalVisibleRect(bounds) || bounds.width() < 1 || bounds.height() < 1) {
            close()
            return
        }
        if (!placed) {
            panelWidth = dp(400)
            panelHeight = dp(104)
            panelX = bounds.left + (bounds.width() - Math.min(panelWidth, bounds.width())) / 2
            panelY = bounds.bottom - Math.min(panelHeight, bounds.height()) - dp(4)
            placed = true
        }
        val width: Int = Math.min(panelWidth, bounds.width())
        val height: Int = Math.min(Math.max(panelHeight, minHeight(width)), bounds.height())
        val x: Int = Math.max(bounds.left, Math.min(panelX, bounds.right - width))
        val y: Int = Math.max(bounds.top, Math.min(panelY, bounds.bottom - height))
        panelWidth = width
        panelHeight = height
        panelX = x
        panelY = y
        val window: Window? = dialog!!.getWindow()
        val p: WindowManager.LayoutParams = window!!.getAttributes()
        if (p.width == width && p.height == height && p.x == x && p.y == y) {
            return
        }
        p.width = width
        p.height = height
        p.x = x
        p.y = Math.max(bounds.top, y)
        window.setAttributes(p)
    }

    fun close() {
        if (dialog == null) {
            return
        }
        val old: Dialog? = dialog
        dialog = null
        if (anchor != null) {
            anchor!!.removeOnAttachStateChangeListener(attachment)
            val observer: ViewTreeObserver = anchor!!.getViewTreeObserver()
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(layout)
            }
            anchor = null
        }
        if (release != null) {
            release!!.run()
            release = null
        }
        old!!.dismiss()
        changed.run()
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(activity, n.toFloat())
    }

    private fun tr(ru: String, en: String): String {
        return if (WorkspaceLanguage.code(activity)!!.equals("ru")) ru else en
    }

    companion object {
        private const val KEY_WIDTH: Int = 36
        private const val KEY_HEIGHT: Int = 30
        private const val HANDLE_WIDTH: Int = 20
        private const val PADDING: Int = 3
    }
}
