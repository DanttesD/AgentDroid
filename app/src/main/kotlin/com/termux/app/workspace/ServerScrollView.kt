package com.termux.app.workspace

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

/** Keeps the server viewport stable during a local content replacement, not during user navigation.*/
class ServerScrollView : ScrollView {
    private var preserving: Boolean = false
    private var savedY: Int = 0

    constructor(context: Context) : super(context)

    fun preserve(update: Runnable) {
        if (!preserving) {
            savedY = getScrollY()
        }
        preserving = true
        try {
            update.run()
        } finally {
            requestLayout()
        }
    }

    fun restoreTo(y: Int) {
        savedY = Math.max(0, y)
        preserving = true
        requestLayout()
    }

    override fun requestChildFocus(child: View, focused: View) {
        super.requestChildFocus(child, focused)
    }

    override fun scrollTo(x: Int, y: Int) {
        if (!preserving) {
            super.scrollTo(x, y)
        }
    }

    override fun requestChildRectangleOnScreen(child: View, rect: Rect, immediate: Boolean): Boolean {
        return !preserving && super.requestChildRectangleOnScreen(child, rect, immediate)
    }

    protected override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val restore: Boolean = preserving
        val y: Int = savedY
        super.onLayout(changed, l, t, r, b)
        if (restore) {
            super.scrollTo(0, y)
            preserving = false
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            preserving = false
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        preserving = false
        return super.onGenericMotionEvent(event)
    }
}
