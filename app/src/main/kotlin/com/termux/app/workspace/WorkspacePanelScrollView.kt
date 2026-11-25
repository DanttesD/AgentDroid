package com.termux.app.workspace

import android.content.Context
import android.view.MotionEvent
import android.widget.ScrollView

/** A gesture starting in a panel belongs to that panel, even over clickable rows.*/
class WorkspacePanelScrollView : ScrollView {
    constructor(context: Context) : super(context) {
        setFillViewport(true)
        // Never pass this panel's unconsumed scroll to the surrounding viewport.
        setNestedScrollingEnabled(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true)
        }
        val handled: Boolean = super.dispatchTouchEvent(event)
        if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }
}
